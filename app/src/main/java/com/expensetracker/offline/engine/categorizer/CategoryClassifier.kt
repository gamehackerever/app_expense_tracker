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
        "Transfer",
        "Uncategorized"
    )

    // Precompile Regex for O(1) matching and to enforce word boundaries (\b).
    // This prevents "tea" from matching "team" and "vi" from matching "movie".
    private val CATEGORY_REGEX_MAP: Map<String, Regex> = mapOf(
        "Transfer" to createRegex("self transfer", "credit card bill", "cc bill", "internal transfer", "auto sweep", "own a/c"),
        "Food & Dining" to createRegex("starbucks", "swiggy", "zomato", "mcdonalds?", "kfc", "burger", "cafe", "restaurant", "pizza", "subway", "dominos?", "tea", "coffee", "bistro", "bakery", "hotel", "shawarma", "biryani"),
        "Groceries" to createRegex("blinkit", "zepto", "instamart", "bigbasket", "dmart", "spencer", "supermarket", "grocery", "vegetables?", "fruits?", "mart", "nature basket", "reliance fresh", "lulu hypermarket"),
        "Shopping" to createRegex("amazon", "flipkart", "myntra", "meesho", "ajio", "zara", "h&m", "retail", "clothing", "apparel", "electronics", "decathlon", "croma", "lifestyle", "nykaa", "tatacliq"),
        "Travel" to createRegex("uber", "ola", "rapido", "namma yatri", "metro", "irctc", "railway", "fuel", "petrol", "diesel", "hpcl", "bpcl", "iocl", "toll", "fastag", "parking", "flight", "redbus", "makemytrip", "ksrtc"),
        "Bills & Utilities" to createRegex("airtel", "jio", "vi", "vodafone", "electricity", "kseb", "bescom", "water", "gas", "broadband", "wifi", "billdesk", "recharge", "dth", "tneb", "postpaid", "prepaid", "bsnl"),
        "Entertainment" to createRegex("netflix", "hotstar", "spotify", "prime video", "pvr", "inox", "cinepolis", "bookmyshow", "steam", "playstation", "youtube", "sony liv", "jiocinema"),
        "Health" to createRegex("apollo", "pharmeasy", "1mg", "pharmacy", "chemist", "hospital", "clinic", "dentist", "medplus", "diagnostic", "dr\\.?", "aster", "medibuddy"),
        "Investments" to createRegex("zerodha", "groww", "upstox", "mutual fund", "sip", "kuvera", "nse", "bse", "indmoney", "cleartax", "coin", "smallcase", "nps", "ppf")
    )

    private fun createRegex(vararg keywords: String): Regex {
        val pattern = keywords.joinToString(separator = "|")
        return Regex("(?i)\\b($pattern)\\b")
    }

    fun classifySync(payee: String, rawContent: String): String {
        val cleanPayee = payee.trim()

        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(cleanPayee)) {
                return category
            }
        }

        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(rawContent)) {
                return category
            }
        }

        return "Uncategorized"
    }

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun clearCache() {
        categoryCache.clear()
    }

    suspend fun classify(
        payee: String,
        rawContent: String,
        ruleDao: CategoryRuleDao
    ): String {
        val cleanPayee = payee.trim().lowercase(Locale.ROOT)

        if (cleanPayee.isNotBlank()) {
            categoryCache[cleanPayee]?.let { return it }

            // 1. Exact learned rule
            val learned = ruleDao.findMatchingCategory(cleanPayee)
            if (!learned.isNullOrBlank()) {
                categoryCache[cleanPayee] = learned
                return learned
            }

            // 2. Known-merchant regex on the payee
            for ((category, regex) in CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(cleanPayee)) {
                    categoryCache[cleanPayee] = category
                    return category
                }
            }

            // 3. Generalize from what the user has taught us
            NaiveBayesCategorizer.predict(cleanPayee, ruleDao)?.let {
                categoryCache[cleanPayee] = it.category
                return it.category
            }
        }

        // 4. Last resort: scan the raw SMS text
        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(rawContent)) return category
        }
        return "Uncategorized"
    }
}