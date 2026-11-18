package com.expensetracker.offline.util

import android.content.Context
import android.net.Uri
import com.expensetracker.offline.data.local.dao.TransactionDao
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Parsed representation of an individual row from an SBI bank statement.
 */
data class SbiParsedRow(
    val dateTimestamp: Long,
    val description: String,
    val payee: String,
    val refNo: String?,
    val amountPaise: Long,
    val type: TransactionType,
    val balancePaise: Long?,
    val rawLine: String,
    val note: String? = null
)

/**
 * Summary of an SBI statement import operation.
 */
data class SbiImportResult(
    val totalParsed: Int,
    val newlyInserted: Int,
    val duplicatesSkipped: Int,
    val insertedTransactions: List<TransactionEntity>,
    val detectedAccountNumber: String? = null,
    val bankName: String = "Bank"
)

object SbiPdfParserUtil {

    private val DATE_FORMATS = listOf(
        "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "d-MMM-yyyy",
        "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
        "dd/MM/yy", "d/M/yy", "dd-MM-yy", "d-M-yy"
    )

    private val NO_YEAR_DATE_FORMATS = listOf(
        "dd MMM", "d MMM", "dd-MMM", "d-MMM",
        "dd/MM", "d/M", "dd-MM", "d-M"
    )

    // Identifies line starting with Txn Date (and optional Value Date)
    private val DATE_START_REGEX = Regex(
        """^(?<date>\d{1,2}(?:[\s/-](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*|[/.-]\d{1,2})(?:[\s/-]\d{2,4})?)(?:\s+(?<vdate>\d{1,2}(?:[\s/-](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*|[/.-]\d{1,2})(?:[\s/-]\d{2,4})?))?\s+(?<rest>.*)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches currency amounts like 250.00, 1,499.00, 45,230.50(Cr), 45,230.50Cr
    private val AMOUNT_PATTERN = Pattern.compile(
        """(?:\b|\s)([0-9,]+\.\d{2})(?:\s*\(?([CcDd][Rr])\)?)?(?=\s|$)"""
    )

    // Standard SBI UPI narration format: TRANSFER-UPI/DR/ref/payee/bank/vpa/narration
    private val UPI_PATTERN = Pattern.compile(
        """(?i)(?:TRANSFER-)?UPI/(DR|CR)/(\d+)/([^/]+)/([^/]+)/?([^/\s]*)"""
    )

    // Account Number header pattern
    private val ACCOUNT_HEADER_REGEX = Regex(
        """(?i)(?:Account\s*(?:Number|No\.?)|A/c\s*(?:No\.?|Number)?)\s*[:\s]+([0-9\s]{9,22})"""
    )

    /**
     * Extracts full raw text from a password-protected SBI PDF statement.
     * Throws [InvalidPasswordException] if the provided password is wrong.
     */
    fun extractTextFromPdf(context: Context, uri: Uri, password: String): String {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open selected PDF file.")
        var document: PDDocument? = null
        try {
            document = PDDocument.load(inputStream, password)
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            return stripper.getText(document)
        } catch (e: InvalidPasswordException) {
            throw e
        } finally {
            try {
                document?.close()
            } catch (_: Exception) {}
            try {
                inputStream.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Detects bank account number from statement header text, if present.
     */
    fun extractAccountNumber(rawText: String): String? {
        val match = ACCOUNT_HEADER_REGEX.find(rawText)
        return match?.groupValues?.getOrNull(1)?.replace(" ", "")?.trim()
    }

    /**
     * Detects bank name from statement header or narration text.
     */
    fun detectBankName(rawText: String): String {
        val header = rawText.take(2500).uppercase(Locale.ROOT)
        val full = rawText.uppercase(Locale.ROOT)
        for (text in listOf(header, full)) {
            when {
                text.contains("CANARA BANK") || text.contains("CANARA") || text.contains("CNRB") -> return "Canara Bank"
                text.contains("STATE BANK OF INDIA") || (text.contains("SBI") && !text.contains("SBIN")) -> return "SBI"
                text.contains("HDFC") -> return "HDFC Bank"
                text.contains("ICICI") -> return "ICICI Bank"
                text.contains("AXIS BANK") || (text.contains("AXIS") && !text.contains("UTIB")) -> return "Axis Bank"
                text.contains("KOTAK") || text.contains("KKBK") -> return "Kotak Mahindra Bank"
                text.contains("PUNJAB NATIONAL BANK") || (text.contains("PNB") && !text.contains("PUNB")) -> return "PNB"
                text.contains("BANK OF BARODA") || (text.contains("BOB") && !text.contains("BARB")) -> return "Bank of Baroda"
                text.contains("UNION BANK") || text.contains("UBIN") -> return "Union Bank"
                text.contains("INDIAN BANK") || text.contains("IDIB") -> return "Indian Bank"
                text.contains("FEDERAL BANK") || text.contains("FDRL") -> return "Federal Bank"
                text.contains("SOUTH INDIAN BANK") || text.contains("SIBL") -> return "South Indian Bank"
            }
        }
        return "Primary"
    }

    /**
     * Parses the decrypted raw text from an SBI PDF statement into structured transaction rows.
     */
    fun parseSbiStatementText(rawText: String): List<SbiParsedRow> {
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val parsedRows = mutableListOf<SbiParsedRow>()

        var previousBalancePaise: Long? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) {
                i++
                continue
            }

            val dateMatch = DATE_START_REGEX.matchEntire(line)
            if (dateMatch != null) {
                val txnDateStr = dateMatch.groups["date"]?.value?.trim() ?: ""
                var rest = dateMatch.groups["rest"]?.value?.trim() ?: ""

                // Handle multi-line descriptions: if rest does not contain amounts, peek next lines
                var combinedLine = line
                var j = i + 1
                while (j < lines.size && findAmounts(rest).size < 2 && !isDateStart(lines[j])) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isNotBlank()) {
                        rest = "$rest $nextLine"
                        combinedLine = "$combinedLine $nextLine"
                    }
                    j++
                }
                i = j - 1

                val parsedTimestamp = parseDateToMillis(txnDateStr)
                if (parsedTimestamp != null) {
                    val parsedRow = parseTransactionRow(
                        timestamp = parsedTimestamp,
                        rest = rest,
                        rawLine = combinedLine,
                        previousBalancePaise = previousBalancePaise
                    )
                    if (parsedRow != null) {
                        parsedRows.add(parsedRow)
                        if (parsedRow.balancePaise != null) {
                            previousBalancePaise = parsedRow.balancePaise
                        }
                    }
                }
            }
            i++
        }

        return parsedRows
    }

    /**
     * Converts a single transaction row into [SbiParsedRow].
     */
    private fun parseTransactionRow(
        timestamp: Long,
        rest: String,
        rawLine: String,
        previousBalancePaise: Long?
    ): SbiParsedRow? {
        val amountMatches = findAmounts(rest)
        if (amountMatches.isEmpty()) return null

        // In standard SBI statement rows:
        // Case 1: [Debit] [Credit] [Balance] (3 amounts)
        // Case 2: [Amount] [Balance] (2 amounts)
        // Case 3: [Amount] (1 amount)
        val balancePaise: Long?
        val amountPaise: Long
        val detectedType: TransactionType

        if (amountMatches.size >= 3) {
            // Three numeric columns: Debit, Credit, Balance
            val debitPaise = toPaise(amountMatches[amountMatches.size - 3].first) ?: 0L
            val creditPaise = toPaise(amountMatches[amountMatches.size - 2].first) ?: 0L
            balancePaise = toPaise(amountMatches.last().first)

            if (debitPaise > 0L) {
                amountPaise = debitPaise
                detectedType = TransactionType.DEBIT
            } else if (creditPaise > 0L) {
                amountPaise = creditPaise
                detectedType = TransactionType.CREDIT
            } else {
                return null
            }
        } else if (amountMatches.size == 2) {
            // Two numeric columns: Amount, Balance
            amountPaise = toPaise(amountMatches[0].first) ?: return null
            balancePaise = toPaise(amountMatches[1].first)

            // Determine Debit vs Credit using narration signals & balance delta
            detectedType = determineType(
                text = rest,
                amountPaise = amountPaise,
                balancePaise = balancePaise,
                previousBalancePaise = previousBalancePaise
            )
        } else {
            // Single numeric column: Amount
            amountPaise = toPaise(amountMatches[0].first) ?: return null
            balancePaise = null
            detectedType = determineType(
                text = rest,
                amountPaise = amountPaise,
                balancePaise = null,
                previousBalancePaise = previousBalancePaise
            )
        }

        if (amountPaise <= 0L) return null

        // Extract narration (everything before the first matched amount)
        val firstAmountIndex = amountMatches.first().second
        val narrationPart = rest.substring(0, firstAmountIndex).trim()

        val parsedNarration = extractPayeeAndRef(narrationPart, detectedType)

        return SbiParsedRow(
            dateTimestamp = timestamp,
            description = narrationPart,
            payee = parsedNarration.payee,
            refNo = parsedNarration.refNo,
            amountPaise = amountPaise,
            type = detectedType,
            balancePaise = balancePaise,
            rawLine = rawLine,
            note = parsedNarration.note
        )
    }

    /**
     * Determines whether a row is DEBIT or CREDIT using narration clues and balance math.
     */
    private fun determineType(
        text: String,
        amountPaise: Long,
        balancePaise: Long?,
        previousBalancePaise: Long?
    ): TransactionType {
        val upper = text.uppercase(Locale.ROOT)

        // 1. Definite UPI / narration keywords
        if (upper.contains("/DR/") || upper.startsWith("TO ") || upper.contains("TRANSFER-UPI/DR")
            || upper.contains("ATM WDL") || upper.contains("WDL") || upper.contains("CHQ PAID")
            || upper.contains("POS ") || upper.contains("DEBIT") || upper.contains("PURCHASE")) {
            return TransactionType.DEBIT
        }
        if (upper.contains("/CR/") || upper.startsWith("BY ") || upper.contains("TRANSFER-UPI/CR")
            || upper.contains("CREDIT") || upper.contains("INTEREST") || upper.contains("INT.PD")
            || upper.contains("SALARY") || upper.contains("REFUND") || upper.contains("CASH DEP")) {
            return TransactionType.CREDIT
        }

        // 2. Balance math check if previous balance is known
        if (balancePaise != null && previousBalancePaise != null) {
            val delta = balancePaise - previousBalancePaise
            if (abs(delta - amountPaise) < 100L) {
                return TransactionType.CREDIT
            }
            if (abs(delta + amountPaise) < 100L) {
                return TransactionType.DEBIT
            }
        }

        // Default fallback to DEBIT (expense) for banking debits
        return TransactionType.DEBIT
    }

    data class ParsedNarration(
        val payee: String,
        val refNo: String?,
        val note: String? = null
    )

    /**
     * Extracts merchant/payee name, reference number, and user note from narration.
     * Supports SBI, Canara Bank, and all standard NPCI UPI / IMPS / NEFT formats.
     */
    private fun extractPayeeAndRef(narration: String, type: TransactionType): ParsedNarration {
        val normalized = narration.replace('_', ' ').trim()

        // 1. UPI Narrations across Indian Banks (Canara, SBI, PNB, BoB, HDFC, ICICI, etc.)
        // Supports:
        // - UPI/413524123456/DR/SWIGGY/CNRB/swiggy@icici/... (Canara Bank / PNB / BoB)
        // - UPI/P2M/413625234567/AMAZON PAY/CNRB/... (Canara Bank Merchant)
        // - UPI/P2A/413726345678/RAHUL/CNRB/... (Canara Bank Person-to-Account)
        // - TRANSFER-UPI/DR/400123456789/SWIGGY/ICIC/... (SBI)
        // - UPI/DR/400123456789/SWIGGY/ICIC/... (SBI)
        if (normalized.contains("UPI/", ignoreCase = true) ||
            normalized.contains("UPI-", ignoreCase = true) ||
            normalized.contains("TRANSFER-UPI", ignoreCase = true)
        ) {
            val upiPayload = normalized
                .replace(Regex("(?i)^.*?TRANSFER-UPI[/\\-]"), "")
                .replace(Regex("(?i)^.*?UPI[/\\-]"), "")
                .trim()

            val segments = upiPayload.split(Regex("[/\\-]"))
                .map { it.trim(' ', '.', ',', ':', ';', '(', ')') }
                .filter { it.isNotBlank() }

            // Find 12-digit UTR / RRN (or sequence of 9..18 digits)
            val refNo = segments.firstOrNull { it.length in 9..18 && it.all { c -> c.isDigit() } }
                ?: Regex("""\b(\d{12}|\d{6,18})\b""").find(normalized)?.value

            val ignoredTokens = setOf(
                "DR", "CR", "P2M", "P2A", "REV", "UPI", "TRANSFER", "TO", "BY",
                "CNRB", "SBIN", "HDFC", "ICIC", "UTIB", "KKBK", "BARB", "PUNB", "UBIN",
                "IOBA", "CBIN", "YESB", "FDRL", "SIBL", "IDFB", "IDIB", "AIRP", "PYTM",
                "OKAXIS", "OKSBI", "OKHDFC", "OKICICI", "PAYTM", "APL", "YBL", "IBL",
                "SBI", "CANARA", "AXIS", "KOTAK", "FEDERAL", "BOB", "PNB", "UNION"
            )

            // Candidates for payee name
            val candidateSegments = segments.filter { seg ->
                !seg.all { it.isDigit() } &&
                !ignoredTokens.contains(seg.uppercase(Locale.ROOT)) &&
                !seg.contains("@") &&
                seg.length >= 2
            }

            val extractedName = candidateSegments.firstOrNull { seg ->
                !seg.equals("UPI TRANSFER", ignoreCase = true) &&
                !seg.startsWith("UPI", ignoreCase = true)
            } ?: candidateSegments.firstOrNull()

            val vpaSegment = segments.firstOrNull { it.contains("@") }
            val vpaClean = vpaSegment?.let { vpa ->
                val user = vpa.substringBefore("@").replace('.', ' ').replace(Regex("""\d+$"""), "").trim()
                if (user.length >= 2 && !user.all { it.isDigit() }) user else vpa
            }

            val cleanPayee = when {
                !extractedName.isNullOrBlank() -> extractedName
                !vpaClean.isNullOrBlank() -> vpaClean
                else -> if (type == TransactionType.DEBIT) "UPI Payment" else "UPI Received"
            }

            // Extract candidate note/memo if present in UPI narration
            val extractedNote = candidateSegments.firstOrNull { seg ->
                seg != extractedName &&
                seg != cleanPayee &&
                !seg.equals("UPI TRANSFER", ignoreCase = true) &&
                !seg.equals("NA", ignoreCase = true) &&
                !seg.equals("NONE", ignoreCase = true) &&
                !seg.equals("PAYMENT", ignoreCase = true) &&
                !seg.equals("TRANSFER", ignoreCase = true) &&
                !seg.equals("UPI", ignoreCase = true) &&
                !seg.startsWith("UPI", ignoreCase = true)
            }

            return ParsedNarration(cleanPayee.take(45), refNo, extractedNote?.take(80))
        }

        var cleaned = normalized
            .replace(Regex("(?i)^TO\\s+TRANSFER-?"), "")
            .replace(Regex("(?i)^BY\\s+TRANSFER-?"), "")
            .replace(Regex("(?i)^TO\\s+"), "")
            .replace(Regex("(?i)^BY\\s+"), "")
            .replace(Regex("(?i)^CHEQUE\\s+TRANSFER-?"), "")
            .trim()

        // Extract 12-digit UTR or 6-digit cheque number if present
        val refMatch = Regex("""\b(\d{12}|\d{6,18})\b""").find(cleaned)
        val refNo = refMatch?.value

        val payee = when {
            cleaned.contains("ATM WDL", ignoreCase = true) || cleaned.contains("ATM CASH", ignoreCase = true) -> "ATM Cash Withdrawal"
            cleaned.contains("INT.PD", ignoreCase = true) || cleaned.contains("INTEREST", ignoreCase = true) -> "Interest Credit"
            cleaned.contains("SALARY", ignoreCase = true) -> "Salary"
            cleaned.contains("POS ", ignoreCase = true) -> {
                cleaned.replace(Regex("(?i)^POS\\s+\\d+\\s*"), "").trim().ifBlank { "POS Spend" }
            }
            cleaned.contains("BIL/ONL", ignoreCase = true) || cleaned.contains("BIL/", ignoreCase = true) -> {
                val billSegments = cleaned.split(Regex("[/\\-]")).filter { it.isNotBlank() && !it.all { c -> c.isDigit() } && !it.equals("BIL", ignoreCase = true) && !it.equals("ONL", ignoreCase = true) }
                billSegments.firstOrNull() ?: "Bill Payment"
            }
            cleaned.contains("NEFT/", ignoreCase = true) -> {
                val neftSegments = cleaned.split(Regex("[/\\-]")).filter { it.isNotBlank() && !it.all { c -> c.isDigit() } && !it.equals("NEFT", ignoreCase = true) && it.length > 4 }
                neftSegments.lastOrNull() ?: "NEFT Transfer"
            }
            cleaned.contains("IMPS/", ignoreCase = true) -> {
                val impsSegments = cleaned.split(Regex("[/\\-]")).filter { it.isNotBlank() && !it.all { c -> c.isDigit() } && !it.equals("IMPS", ignoreCase = true) && !it.equals("P2A", ignoreCase = true) && !it.equals("P2M", ignoreCase = true) }
                impsSegments.lastOrNull() ?: "IMPS Transfer"
            }
            cleaned.isNotBlank() -> cleaned.take(45)
            else -> if (type == TransactionType.DEBIT) "Bank Debit" else "Bank Credit"
        }

        return ParsedNarration(payee, refNo, null)
    }

    /**
     * Searches for currency amounts in a string and returns pairs of (amountString, startIndex).
     */
    private fun findAmounts(text: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        val matcher = AMOUNT_PATTERN.matcher(text)
        while (matcher.find()) {
            val amt = matcher.group(1)
            if (amt != null) {
                results.add(Pair(amt, matcher.start()))
            }
        }
        return results
    }

    private fun isDateStart(line: String): Boolean {
        return DATE_START_REGEX.matches(line.trim())
    }

    private fun toPaise(amountStr: String): Long? {
        return runCatching {
            val clean = amountStr.replace(",", "").trim()
            BigDecimal(clean).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        }.getOrNull()
    }

    private fun parseDateToMillis(dateStr: String): Long? {
        val clean = dateStr.trim().replace('-', ' ').replace('/', ' ').replace('.', ' ')
        for (format in DATE_FORMATS) {
            try {
                val cleanFmt = format.replace('-', ' ').replace('/', ' ').replace('.', ' ')
                val sdf = SimpleDateFormat(cleanFmt, Locale.US).apply { isLenient = false }
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        for (format in NO_YEAR_DATE_FORMATS) {
            try {
                val cleanFmt = format.replace('-', ' ').replace('/', ' ').replace('.', ' ')
                val sdf = SimpleDateFormat(cleanFmt, Locale.US).apply { isLenient = false }
                val parsed = sdf.parse(clean)
                if (parsed != null) {
                    val cal = Calendar.getInstance()
                    val currentYear = cal.get(Calendar.YEAR)
                    val parsedCal = Calendar.getInstance().apply { time = parsed }
                    parsedCal.set(Calendar.YEAR, currentYear)
                    return parsedCal.timeInMillis
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Maps parsed statement rows into [TransactionEntity] objects, performs deduplication against the Room database,
     * and performs a batch insert for new entries.
     */
    suspend fun importParsedTransactions(
        dao: TransactionDao,
        rows: List<SbiParsedRow>,
        accountNumber: String? = null,
        bankNameOverride: String? = null
    ): SbiImportResult = withContext(Dispatchers.IO) {
        val toInsert = mutableListOf<TransactionEntity>()
        var duplicatesCount = 0

        val processedKeys = mutableSetOf<String>()

        for (row in rows) {
            // Deduplication Check 1: Reference ID (UTR / Cheque)
            if (!row.refNo.isNullOrBlank()) {
                val countByRef = dao.countByReference(row.refNo)
                if (countByRef > 0) {
                    duplicatesCount++
                    continue
                }
            }

            // Deduplication Check 2: Same amount, date, and direction
            val cal = Calendar.getInstance().apply {
                timeInMillis = row.dateTimestamp
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val endOfDay = cal.timeInMillis

            val sameTxnCount = dao.countSameTransaction(
                fromTs = startOfDay,
                toTs = endOfDay,
                amount = row.amountPaise,
                type = row.type,
                payee = row.payee
            )
            if (sameTxnCount > 0) {
                duplicatesCount++
                continue
            }

            // Deduplication Check 3: Check candidate transactions on the same day for fuzzy/same-amount overlap
            val candidates = dao.findStatementCandidatesByAmount(
                amount = row.amountPaise,
                type = row.type,
                startTime = startOfDay,
                endTime = endOfDay
            )
            val isDuplicateCandidate = candidates.any { candidate ->
                (!row.refNo.isNullOrBlank() && candidate.referenceId == row.refNo) ||
                        (row.payee.isNotBlank() && !row.payee.equals("UPI Payment", ignoreCase = true) && candidate.rawContent.contains(row.payee, ignoreCase = true))
            }
            if (isDuplicateCandidate) {
                duplicatesCount++
                continue
            }

            // Deduplication Check 4: In-batch duplicate check
            val inBatchKey = "${row.dateTimestamp}_${row.amountPaise}_${row.type}_${row.payee}_${row.refNo ?: ""}"
            if (processedKeys.contains(inBatchKey)) {
                duplicatesCount++
                continue
            }
            processedKeys.add(inBatchKey)

            // Auto-categorize using CategoryClassifier smart layer; evaluate extracted note if present, otherwise description
            val rawCategory = CategoryClassifier.classifySync(row.payee, row.description, note = row.note ?: row.description)
            val effectiveCategory = if (row.type == TransactionType.DEBIT &&
                (rawCategory.isBlank() || rawCategory.equals("Transfer", ignoreCase = true) || rawCategory.equals("Uncategorized", ignoreCase = true) || rawCategory.equals("Unspecified", ignoreCase = true))
            ) {
                "Unspecified"
            } else {
                rawCategory
            }
            val isTransfer = effectiveCategory.equals("Self Transfer", ignoreCase = true)
            val canonicalBank = "Primary"
            val canonicalAcc = AccountNormalizer.accountNumber(accountNumber)

            val entity = TransactionEntity(
                id = 0,
                amount = row.amountPaise,
                payee = row.payee,
                timestamp = row.dateTimestamp,
                type = row.type,
                source = TransactionSource.MANUAL,
                referenceId = row.refNo,
                rawContent = "Imported statement: ${row.rawLine}",
                category = effectiveCategory,
                note = row.note,
                balance = row.balancePaise,
                itemsSummary = null,
                bankName = canonicalBank,
                accountNumber = canonicalAcc,
                accountType = "BANK",
                isNecessity = false,
                excludeFromSpend = isTransfer,
                paidBy = "You"
            )

            toInsert.add(entity)
        }

        val statementBank = "Primary"
        val statementAcc = AccountNormalizer.accountNumber(accountNumber)

        if (toInsert.isNotEmpty()) {
            dao.insertTransactions(toInsert)
            dao.unifyAllTransactionsToSingleAccount(statementBank, statementAcc)
        }

        SbiImportResult(
            totalParsed = rows.size,
            newlyInserted = toInsert.size,
            duplicatesSkipped = duplicatesCount,
            insertedTransactions = toInsert,
            detectedAccountNumber = statementAcc,
            bankName = statementBank
        )
    }
}
