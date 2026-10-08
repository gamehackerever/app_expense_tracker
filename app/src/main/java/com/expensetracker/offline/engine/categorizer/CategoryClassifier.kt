package com.expensetracker.offline.engine.categorizer

import com.expensetracker.offline.data.local.dao.CategoryRuleDao
import java.util.Locale

object CategoryClassifier {

    val PREDEFINED_CATEGORIES = listOf(
        "Food & Dining",
        "Groceries",
        "Shopping",
        "Travel",
        "Bills & Utilities",
        "Entertainment",
        "Health",
        "Investments",
        "Uncategorized"
    )

    // Precompile Regex for O(1) matching and to enforce word boundaries (\b).
    // This prevents "tea" from matching "team" and "vi" from matching "movie".
    private val CATEGORY_REGEX_MAP: Map<String, Regex> = mapOf(
        "Food & Dining" to createRegex("starbucks", "swiggy", "zomato", "mcdonalds?", "kfc", "burger", "cafe", "restaurant", "pizza", "subway", "dominos?", "tea", "coffee", "bistro", "bakery", "hotel"),
        "Groceries" to createRegex("blinkit", "zepto", "instamart", "bigbasket", "dmart", "spencer", "supermarket", "grocery", "vegetables?", "fruits?", "mart", "nature basket", "reliance fresh"),
        "Shopping" to createRegex("amazon", "flipkart", "myntra", "meesho", "ajio", "zara", "h&m", "retail", "clothing", "apparel", "electronics", "decathlon", "croma", "lifestyle"),
        "Travel" to createRegex("uber", "ola", "rapido", "namma yatri", "metro", "irctc", "railway", "fuel", "petrol", "diesel", "hpcl", "bpcl", "iocl", "toll", "fastag", "parking", "flight", "redbus", "makemytrip"),
        "Bills & Utilities" to createRegex("airtel", "jio", "vi", "vodafone", "electricity", "kseb", "bescom", "water", "gas", "broadband", "wifi", "billdesk", "recharge", "dth", "tneb", "postpaid", "prepaid"),
        "Entertainment" to createRegex("netflix", "hotstar", "spotify", "prime video", "pvr", "inox", "cinepolis", "bookmyshow", "steam", "playstation", "youtube", "sony liv"),
        "Health" to createRegex("apollo", "pharmeasy", "1mg", "pharmacy", "chemist", "hospital", "clinic", "dentist", "medplus", "diagnostic", "dr\\.?"),
        "Investments" to createRegex("zerodha", "groww", "upstox", "mutual fund", "sip", "kuvera", "nse", "bse", "indmoney", "cleartax", "coin")
    )

    private fun createRegex(vararg keywords: String): Regex {
        // Joins words into a capturing group (word1|word2) wrapped in boundaries
        val pattern = keywords.joinToString(separator = "|")
        return Regex("(?i)\\b($pattern)\\b")
    }

    fun classifySync(payee: String, rawContent: String): String {
        val cleanPayee = payee.trim()

        // 1. High Priority: Check if the Merchant/Payee name matches a category directly
        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(cleanPayee)) {
                return category
            }
        }

        // 2. Low Priority: Fallback to scanning the raw SMS content
        // (Only done if the Payee name was ambiguous)
        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(rawContent)) {
                return category
            }
        }

        return "Uncategorized"
    }

    suspend fun classify(
        payee: String,
        rawContent: String,
        ruleDao: CategoryRuleDao
    ): String {
        val cleanPayee = payee.trim().lowercase(Locale.ROOT)

        if (cleanPayee.isNotBlank()) {
            // 1. Exact learned rule
            val learned = ruleDao.findMatchingCategory(cleanPayee)
            if (!learned.isNullOrBlank()) return learned

            // 2. Known-merchant regex on the payee
            for ((category, regex) in CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(cleanPayee)) return category
            }

            // 3. Generalize from what the user has taught us
            NaiveBayesCategorizer.predict(cleanPayee, ruleDao)?.let { return it.category }
        }

        // 4. Last resort: scan the raw SMS text
        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(rawContent)) return category
        }
        return "Uncategorized"
    }
}