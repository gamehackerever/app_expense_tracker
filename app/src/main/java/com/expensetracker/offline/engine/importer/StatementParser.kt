package com.expensetracker.offline.engine.importer

import java.math.BigDecimal
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Turns payment statements into plain rows. Pure Kotlin on purpose (no Android types) so every format
 * can be unit-tested. Supported inputs, all as already-extracted text:
 *  - PhonePe statement (PDF text)
 *  - Google Pay activity from Google Takeout (HTML or JSON)
 *  - Bank / wallet CSV exports (generic header detection)
 */
enum class StatementFormat(val label: String) {
    PHONEPE("PhonePe statement"),
    GOOGLE_PAY("Google Pay activity"),
    CSV("Bank statement (CSV)"),
    UNKNOWN("Unrecognised file")
}

data class StatementRow(
    val timestamp: Long,
    /** false when the statement only has a date, so duplicate matching must allow a whole day. */
    val timeKnown: Boolean,
    val amountPaise: Long,
    val isDebit: Boolean,
    val payee: String,
    val referenceId: String? = null
)

data class StatementParseResult(
    val format: StatementFormat,
    val rows: List<StatementRow>,
    /** Blocks that looked like a transaction but could not be read (shown to the user, never silently dropped). */
    val skipped: Int
)

object StatementParser {

    fun parse(rawText: String): StatementParseResult {
        val text = normalize(rawText)
        val candidates = listOf(
            parseGooglePayJson(text),
            parseGooglePayText(text),
            parsePhonePe(text),
            parseCsv(text)
        )
        return candidates.maxByOrNull { it.rows.size }?.takeIf { it.rows.isNotEmpty() }
            ?: StatementParseResult(StatementFormat.UNKNOWN, emptyList(), candidates.sumOf { it.skipped })
    }

    // ───────────────────────────── text normalisation ─────────────────────────────

    internal fun normalize(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace('\u202f', ' ').replace('\u00a0', ' ').replace("\uFEFF", "")
        if (t.contains("<div", true) || t.contains("<html", true) || t.contains("<br", true) || t.contains("<td", true)) {
            t = t.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</(div|p|tr|li|h\\d|td)>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
            t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&#8377;", "₹").replace("&#x20B9;", "₹", ignoreCase = true)
        }
        return t
    }

    // ───────────────────────────── money & dates ─────────────────────────────

    private val MONEY = Regex("""(?i)(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""")

    internal fun toPaise(num: String): Long? =
        runCatching { BigDecimal(num.replace(",", "")).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong() }
            .getOrNull()?.takeIf { it > 0L }

    private val TZ_SUFFIX = Regex("""\s*(?:IST|UTC|GMT(?:[+\-]\d{1,2}(?::?\d{2})?)?)\s*$""")

    private data class DateFmt(val pattern: String, val hasTime: Boolean)

    private val DATE_FORMATS = listOf(
        DateFmt("MMM d, yyyy, h:mm:ss a", true), DateFmt("MMM d, yyyy, h:mm a", true),
        DateFmt("MMM d, yyyy h:mm:ss a", true), DateFmt("MMM d, yyyy h:mm a", true),
        DateFmt("d MMM yyyy, HH:mm:ss", true), DateFmt("d MMM yyyy HH:mm:ss", true),
        DateFmt("d MMM yyyy, HH:mm", true), DateFmt("d MMM yyyy HH:mm", true),
        DateFmt("d MMM yyyy, h:mm a", true), DateFmt("d MMM yyyy h:mm a", true),
        DateFmt("dd/MM/yyyy HH:mm:ss", true), DateFmt("dd/MM/yyyy HH:mm", true),
        DateFmt("dd-MM-yyyy HH:mm:ss", true), DateFmt("dd-MM-yyyy HH:mm", true),
        DateFmt("yyyy-MM-dd HH:mm:ss", true), DateFmt("yyyy-MM-dd HH:mm", true),
        DateFmt("yyyy-MM-dd'T'HH:mm:ss", true),
        DateFmt("MMM d, yyyy", false), DateFmt("d MMM yyyy", false), DateFmt("d MMM, yyyy", false),
        DateFmt("dd/MM/yyyy", false), DateFmt("dd-MM-yyyy", false), DateFmt("yyyy-MM-dd", false),
        DateFmt("dd-MMM-yyyy", false), DateFmt("dd-MMM-yy", false), DateFmt("dd/MM/yy", false),
        DateFmt("dd MMM yy", false), DateFmt("d MMM yy", false)
    )

    /** @return epoch millis and whether the time of day was present, or null. */
    internal fun parseDate(input: String, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Boolean>? {
        var s = input.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        // ISO instants from Google Takeout JSON: 2025-10-26T10:05:11.123Z
        Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.\d+)?Z$""").find(s)?.let {
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            val d = f.parse("${it.groupValues[1]} ${it.groupValues[2]}") ?: return null
            return d.time to true
        }
        val tzMatch = TZ_SUFFIX.find(s)
        s = s.replace(TZ_SUFFIX, "").trim()
        val useZone = if (tzMatch != null && !tzMatch.value.trim().startsWith("IST", true)) {
            TimeZone.getTimeZone(tzMatch.value.trim().let { if (it.equals("UTC", true)) "UTC" else it })
        } else if (tzMatch != null) TimeZone.getTimeZone("Asia/Kolkata") else zone
        // am/pm in any case, with or without dots: "3:35 pm", "3:35 P.M."
        s = s.replace(Regex("""(?i)\b([ap])\.?m\.?\b""")) { it.groupValues[1].uppercase(Locale.ROOT) + "M" }
        s = s.replace(Regex("""(?<=\d)(AM|PM)""", RegexOption.IGNORE_CASE)) { " " + it.value.uppercase(Locale.ROOT) }
        for (fmt in DATE_FORMATS) {
            val f = SimpleDateFormat(fmt.pattern, Locale.ENGLISH).apply { timeZone = useZone; isLenient = false }
            val pos = ParsePosition(0)
            val d = f.parse(s, pos)
            if (d != null && pos.index == s.length) return d.time to fmt.hasTime
        }
        return null
    }

    private fun titleCase(raw: String): String {
        val t = raw.trim().trim('.', ',', '-', ':', ' ')
        if (t.isEmpty()) return t
        val letters = t.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            return t.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        }
        return t
    }

    // ───────────────────────────── Google Pay (Takeout) ─────────────────────────────

    private val GPAY_LINE = Regex(
        """(?i)^(paid|sent|received|refunded|refund)\s+(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)\s*(.*)$"""
    )

    private fun gpayRow(verb: String, amount: String, rest: String, time: Pair<Long, Boolean>): StatementRow? {
        val paise = toPaise(amount) ?: return null
        val debit = verb.lowercase(Locale.ROOT).let { it == "paid" || it == "sent" }
        var payee = rest.trim()
        payee = payee.removePrefix("to ").removePrefix("To ").removePrefix("from ").removePrefix("From ")
        payee = payee.substringBefore(" using ").substringBefore(" via ").trim()
        if (payee.startsWith("using", true) || payee.isBlank()) payee = "Google Pay"
        return StatementRow(time.first, time.second, paise, debit, titleCase(payee))
    }

    internal fun parseGooglePayText(text: String): StatementParseResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val rows = ArrayList<StatementRow>()
        var skipped = 0
        var i = 0
        while (i < lines.size) {
            val m = GPAY_LINE.matchEntire(lines[i])
            if (m == null) { i++; continue }
            // The timestamp sits within the next few lines of the same activity entry.
            var time: Pair<Long, Boolean>? = null
            var j = i + 1
            while (j < lines.size && j <= i + 4 && time == null) {
                if (GPAY_LINE.matches(lines[j])) break
                time = parseDate(lines[j]); j++
            }
            val row = time?.let { gpayRow(m.groupValues[1], m.groupValues[2], m.groupValues[3], it) }
            if (row != null) rows += row else skipped++
            i = if (time != null) j else i + 1
        }
        return StatementParseResult(StatementFormat.GOOGLE_PAY, rows, skipped)
    }

    internal fun parseGooglePayJson(text: String): StatementParseResult {
        if (!text.contains("\"title\"")) return StatementParseResult(StatementFormat.GOOGLE_PAY, emptyList(), 0)
        val titleRe = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val timeRe = Regex("\"time\"\\s*:\\s*\"([^\"]+)\"")
        val titles = titleRe.findAll(text).toList()
        val rows = ArrayList<StatementRow>()
        var skipped = 0
        for ((idx, t) in titles.withIndex()) {
            val title = unescapeJson(t.groupValues[1]).replace('\u202f', ' ').replace('\u00a0', ' ').trim()
            val m = GPAY_LINE.matchEntire(title) ?: continue
            val upper = titles.getOrNull(idx + 1)?.range?.first ?: text.length
            val time = timeRe.find(text.substring(t.range.last, upper))?.groupValues?.get(1)?.let { parseDate(it) }
            val row = time?.let { gpayRow(m.groupValues[1], m.groupValues[2], m.groupValues[3], it) }
            if (row != null) rows += row else skipped++
        }
        return StatementParseResult(StatementFormat.GOOGLE_PAY, rows, skipped)
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) {
                        sb.append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?'); i += 6; continue
                    }
                    'n' -> sb.append('\n'); 't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // ───────────────────────────── PhonePe ─────────────────────────────

    private const val DATE_ALT =
        """(?:[A-Za-z]{3,9}\.?\s+\d{1,2},?\s+\d{4}|\d{1,2}\s+[A-Za-z]{3,9}\.?,?\s+\d{4}|\d{1,2}[/-]\d{1,2}[/-]\d{2,4})"""
    private const val TIME_ALT = """\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AaPp]\.?[Mm]\.?)?"""
    private val PP_START = Regex("""^($DATE_ALT)(?:[,\s]+($TIME_ALT))?(?:\s+|$)(.*)$""")
    private val PP_TIME_ONLY = Regex("""^$TIME_ALT$""")
    private val PP_TIME_LEAD = Regex("""^($TIME_ALT)\s+(\D.*)$""")
    private val PP_DESC = Regex(
        """(?im)\b(paid to|received from|payment to|sent to|money sent to|refund from|cashback from|bill payment to|recharge of|paid for)[ \t]+(.+)$"""
    )
    private val PP_TYPE = Regex("""(?<![A-Za-z])(DEBIT|CREDIT)(?![A-Za-z])""")
    private val PP_REF = Regex("""(?i)Transaction ID\s*:?\s*([A-Za-z0-9]{8,})""")
    private val PP_UTR = Regex("""(?i)UTR(?:\s*No\.?)?\s*:?\s*(\d{9,})""")
    private val PP_CUT = Regex("""\s+(?:DEBIT|CREDIT|Transaction ID|UTR|Paid by|Credited to|₹|Rs\.?\s*\d|INR\s*\d)""")

    internal fun parsePhonePe(text: String): StatementParseResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Split into blocks, one per date line.
        data class Block(val date: String, var time: String?, val body: MutableList<String>)
        val blocks = ArrayList<Block>()
        for (line in lines) {
            val m = PP_START.matchEntire(line)
            if (m != null && parseDate(m.groupValues[1]) != null) {
                val b = Block(m.groupValues[1], m.groupValues[2].ifBlank { null }, mutableListOf())
                if (m.groupValues[3].isNotBlank()) b.body += m.groupValues[3]
                blocks += b
            } else if (blocks.isNotEmpty()) {
                val b = blocks.last()
                if (b.time == null && PP_TIME_ONLY.matches(line)) {
                    b.time = line
                } else {
                    // Real PhonePe PDFs usually put the time at the start of the next line ("03:35 pm Transaction ID T25…").
                    val lead = if (b.time == null) PP_TIME_LEAD.matchEntire(line) else null
                    if (lead != null && parseDate("${b.date} ${lead.groupValues[1]}")?.second == true) {
                        b.time = lead.groupValues[1]
                        if (lead.groupValues[2].isNotBlank()) b.body += lead.groupValues[2]
                    } else b.body += line
                }
            }
        }
        val rows = ArrayList<StatementRow>()
        var skipped = 0
        for (b in blocks) {
            val body = b.body.joinToString("\n")
            val desc = PP_DESC.find(body) ?: continue          // header / footer text, not a payment
            val amount = MONEY.find(body)?.groupValues?.get(1)?.let { toPaise(it) }
            val whenPair = parseDate(listOfNotNull(b.date, b.time).joinToString(" ")) ?: parseDate(b.date)
            if (amount == null || whenPair == null) { skipped++; continue }
            val verb = desc.groupValues[1].lowercase(Locale.ROOT)
            val typeToken = PP_TYPE.find(body)?.groupValues?.get(1)
            val debit = when (typeToken) {
                "DEBIT" -> true
                "CREDIT" -> false
                else -> !(verb.startsWith("received") || verb.startsWith("refund") || verb.startsWith("cashback"))
            }
            val rawPayee = desc.groupValues[2].lineSequence().first().let { first ->
                PP_CUT.find(first)?.let { first.substring(0, it.range.first) } ?: first
            }
            val ref = PP_REF.find(body)?.groupValues?.get(1) ?: PP_UTR.find(body)?.groupValues?.get(1)
            rows += StatementRow(whenPair.first, whenPair.second, amount, debit, titleCase(rawPayee).ifBlank { "PhonePe" }, ref)
        }
        return StatementParseResult(StatementFormat.PHONEPE, rows, skipped)
    }

    // ───────────────────────────── CSV ─────────────────────────────

    private val H_DATE = Regex("""(?i)^(?:txn\s*|transaction\s*|value\s*|posting\s*)?date(?:\s*&\s*time)?$|^date$""")
    private val H_DESC = Regex("""(?i)description|narration|particulars|details|payee|merchant|remarks|transaction\s*(?:details|remarks)""")
    private val H_DEBIT = Regex("""(?i)^(?:debit|withdrawal(?:s)?(?:\s*amt\.?)?|paid\s*out|dr\.?)(?:\s*amount)?(?:\s*\(.*\))?$""")
    private val H_CREDIT = Regex("""(?i)^(?:credit|deposit(?:s)?(?:\s*amt\.?)?|paid\s*in|cr\.?)(?:\s*amount)?(?:\s*\(.*\))?$""")
    private val H_AMOUNT = Regex("""(?i)^amount(?:\s*\(.*\))?$""")
    private val H_TYPE = Regex("""(?i)^(?:type|dr\s*/\s*cr|cr\s*/\s*dr|transaction\s*type|debit\s*/\s*credit)$""")
    private val H_REF = Regex("""(?i)ref(?:erence)?(?:\s*(?:no|number|id))?\.?|utr|chq|transaction\s*id|txn\s*id""")

    private fun splitCsvLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == delim && !q -> { out += sb.toString().trim(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    private val SEGMENT_NOISE = setOf("UPI", "DR", "CR", "P2A", "P2M", "P2P", "IMPS", "NEFT", "RTGS", "POS", "ECOM", "ATM", "MOB", "INB")

    /** "UPI/DR/529012345678/MAIN CANTEEN/YESB/q123@ybl/UPI" -> "Main Canteen". */
    internal fun payeeFromNarration(narration: String): String {
        val n = narration.trim()
        if (n.contains('/') || n.contains('-')) {
            val parts = n.split('/', '-').map { it.trim() }
            val pick = parts.firstOrNull { p ->
                p.length >= 3 && p.any { it.isLetter() } && !p.all { it.isDigit() } &&
                    p.uppercase(Locale.ROOT) !in SEGMENT_NOISE && !p.contains('@') && !Regex("^[A-Z]{4}\\d{0,3}$").matches(p) &&
                    !Regex("(?i)^(?:upi|imps|neft|rtgs)\\b").containsMatchIn(p)
            }
            if (pick != null) return titleCase(pick)
        }
        return titleCase(n.take(40))
    }

    private fun parseAmountCell(s: String): Long? {
        val cleaned = s.replace(Regex("(?i)(?:₹|rs\\.?|inr|dr|cr)"), "").replace(",", "").replace(" ", "").trim()
        if (cleaned.isEmpty() || cleaned == "-") return null
        return toPaise(cleaned.removePrefix("-"))
    }

    internal fun parseCsv(text: String): StatementParseResult {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return StatementParseResult(StatementFormat.CSV, emptyList(), 0)
        for (delim in charArrayOf(',', ';', '\t', '|')) {
            val result = parseCsvWith(lines, delim)
            if (result.rows.isNotEmpty()) return result
        }
        return StatementParseResult(StatementFormat.CSV, emptyList(), 0)
    }

    private fun parseCsvWith(lines: List<String>, delim: Char): StatementParseResult {
        var headerIdx = -1
        var cols: List<String> = emptyList()
        for ((i, l) in lines.take(40).withIndex()) {
            val c = splitCsvLine(l, delim)
            if (c.size < 3) continue
            val hasDate = c.any { H_DATE.matches(it.trim()) }
            val hasMoney = c.any { H_DEBIT.matches(it.trim()) || H_CREDIT.matches(it.trim()) || H_AMOUNT.matches(it.trim()) }
            if (hasDate && hasMoney) { headerIdx = i; cols = c; break }
        }
        if (headerIdx < 0) return StatementParseResult(StatementFormat.CSV, emptyList(), 0)

        fun idx(re: Regex) = cols.indexOfFirst { re.matches(it.trim()) }
        val dateI = idx(H_DATE)
        val debitI = idx(H_DEBIT); val creditI = idx(H_CREDIT); val amountI = idx(H_AMOUNT); val typeI = idx(H_TYPE)
        val descI = cols.indexOfFirst { H_DESC.containsMatchIn(it) }
        val refI = cols.indexOfFirst { H_REF.containsMatchIn(it) && it != cols.getOrNull(descI) }
        val rows = ArrayList<StatementRow>(); var skipped = 0

        for (l in lines.drop(headerIdx + 1)) {
            val c = splitCsvLine(l, delim)
            if (c.size < 3) continue
            val dateCell = c.getOrNull(dateI).orEmpty()
            val whenPair = parseDate(dateCell)
            if (whenPair == null) { if (dateCell.isNotBlank() && c.any { MONEY.containsMatchIn(it) || it.any(Char::isDigit) }) skipped++; continue }

            var debit: Boolean; var paise: Long?
            val dPaise = if (debitI >= 0) parseAmountCell(c.getOrNull(debitI).orEmpty()) else null
            val cPaise = if (creditI >= 0) parseAmountCell(c.getOrNull(creditI).orEmpty()) else null
            if (dPaise != null || cPaise != null) {
                debit = dPaise != null; paise = dPaise ?: cPaise
            } else if (amountI >= 0) {
                val cell = c.getOrNull(amountI).orEmpty()
                paise = parseAmountCell(cell)
                val typeCell = (c.getOrNull(typeI).orEmpty() + " " + cell).lowercase(Locale.ROOT)
                debit = when {
                    Regex("\\b(cr|credit|deposit|received)\\b").containsMatchIn(typeCell) -> false
                    Regex("\\b(dr|debit|withdrawal|paid|sent)\\b").containsMatchIn(typeCell) || cell.trim().startsWith("-") -> true
                    else -> true
                }
            } else { skipped++; continue }
            if (paise == null) { skipped++; continue }

            val narration = c.getOrNull(descI).orEmpty()
            val ref = (c.getOrNull(refI).orEmpty().takeIf { it.length >= 6 } ?: Regex("\\d{9,}").find(narration)?.value)
            rows += StatementRow(whenPair.first, whenPair.second, paise, debit, payeeFromNarration(narration).ifBlank { "Statement entry" }, ref)
        }
        return StatementParseResult(StatementFormat.CSV, rows, skipped)
    }
}
