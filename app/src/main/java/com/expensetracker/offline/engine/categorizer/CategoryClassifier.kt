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
    // Expanded with regional Indian & campus/local merchant keywords (broast, shawaya, mandhi, mess, coolbar, etc.)
    private val CATEGORY_REGEX_MAP: Map<String, Regex> = mapOf(
        "Transfer" to createRegex("self transfer", "credit card bill", "cc bill", "internal transfer", "auto sweep", "own a/c"),
        "Food & Dining" to createRegex(
            "starbucks", "swiggy", "zomato", "mcdonalds?", "kfc", "burger", "cafe", "restaurant",
            "pizza", "subway", "dominos?", "tea", "coffee", "bistro", "bakery", "hotel", "shawarma",
            "biryani", "broast", "shawaya", "mandhi", "alfaham", "canteen", "mess", "coolbar",
            "bakes", "juice", "chai", "diner", "tiffin", "eatery", "kitchen", "grill"
        ),
        "Groceries" to createRegex(
            "blinkit", "zepto", "instamart", "bigbasket", "dmart", "spencer", "supermarket",
            "grocery", "vegetables?", "fruits?", "mart", "nature basket", "reliance fresh",
            "lulu hypermarket", "hypermarket", "stores?", "provisions?", "minimart", "dairy", "milk", "milma"
        ),
        "Shopping" to createRegex(
            "amazon", "flipkart", "myntra", "meesho", "ajio", "zara", "h&m", "retail", "clothing",
            "apparel", "electronics", "decathlon", "croma", "lifestyle", "nykaa", "tatacliq",
            "trends", "zudio", "max fashion", "footwear", "stationery", "books?", "xerox"
        ),
        "Travel" to createRegex(
            "uber", "ola", "rapido", "namma yatri", "metro", "irctc", "railway", "fuel", "petrol",
            "diesel", "hpcl", "bpcl", "iocl", "toll", "fastag", "parking", "flight", "redbus",
            "makemytrip", "ksrtc", "bus", "auto", "cab", "taxi", "indigo", "air india"
        ),
        "Bills & Utilities" to createRegex(
            "airtel", "jio", "vi", "vodafone", "electricity", "kseb", "bescom", "water", "gas",
            "broadband", "wifi", "billdesk", "recharge", "dth", "tneb", "postpaid", "prepaid", "bsnl"
        ),
        "Entertainment" to createRegex(
            "netflix", "hotstar", "spotify", "prime video", "pvr", "inox", "cinepolis",
            "bookmyshow", "steam", "playstation", "youtube", "sony liv", "jiocinema", "apple",
            "google play", "google photos", "turf", "gaming"
        ),
        "Health" to createRegex(
            "apollo", "pharmeasy", "1mg", "pharmacy", "chemist", "hospital", "clinic", "dentist",
            "medplus", "diagnostic", "dr\\.?", "aster", "medibuddy", "medicals?", "gym", "fitness",
            "creatine", "protein", "whey", "workout"
        ),
        "Investments" to createRegex(
            "zerodha", "groww", "upstox", "mutual fund", "sip", "kuvera", "nse", "bse",
            "indmoney", "cleartax", "coin", "smallcase", "nps", "ppf"
        )
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
        NaiveBayesCategorizer.invalidate()
    }

    suspend fun classify(
        payee: String,
        rawContent: String,
        ruleDao: CategoryRuleDao
    ): String {
        val cleanPayee = payee.trim().lowercase(Locale.ROOT)

        if (cleanPayee.isNotBlank()) {
            categoryCache[cleanPayee]?.let { return it }

            // 1. Exact or substring learned rule
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

            // 3. Subword 3-gram + word token Naive Bayes generalization from taught rules
            NaiveBayesCategorizer.predict(cleanPayee, ruleDao)?.let {
                categoryCache[cleanPayee] = it.category
                return it.category
            }
        }

        // 4. Last resort: scan the raw SMS/notification text
        for ((category, regex) in CATEGORY_REGEX_MAP) {
            if (regex.containsMatchIn(rawContent)) return category
        }
        return "Uncategorized"
    }
}