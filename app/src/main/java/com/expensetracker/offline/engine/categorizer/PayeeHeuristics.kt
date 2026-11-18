package com.expensetracker.offline.engine.categorizer

import java.util.Locale

/**
 * Cheap, offline helpers that sit around the keyword rules in [CategoryClassifier]. Pure Kotlin on
 * purpose so they can be unit-tested without Android.
 *
 *  1. [canonicalize]     folds common spelling variants ("Biriyani", "Cofee", "Restaurent") onto the spelling the
 *                        keyword rules know, so one rule covers every way Indian shop names are written.
 *  2. [fuzzyHint]        catches remaining one-letter typos in long food/shop words.
 *  3. [wordHint]         generic shop-type words ("... Stores", "... Foods", "... Mobiles") that carry a category
 *                        even when the brand itself is unknown. Lower priority than anything the user taught.
 *  4. [looksLikePerson]  "Vijeesh V", "Hemavathi M": a UPI payment to an individual. Their category cannot be
 *                        guessed from the name, so the UI treats them as people and offers one-tap bulk sorting.
 */
object PayeeHeuristics {

    // ───────────────────────── spelling variants ─────────────────────────

    private val VARIANTS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(biriyani|biriani|briyani|biryaani|biriyaani|beriyani|biriyanii|briani)\\b") to "biryani",
        Regex("(?i)\\b(cofee|coffe|cofe|cofffee|cofy)\\b") to "coffee",
        Regex("(?i)\\b(restaurent|restarant|resturant|restuarant|restorant|restaurants?)\\b") to "restaurant",
        Regex("(?i)\\b(bakary|bekery|bakry)\\b") to "bakery",
        Regex("(?i)\\b(supermarkets?|super\\s+markets?|super\\s+mart|supermart)\\b") to "supermarket",
        Regex("(?i)\\b(shawarma|shawerma|shawurma|shwarma|shawarmma)\\b") to "shawarma",
        Regex("(?i)\\b(parotta|porotta|paratha|parota)\\b") to "parotta",
        Regex("(?i)\\b(juse|juce|jusce)\\b") to "juice",
        Regex("(?i)\\b(medicals?|medicle|medicals)\\b") to "medical",
        Regex("(?i)\\b(chikken|chiken|chikn|chicken)\\b") to "chicken",
        Regex("(?i)\\b(hotal|hottel)\\b") to "hotel",
        Regex("(?i)\\b(mobail|mobiles?)\\b") to "mobile",
        Regex("(?i)\\b(puf|pufff|pufs)\\b") to "puff",
        Regex("(?i)\\b(samosas|somosa|samosaa)\\b") to "samosa",
        Regex("(?i)\\b(momos|momo|momoes)\\b") to "momos",
        Regex("(?i)\\b(dosaa|dose|dosai)\\b") to "dosa",
        Regex("(?i)\\b(idly|iddly)\\b") to "idli",
        Regex("(?i)\\b(stationary|stationery|staionery)\\b") to "stationery",
        Regex("(?i)\\b(tution|tuition|tutions)\\b") to "tuition",
        Regex("(?i)\\b(book\\s*my\\s*show|bookmyshow|bigtree)\\b") to "bookmyshow",
        Regex("(?i)\\b(xerox|zerox|photostat)\\b") to "xerox",
        Regex("(?i)\\b(recharg|rechargee)\\b") to "recharge",
        Regex("(?i)\\b(hair\\s*cutt?|haircut)\\b") to "haircut",
        Regex("(?i)\\b(vegitables?|vegitable|vegitbl)\\b") to "vegetables"
    )

    /** Lower-cases and folds spelling variants. Safe to call on any payee. */
    fun canonicalize(payee: String): String {
        var t = payee.trim().lowercase(Locale.ROOT)
        for ((re, to) in VARIANTS) t = re.replace(t, to)
        return t
    }

    // ───────────────────────── fuzzy words (typos) ─────────────────────────

    private val FUZZY_WORDS: List<Pair<String, String>> = listOf(
        "biryani" to "Food & Dining", "restaurant" to "Food & Dining", "bakery" to "Food & Dining",
        "coffee" to "Food & Dining", "shawarma" to "Food & Dining", "canteen" to "Food & Dining",
        "kitchen" to "Food & Dining", "chicken" to "Food & Dining", "juice" to "Food & Dining",
        "tiffin" to "Food & Dining", "dosa" to "Food & Dining", "sweets" to "Food & Dining",
        "puff" to "Food & Dining", "samosa" to "Food & Dining", "burger" to "Food & Dining",
        "pizza" to "Food & Dining", "sandwich" to "Food & Dining", "tea" to "Food & Dining",
        "momos" to "Food & Dining", "dhaba" to "Food & Dining", "curry" to "Food & Dining",
        "supermarket" to "Groceries", "vegetables" to "Groceries", "provision" to "Groceries",
        "grocery" to "Groceries", "dairy" to "Groceries",
        "pharmacy" to "Health", "hospital" to "Health", "clinic" to "Health", "medicine" to "Health",
        "petrol" to "Fuel & Fastag", "diesel" to "Fuel & Fastag", "fastag" to "Fuel & Fastag",
        "electricity" to "Bills & Utilities", "broadband" to "Bills & Utilities",
        "haircut" to "Personal Care", "salon" to "Personal Care",
        "stationery" to "Education", "tuition" to "Education", "coaching" to "Education",
        "school" to "Education", "college" to "Education",
        "bookmyshow" to "Entertainment", "cinema" to "Entertainment", "theatre" to "Entertainment",
        "movie" to "Entertainment"
    )

    /** One-letter typo in a long word ("Biriyani" is handled by [canonicalize]; "Restorent" lands here). */
    fun fuzzyHint(payee: String): String? {
        val tokens = canonicalize(payee).split(Regex("[^a-z]+")).filter { it.length >= 4 }
        for (token in tokens) {
            for ((word, category) in FUZZY_WORDS) {
                if (kotlin.math.abs(token.length - word.length) > 1) continue
                if (token == word) return category
                if (word.length >= 5 && withinOneEdit(token, word)) return category
            }
        }
        return null
    }

    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                la == lb -> { i++; j++ }      // substitution
                la > lb -> i++                 // deletion from a
                else -> j++                    // insertion into a
            }
        }
        edits += (la - i) + (lb - j)
        return edits <= 1
    }

    // ───────────────────────── generic shop-type words ─────────────────────────

    private val WORD_HINTS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(foods?|fast\\s?food|meals?|oota|kebabs?|tandoori|grill|fry|chicken|mutton|beef|fish\\s?fry|ice\\s?cream|icecream|kulfi|falooda|milkshake|cool\\s?bar|bhavan|bhavanam|snack\\s?bar|tea\\s?stall|tea\\s?shop|coffee\\s?shop|juice\\s?(shop|centre|center)|cakes?|bakes?|chips|hot\\s?chips|sweet\\s?stall|dhaba|nadan|thattu|puffs?|samosas?|biryani|shawarma|burger|pizza|sandwich|momos?|rolls?|dosa|idli|parotta|porotta|tiffin|canteen|mess|bakers?|bakery|chai|coffee|breakfast|lunch|dinner|curry|roast|paneer|kulcha|bhurji|dhokla|dabeli|pav\\s?bhaji|vada\\s?pav)\\b") to "Food & Dining",
        Regex("(?i)\\b(stores?|provision|traders|agencies|vegetable|veg|veggies|fruit|fruits|poultry|dairy|milk|ration|kirana|bazaar|bazar|supermarket|grocery|groceries|sabzi|mandi|supermart|minimart|freshmart)\\b") to "Groceries",
        Regex("(?i)\\b(medical|pharma|pharmacy|labs?|scans?|opticals?|eye\\s?care|hospital|clinic|dental|ayurved\\w*|medicine|medicines|tablet|tablets|doctor)\\b") to "Health",
        Regex("(?i)\\b(mobile|communications?|electricals?|electronics?|hardware|fancy|readymades?|garments?|gents|ladies\\s?wear|collections|fashions?|footwear|watch(es)?|gold|jewel\\w*|furniture|toys|gift\\s?(shop|centre|center)|clothes|shopping)\\b") to "Shopping",
        Regex("(?i)\\b(uidai|aadhaar|aadhar|passport|parivahan|mparivahan|rto|akshaya|csc|common\\s?service|registration|challan|stamp\\s?paper|court\\s?fee|e-?seva|electricity|bijli|power\\s?bill|water\\s?bill|cylinder|lpg|gas\\s?bill|broadband|wifi|bbps)\\b") to "Bills & Utilities",
        Regex("(?i)\\b(travels|tours|transport|cabs?|taxi|autos?\\s?stand|bus\\s?stand|ksrtc|auto\\s?fare|metro|train|flight|fare|ride|rickshaw|autorickshaw)\\b") to "Travel",
        Regex("(?i)\\b(petro|bunk|filling|fuels?|petrol|diesel|cng|fastag|toll|parking)\\b") to "Fuel & Fastag",
        Regex("(?i)\\b(beauty|unisex|saloon|salon|parlour|hair|barbers?|spa|haircut|grooming|facial)\\b") to "Personal Care",
        Regex("(?i)\\b(rent|flat\\s?rent|house\\s?rent|room\\s?rent|pg\\s?rent|hostel\\s?rent|maintenance|society\\s?maintenance)\\b") to "Rent & Housing",
        Regex("(?i)\\b(game\\s?zone|arcade|snooker|turf|cinemas?|theatres?|movies?|show|bookmyshow|bigtree|pvr|inox|cinepolis|multiplex|imax|amusement|wonderla)\\b") to "Entertainment",
        Regex("(?i)\\b(tuition|tution|academy|school|college|institute|stationery|stationary|staionery|books?|xerox|photostat|fees?|coaching|pens?|pencils?|notebooks?|notepads?|registers?|classmate|camlin|doms|apsara|nataraj|reynolds)\\b") to "Education"
    )

    /** Category implied by generic shop-type words, or null when the name says nothing. */
    fun wordHint(payee: String): String? {
        val t = canonicalize(payee)
        for ((re, category) in WORD_HINTS) if (re.containsMatchIn(t)) return category
        return null
    }

    /** Best guess from spelling-tolerant rules; null when nothing fits. */
    fun categoryHint(payee: String): String? = wordHint(payee) ?: fuzzyHint(payee)

    // ───────────────────────── people ─────────────────────────

    private val BUSINESS_WORDS = setOf(
        "stores", "store", "mart", "foods", "food", "traders", "trader", "enterprises", "enterprise", "agency", "agencies",
        "pvt", "ltd", "private", "limited", "llp", "inc", "hotel", "restaurant", "shop", "mobiles", "mobile", "medicals",
        "medical", "bakery", "cafe", "centre", "center", "services", "service", "solutions", "technologies", "tech",
        "bank", "payments", "pay", "wallet", "recharge", "india", "company", "co", "corp", "works", "garage", "studio",
        "textiles", "jewellers", "jewellery", "electricals", "electronics", "hardware", "pharmacy", "clinic", "hospital",
        "super", "market", "supermarket", "canteen", "kitchen", "dairy", "juice", "tea", "coffee", "paradise", "biryani",
        "uidai", "gst", "tax", "fee", "fees", "bill", "bills", "insurance", "finance", "fund", "trust", "society", "club"
    )

    /**
     * True for a UPI payee that reads like an individual's name: two to four alphabetic words, at least one a real
     * name-sized word, and none of the words say "shop". Initials are fine ("Vijeesh V", "Jeeshma M M").
     */
    // Called for every visible row while scrolling, and each call runs ~25 regexes. Payees repeat a lot, so cache.
    private val personCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun looksLikePerson(payee: String): Boolean {
        if (personCache.size > 4000) personCache.clear()
        return personCache.getOrPut(payee) { computeLooksLikePerson(payee) }
    }

    private fun computeLooksLikePerson(payee: String): Boolean {
        val cleaned = payee.trim()
        if (cleaned.isEmpty() || cleaned.any { it.isDigit() } || cleaned.contains('@')) return false
        val tokens = cleaned.split(Regex("[\\s.]+")).filter { it.isNotEmpty() }
        if (tokens.size !in 2..4) return false
        if (!tokens.all { t -> t.all { it.isLetter() || it == '\'' || it == '-' } }) return false
        if (tokens.none { it.length >= 3 }) return false
        val canon = canonicalize(cleaned).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (canon.any { it in BUSINESS_WORDS }) return false
        if (categoryHint(cleaned) != null) return false
        return true
    }
}
