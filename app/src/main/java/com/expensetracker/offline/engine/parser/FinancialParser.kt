package com.expensetracker.offline.engine.parser

import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.ml.SmsPatternLearner
import java.math.BigDecimal
import java.util.Locale
import java.util.regex.Pattern

object FinancialParser {

    // ─────────────────────────────────────────────────────────────────────────
    // PRECOMPILED PATTERNS (Critical for performance during batch SMS scanning)
    // ─────────────────────────────────────────────────────────────────────────

    // Word boundaries (\b) so "consent" doesn't match "sent"
    private val FINANCIAL_KEYWORDS = listOf(
        "\\bdebited\\b", "\\bdebit\\b", "\\bcredited\\b", "\\bcredit\\b", "\\bspent\\b", "\\bpaid\\b",
        "\\btransferred\\b", "\\bsent\\b", "\\breceived\\b", "\\brefunded\\b", "\\bwithdrawn\\b",
        "\\bdeposited\\b", "\\bdeducted\\b", "\\bsent you\\b", "\\bpaid you\\b",
        "\\bautopay\\b", "\\bcharged\\b", "\\bused\\s+(?:for|at)\\b", "\\bpurchase\\b", "\\bmandate\\s+executed\\b", "\\btoll\\s+plaza\\b", "\\bint\\.pd\\b"
    ).map { Pattern.compile("(?i)$it") }

    // Reject list for OTPs, limits, failed transactions, future mandate reminders, and telecom data/reward promos
    private val REJECT_PATTERNS = listOf(
        "\\botp\\b", "\\bdeclined\\b", "\\bfailed\\b",
        "\\btotal due\\b", "\\bwill be debited\\b", "\\bcollect request\\b",
        "\\b\\d+(?:\\.\\d+)?\\s*(?:gb|mb|tb)\\s+data\\b",
        "\\bdata\\s+(?:credited|added|pack|balance|quota|voucher|loan)\\b",
        "\\b(?:vi|jio|airtel|bsnl)\\s+a/c\\b",
        "\\bvalidity\\s*[:\\-]?\\s*\\d+\\s*(?:days?|hrs?|hours?|months?)\\b",
        "\\b(?:reward\\s+points?|supercoins?|loyalty\\s+points?)\\s+(?:credited|added|expiring|worth)\\b",
        "\\bonelink\\.me\\b"
    ).map { Pattern.compile("(?i)$it") }

    // A reversal puts money back into the account. Messages that are both failed and reversed
    // ("payment failed, amount reversed") are still rejected by REJECT_PATTERNS.
    private val REVERSAL_PATTERN = Pattern.compile("(?i)\\breversed\\b")

    // Removed "cr." and "dr." from generic matchers to fix the "Dr. Sharma" bug. Prefer verbs.
    private val CREDIT_PATTERN = Pattern.compile("(?i)\\b(credit|credited|received|refunded|deposited|added|sent you|paid you|transfer(?:red)? from|int\\.pd|interest\\s+credited)\\b")
    private val DEBIT_PATTERN = Pattern.compile("(?i)\\b(debit|debited|spent|paid(?!\\s+you)|deducted|withdrawn|sent(?!\\s+you)|transfer(?:red)? to|autopay|charged|used\\s+(?:for|at)\\s+(?:a\\s+)?(?:transaction|purchase|payment)|purchase\\s+of|mandate\\s+executed|thank\\s+you\\s+for\\s+using\\s+(?:your\\s+)?[A-Za-z0-9 ]{0,30}?card)\\b")
    private val CREDIT_CARD_PATTERN = Pattern.compile("(?i)\\bcredit\\s*(?:card|limit|line|bill)\\b")
    private val CC_ACCOUNT_DETECT_PATTERN = Pattern.compile("(?i)\\b(credit\\s*card|cc\\s+xx|cc\\s+ending|onecard|slice\\s+card|amex|american\\s+express|lazypay|simpl)\\b")
    // NOTE: upi/imps/neft/rtgs are deliberately NOT here. A RuPay credit card used over UPI says
    // "credit card ... via UPI" and must stay a credit card. Only real bank-account markers count.
    private val BANK_ACCOUNT_OR_DEBIT_CARD_PATTERN = Pattern.compile("(?i)\\b(debit\\s*card|atm\\s*card|a/c|acct|savings|avl\\s*bal|avail\\s*bal|available\\s*balance)\\b")
    // "Avl Lmt" / "Available Credit Limit" only ever appears on credit-card alerts.
    private val CC_LIMIT_SIGNAL_PATTERN = Pattern.compile("(?i)\\b(?:avl|avail(?:able)?)\\.?\\s*(?:cr(?:edit)?\\.?\\s*)?(?:lmt|limit)\\b")
    // Bank-side or card-side message about paying a card bill (both legs must be excluded from spend).
    private val CC_BILL_PATTERN = Pattern.compile("(?i)\\b(credit\\s*card\\s*(?:no\\.?\\s*)?[*xX.]*\\d*\\s*(?:bill|payment|pmt|dues?|repayment)|cc\\s*(?:bill\\s*)?payment|billdesk\\s*cc|(?:towards|for|repay(?:ment)?\\s+of)\\s+(?:your\\s+)?(?:[a-z]+\\s+){0,3}credit\\s*card)\\b")
    private val SELF_TRANSFER_PATTERN = Pattern.compile("(?i)\\b(self\\s*transfer|transfer(?:red)?\\s+to\\s+self|to\\s+own\\s+a/c|internal\\s+transfer|auto[-\\s]?sweep|sweep\\s*(?:in|out)|linked\\s+a/c)\\b")
    private val REFUND_FLAG_PATTERN = Pattern.compile("(?i)\\b(refund(?:ed)?|cashback)\\b")

    private val PROMO_PATTERNS = Pattern.compile("(?i)\\b(upgrade\\s+today|recharge\\s+(?:now|with|for)|plan|validity|per\\s+day|days\\b|pre-approved|apply\\s+now|congrats|congratulations|exclusive\\s+offer|special\\s+offer|discount|flat\\s+(?:rs|inr|₹)|free\\s+trial|win\\b|hurry|coupon|gift\\s+card|promo\\s+code|valid\\s+till|dial\\s+\\*|unlimited\\s+data|calls\\s+for\\s+just|bonus)\\b")
    private val BANK_INDICATORS = Pattern.compile("(?i)\\b(a/c|acct|account|vpa|upi\\s*ref|rrn|txn|card\\s+xx|avl\\s+bal|avail\\s+bal|balance|bank)\\b")

    private val DISCLAIMER_PATTERN = Pattern.compile("(?is)\\b(?:block\\s*(?:a/c|card|upi)|if\\s+not\\s+(?:done|you|u)|call\\s*[-:]?\\s*\\d+|sms\\s+blk|not\\s+(?:you|u)\\s*\\?).*$")

    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("(?i)(?:inr|rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("(?i)([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:inr|rs\\.?|₹)"),
        Pattern.compile("(?i)(?:amount|amt|spent|paid|for|of|by)\\s*(?:of)?\\s*(?:inr|rs\\.?|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")
    )
    private val BALANCE_PATTERN = Pattern.compile("(?i)(?:final balance|available balance|avail bal|avl bal|bal(?:ance)?)\\s*(?:is|:|-)?\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")

    private val AVAILABLE_LIMIT_VALUE_PATTERN = Pattern.compile("(?i)\\b(?:avl|avail(?:able)?)\\.?\\s*(?:cr(?:edit)?\\.?\\s*)?(?:lmt|limit)\\b[^0-9]{0,14}([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val BALANCE_SANITIZER_REGEX = BALANCE_PATTERN.toRegex()
    // "Avl limit INR 38898.03" is what is LEFT on the card after the spend; it must never be read as the amount.
    private val LIMIT_SANITIZER_REGEX = Regex("(?i)\\b(?:total\\s+)?(?:avl|avail(?:able)?)\\.?\\s*(?:cr(?:edit)?\\.?\\s*)?(?:lmt|limit)\\b[^0-9]{0,14}[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
    private val FOREIGN_AMOUNT_REGEX = Regex("(?<![A-Za-z])(" + ForeignCurrency.codes.joinToString("|") + ")\\s*[:.]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val DOLLAR_AMOUNT_REGEX = Regex("(?<![A-Za-z0-9])(?:US)?\\$\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val QUOTED_NOTE_REGEX = Regex("""["“]([^"”\n\r]{2,80})["”]""")
    private val PREFIX_NOTE_REGEX = Regex("""(?i)(?:note\s*[:\-]\s*|for\s+["']?)([^"'\n\r,]+)(?:["']|\s*(?:using|via|upi|ref|$))""")
    private val SYSTEM_NOISE_REGEX = Regex("""(?i)(upi ref|transaction id|paid using|bank account|google pay|\d{10,16})""")
    private val DIGIT_ONLY_REGEX = Regex("""^\d{6,16}.*""")
    private val VPA_CLEANUP_REGEX = Regex("(?i)^(vpa|a/c|acct|upi|account)\\s+")
    private val SMS_HEADER_REGEX = Regex("""(?i)^[a-z]{2}-[a-z0-9]{4,8}(?:-[a-z0-9]+)?$""")
    private val BANK_HEADER_REGEX = Regex("""(?i)^[a-z]{3,8}(?:bk|bnk|upi|bank)$""")

    private val BANK_ACCT_NUM_PATTERN = Pattern.compile("(?i)(?:a/c|acct|account)(?:\\s+no\\.?|\\s+number|\\s+ending(?:\\s+in|\\s+with)?)?\\s*[:\\-]?\\s*[*xX.]*(\\d{3,6})\\b")
    private val ACCOUNT_NUM_PATTERN = Pattern.compile("(?i)(?:a/c|acct|account|card)(?:\\s+no\\.?|\\s+number|\\s+ending(?:\\s+in|\\s+with)?)?\\s*[:\\-]?\\s*[*xX.]*(\\d{3,6})\\b")

    private val BANK_NAME_PATTERNS = listOf(
        Pattern.compile("(?i)-\\s*([a-zA-Z\\s]+(?:Bank|SBI|Card))\\s*$", Pattern.MULTILINE),
        Pattern.compile("(?i)\\b((?:HDFC|SBI|ICICI|Axis|Kotak|PNB|Federal|Canara|Union|IDFC|IndusInd|Yes|South Indian|Bank of Baroda|Bank of India|Indian|Indian Overseas|Central Bank|UCO|Punjab & Sind|IDBI|Bandhan|RBL|Karnataka|Karur Vysya|City Union|Tamilnad Mercantile|Dhanlaxmi|CSB|Saraswat|Kerala|Kerala Gramin|AU Small Finance|Equitas|Ujjivan|Utkarsh|Jana|ESAF|DBS|Standard Chartered|HSBC|Citi|American Express|OneCard|Slice|Jupiter|Fi|Airtel Payments|Jio Payments|India Post|Fino Payments)[\\sA-Za-z]*(?:Bank|Card)?)\\b")
    )

    // Expanded to 60+ Indian banks, small finance banks, payments banks, credit cards, and wallets
    private val KNOWN_BANK_ALIASES: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bhdfc\\b|hdfcbk|hdfcbank") to "HDFC Bank",
        Regex("(?i)\\bsbi\\b|state\\s*bank|sbiinb|sbipsg|sbicrd|yonosbi") to "SBI",
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
        Regex("(?i)\\bdbs\\b|dbsbnk") to "DBS Bank",
        Regex("(?i)\\bstandard\\s*chartered\\b|scbank|\\bscb\\b") to "Standard Chartered",
        Regex("(?i)\\bhsbc\\b") to "HSBC",
        Regex("(?i)\\bciti\\b|citibk") to "Citibank",
        Regex("(?i)\\bamex\\b|american\\s*express") to "American Express",
        Regex("(?i)\\bonecard\\b|fpl\\s*technologies") to "OneCard",
        Regex("(?i)\\bslice\\b|sliceit") to "Slice",
        Regex("(?i)\\bjupiter\\b") to "Jupiter",
        Regex("(?i)\\bfi\\s*money\\b|epifi") to "Fi Money",
        Regex("(?i)\\bairtel\\s*payments?\\s*bank\\b|airbnk|airp") to "Airtel Payments Bank",
        Regex("(?i)\\bjio\\s*payments?\\s*bank\\b|jiopay|jiobnk") to "Jio Payments Bank",
        Regex("(?i)\\bindia\\s*post\\b|\\bippb\\b|ippbsm") to "India Post Payments Bank",
        Regex("(?i)\\bfino\\s*payments?\\b|finobk") to "Fino Payments Bank",
        Regex("(?i)\\bnsdl\\s*payments?\\b|nsdlpb") to "NSDL Payments Bank",
        Regex("(?i)\\bamazon\\s*pay\\b|amzpay") to "Amazon Pay",
        Regex("(?i)\\bpluxee\\b|\\bsodexo\\b") to "Pluxee",
        Regex("(?i)\\blazypay\\b") to "LazyPay",
        Regex("(?i)\\bsimpl\\b") to "Simpl"
    )

    private val SECONDARY_TEXT_ACCOUNT_PATTERN = Regex(
        pattern = """(?i)(?:a/c|acct|account|card|ending in|ending with)\s*[-:]?\s*[*xX]*(\d{4,})"""
    )

    private val SECONDARY_TEXT_BANK_PATTERN = Regex(
        pattern = """(?:using|via|from(?:\s+your)?)\s+([a-zA-Z\s]{2,25}?)\s+(?:Bank|A/c|Account|Card)\b""",
        option = RegexOption.IGNORE_CASE
    )

    private val TITLE_BANK_REGEX = Regex("(?i)^(?:[A-Z]{2}-)?(HDFC|SBI|ICICI|AXIS|KOTAK|PNB|FEDERAL|FEDBNK|CANARA|UNION|IDFC|INDUSIND|YES|SIBSMS|BOB|BOI|IOB|IDBI|RBL|KVB|CUB|TMB|DBS|HSBC|AMEX|ONECARD|SLICE|AUBANK|EQUITAS|UJJIVAN|IPPB|ESAF)")

    // Universal lookahead terminator for Indian bank & UPI SMS payee slots (handles periods, commas,
    // parentheses, "Final balance", "Avl bal", "Ref", "RRN", "UTR", "on", "via", "-South Indian Bank", etc.)
    private const val PAYEE_TERMINATOR =
        "(?=(?:\\s*\\(|\\s+(?:from|on|using|via|ref|rrn|utr|upi|avl|bal|avail|final|umrn|info|chq|thru|not\\s+you|if\\s+not)|[.,;()]|\\s+-|$))"

    /** "A/c XX123 debited", "your a/c no. XXXX1234 is debited", "Acct XX1 has been credited": the first verb after the user's own account. */
    private val OWN_ACCOUNT_VERB_PATTERN = Pattern.compile(
        "(?i)\\b(?:a/?c|acct|account|card)(?:\\b|(?=[xX*\\d]))[^;\\n]{0,45}?\\b(debited|credited)\\b"
    )

    /** "Rs.250 debited A/cXX1234", "credited to your a/c XX1": the verb written before the account. */
    private val VERB_THEN_OWN_ACCOUNT_PATTERN = Pattern.compile(
        "(?i)\\b(debited|credited)\\b(?:\\s+(?:from|to|in|into))?\\s+(?:your\\s+|my\\s+)?(?:a/?c|acct|account|card)(?:\\b|(?=[xX*\\d]))"
    )

    private val ATM_PATTERN = Pattern.compile(
        "(?i)\\b(?:atm\\s*(?:wdl|withdrawal|cash|txn)|cash\\s+withdrawal\\s+at\\s+atm|withdrawn\\s+(?:at|from)\\s+atm|nfs\\s*(?:cash|wdl))\\b"
    )

    private val PAYEE_PATTERNS = listOf(
        // ICICI debit alerts: "...debited for Rs 500.00 on 12-Oct-25; SWIGGY credited. UPI:123"
        Pattern.compile("(?i);\\s*([A-Za-z0-9@._\\-&'\\s]{2,35}?)\\s+credited\\b"),
        // "Info: UPI-529012345678-MAIN CANTEEN": skip the channel + reference, keep the merchant
        Pattern.compile("(?i)\\binfo[:\\s]+(?:upi|imps|neft|rtgs)[-/]\\d{6,}[-/]([A-Za-z0-9@._\\-&'\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:^|.*?[\\n:])\\s*([A-Za-z0-9\\s._\\-]{2,35}?)\\s+(?:paid|sent)\\s+you", Pattern.MULTILINE),
        Pattern.compile("(?i)(?:via\\s+upi\\s+to|upi\\s+to|by\\s+upi\\s+to)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:received(?:\\s+[₹Rs\\d,.]+)?\\s+from)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:by transfer from|transferred from|transfer from|trf from)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)by UPI\\s*[:\\-]\\s*([A-Za-z0-9\\s._\\-]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:linked to VPA|from VPA|to VPA|vpa[:\\s])\\s*([A-Za-z0-9@._\\-]+)"),
        // Kotak-style: "Sent Rs.120.00 from Kotak Bank AC X1234 to nidhi@ybl on 12-10-25"
        Pattern.compile("(?i)\\bsent\\s+(?:inr|rs\\.?|₹)\\s*[0-9,.]+\\s+from\\s+[A-Za-z0-9 ]{2,40}?\\s+to\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:paid|sent)\\s+(?:inr|rs\\.?|₹)?\\s*[0-9,.]*\\s+to\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:you\\s+paid|you\\s+sent)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)(?=\\s+(?:inr|rs\\.?|₹|[0-9]))"),
        Pattern.compile("(?i)(?:paid to|transferred to|by transfer to|transfer to|trf to|sent to|credited to)\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:debited|debit)\\b[^.\\n]{0,65}?\\bto\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:credited|credit)\\b[^.\\n]{0,65}?\\bfrom\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)^To\\s+([A-Za-z0-9@._\\-\\s]{2,35}?)$PAYEE_TERMINATOR", Pattern.MULTILINE),
        Pattern.compile("(?i)\\b(?:plaza\\s*:\\s*|for\\s+toll\\s+at\\s+)([A-Za-z0-9\\s._\\-]{2,35}?)$PAYEE_TERMINATOR"),
        Pattern.compile("(?i)(?:at|towards|info[:/\\-\\s])\\s+([A-Za-z0-9@._\\-*\\s]{2,45}?)$PAYEE_TERMINATOR")
    )

    // Note: [.,;()] is outside \s+ so "UPI/6284123456/MERCHANT. Final balance" stops cleanly at the period!
    private val UPI_SLASH_PATTERN = Pattern.compile(
        "(?i)UPI[:/]([A-Za-z0-9/_\\-.\\s]+?)(?=(?:\\s*\\(|\\s+(?:on|using|via|ref|rrn|utr|avl|bal|avail|final|sms|call|not\\s+you)|[.,;()]|\\s+-|$))"
    )

    private val REF_ID_PATTERNS = listOf(
        Pattern.compile("(?i)(?:upi ref(?: no)?|ref no|rrn|txn id|transaction id|umrn)[:\\s.]*([0-9a-zA-Z]{9,})"),
        Pattern.compile("(?i)UPI/(?:[A-Za-z0-9]+/)*?([0-9]{10,14})"),
        Pattern.compile("(?i)\\b(?=[a-zA-Z]*\\d)[a-zA-Z0-9]{9,}\\b")
    )

    private val IGNORED_IDENTIFIERS = setOf(
        "UPI", "CR", "DR", "P2A", "P2M", "REV", "NA", "INFO", "NULL",
        "MERCHANT", "UPIMERCHANT", "UPI-MERCHANT", "PAYEE", "PAYMENT", "TRANSFER",
        "COLLECT", "NODAL", "SIBL", "HDFC", "ICICI", "SBIN", "UTIB", "KKBK",
        "FDRL", "YESB", "PAYTM", "BILLDESK", "RAZORPAY", "CCAVENUE", "AIRP", "KVB", "KVBL",
        "FEDBK", "FEDBNK", "FEDERAL", "BARB", "BKID", "CBIN", "IOBA", "IDIB",
        "AC", "ACCT", "ACCOUNT", "ACNO", "YOURAC", "YOURACCOUNT", "SELF", "FINAL", "BALANCE"
    )

    private val ACCOUNT_REF_NOISE_REGEX = Regex(
        """(?i)^(?:your\s+)?(?:a/c|acct|account|card|sb|ca|od)\b.*|^[xX*#.\d\s\-]+$"""
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

        // 0. Consult SmsPatternLearner for user-approved or user-discarded message templates
        val learnedEval = SmsPatternLearner.evaluate(title, rawText)
        if (learnedEval.isBlockedNonTransaction) {
            return createRejected(rawText, "Blocked by learned user discard pattern")
        }

        // 1. Run Reject List First (OTPs, failed, declined, future mandates)
        for (pattern in REJECT_PATTERNS) {
            if (pattern.matcher(combinedText).find()) {
                return createRejected(rawText, "Matched reject pattern: ${pattern.pattern()}")
            }
        }

        val lowerText = combinedText.lowercase(Locale.ROOT)
        val hasTransactionVerb = FINANCIAL_KEYWORDS.any { it.matcher(lowerText).find() }
        val isReversal = REVERSAL_PATTERN.matcher(combinedText).find()
        val detectedType = if (isReversal) TransactionType.CREDIT else detectTransactionType(combinedText)
        val type = detectedType ?: if (learnedEval.isConfirmedTransaction) learnedEval.inferredType else null

        if (!hasTransactionVerb && type == null && !learnedEval.isConfirmedTransaction) {
            return createRejected(rawText, "No financial keywords found")
        }

        val isPromo = PROMO_PATTERNS.matcher(combinedText).find()
        val hasBankVerification = BANK_INDICATORS.matcher(combinedText).find()

        // 2. Conditional Promo Filter (Only drop if it's an SMS and not an explicitly approved template)
        if (!learnedEval.isConfirmedTransaction && isPromo && !hasBankVerification && source == TransactionSource.SMS) {
            return createRejected(rawText, "Matched SMS promo pattern")
        }

        val cleanedForPayee = DISCLAIMER_PATTERN.matcher(combinedText).replaceAll("").trim()

        val balance = extractBalance(combinedText)
        val inrAmount = extractAmount(combinedText)
        // International spend: "USD 2.12 spent on ... Avl limit INR 38898.03" has no INR amount of its own.
        val foreign = extractForeign(combinedText)
        val foreignEstimate = if (inrAmount == null && foreign != null) ForeignCurrency.approxInrPaise(foreign.first, foreign.second) else null
        val amount = inrAmount ?: foreignEstimate

        // A message with no monetary amount and no account balance is never a financial transaction
        if ((amount == null || amount <= 0L) && balance == null && !learnedEval.isConfirmedTransaction) {
            return createRejected(rawText, "No monetary amount or balance found")
        }
        // If promo keywords matched and there is no transaction amount, reject it
        if (!learnedEval.isConfirmedTransaction && isPromo && (amount == null || amount <= 0L)) {
            return createRejected(rawText, "Promo message without transaction amount")
        }

        val vpaLearnedPayee = SmsPatternLearner.lookupVpa(combinedText)?.payee
        val rawPayee = vpaLearnedPayee
            ?: extractPayee(cleanedForPayee, title, type ?: TransactionType.DEBIT)
            ?: learnedEval.inferredPayee
        val refId = extractReferenceId(combinedText)
        val isRefund = REFUND_FLAG_PATTERN.matcher(combinedText).find()
        val extractedNote = extractGPayNote(rawText = rawText, title = title)
        val note = when {
            extractedNote != null && foreign == null -> extractedNote
            foreign != null -> "International · ${foreign.first} ${foreign.second}"
            isRefund && type == TransactionType.CREDIT -> "Refund / Cashback"
            else -> null
        }

        val rawBankName = extractBankName(combinedText, title)
            ?: (if (source == TransactionSource.SMS) learnedEval.inferredBankName else null)
        val hasBankOrDebitContext = BANK_ACCOUNT_OR_DEBIT_CARD_PATTERN.matcher(combinedText).find()
        val hasCcSignal = CC_ACCOUNT_DETECT_PATTERN.matcher(cleanedForPayee).find() ||
                CC_LIMIT_SIGNAL_PATTERN.matcher(combinedText).find()
        val isCreditCard = hasCcSignal && !hasBankOrDebitContext
        val normalizedBank = normalizeBankName(rawBankName)
        val bankName = when {
            normalizedBank != null && isCreditCard && !normalizedBank.contains("Card", ignoreCase = true) &&
                    normalizedBank !in setOf("OneCard", "Slice", "American Express", "LazyPay", "Simpl") ->
                "$normalizedBank CC"
            normalizedBank == null && isCreditCard -> "Credit Card"
            else -> normalizedBank
        }
        val accountNumber = normalizeAccountNumber(extractAccountNumber(combinedText))

        // 3. CC and Self-Transfer Flagging (Note: refunds are NOT excluded so they can offset spend)
        val isReversalText = REVERSAL_PATTERN.matcher(combinedText).find()
        // On a card, a CREDIT that is not a refund/cashback/reversal is the user paying the bill.
        val isCcPaymentReceived = isCreditCard && type == TransactionType.CREDIT && !isRefund && !isReversalText
        val isCcPayment = CC_BILL_PATTERN.matcher(combinedText).find() || isCcPaymentReceived
        val isSelfTransfer = SELF_TRANSFER_PATTERN.matcher(combinedText).find()
        val isTransferFlag = isCcPayment || isSelfTransfer

        val cleanedPayee = if (isCcPayment) "Credit Card Bill" else cleanPayee(rawPayee) ?: when {
            isSelfTransfer -> "Self Transfer"
            else -> null
        }

        return ParsedTransaction(
            isFinancial = true,
            amount = amount,
            payee = cleanedPayee,
            type = type,
            referenceId = refId,
            balance = balance,
            note = note,
            bankName = bankName,
            accountNumber = accountNumber,
            rawText = rawText,
            currency = "INR",
            accountType = if (isCreditCard) "CREDIT_CARD" else "BANK",
            isTransfer = isTransferFlag,
            excludeFromSpend = isCcPayment || isRefund || isSelfTransfer,
            confidence = if (type != null && amount != null) 1.0f else 0.5f,
            availableLimit = if (isCreditCard) extractAvailableLimit(combinedText) else null,
            foreignCurrency = foreign?.first,
            foreignAmountText = foreign?.second,
            amountIsApproximate = foreignEstimate != null
        )
    }

    private fun extractAvailableLimit(text: String): Long? {
        val m = AVAILABLE_LIMIT_VALUE_PATTERN.matcher(text)
        if (!m.find()) return null
        return m.group(1)?.replace(",", "")?.trim()?.toBigDecimalOrNull()
            ?.multiply(BigDecimal("100"))?.toLong()
    }

    fun inferFallbackPayee(
        rawText: String,
        type: TransactionType?,
        userFallback: String = "UPI Payment"
    ): String {
        val lower = rawText.lowercase(Locale.ROOT)
        val effectiveDefault = if (userFallback.isBlank() || userFallback.equals("Bank Transfer", ignoreCase = true)) {
            "UPI Payment"
        } else {
            userFallback
        }
        return when {
            lower.contains("fastag") || lower.contains("toll plaza") -> "FASTag Toll"
            lower.contains("upi") -> if (type == TransactionType.CREDIT) "UPI Received" else effectiveDefault
            lower.contains("atm") || lower.contains("withdrawn") -> "ATM Withdrawal"
            lower.contains("pos ") || lower.contains("debit card") || lower.contains("card ending") -> "Card Spend"
            lower.contains("imps") || lower.contains("neft") || lower.contains("rtgs") ->
                if (type == TransactionType.CREDIT) "Bank Credit" else "Bank Transfer"
            type == TransactionType.CREDIT -> "Bank Credit"
            else -> effectiveDefault
        }
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
        val sanitized = text.replace(BALANCE_SANITIZER_REGEX, "").replace(LIMIT_SANITIZER_REGEX, "")
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

    /** (currency code, amount text) of a foreign-currency amount such as "USD 2.12" or "$2.12", if any. */
    private fun extractForeign(text: String): Pair<String, String>? {
        val sanitized = text.replace(BALANCE_SANITIZER_REGEX, "").replace(LIMIT_SANITIZER_REGEX, "")
        FOREIGN_AMOUNT_REGEX.find(sanitized)?.let { return it.groupValues[1].uppercase(Locale.ROOT) to it.groupValues[2] }
        DOLLAR_AMOUNT_REGEX.find(sanitized)?.let { return "USD" to it.groupValues[1] }
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
        if (type == TransactionType.DEBIT && ATM_PATTERN.matcher(text).find()) return "ATM Withdrawal"
        for (pattern in PAYEE_PATTERNS) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
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
                .map { it.trim(' ', '.', ',', '-', ':', ';', '(', ')') }
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
        val trimmed = candidate.trim(' ', '.', ',', '-', ':', ';', '(', ')')
        if (trimmed.length < 2) return true
        val normalized = trimmed.uppercase(Locale.ROOT).replace("-", "").replace("_", "").replace("/", "").replace(" ", "").trim()
        if (IGNORED_IDENTIFIERS.contains(normalized)) return true

        if (DIGIT_ONLY_REGEX.matches(trimmed) || ACCOUNT_REF_NOISE_REGEX.matches(trimmed)) return true

        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.contains("block") || lower.contains("call") || lower.contains("sms") ||
            lower.contains("toll free") || lower.contains("final balance") || lower.contains("avl bal") ||
            lower.contains("avail bal") || lower.contains("upi ref") || lower.contains("ref no") ||
            lower.contains("credit card") || lower.contains("debit card")
        ) {
            return true
        }

        return false
    }

    private fun isAppNameOrGeneric(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT).trim()
        val isGenericApp = t in setOf(
            "google pay", "gpay", "phonepe", "paytm", "bhim", "alert",
            "bank", "credit alert", "debit alert", "amazon pay", "super.money",
            "mobikwik", "slice", "navi", "samsung wallet", "fi money", "jupiter", "freecharge",
            "cred", "kiwi", "tata neu", "fampay", "whatsapp"
        )
        val isSmsHeader = SMS_HEADER_REGEX.matches(title) || BANK_HEADER_REGEX.matches(title) ||
                title.uppercase(Locale.ROOT) in setOf(
            "FEDBK", "FEDBNK", "HDFCBK", "SBIINB", "ICICIB",
            "AXISBK", "KOTAKB", "CANBNK", "PNBSMS", "SIBL", "YESBNK", "SIBSMS",
            "BOBTXN", "BOIIND", "IOBCHN", "INDBNK", "CBIN", "UCOBNK", "IDBIBK", "RBLBNK"
        )
        return isGenericApp || isSmsHeader
    }

    private fun cleanPayee(payee: String?): String? {
        if (payee.isNullOrBlank()) return null
        var cleaned = payee.replace(VPA_CLEANUP_REGEX, "").trimEnd('.', ',', '-', ' ', ':').take(45)

        val parenthesized = PAYEE_PARENTHESES_REGEX.find(cleaned)?.groupValues?.get(1)?.trim()
        if (!parenthesized.isNullOrBlank() && !parenthesized.all { it.isDigit() } && !isIgnored(parenthesized)) {
            cleaned = parenthesized
        }

        if (cleaned.contains("@")) {
            var username = cleaned.substringBefore("@").replace(".", " ").replace("_", " ").replace("-", " ").trim()
            username = username.replace(TRAILING_DIGITS_REGEX, "").trim()
            if (username.length >= 2 && !username.all { it.isDigit() }) cleaned = username
        }

        cleaned = cleaned.replace(WEB_DOMAIN_REGEX, "").trim()
        cleaned = cleaned.replace(CORPORATE_NOISE_REGEX, "").trim()
        cleaned = cleaned.trim(' ', '.', ',', '-', ':', '_')

        if (cleaned.isBlank() || DIGIT_ONLY_REGEX.matches(cleaned) || isIgnored(cleaned)) {
            return null
        }

        val titleCased = cleaned.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }.ifBlank { return null }

        return when {
            titleCased.equals("Google I", ignoreCase = true) -> "Google India"
            else -> titleCased
        }
    }

    private fun extractBankName(text: String, title: String?): String? {
        if (!title.isNullOrBlank()) {
            for ((pattern, canonical) in KNOWN_BANK_ALIASES) {
                if (pattern.containsMatchIn(title)) return canonical
            }
            val titleMatch = TITLE_BANK_REGEX.find(title)
            if (titleMatch != null) {
                return titleMatch.groupValues[1].uppercase(Locale.ROOT) + " Bank"
            }
        }

        val subtextMatch = SECONDARY_TEXT_BANK_PATTERN.find(text)
        if (subtextMatch != null) {
            val rawBank = subtextMatch.groupValues[1].trim()
            if (rawBank.isNotBlank() && rawBank.length > 2 && !rawBank.equals("UPI", ignoreCase = true)) {
                return rawBank.uppercase(Locale.ROOT) + " Bank"
            }
        }

        for ((pattern, canonical) in KNOWN_BANK_ALIASES) {
            if (pattern.containsMatchIn(text)) return canonical
        }

        for (pattern in BANK_NAME_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val bank = matcher.group(1)?.trim()
                if (!bank.isNullOrBlank()) {
                    return bank.replace(Regex("(?i)\\s+bank$"), " Bank")
                        .replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
            }
        }

        return null
    }

    private fun detectTransactionType(rawText: String): TransactionType? {
        val sanitized = CREDIT_CARD_PATTERN.matcher(rawText).replaceAll("")
        val creditMatcher = CREDIT_PATTERN.matcher(sanitized)
        val debitMatcher = DEBIT_PATTERN.matcher(sanitized)
        val hasCredit = creditMatcher.find()
        val hasDebit = debitMatcher.find()

        return when {
            hasCredit && !hasDebit -> TransactionType.CREDIT
            hasDebit && !hasCredit -> TransactionType.DEBIT
            // Both verbs present ("Acct XX123 debited for Rs 500; SWIGGY credited", IMPS/NEFT alerts):
            // the verb that belongs to the user's own account decides.
            hasCredit && hasDebit -> {
                // Whichever own-account phrasing appears first in the text wins.
                val after = OWN_ACCOUNT_VERB_PATTERN.matcher(sanitized)
                val before = VERB_THEN_OWN_ACCOUNT_PATTERN.matcher(sanitized)
                val afterAt = if (after.find()) after.start() else Int.MAX_VALUE
                val beforeAt = if (before.find()) before.start() else Int.MAX_VALUE
                val verb = when {
                    afterAt == Int.MAX_VALUE && beforeAt == Int.MAX_VALUE -> null
                    afterAt <= beforeAt -> after.group(1)
                    else -> before.group(1)
                }
                when {
                    verb == null -> null
                    verb.lowercase(Locale.ROOT).startsWith("debit") -> TransactionType.DEBIT
                    else -> TransactionType.CREDIT
                }
            }
            else -> null
        }
    }

    private fun normalizeBankName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        for ((pattern, canonical) in KNOWN_BANK_ALIASES) {
            if (pattern.containsMatchIn(trimmed)) return canonical
        }

        return trimmed.replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
            .split(" ")
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
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
                return if (matcher.groupCount() > 0) matcher.group(1)?.trim() else matcher.group()?.trim()
            }
        }
        return null
    }

    private fun extractAccountNumber(text: String): String? {
        val bankAcctMatcher = BANK_ACCT_NUM_PATTERN.matcher(text)
        if (bankAcctMatcher.find()) {
            return bankAcctMatcher.group(1)?.trim()
        }

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
