package com.expensetracker.offline.engine.parser

import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import java.math.BigDecimal
import java.util.regex.Pattern
import kotlin.math.round

object FinancialParser {

    // ─────────────────────────────────────────────────────────────────────────
    // PRECOMPILED PATTERNS (Critical for performance during batch SMS scanning)
    // ─────────────────────────────────────────────────────────────────────────

    // FIXED: Added word boundaries (\b) so "consent" doesn't match "sent"
    private val FINANCIAL_KEYWORDS = listOf(
        "\\bdebited\\b", "\\bdebit\\b", "\\bcredited\\b", "\\bcredit\\b", "\\bspent\\b", "\\bpaid\\b",
        "\\btransferred\\b", "\\bsent\\b", "\\breceived\\b", "\\brefunded\\b", "\\bwithdrawn\\b",
        "\\bdeposited\\b", "\\bdeducted\\b", "\\bsent you\\b", "\\bpaid you\\b"
    ).map { Pattern.compile("(?i)$it") }

    // FIXED: Added reject list for OTPs, limits, failed transactions, and mandates
    private val REJECT_PATTERNS = listOf(
        "\\botp\\b", "\\bdeclined\\b", "\\bfailed\\b",
        "\\bavl lmt\\b", "\\btotal due\\b", "\\bwill be debited\\b", "\\bcollect request\\b"
    ).map { Pattern.compile("(?i)$it") }

    // A reversal puts money back into the account. Dropping it leaves the balance unexplained (the next
    // alert then shows a false "missing transaction"), so it is kept as a CREDIT. Messages that are
    // both failed and reversed ("payment failed, amount reversed") are still rejected by REJECT_PATTERNS.
    private val REVERSAL_PATTERN = Pattern.compile("(?i)\\breversed\\b")

    // FIXED: Removed "cr." and "dr." from generic matchers to fix the "Dr. Sharma" bug. Prefer verbs.
    private val CREDIT_PATTERN = Pattern.compile("(?i)\\b(credit|credited|received|refunded|deposited|added|sent you|paid you|transfer(?:red)? from)\\b")
    private val DEBIT_PATTERN = Pattern.compile("(?i)\\b(debit|debited|spent|paid(?!\\s+you)|deducted|withdrawn|sent(?!\\s+you)|transfer(?:red)? to)\\b")
    private val CREDIT_CARD_PATTERN = Pattern.compile("(?i)\\bcredit\\s*(?:card|limit|line|bill)\\b")

    private val PROMO_PATTERNS = Pattern.compile("(?i)\\b(upgrade\\s+today|recharge\\s+(?:now|with|for)|plan|validity|per\\s+day|days\\b|pre-approved|apply\\s+now|congrats|congratulations|exclusive\\s+offer|special\\s+offer|discount|flat\\s+(?:rs|inr|₹)|free\\s+trial|win\\b|hurry|coupon|gift\\s+card|promo\\s+code|valid\\s+till|dial\\s+\\*|unlimited\\s+data|calls\\s+for\\s+just|bonus)\\b")
    private val BANK_INDICATORS = Pattern.compile("(?i)\\b(a/c|acct|account|vpa|upi\\s*ref|rrn|txn|card\\s+xx|avl\\s+bal|avail\\s+bal|balance|bank)\\b")

    private val DISCLAIMER_PATTERN = Pattern.compile("(?is)\\b(?:block\\s*(?:a/c|card|upi)|if\\s+not\\s+(?:done|you)|call\\s*\\d+|sms\\s+blk|not\\s+you\\s*\\?).*$")

    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("(?i)(?:inr|rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("(?i)([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:inr|rs\\.?|₹)"),
        Pattern.compile("(?i)(?:amount|amt|spent|paid|for|of)\\s*(?:of)?\\s*(?:inr|rs\\.?|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")
    )
    private val BALANCE_PATTERN = Pattern.compile("(?i)(?:final balance|available balance|avail bal|avl bal|bal(?:ance)?)\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")

    private val BALANCE_SANITIZER_REGEX = BALANCE_PATTERN.toRegex()
    private val QUOTED_NOTE_REGEX = Regex("""["“]([^"”\n\r]{2,80})["”]""")
    private val PREFIX_NOTE_REGEX = Regex("""(?i)(?:note\s*[:\-]\s*|for\s+["']?)([^"'\n\r,]+)(?:["']|\s*(?:using|via|upi|ref|$))""")
    private val SYSTEM_NOISE_REGEX = Regex("""(?i)(upi ref|transaction id|paid using|bank account|google pay|\d{10,16})""")
    private val DIGIT_ONLY_REGEX = Regex("""^\d{8,12}.*""")
    private val VPA_CLEANUP_REGEX = Regex("(?i)^(vpa|a/c|acct|upi|account)\\s+")
    private val SMS_HEADER_REGEX = Regex("""(?i)^[a-z]{2}-[a-z0-9]{4,8}(?:-[a-z0-9]+)?$""")
    private val BANK_HEADER_REGEX = Regex("""(?i)^[a-z]{3,8}(?:bk|bnk|upi|bank)$""")

    private val ACCOUNT_NUM_PATTERN = Pattern.compile("(?i)(?:a/c|acct|account)(?:\\s+no\\.?|\\s+number)?\\s*[:\\-]?\\s*[*xX]*(\\d{3,6})\\b")

    private val BANK_NAME_PATTERNS = listOf(
        Pattern.compile("(?i)-\\s*([a-zA-Z\\s]+(?:Bank|SBI))\\s*$", Pattern.MULTILINE),
        Pattern.compile("(?i)\\b((?:HDFC|SBI|ICICI|Axis|Kotak|PNB|Federal|Canara|Union|IDFC|IndusInd|Yes|South Indian)[\\sA-Za-z]*(?:Bank)?)\\b")
    )

    private val KNOWN_BANK_ALIASES: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bhdfc\\b") to "HDFC Bank",
        Regex("(?i)\\bsbi\\b|state\\s*bank") to "SBI",
        Regex("(?i)\\bicici\\b") to "ICICI Bank",
        Regex("(?i)\\baxis\\b") to "Axis Bank",
        Regex("(?i)\\bkotak\\b") to "Kotak Bank",
        Regex("(?i)\\bpnb\\b|punjab\\s*national") to "PNB",
        Regex("(?i)\\bfederal\\b|fedbnk|fedbk") to "Federal Bank",
        Regex("(?i)\\bcanara\\b") to "Canara Bank",
        Regex("(?i)\\bunion\\b") to "Union Bank",
        Regex("(?i)\\bidfc\\b") to "IDFC First Bank",
        Regex("(?i)\\bindusind\\b") to "IndusInd Bank",
        Regex("(?i)\\byes\\b") to "Yes Bank",
        Regex("(?i)\\bsib\\b|south\\s*indian|sibsms") to "South Indian Bank"
    )

    // FIXED: Requires explicit context (a/c, acct, account, card, ending in). Dropped bare '-' fallback.
    private val SECONDARY_TEXT_ACCOUNT_PATTERN = Regex(
        pattern = """(?i)(?:a/c|acct|account|card|ending in|ending with)\s*[-:]?\s*[*xX]*(\d{4,})"""
    )

    private val SECONDARY_TEXT_BANK_PATTERN = Regex(
        pattern = """(?:using|via|from(?:\s+your)?)\s+([a-zA-Z\s]{2,25}?)\s+(?:Bank|A/c|Account)\b""",
        option = RegexOption.IGNORE_CASE
    )

    private val TITLE_BANK_REGEX = Regex("(?i)^(?:[A-Z]{2}-)?(HDFC|SBI|ICICI|AXIS|KOTAK|PNB|FEDERAL|FEDBNK|CANARA|UNION|IDFC|INDUSIND|YES|SIBSMS)")

    private val PAYEE_PATTERNS = listOf(
        Pattern.compile("(?i)(?:^|.*?[\\n:])\\s*([A-Za-z0-9\\s._\\-]{2,35}?)\\s+(?:paid|sent)\\s+you", Pattern.MULTILINE),
        Pattern.compile("(?i)(?:via\\s+upi\\s+to|upi\\s+to)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=(?:\\s+(?:Ref|Rrn|UPI|on|via|avl|bal|avail)|[.,]|$))"),
        Pattern.compile("(?i)(?:received(?:\\s+[₹Rs\\d,.]+)?\\s+from)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=(?:\\s+(?:using|on|via|ref|upi|avl|bal|avail)|[.,]|$))"),
        Pattern.compile("(?i)(?:by transfer from|transferred from|transfer from)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=(?:\\s+(?:Ref|Rrn|UPI|on|via|avl|bal)|[.,]|$))"),
        Pattern.compile("(?i)by UPI:\\s*([A-Za-z0-9\\s._\\-]{2,35}?)(?=\\s*\\(|\\s+(?:Ref|on|via)|[.,]|$)"),
        Pattern.compile("(?i)(?:linked to VPA|from VPA|to VPA)\\s+([A-Za-z0-9@._\\-]+)"),
        Pattern.compile("(?i)(?:paid|sent)\\s+(?:inr|rs\\.?|₹)?\\s*[0-9,.]*\\s+to\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=(?:\\s+(?:using|from|on|via|ref|upi|avl|bal|avail)|[.,]|$))"),
        Pattern.compile("(?i)(?:you\\s+paid|you\\s+sent)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=\\s+(?:inr|rs\\.?|₹|[0-9]))"),
        Pattern.compile("(?i)(?:paid to|transferred to|transfer to|trf to|sent to)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=(?:\\s+(?:from|on|using|via|ref|upi|avl|bal|avail)|[.,]|$))"),
        Pattern.compile("(?i)^To\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=(?:\\s+(?:on|using|via|ref|upi|avl|bal|avail)|[.,]|$))", Pattern.MULTILINE),
        Pattern.compile("(?i)(?:at|towards|info[:/\\-\\s])\\s+([A-Za-z0-9@._\\-*\\s]{2,45}?)(?=(?:\\s+(?:on|using|via|ref|upi|avl|bal|avail|final|umrn)|[.,]|$))")
    )

    private val UPI_SLASH_PATTERN = Pattern.compile("(?i)UPI/([A-Za-z0-9/_\\-.\\s]+?)(?=\\s+(?:on|using|via|ref|avl|bal|avail|final|[.,])|$)")

    // FIXED: Require a boundary and at least 9 characters containing a digit
    private val REF_ID_PATTERNS = listOf(
        Pattern.compile("(?i)(?:upi ref(?: no)?|ref no|rrn|txn id|transaction id|umrn)[:\\s]*([0-9a-zA-Z]{9,})"),
        Pattern.compile("(?i)UPI/(?:[A-Za-z0-9]+/)*?([0-9]{10,14})"),
        Pattern.compile("(?i)\\b(?=[a-zA-Z]*\\d)[a-zA-Z0-9]{9,}\\b")
    )

    private val IGNORED_IDENTIFIERS = setOf(
        "UPI", "CR", "DR", "P2A", "P2M", "REV", "NA", "INFO", "NULL",
        "MERCHANT", "UPIMERCHANT", "UPI-MERCHANT", "PAYEE", "PAYMENT", "TRANSFER",
        "COLLECT", "NODAL", "SIBL", "HDFC", "ICICI", "SBIN", "UTIB", "KKBK",
        "FDRL", "YESB", "PAYTM", "BILLDESK", "RAZORPAY", "CCAVENUE", "AIRP", "KVB", "KVBL",
        "FEDBK", "FEDBNK", "FEDERAL"
    )

    private val PAYEE_PARENTHESES_REGEX = Regex("""\(([^)]+)\)""")
    private val TRAILING_DIGITS_REGEX = Regex("""\s*\d+$""")
    private val WEB_DOMAIN_REGEX = Regex("(?i)\\.(com|in|co\\.in|net|org)$")
    private val CORPORATE_NOISE_REGEX = Regex("(?i)\\b(pvt ltd|private limited|ltd|limited|inc|technologies|services|order)\\b")

    fun parse(rawText: String, title: String? = null, source: TransactionSource = TransactionSource.SMS): ParsedTransaction {
        val combinedText = if (!title.isNullOrBlank() && !rawText.startsWith(title)) {
            "$title:$rawText"
        } else {
            rawText
        }

        // 1. Run Reject List First
        for (pattern in REJECT_PATTERNS) {
            if (pattern.matcher(combinedText).find()) {
                return createRejected(rawText, "Matched reject pattern: ${pattern.pattern()}")
            }
        }

        val lowerText = combinedText.lowercase()
        val hasTransactionVerb = FINANCIAL_KEYWORDS.any { it.matcher(lowerText).find() }
        val isReversal = REVERSAL_PATTERN.matcher(combinedText).find()
        val type = if (isReversal) TransactionType.CREDIT else detectTransactionType(combinedText)

        if (!hasTransactionVerb && type == null) {
            return createRejected(rawText, "No financial keywords found")
        }

        val isPromo = PROMO_PATTERNS.matcher(combinedText).find()
        val hasBankVerification = BANK_INDICATORS.matcher(combinedText).find()

        // 2. Conditional Promo Filter (Only drop if it's an SMS)
        if (isPromo && !hasBankVerification && source == TransactionSource.SMS) {
            return createRejected(rawText, "Matched SMS promo pattern")
        }

        val cleanedForPayee = DISCLAIMER_PATTERN.matcher(combinedText).replaceAll("").trim()

        val balance = extractBalance(combinedText)
        val amount = extractAmount(combinedText)
        val payee = extractPayee(cleanedForPayee, title, type ?: TransactionType.DEBIT)
        val refId = extractReferenceId(combinedText)
        val note = extractGPayNote(rawText = rawText, title = title)

        val bankName = normalizeBankName(extractBankName(combinedText, title))
        val accountNumber = normalizeAccountNumber(extractAccountNumber(combinedText))

        // 3. CC and Refund Flagging
        val isCcPayment = Pattern.compile("(?i)\\bcredit card bill\\b").matcher(combinedText).find()
        val isRefund = Pattern.compile("(?i)\\brefund(?:ed)?\\b").matcher(combinedText).find()

        return ParsedTransaction(
            isFinancial = true,
            amount = amount,
            payee = cleanPayee(payee),
            type = type,
            referenceId = refId,
            balance = balance,
            note = note,
            bankName = bankName,
            accountNumber = accountNumber,
            rawText = rawText,
            currency = "INR",
            excludeFromSpend = isCcPayment || isRefund,
            confidence = if (type != null && amount != null) 1.0f else 0.5f
        )
    }

    private fun createRejected(rawText: String, reason: String): ParsedTransaction {
        return ParsedTransaction(
            isFinancial = false,
            rawText = rawText,
            rejectReason = reason,
            confidence = 0.0f
        )
    }

    fun extractGPayNote(rawText: String, title: String? = null): String? {
        QUOTED_NOTE_REGEX.find(rawText)?.groupValues?.get(1)?.trim()?.let {
            if (!isSystemNoise(it)) return it
        }

        PREFIX_NOTE_REGEX.find(rawText)?.groupValues?.get(1)?.trim()?.let {
            if (!isSystemNoise(it)) return it
        }

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size >= 2) {
            val candidate = lines.getOrNull(1) ?: return null
            // BUG FIX: many bank SMS are exactly two lines, with line 2 being the
            // "Avl Bal Rs X" balance readout, not a genuine payment note. Since
            // SYSTEM_NOISE_REGEX didn't recognize balance text, that line was
            // getting saved and displayed as the transaction's note (e.g. an
            // italic "Avl Bal Rs 15,000.00" under a plain SMS-debited entry).
            if (!isSystemNoise(candidate) && !looksLikeBalanceLine(candidate) && candidate.length in 2..80) {
                return candidate.replace("\"", "").replace("“", "").replace("”", "").trim()
            }
        }

        return null
    }

    private fun isSystemNoise(text: String): Boolean {
        return SYSTEM_NOISE_REGEX.containsMatchIn(text)
    }

    private fun looksLikeBalanceLine(text: String): Boolean {
        return BALANCE_PATTERN.matcher(text).find()
    }

    private fun extractAmount(text: String): Long? {
        val sanitized = text.replace(BALANCE_SANITIZER_REGEX, "")
        for (pattern in AMOUNT_PATTERNS) {
            val matcher = pattern.matcher(sanitized)
            if (matcher.find()) {
                val candidate = matcher.group(1)?.replace(",", "")?.trim()
                val parsed = candidate?.toBigDecimalOrNull()
                    ?.multiply(BigDecimal("100"))
                    ?.toLong()
                if (parsed != null && parsed > 0L) return parsed
            }
        }
        return null
    }

    private fun extractBalance(text: String): Long? {
        val matcher = BALANCE_PATTERN.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.replace(",", "")?.trim()
                ?.toBigDecimalOrNull()
                ?.multiply(BigDecimal("100"))
                ?.toLong()
        }
        return null
    }

    private fun extractPayee(text: String, title: String?, type: TransactionType): String? {
        for (pattern in PAYEE_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val candidate = matcher.group(1)?.trim()
                if (!candidate.isNullOrBlank() && candidate.length > 1 && !isIgnored(candidate)) {
                    return candidate
                }
            }
        }

        val upiMatcher = UPI_SLASH_PATTERN.matcher(text)
        if (upiMatcher.find()) {
            val rawPath = upiMatcher.group(1).orEmpty()
            val segments = rawPath.split("/")
                .map { it.trim() }
                .filter { segment ->
                    segment.isNotBlank() &&
                            !segment.all { it.isDigit() } &&
                            segment.length > 1 &&
                            !isIgnored(segment)
                }

            if (segments.isNotEmpty()) {
                return segments.maxByOrNull { it.length }
            }
        }

        if (!title.isNullOrBlank() && !isAppNameOrGeneric(title)) {
            return title.trim()
        }

        return null
    }

    private fun isIgnored(candidate: String): Boolean {
        val trimmed = candidate.trim()
        val normalized = trimmed.uppercase().replace("-", "").replace("_", "").trim()
        if (IGNORED_IDENTIFIERS.contains(normalized)) return true

        if (DIGIT_ONLY_REGEX.matches(trimmed)) return true

        val lower = trimmed.lowercase()
        if (lower.contains("block") || lower.contains("call") || lower.contains("sms") || lower.contains("toll free")) {
            return true
        }

        return false
    }

    private fun isAppNameOrGeneric(title: String): Boolean {
        val t = title.lowercase().trim()
        val isGenericApp = t in setOf(
            "google pay", "gpay", "phonepe", "paytm", "bhim", "alert",
            "bank", "credit alert", "debit alert"
        )
        val isSmsHeader = SMS_HEADER_REGEX.matches(title) || BANK_HEADER_REGEX.matches(title) ||
                title.uppercase() in setOf(
            "FEDBK", "FEDBNK", "HDFCBK", "SBIINB", "ICICIB",
            "AXISBK", "KOTAKB", "CANBNK", "PNBSMS", "SIBL", "YESBNK", "SIBSMS" // <-- ADDED SIBSMS
        )
        return isGenericApp || isSmsHeader
    }

    private fun cleanPayee(payee: String?): String? {
        if (payee.isNullOrBlank()) return null
        var cleaned = payee.replace(VPA_CLEANUP_REGEX, "").trimEnd('.', ',', '-', ' ', ':').take(45)

        // 1. Extract human name
        val parenthesized = PAYEE_PARENTHESES_REGEX.find(cleaned)?.groupValues?.get(1)?.trim()
        if (!parenthesized.isNullOrBlank() && !parenthesized.all { it.isDigit() } && !isIgnored(parenthesized)) {
            cleaned = parenthesized
        }

        // 2. Strip VPA handles
        if (cleaned.contains("@")) {
            var username = cleaned.substringBefore("@").replace(".", " ").replace("_", " ").replace("-", " ").trim()
            username = username.replace(TRAILING_DIGITS_REGEX, "").trim()
            if (username.length >= 2 && !username.all { it.isDigit() }) cleaned = username
        }

        // 3 & 4. Clean noise
        cleaned = cleaned.replace(WEB_DOMAIN_REGEX, "").trim()
        cleaned = cleaned.replace(CORPORATE_NOISE_REGEX, "").trim()
        cleaned = cleaned.trim(' ', '.', ',', '-', ':', '_')

        if (cleaned.isBlank() || DIGIT_ONLY_REGEX.matches(cleaned) || isIgnored(cleaned)) {
            return null
        }

        return cleaned.lowercase().split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
        }.ifBlank { null }
    }

    private fun extractBankName(text: String, title: String?): String? {
        // The DLT-registered sender header is the authoritative signal for which bank
        // actually owns this route. Some banks route SMS through a correspondent/
        // gateway bank whose name appears in the BODY text (e.g. a message reading
        // "Dear SBI User...-SBI" that actually arrives from a Federal Bank sender
        // header), so body text alone can misidentify the bank. Header text can't be
        // spoofed the way a template's boilerplate signature can — check it first.
        if (!title.isNullOrBlank()) {
            for ((pattern, canonical) in KNOWN_BANK_ALIASES) {
                if (pattern.containsMatchIn(title)) return canonical
            }
            val titleMatch = TITLE_BANK_REGEX.find(title)
            if (titleMatch != null) {
                return titleMatch.groupValues[1].uppercase() + " Bank"
            }
        }

        // No recognizable bank header (e.g. a generic app sender like "Google Pay")
        // — fall back to scanning the body text as before.
        val subtextMatch = SECONDARY_TEXT_BANK_PATTERN.find(text)
        if (subtextMatch != null) {
            val rawBank = subtextMatch.groupValues[1].trim()
            if (rawBank.isNotBlank() && rawBank.length > 2 && !rawBank.equals("UPI", ignoreCase = true)) {
                return rawBank.uppercase() + " Bank"
            }
        }

        for (pattern in BANK_NAME_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val bank = matcher.group(1)?.trim()
                if (!bank.isNullOrBlank()) {
                    return bank.replace(Regex("(?i)\\s+bank$"), " Bank")
                        .replaceFirstChar { it.uppercase() }
                }
            }
        }

        return null
    }

    private fun detectTransactionType(rawText: String): TransactionType? {
        val sanitized = CREDIT_CARD_PATTERN.matcher(rawText).replaceAll("")
        val hasCredit = CREDIT_PATTERN.matcher(sanitized).find()
        val hasDebit = DEBIT_PATTERN.matcher(sanitized).find()

        // FIXED: Dropped the earliest-match rule that forced "Dr. Sharma" into a DEBIT
        return when {
            hasCredit && !hasDebit -> TransactionType.CREDIT
            hasDebit && !hasCredit -> TransactionType.DEBIT
            else -> null // Ambiguous texts go to review
        }
    }

    private fun normalizeBankName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        for ((pattern, canonical) in KNOWN_BANK_ALIASES) {
            if (pattern.containsMatchIn(trimmed)) return canonical
        }

        return trimmed.replace(Regex("\\s+"), " ")
            .lowercase()
            .split(" ")
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .ifBlank { null }
    }

    private fun normalizeAccountNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digitsOnly = raw.filter { it.isDigit() }
        if (digitsOnly.isBlank()) return null
        return digitsOnly.takeLast(4)
    }

    private fun extractReferenceId(text: String): String? {
        for (pattern in REF_ID_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                // Return matched group 1 (if exists) else the entire match
                return if (matcher.groupCount() > 0) matcher.group(1)?.trim() else matcher.group()?.trim()
            }
        }
        return null
    }

    private fun extractAccountNumber(text: String): String? {
        val matcher = ACCOUNT_NUM_PATTERN.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }

        val subtextMatch = SECONDARY_TEXT_ACCOUNT_PATTERN.find(text)
        if (subtextMatch != null) {
            return subtextMatch.groupValues[1]
        }

        return null
    }
}
