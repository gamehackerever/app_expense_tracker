package com.expensetracker.offline.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri
import kotlin.math.round

sealed class CsvResult {
    data class Success(val message: String, val transactions: List<TransactionEntity> = emptyList()) : CsvResult()
    data class PartialSuccess(val message: String, val transactions: List<TransactionEntity>) : CsvResult()
    data class Error(val message: String) : CsvResult()
}

object CsvEngine {

    private const val HEADER = "Date,Time,Type,Category,Amount,Payee,Bank,Account,Notes,Your Split Share"

    suspend fun exportToCsv(
        context: Context,
        transactions: List<TransactionEntity>,
        targetTreeUri: String,
        splitShares: Map<Long, Long> = emptyMap()
    ): CsvResult = withContext(Dispatchers.IO) {
        try {
            val treeUri = targetTreeUri.toUri()
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val newFileUri = DocumentsContract.createDocument(context.contentResolver, docUri, "text/csv", "ExpenseExport_$timestamp.csv")
                ?: return@withContext CsvResult.Error("Could not create CSV file.")

            val output = context.contentResolver.openOutputStream(newFileUri)
                ?: return@withContext CsvResult.Error("Could not open the new CSV file for writing.")

            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(HEADER + "\n")

                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

                transactions.sortedByDescending { it.timestamp }.forEach { txn ->
                    val date = dateFormat.format(Date(txn.timestamp))
                    val time = timeFormat.format(Date(txn.timestamp))
                    val type = if (txn.type == TransactionType.DEBIT) "Expense" else "Income"
                    val amount = String.format(Locale.US, "%.2f", txn.amount / 100.0)

                    val share = splitShares[txn.id]?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: ""

                    // FIXED: Prefix account with ' to prevent Excel from dropping leading zeros (PO-I)
                    val accRaw = txn.accountNumber ?: ""
                    val account = if (accRaw.isNotBlank()) "'$accRaw" else ""

                    val row = listOf(
                        date, time, type,
                        quote(txn.category),
                        amount,
                        quote(txn.payee),
                        quote(txn.bankName ?: ""),
                        quote(account),
                        quote(txn.itemsSummary ?: txn.note ?: ""),
                        share
                    )
                    writer.write(row.joinToString(",") + "\n")
                }
            }
            CsvResult.Success("SUCCESS")
        } catch (e: Exception) {
            CsvResult.Error("Export failed: ${e.localizedMessage}")
        }
    }

    suspend fun importFromCsv(context: Context, uri: Uri, onImported: suspend (List<TransactionEntity>) -> Unit): CsvResult = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@withContext CsvResult.Error("Could not open the selected file.")

            // FIXED: Caps memory size and gracefully fails on unterminated quotes
            if (text.length > 5 * 1024 * 1024) return@withContext CsvResult.Error("File is too large (> 5MB).")

            val rows = try {
                parseCsv(text.removePrefix("\uFEFF"))
            } catch (e: IllegalStateException) {
                return@withContext CsvResult.Error("Invalid CSV formatting: ${e.message}")
            }
            if (rows.isEmpty()) return@withContext CsvResult.Error("File is empty.")

            val isNewFormat = rows[0].joinToString(",").lowercase().contains("bank")
            val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd").map {
                SimpleDateFormat(it, Locale.US).apply { isLenient = false }
            }

            fun parseDate(date: String, time: String): Long? {
                val candidates = listOf("$date $time".trim(), date.trim())
                for (candidate in candidates) {
                    for (f in formats) {
                        try { return f.parse(candidate)?.time } catch (_: ParseException) { }
                    }
                }
                return null
            }

            val transactions = mutableListOf<TransactionEntity>()
            var skipped = 0

            for (tokens in rows.drop(1)) {
                if (tokens.all { it.isBlank() }) continue
                if (tokens.size < 6) { skipped++; continue }

                val timestamp = parseDate(tokens[0], tokens[1])

                // FIXED: Convert amount in rupees from CSV to paise (PO-I)
                val parsedAmountRupees = tokens[4].trim().replace(",", "").toDoubleOrNull()
                if (timestamp == null || parsedAmountRupees == null || parsedAmountRupees <= 0.0 || parsedAmountRupees.isNaN() || parsedAmountRupees.isInfinite()) { skipped++; continue }
                val amount = round(parsedAmountRupees * 100.0).toLong()

                // FIXED: Strict mapping to prevent "Withdrawal" from silently becoming Income (PO-I)
                val typeStr = tokens[2].trim().lowercase()
                val type = when (typeStr) {
                    "expense", "dr", "debit", "withdrawal" -> TransactionType.DEBIT
                    "income", "cr", "credit", "deposit" -> TransactionType.CREDIT
                    else -> { skipped++; continue }
                }

                val payee = unquote(tokens[5])
                val category = unquote(tokens[3]).ifBlank { "Uncategorized" }

                val bank = if (isNewFormat) tokens.getOrNull(6)?.let(::unquote)?.takeIf { it.isNotBlank() } else null

                // FIXED: Normalize account number to last 4 digits (PO-I)
                val accountRaw = if (isNewFormat) tokens.getOrNull(7)?.let(::unquote)?.takeIf { it.isNotBlank() } else null
                val account = accountRaw?.filter { it.isDigit() }?.takeLast(4)?.ifBlank { null }

                val notes = (if (isNewFormat) tokens.getOrNull(8) else tokens.getOrNull(6))?.let(::unquote)?.takeIf { it.isNotBlank() }
                val splitRupees = (if (isNewFormat) tokens.getOrNull(9) else tokens.getOrNull(7))?.trim()?.replace(",", "")?.toDoubleOrNull()
                val split = splitRupees?.let { round(it * 100.0).toLong() }

                val finalNotes = if (split != null) listOfNotNull(notes, "Imported Share: $split").joinToString(" | ") else notes

                transactions.add(
                    TransactionEntity(
                        amount = amount, payee = payee, timestamp = timestamp, type = type,
                        source = TransactionSource.MANUAL, referenceId = null, rawContent = "Imported from CSV",
                        category = category, note = finalNotes, bankName = bank, accountNumber = account
                    )
                )
            }

            if (transactions.isEmpty()) {
                return@withContext CsvResult.Error(if (skipped > 0) "No valid rows found ($skipped skipped)." else "No transactions found.")
            }

            onImported(transactions)
            if (skipped > 0) CsvResult.PartialSuccess("Imported ${transactions.size} transactions, skipped $skipped invalid rows.", transactions)
            else CsvResult.Success("SUCCESS", transactions)

        } catch (e: Exception) {
            CsvResult.Error("Import failed: ${e.localizedMessage}")
        }
    }

    private const val FORMULA_TRIGGERS = "=+-@\t\r"

    private fun quote(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_TRIGGERS) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun unquote(value: String): String {
        val v = value.trim()
        return if (v.length > 1 && v[0] == '\'' && v[1] in FORMULA_TRIGGERS) v.substring(1) else v
    }

    @Throws(IllegalStateException::class)
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }

        // FIXED: Throws rather than silently swallowing the rest of the file into one giant row
        if (inQuotes) throw IllegalStateException("Unterminated quote at EOF")

        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
