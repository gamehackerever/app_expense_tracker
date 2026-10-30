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
import com.expensetracker.offline.widget.ExpenseWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

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
        val rangeStart = parsed.rows.minOf { it.timestamp }
        val rangeEnd = parsed.rows.maxOf { it.timestamp } + 24 * 3_600_000L - 1
        val earlier = dao.countImportedBetween(rangeStart, rangeEnd)
        val consumed = HashMap<String, Int>()
        val consumedIgnoringImports = HashMap<String, Int>()
        val fresh = ArrayList<StatementRow>()
        val freshIgnoringImports = ArrayList<StatementRow>()
        var tracked = 0
        for (row in parsed.rows) {
            val type = if (row.isDebit) TransactionType.DEBIT else TransactionType.CREDIT
            val ref = row.referenceId
            val refAll = if (ref != null) dao.countByReference(ref) else 0
            // Only needed when an earlier import exists in this period (the "replace earlier imports" option).
            val refImported = if (ref != null && earlier > 0) dao.countImportedByReference(ref) else 0
            // With a time, match within 15 minutes. Date-only rows match anything the same local day, never the
            // neighbouring days (a plain +/-24h window wrongly swallowed same-amount payments on adjacent days).
            val (from, to) = if (row.timeKnown) {
                (row.timestamp - 15 * 60_000L) to (row.timestamp + 15 * 60_000L)
            } else {
                val startCalendar = java.util.Calendar.getInstance()
                startCalendar.timeInMillis = row.timestamp
                startCalendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                startCalendar.set(java.util.Calendar.MINUTE, 0)
                startCalendar.set(java.util.Calendar.SECOND, 0)
                startCalendar.set(java.util.Calendar.MILLISECOND, 0)
                val dayStart = startCalendar.timeInMillis
                val endCalendar = java.util.Calendar.getInstance().apply {
                    timeInMillis = dayStart
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                val dayEnd = endCalendar.timeInMillis - 1
                dayStart to dayEnd
            }
            val key = "${row.amountPaise}|$type|${if (row.timeKnown) row.timestamp / (15 * 60_000L) else from}"
            val sameAll = dao.countSameAmountBetween(row.amountPaise, type, from, to)
            val sameImported = if (earlier > 0) dao.countImportedSameAmountBetween(row.amountPaise, type, from, to) else 0

            // A statement row with the same reference as an app transaction is that transaction: skip it outright.
            val existing = if (refAll > 0) Int.MAX_VALUE else sameAll
            val used = consumed[key] ?: 0
            if (existing > used) { consumed[key] = used + 1; tracked++ } else fresh += row

            // Same check as if earlier statement imports did not exist (used when the user replaces them).
            val existingOther = if (refAll - refImported > 0) Int.MAX_VALUE else (sameAll - sameImported).coerceAtLeast(0)
            val usedOther = consumedIgnoringImports[key] ?: 0
            if (existingOther > usedOther) consumedIgnoringImports[key] = usedOther + 1 else freshIgnoringImports += row
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
        val ruleDao = database.categoryRuleDao()
        val label = analysis.format.label
        val entities = rows.map { row ->
            val payee = settings.getMerchantAlias(row.payee) ?: row.payee
            val type = if (row.isDebit) TransactionType.DEBIT else TransactionType.CREDIT
            val category = if (!row.isDebit) "Uncategorized" else CategoryClassifier.classify(payee, payee, ruleDao)
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
                note = null
            )
        }
        CaptureDispatcher.ingestMutex.withLock {
            database.withTransaction {
                if (replaceEarlier) dao.deleteImportedBetween(analysis.rangeStart, analysis.rangeEnd)
                dao.insertTransactions(entities)
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
