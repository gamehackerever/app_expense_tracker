package com.expensetracker.offline.engine.categorizer

import java.util.Locale

/**
 * Lightweight, 100% offline Semantic Subword Centroid Matcher.
 *
 * Uses character 3-gram and 4-gram subword embeddings alongside canonical semantic terms
 * to calculate similarity between transaction text and category semantic prototypes.
 *
 * Allows arbitrary compounds, spelling variations, typos, and unseen merchant names
 * (e.g. "fountainpen", "pencilbox", "xeroxing", "multiplexes", "cinephile", "pastas")
 * to be categorized with mathematical confidence in sub-millisecond execution time.
 */
object SemanticCentroidClassifier {

    data class SemanticMatch(
        val category: String,
        val similarity: Float,
        val explanation: String
    )

    private val CATEGORY_PROTOTYPE_TOKENS: Map<String, List<String>> = mapOf(
        "Education" to listOf(
            "pen", "pens", "fountainpen", "ballpen", "gelpen", "sketchpen",
            "pencil", "pencils", "pencilbox", "pencilcase",
            "stationery", "stationary", "staionery", "stationry", "stationer", "stationeryshop",
            "notebook", "notebooks", "notepad", "notepads", "register", "registers",
            "eraser", "sharpener", "ruler", "geometry", "compass", "highlighter", "marker",
            "stapler", "staples", "fevicol", "gluestick", "paperclip", "chartpaper", "a4sheet",
            "drawingbook", "craftpaper", "xerox", "xeroxing", "photostat", "photocopy", "printout", "spiralbinding",
            "bookstore", "bookdepot", "bookstall", "bookshop", "crossword", "classmate", "camlin",
            "kokuyo", "doms", "apsara", "nataraj", "reynolds", "flair", "luxor", "uniball",
            "staedtler", "fabercastell", "parker", "tuition", "tution", "coaching", "academy",
            "institute", "school", "college", "university", "vidyalaya", "gurukul", "preschool",
            "kindergarten", "daycare", "creche", "fees", "admission", "semester", "physicswallah",
            "allen", "aakash", "resonance", "fiitjee", "unacademy", "byjus", "vedantu", "coursera",
            "udemy", "edx", "upsc", "neet", "exam", "textbook", "studymaterial"
        ),
        "Entertainment" to listOf(
            "bookmyshow", "bigtree", "bms", "ticketnew", "insider", "district", "pvr", "inox",
            "cinepolis", "miraj", "carnival", "multiplex", "multiplexes", "imax", "cinema", "cinemas",
            "theatre", "theatres", "movie", "movies", "flick", "flicks", "ticket", "tickets",
            "film", "films", "wonderla", "imagicaa", "esselworld", "ramoji", "smaaash", "playarena",
            "timezone", "amusement", "waterpark", "arcade", "gaming", "steam", "playstation",
            "xbox", "nintendo", "epicgames", "riotgames", "krafton", "bgmi", "supercell", "roblox",
            "discord", "snooker", "bowling", "standup", "comedy", "concert", "show"
        ),
        "Food & Dining" to listOf(
            "food", "dining", "restaurant", "restuarant", "cafe", "coffee", "tea", "chai", "chaayos", "starbucks",
            "biryani", "dosa", "idli", "samosa", "samosas", "puff", "puffs", "roll", "rolls",
            "momo", "momos", "pizza", "pizzas", "burger", "burgers", "swiggy", "zomato", "bakery", "bakers", "dhaba",
            "kitchen", "canteen", "mess", "shawarma", "kebab", "tandoori", "chicken", "mutton",
            "meals", "breakfast", "lunch", "dinner", "tiffin", "bhojanalaya", "thali", "sweets",
            "mithai", "icecream", "kulfi", "falooda", "milkshake", "smoothie", "juice", "pasta",
            "noodles", "sandwich", "toast", "waffle", "eatsure", "mcdonalds", "kfc", "subway"
        ),
        "Groceries" to listOf(
            "grocery", "groceries", "supermarket", "supermart", "minimart", "freshmart", "mandi",
            "vegetables", "veggies", "fruits", "milk", "dairy", "amul", "nandini", "milma",
            "motherdairy", "countrydelight", "blinkit", "zepto", "instamart", "bigbasket", "bbnow",
            "dmart", "kirana", "provisions", "atta", "wheat", "flour", "rice", "dal", "pulses",
            "oil", "spices", "masala", "salt", "sugar", "eggs", "bread", "biscuits", "paneer",
            "curd", "dahi", "butter", "cheese", "ghee", "licious", "freshtohome"
        ),
        "Fuel & Fastag" to listOf(
            "fuel", "petrol", "diesel", "cng", "fastag", "toll", "tollplaza", "hpcl", "bpcl",
            "iocl", "shell", "nayara", "jiobp", "petrolpump", "fillingstation", "parking",
            "parkplus", "evcharging", "athergrid", "statiq", "chargezone"
        ),
        "Travel" to listOf(
            "travel", "cab", "cabs", "taxi", "auto", "rickshaw", "autorickshaw", "uber", "ola",
            "rapido", "nammayatri", "blusmart", "metro", "train", "railway", "irctc", "bus",
            "flight", "airline", "indigo", "airindia", "vistara", "akasa", "spicejet", "redbus",
            "abhibus", "makemytrip", "goibibo", "cleartrip", "ixigo", "hotel", "resort", "airbnb"
        ),
        "Bills & Utilities" to listOf(
            "electricity", "power", "bill", "utility", "water", "gas", "cylinder", "lpg",
            "broadband", "wifi", "kseb", "bescom", "tneb", "msedcl", "tpddl", "adani", "indane",
            "bharatgas", "hpgas", "bwssb", "bbps", "propertytax", "municipal"
        ),
        "Recharge & Telecom" to listOf(
            "recharge", "telecom", "jio", "airtel", "vodafone", "vi", "bsnl", "dth", "tataplay",
            "dishtv", "sundirect", "prepaid", "postpaid", "datapack", "topup", "talktime"
        ),
        "Health" to listOf(
            "health", "medical", "medicine", "medicines", "tablet", "tablets", "syrup", "pharmacy",
            "hospital", "clinic", "doctor", "dentist", "dental", "lab", "pathology", "diagnostics",
            "bloodtest", "scan", "apollo", "1mg", "pharmeasy", "netmeds", "medplus", "lalpathlabs",
            "thyrocare", "fortis", "manipal", "cultfit", "opticals", "eyecare"
        ),
        "Personal Care" to listOf(
            "salon", "saloon", "parlour", "spa", "haircut", "barber", "grooming", "facial",
            "massage", "shave", "beard", "nykaa", "purplle", "sephora", "cosmetics", "skincare",
            "lakme", "naturals", "bodycare"
        ),
        "Shopping" to listOf(
            "shopping", "clothes", "clothing", "apparel", "garments", "fashion", "electronics",
            "shoes", "footwear", "jewellery", "gold", "amazon", "flipkart", "myntra", "ajio",
            "zudio", "westside", "croma", "decathlon", "tanishq", "caratlane", "lenskart", "firstcry"
        ),
        "Investments" to listOf(
            "investment", "investments", "mutualfund", "sip", "stocks", "shares", "demat",
            "zerodha", "groww", "upstox", "angelone", "paytmmoney", "indmoney", "smallcase",
            "cams", "kfintech", "gold", "safegold", "jarapp", "ppf", "nps"
        ),
        "Rent & Housing" to listOf(
            "rent", "housing", "society", "maintenance", "flat", "apartment", "pg", "hostel",
            "landlord", "nobroker", "mygate", "apnacomplex"
        ),
        "Subscriptions" to listOf(
            "subscription", "subscriptions", "netflix", "spotify", "prime", "amazonprime",
            "youtube", "youtubepremium", "hotstar", "sonyliv", "zee5", "chatgpt", "openai", "claude"
        ),
        "EMI & Loans" to listOf(
            "emi", "loan", "loans", "bajajfinserv", "hdb", "tatacapital", "muthoot", "manappuram",
            "kreditbee", "nach", "repayment"
        ),
        "Gifts & Festivals" to listOf(
            "gift", "gifts", "festival", "pooja", "puja", "temple", "mandir", "donation",
            "charity", "fnp", "fernsnpetals", "floweraura", "prasadam", "dakshina", "zakat"
        )
    )

    private class PrototypeToken(
        val token: String,
        val subwords: Set<String>
    )

    private val CATEGORY_PROTOTYPES: Map<String, List<PrototypeToken>> by lazy {
        CATEGORY_PROTOTYPE_TOKENS.mapValues { (_, tokens) ->
            tokens.map { token ->
                PrototypeToken(
                    token = token,
                    subwords = extractSubwords(token)
                )
            }
        }
    }

    private fun extractSubwords(word: String): Set<String> {
        val set = HashSet<String>()
        val len = word.length
        if (len >= 3) {
            for (i in 0..len - 3) {
                set.add(word.substring(i, i + 3))
            }
        }
        if (len >= 4) {
            for (i in 0..len - 4) {
                set.add(word.substring(i, i + 4))
            }
        }
        return set
    }

    private val TOKEN_REGEX = Regex("[^a-z0-9]+")

    private fun wordSimilarity(queryWord: String, querySubwords: Set<String>, proto: PrototypeToken): Float {
        val protoToken = proto.token
        if (queryWord == protoToken) return 1.0f

        val shorter = if (queryWord.length <= protoToken.length) queryWord else protoToken
        val longer = if (queryWord.length > protoToken.length) queryWord else protoToken

        // Compound or affix matching: e.g. "fountainpen" ends with "pen", "pencilbox" starts with "pencil"
        var compoundScore = 0f
        if (shorter.length >= 3 && (longer.startsWith(shorter) || longer.endsWith(shorter))) {
            compoundScore = 0.65f + 0.35f * (shorter.length.toFloat() / longer.length.toFloat())
        }

        // Subword n-gram Dice overlap
        var dice = 0f
        if (querySubwords.isNotEmpty() && proto.subwords.isNotEmpty()) {
            var overlap = 0
            for (sw in querySubwords) {
                if (sw in proto.subwords) overlap++
            }
            dice = (2.0f * overlap) / (querySubwords.size + proto.subwords.size)
        }

        return maxOf(compoundScore, dice)
    }

    /**
     * Computes the semantic similarity of input text against category prototypes.
     * Returns top match if similarity exceeds threshold (default 0.45) with strong margin.
     */
    fun classifySemantic(text: String, threshold: Float = 0.45f): SemanticMatch? {
        val queryWords = text.lowercase(Locale.ROOT)
            .split(TOKEN_REGEX)
            .filter { it.length >= 2 && !it.all { ch -> ch.isDigit() } }

        if (queryWords.isEmpty()) return null

        val queryWordTokens = queryWords.map { w ->
            Pair(w, extractSubwords(w))
        }

        var bestCategory: String? = null
        var bestSim = 0f
        var secondBestSim = 0f
        var bestMatchToken = ""

        for ((category, prototypes) in CATEGORY_PROTOTYPES) {
            var categoryMaxSim = 0f
            var categoryBestToken = ""

            for ((qWord, qSubwords) in queryWordTokens) {
                for (proto in prototypes) {
                    val sim = wordSimilarity(qWord, qSubwords, proto)
                    if (sim > categoryMaxSim) {
                        categoryMaxSim = sim
                        categoryBestToken = proto.token
                    }
                }
            }

            if (categoryMaxSim > bestSim) {
                secondBestSim = bestSim
                bestSim = categoryMaxSim
                bestCategory = category
                bestMatchToken = categoryBestToken
            } else if (categoryMaxSim > secondBestSim) {
                secondBestSim = categoryMaxSim
            }
        }

        if (bestCategory != null && bestSim >= threshold && (bestSim - secondBestSim) >= 0.05f) {
            return SemanticMatch(
                category = bestCategory,
                similarity = bestSim,
                explanation = "Semantic subword similarity with '$bestMatchToken' (${(bestSim * 100).toInt()}%)"
            )
        }

        return null
    }
}
