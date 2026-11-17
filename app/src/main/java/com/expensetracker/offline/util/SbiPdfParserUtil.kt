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
    val rawLine: String
)

/**
 * Summary of an SBI statement import operation.
 */
data class SbiImportResult(
    val totalParsed: Int,
    val newlyInserted: Int,
    val duplicatesSkipped: Int,
    val insertedTransactions: List<TransactionEntity>,
    val detectedAccountNumber: String? = null
)

object SbiPdfParserUtil {

    private val DATE_FORMATS = listOf(
        "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "d-MMM-yyyy",
        "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
        "dd/MM/yy", "d/M/yy", "dd-MM-yy", "d-M-yy"
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
        """(?i)(?:Account\s*(?:Number|No\.?)|A/c\s*No\.?)\s*[:\s]+(\d{9,18})"""
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
        return match?.groupValues?.getOrNull(1)?.trim()
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

        val (payee, refNo) = extractPayeeAndRef(narrationPart, detectedType)

        return SbiParsedRow(
            dateTimestamp = timestamp,
            description = narrationPart,
            payee = payee,
            refNo = refNo,
            amountPaise = amountPaise,
            type = detectedType,
            balancePaise = balancePaise,
            rawLine = rawLine
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

    /**
     * Extracts merchant/payee name and reference number (UTR/cheque) from the narration.
     */
    private fun extractPayeeAndRef(narration: String, type: TransactionType): Pair<String, String?> {
        val upiMatcher = UPI_PATTERN.matcher(narration)
        if (upiMatcher.find()) {
            val refNo = upiMatcher.group(2)?.trim()
            val payeeName = upiMatcher.group(3)?.trim()?.replace('_', ' ')
            val vpa = upiMatcher.group(5)?.trim()

            val cleanPayee = when {
                !payeeName.isNullOrBlank() && !payeeName.all { it.isDigit() } -> payeeName
                !vpa.isNullOrBlank() -> vpa
                else -> if (type == TransactionType.DEBIT) "UPI Payment" else "UPI Received"
            }
            return Pair(cleanPayee.take(45), refNo)
        }

        var cleaned = narration
            .replace(Regex("(?i)^TO\\s+TRANSFER-?"), "")
            .replace(Regex("(?i)^BY\\s+TRANSFER-?"), "")
            .replace(Regex("(?i)^TO\\s+"), "")
            .replace(Regex("(?i)^BY\\s+"), "")
            .replace(Regex("(?i)^CHEQUE\\s+TRANSFER-?"), "")
            .trim()

        // Extract 12-digit UTR or 6-digit cheque number if present
        val refMatch = Regex("""\b(\d{12}|\d{6})\b""").find(cleaned)
        val refNo = refMatch?.value

        val payee = when {
            cleaned.contains("ATM WDL", ignoreCase = true) -> "ATM Cash Withdrawal"
            cleaned.contains("INT.PD", ignoreCase = true) || cleaned.contains("INTEREST", ignoreCase = true) -> "Interest Credit"
            cleaned.contains("SALARY", ignoreCase = true) -> "Salary"
            cleaned.contains("POS ", ignoreCase = true) -> {
                cleaned.replace(Regex("(?i)^POS\\s+\\d+\\s*"), "").trim().ifBlank { "POS Spend" }
            }
            cleaned.isNotBlank() -> cleaned.take(45)
            else -> if (type == TransactionType.DEBIT) "Bank Debit" else "Bank Credit"
        }

        return Pair(payee, refNo)
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
        val clean = dateStr.trim().replace('-', ' ').replace('/', ' ')
        for (format in DATE_FORMATS) {
            try {
                val cleanFmt = format.replace('-', ' ').replace('/', ' ')
                val sdf = SimpleDateFormat(cleanFmt, Locale.US).apply { isLenient = false }
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Maps parsed SBI rows into [TransactionEntity] objects, performs deduplication against the Room database,
     * and performs a batch insert for new entries.
     */
    suspend fun importParsedTransactions(
        dao: TransactionDao,
        rows: List<SbiParsedRow>,
        accountNumber: String? = null
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
                        candidate.rawContent.contains(row.payee, ignoreCase = true)
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

            // Auto-categorize using CategoryClassifier
            val category = CategoryClassifier.classifySync(row.payee, row.description)
            val isTransfer = category.equals("Transfer", ignoreCase = true)

            val entity = TransactionEntity(
                id = 0,
                amount = row.amountPaise,
                payee = row.payee,
                timestamp = row.dateTimestamp,
                type = row.type,
                source = TransactionSource.MANUAL,
                referenceId = row.refNo,
                rawContent = "Imported from SBI statement: ${row.rawLine}",
                category = category,
                note = row.description.takeIf { it != row.payee },
                balance = row.balancePaise,
                itemsSummary = null,
                bankName = "State Bank of India",
                accountNumber = accountNumber?.takeLast(4),
                accountType = "BANK",
                isNecessity = false,
                excludeFromSpend = isTransfer,
                paidBy = "You"
            )

            toInsert.add(entity)
        }

        if (toInsert.isNotEmpty()) {
            dao.insertTransactions(toInsert)
        }

        SbiImportResult(
            totalParsed = rows.size,
            newlyInserted = toInsert.size,
            duplicatesSkipped = duplicatesCount,
            insertedTransactions = toInsert,
            detectedAccountNumber = accountNumber
        )
    }
}
