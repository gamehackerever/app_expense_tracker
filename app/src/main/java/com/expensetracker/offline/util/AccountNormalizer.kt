package com.expensetracker.offline.util

import java.util.Locale

object AccountNormalizer {
    private val KNOWN_BANK_ALIASES: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bsbi\\b|state\\s*bank|sbiinb|sbipsg|sbicrd|yonosbi") to "SBI",
        Regex("(?i)\\bhdfc\\b|hdfcbk|hdfcbank") to "HDFC Bank",
        Regex("(?i)\\bicici\\b|icicib") to "ICICI Bank",
        Regex("(?i)\\baxis\\b|axisbk") to "Axis Bank",
        Regex("(?i)\\bkotak\\b|kotakb") to "Kotak Bank",
        Regex("(?i)\\bpnb\\b|punjab\\s*national|pnbsms") to "PNB",
        Regex("(?i)\\bfederal\\b|fedbnk|fedbk") to "Federal Bank",
        Regex("(?i)\\bcanara\\b|canbnk") to "Canara Bank",
        Regex("(?i)\\bunion\\s*bank\\b|\\bubin\\b|unionb") to "Union Bank",
        Regex("(?i)\\bidfc\\b|idfcfb") to "IDFC First Bank",
        Regex("(?i)\\bindusind\\b|indusb") to "IndusInd Bank",
        Regex("(?i)\\byes\\s*bank\\b|yesbnk|yesb") to "Yes Bank",
        Regex("(?i)\\bsib\\b|south\\s*indian|sibsms|sibl") to "South Indian Bank",
        Regex("(?i)\\bbob\\b|bank\\s*of\\s*baroda|baroda|bobtxn|bobsms") to "Bank of Baroda",
        Regex("(?i)\\bboi\\b|bank\\s*of\\s*india|boisms|boiind") to "Bank of India",
        Regex("(?i)\\bindian\\s*overseas\\b|\\biob\\b|iobchn|iobsms") to "Indian Overseas Bank",
        Regex("(?i)\\bindian\\s*bank\\b|indbnk|indoasis") to "Indian Bank",
        Regex("(?i)\\bcentral\\s*bank\\b|cbin|centbk") to "Central Bank of India",
        Regex("(?i)\\bbank\\s*of\\s*maharashtra\\b|mahabk|bomaha") to "Bank of Maharashtra",
        Regex("(?i)\\buco\\b|ucobnk") to "UCO Bank",
        Regex("(?i)\\bpunjab\\s*(?:&|and)\\s*sind\\b|psbank") to "Punjab & Sind Bank",
        Regex("(?i)\\bidbi\\b|idbibk") to "IDBI Bank",
        Regex("(?i)\\bbandhan\\b|bdnbnk") to "Bandhan Bank",
        Regex("(?i)\\brbl\\b|rblbnk|rblcrd") to "RBL Bank",
        Regex("(?i)\\bkarnataka\\s*bank\\b|ktkbnk|ktkbk") to "Karnataka Bank",
        Regex("(?i)\\bkarur\\s*vysya\\b|\\bkvb\\b|kvbl|kvbbnk") to "Karur Vysya Bank",
        Regex("(?i)\\bcity\\s*union\\b|\\bcub\\b|cubank") to "City Union Bank",
        Regex("(?i)\\btamilnad\\s*mercantile\\b|\\btmb\\b|tmbbnk") to "Tamilnad Mercantile Bank",
        Regex("(?i)\\bdhanlaxmi\\b|dhlbnk|dlbbnk") to "Dhanlaxmi Bank",
        Regex("(?i)\\bcsb\\b|catholic\\s*syrian|csbbnk") to "CSB Bank",
        Regex("(?i)\\bsaraswat\\b|srcb") to "Saraswat Bank",
        Regex("(?i)\\bkerala\\s*gramin\\b|klgb|kgbsms") to "Kerala Gramin Bank",
        Regex("(?i)\\bkerala\\s*bank\\b|kscb") to "Kerala Bank",
        Regex("(?i)\\bau\\s*small\\s*finance\\b|\\baubank\\b|aubl") to "AU Small Finance Bank",
        Regex("(?i)\\bequitas\\b|equitb") to "Equitas Bank",
        Regex("(?i)\\bujjivan\\b|ujjivn") to "Ujjivan Bank",
        Regex("(?i)\\butkarsh\\b|utksfb") to "Utkarsh Bank",
        Regex("(?i)\\bjana\\s*small\\s*finance\\b|janabk") to "Jana Bank",
        Regex("(?i)\\besaf\\b|esafbk") to "ESAF Bank",
        Regex("(?i)\\bpaytm\\b") to "Paytm Payments Bank",
        Regex("(?i)\\bairtel\\b") to "Airtel Payments Bank",
        Regex("(?i)\\bjio\\b") to "Jio Payments Bank",
        Regex("(?i)\\bpost\\s*office\\b|ippb") to "India Post Payments Bank"
    )

    fun canonicalBankName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        val withoutCc = if (cleaned.endsWith(" CC", ignoreCase = true) &&
            !cleaned.equals("OneCard CC", ignoreCase = true) &&
            !cleaned.equals("American Express CC", ignoreCase = true)
        ) {
            cleaned.substring(0, cleaned.length - 3).trim()
        } else {
            cleaned
        }

        for ((regex, canonical) in KNOWN_BANK_ALIASES) {
            if (regex.containsMatchIn(withoutCc)) {
                return canonical
            }
        }
        return withoutCc
    }

    fun bankName(raw: String?): String? = canonicalBankName(raw)

    fun accountNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digitsOnly = raw.trim().trimStart('X', 'x', '*').filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return null
        return if (digitsOnly.length > 4) digitsOnly.takeLast(4) else digitsOnly
    }
}
