package com.expensetracker.offline.engine.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.interceptor.CaptureDispatcher
import com.expensetracker.offline.util.CardPaymentReconciler
import com.expensetracker.offline.util.ImportedSource
import com.expensetracker.offline.widget.ExpenseWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Thrown when a PDF is picked but no PDF text library is available in the app. */
class PdfLibraryMissingException : Exception(
    "Reading PDF statements needs the PDFBox-Android library. Add implementation(\"com.tom-roush:pdfbox-android:2.0.27.0\") to the app's build.gradle, or import the CSV / Google Takeout file instead."
)

private class StatementTooLargeException : Exception("Statement file exceeds the 30 MB import limit.")

/**
 * Reads a payment statement the user picked and adds the payments the app does not already have.
 * Two steps so the user can see what will happen first: [analyze] changes nothing, [commit] inserts.
 */
class StatementImporter(private val context: Context, private val database: AppDatabase) {

    data class Analysis(
        val format: StatementFormat,
        val fileName: String,
        val newRows: List<StatementRow>,
        val alreadyTracked: Int,
        val unreadable: Int,
        /** What would be new if the payments imported from earlier statements in this period were replaced. */
        val replaceRows: List<StatementRow> = newRows,
        /** Payments an earlier statement import already added inside this file's period. */
        val earlierImported: Int = 0,
        val rangeStart: Long = 0L,
        val rangeEnd: Long = 0L
    ) {
        val earliest: Long? get() = newRows.minOfOrNull { it.timestamp }
        val latest: Long? get() = newRows.maxOfOrNull { it.timestamp }
    }

    sealed class Outcome {
        data class Ready(val analysis: Analysis) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private val dao = database.transactionDao()

    suspend fun analyze(uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val text = try {
            readText(uri, name)
        } catch (e: PdfLibraryMissingException) {
            return@withContext Outcome.Failed(e.message.orEmpty())
        } catch (e: StatementTooLargeException) {
            return@withContext Outcome.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            return@withContext Outcome.Failed("Could not read this file (${e.message ?: e.javaClass.simpleName}). If it is a password-protected PDF, open it elsewhere and export a CSV instead.")
        }
        if (text.isBlank()) return@withContext Outcome.Failed("The file has no readable text. Scanned (image) statements are not supported.")

        val parsed = StatementParser.parse(text)
        if (parsed.rows.isEmpty()) {
            return@withContext Outcome.Failed(
                "No payments were recognised in this file. Supported: PhonePe statements, Google Pay activity from Google Takeout, and bank CSV exports."
            )
        }

        // Skip rows the app already has (SMS usually got there first). Rows within one file are never
        // treated as duplicates of each other: only as many rows as already exist are skipped.
        val rangeStart = localDayStart(parsed.rows.minOf { it.timestamp })
        val rangeEnd = localDayEnd(parsed.rows.maxOf { it.timestamp })
        val earlier = dao.countImportedBetween(rangeStart, rangeEnd)
        // A DB row can account for only one statement row. Count based on a time bucket incorrectly
        // reused the same existing transaction for two rows near opposite sides of a bucket edge.
        val consumedExistingIds = HashSet<Long>()
        val consumedNonImportedIds = HashSet<Long>()
        val fresh = ArrayList<StatementRow>()
        val freshIgnoringImports = ArrayList<StatementRow>()
        var tracked = 0
        for (row in parsed.rows) {
            val type = if (row.isDebit) TransactionType.DEBIT else TransactionType.CREDIT
            val ref = row.referenceId
            // With a time, match within 15 minutes. Date-only rows match anything the same local day, never the
            // neighbouring days (a plain +/-24h window wrongly swallowed same-amount payments on adjacent days).
            val (from, to) = if (row.timeKnown) {
                (row.timestamp - 15 * 60_000L) to (row.timestamp + 15 * 60_000L)
            } else {
                val dayStart = localDayStart(row.timestamp)
                val dayEnd = localDayEnd(row.timestamp)
                dayStart to dayEnd
            }
            val byReference = if (ref != null) dao.findStatementCandidatesByReference(ref) else emptyList()
            val byAmountAndTime = dao.findStatementCandidatesByAmount(row.amountPaise, type, from, to)
            val candidates = (byReference + byAmountAndTime)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<com.expensetracker.offline.data.local.dao.StatementDuplicateCandidate> {
                        ref != null && it.referenceId == ref
                    }.thenBy { abs(it.timestamp - row.timestamp) }.thenBy { it.id }
                )

            // A statement row with the same reference as an app transaction is that transaction. Each
            // matching row is consumed once, including repeated same-amount payments in one file.
            val existing = candidates.firstOrNull { it.id !in consumedExistingIds }
            if (existing != null) {
                consumedExistingIds += existing.id
                tracked++
            } else {
                fresh += row
            }

            // When replacing earlier imports, only a separately matched non-import transaction keeps
            // the statement row out of the replacement set. Imported transactions linked to debts
            // or carrying split debts, and imports outside the replacement date range, are preserved.
            val existingWithoutImports = candidates.firstOrNull {
                it.id !in consumedNonImportedIds &&
                    (!ImportedSource.isImported(it.rawContent) ||
                        it.linkedDebtId != null ||
                        it.hasSplitDebts ||
                        it.timestamp !in rangeStart..rangeEnd)
            }
            if (existingWithoutImports != null) {
                consumedNonImportedIds += existingWithoutImports.id
            } else {
                freshIgnoringImports += row
            }
        }
        Outcome.Ready(
            Analysis(parsed.format, name, fresh, tracked, parsed.skipped, freshIgnoringImports, earlier, rangeStart, rangeEnd)
        )
    }

    /** @return how many transactions were added. */
    suspend fun commit(analysis: Analysis, replaceEarlier: Boolean = false): Int = withContext(Dispatchers.IO) {
        val rows = if (replaceEarlier) analysis.replaceRows else analysis.newRows
        if (rows.isEmpty()) return@withContext 0
        val settings = SettingsRepository(context)
        val primaryBank = settings.primaryAccountBank
        val primaryAcc = settings.primaryAccountNumber
        val ruleDao = database.categoryRuleDao()
        val label = analysis.format.label
        val entities = rows.map { row ->
            val payee = settings.getMerchantAlias(row.payee) ?: row.payee
            val type = if (row.isDebit) TransactionType.DEBIT else TransactionType.CREDIT
            val category = if (!row.isDebit) "Uncategorized" else {
                val c = CategoryClassifier.classify(payee = payee, rawContent = payee, ruleDao = ruleDao, note = null)
                if (c == "Uncategorized" || c.isBlank()) "Unspecified" else c
            }
            TransactionEntity(
                amount = row.amountPaise,
                payee = payee,
                timestamp = row.timestamp,
                type = type,
                source = TransactionSource.MANUAL,
                referenceId = row.referenceId,
                // Recognised by its rawContent prefix (see ImportedSource); no note, so the dashboard row stays clean.
                rawContent = "Imported from $label",
                category = category,
                note = null,
                bankName = primaryBank,
                accountNumber = primaryAcc
            )
        }
        CaptureDispatcher.ingestMutex.withLock {
            database.withTransaction {
                if (replaceEarlier) dao.deleteImportedBetween(analysis.rangeStart, analysis.rangeEnd)
                dao.insertTransactions(entities)
                dao.unifyAllTransactionsToSingleAccount(primaryBank, primaryAcc)
            }
        }
        // Paying a card bill from UPI is a transfer, not a new expense: pair it with the card's payment credit.
        runCatching {
            CardPaymentReconciler.reconcileAll(dao, (rows.minOfOrNull { it.timestamp } ?: 0L) - CardPaymentReconciler.WINDOW_MS)
        }
        runCatching { ExpenseWidget.updateAll(context) }
        entities.size
    }

    // ───────────────────────────── file reading ─────────────────────────────

    private fun localDayStart(timestamp: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun localDayEnd(timestamp: Long): Long = Calendar.getInstance().apply {
        timeInMillis = localDayStart(timestamp)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis - 1L

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "statement"

    private fun readText(uri: Uri, name: String): String {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val lower = name.lowercase(Locale.ROOT)
        val open: () -> InputStream = {
            context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("file not readable")
        }
        return when {
            lower.endsWith(".pdf") || mime == "application/pdf" -> open().use { extractPdfText(it) }
            lower.endsWith(".zip") || mime == "application/zip" -> open().use { readZip(it) }
            else -> open().use { readBounded(it, MAX_BYTES).toString(Charsets.UTF_8) }
        }
    }

    /** Google Takeout zip: use the Google Pay activity files (and any CSV), ignore everything else. */
    private fun readZip(input: InputStream): String {
        val sb = StringBuilder()
        var totalBytes = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name.lowercase(Locale.ROOT)
                val wanted = !entry.isDirectory && (n.endsWith(".csv") ||
                    ((n.endsWith(".html") || n.endsWith(".json")) && (n.contains("google pay") || n.contains("gpay") || n.contains("my activity"))))
                if (wanted) {
                    val bytes = readBounded(zip, MAX_BYTES - totalBytes)
                    totalBytes += bytes.size
                    sb.append(bytes.toString(Charsets.UTF_8)).append('\n')
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return sb.toString()
    }

    /** Reads at most [maxBytes]; calling readBytes() before truncating can exhaust memory on huge files. */
    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            if (read == 0) {
                val singleByte = input.read()
                if (singleByte < 0) break
                output.write(singleByte)
                remaining--
                continue
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
        // Don't silently parse/import only the first 30 MB of a larger statement.
        if (remaining == 0 && input.read() != -1) throw StatementTooLargeException()
        return output.toByteArray()
    }

    /** Uses PDFBox-Android through reflection so the app still builds (and the rest still works) without it. */
    private fun extractPdfText(input: InputStream): String {
        val loader = try {
            Class.forName("com.tom_roush.pdfbox.android.PDFBoxResourceLoader")
        } catch (_: ClassNotFoundException) {
            throw PdfLibraryMissingException()
        }
        loader.getMethod("init", Context::class.java).invoke(null, context.applicationContext)
        val docClass = Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument")
        val doc = docClass.getMethod("load", InputStream::class.java).invoke(null, input)
        try {
            val stripperClass = Class.forName("com.tom_roush.pdfbox.text.PDFTextStripper")
            val stripper = stripperClass.getDeclaredConstructor().newInstance()
            return stripperClass.getMethod("getText", docClass).invoke(stripper, doc) as String
        } finally {
            runCatching { docClass.getMethod("close").invoke(doc) }
        }
    }

    private companion object {
        const val MAX_BYTES = 30 * 1024 * 1024
    }
}
