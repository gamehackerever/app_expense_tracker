package com.expensetracker.offline.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.DebtStatus
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.dashboard.mySpendShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

sealed class CsvResult {
    data class Success(val message: String, val transactions: List<TransactionEntity> = emptyList()) : CsvResult()
    data class PartialSuccess(val message: String, val transactions: List<TransactionEntity>) : CsvResult()
    data class Error(val message: String) : CsvResult()
}

object CsvEngine {

    private const val HEADER =
        "Date,Time,Type,Category,Amount,Payee,Bank,Account,Notes,Your Split Share,Paid By,Exclude From Spend,Reference ID,Split Details"

    suspend fun exportToCsv(
        context: Context,
        transactions: List<TransactionEntity>,
        targetTreeUri: String,
        splitShares: Map<Long, Long> = emptyMap()
    ): CsvResult {
        val wrapped = transactions.map { TransactionWithDebts(it, emptyList()) }
        return exportWithDebtsToCsv(context, wrapped, targetTreeUri, splitShares)
    }

    suspend fun exportWithDebtsToCsv(
        context: Context,
        transactionsWithDebts: List<TransactionWithDebts>,
        targetTreeUri: String,
        splitSharesOverride: Map<Long, Long> = emptyMap()
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

                transactionsWithDebts.sortedByDescending { it.transaction.timestamp }.forEach { item ->
                    val txn = item.transaction
                    val debts = item.debts
                    val date = dateFormat.format(Date(txn.timestamp))
                    val time = timeFormat.format(Date(txn.timestamp))
                    val type = if (txn.type == TransactionType.DEBIT) "Expense" else "Income"
                    val amount = String.format(Locale.US, "%.2f", txn.amount / 100.0)

                    val mySharePaise = splitSharesOverride[txn.id]
                        ?: if (debts.isNotEmpty() && txn.type == TransactionType.DEBIT) mySpendShare(item) else null
                    val share = mySharePaise?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: ""

                    val accRaw = txn.accountNumber ?: ""
                    val account = if (accRaw.isNotBlank()) "'$accRaw" else ""

                    val paidBy = txn.paidBy?.takeIf { it.isNotBlank() } ?: "You"
                    val excludeFlag = if (txn.excludeFromSpend) "true" else "false"
                    val refId = txn.referenceId ?: ""
                    val splitDetails = if (debts.isEmpty()) "" else {
                        debts.joinToString(";") { d ->
                            val cleanDebtor = d.debtorName.replace(";", " ").replace(":", " ").trim()
                            val amtStr = String.format(Locale.US, "%.2f", d.amountOwed / 100.0)
                            "$cleanDebtor:$amtStr:${d.status.name}"
                        }
                    }

                    val row = listOf(
                        date, time, type,
                        quote(txn.category),
                        amount,
                        quote(txn.payee),
                        quote(txn.bankName ?: ""),
                        quote(account),
                        quote(txn.itemsSummary ?: txn.note ?: ""),
                        share,
                        quote(paidBy),
                        excludeFlag,
                        quote(refId),
                        quote(splitDetails)
                    )
                    writer.write(row.joinToString(",") + "\n")
                }
            }
            CsvResult.Success("SUCCESS")
        } catch (e: Exception) {
            CsvResult.Error("Export failed: ${e.localizedMessage}")
        }
    }

    suspend fun importFromCsv(
        context: Context,
        uri: Uri,
        onImportedWithDebts: (suspend (List<TransactionEntity>, Map<Int, List<SplitDebtEntity>>) -> Int)? = null,
        onImported: suspend (List<TransactionEntity>) -> Int
    ): CsvResult = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@withContext CsvResult.Error("Could not open the selected file.")

            if (text.length > 5 * 1024 * 1024) return@withContext CsvResult.Error("File is too large (> 5MB).")

            val rows = try {
                parseCsv(text.removePrefix("\uFEFF"))
            } catch (e: IllegalStateException) {
                return@withContext CsvResult.Error("Invalid CSV formatting: ${e.message}")
            }
            if (rows.isEmpty()) return@withContext CsvResult.Error("File is empty.")

            val headerLine = rows[0].joinToString(",").lowercase()
            val isNewFormat = headerLine.contains("bank")
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
            val splitsByIndex = mutableMapOf<Int, List<SplitDebtEntity>>()
            var skipped = 0

            for (tokens in rows.drop(1)) {
                if (tokens.all { it.isBlank() }) continue
                if (tokens.size < 6) { skipped++; continue }

                val timestamp = parseDate(tokens[0], tokens[1])

                val parsedAmountRupees = tokens[4].trim().replace(",", "").toDoubleOrNull()
                if (timestamp == null || parsedAmountRupees == null || parsedAmountRupees <= 0.0 || parsedAmountRupees.isNaN() || parsedAmountRupees.isInfinite()) { skipped++; continue }
                val amount = round(parsedAmountRupees * 100.0).toLong()

                val typeStr = tokens[2].trim().lowercase()
                val type = when (typeStr) {
                    "expense", "dr", "debit", "withdrawal" -> TransactionType.DEBIT
                    "income", "cr", "credit", "deposit" -> TransactionType.CREDIT
                    else -> { skipped++; continue }
                }

                val payee = unquote(tokens[5])
                val category = unquote(tokens[3]).ifBlank { "Uncategorized" }

                val bank = if (isNewFormat) tokens.getOrNull(6)?.let(::unquote)?.takeIf { it.isNotBlank() } else null
                val accountRaw = if (isNewFormat) tokens.getOrNull(7)?.let(::unquote)?.takeIf { it.isNotBlank() } else null
                val account = accountRaw?.filter { it.isDigit() }?.takeLast(4)?.ifBlank { null }

                val notes = (if (isNewFormat) tokens.getOrNull(8) else tokens.getOrNull(6))?.let(::unquote)?.takeIf { it.isNotBlank() }
                val splitRupees = (if (isNewFormat) tokens.getOrNull(9) else tokens.getOrNull(7))?.trim()?.replace(",", "")?.toDoubleOrNull()
                val mySplitSharePaise = splitRupees?.let { round(it * 100.0).toLong() }

                val paidBy = if (isNewFormat) tokens.getOrNull(10)?.let(::unquote)?.takeIf { it.isNotBlank() } ?: "You" else "You"
                val excludeFromSpend = if (isNewFormat) {
                    tokens.getOrNull(11)?.trim()?.equals("true", ignoreCase = true) == true ||
                            category.equals("Transfer", ignoreCase = true)
                } else category.equals("Transfer", ignoreCase = true)
                val referenceId = if (isNewFormat) tokens.getOrNull(12)?.let(::unquote)?.takeIf { it.isNotBlank() } else null
                val splitDetailsRaw = if (isNewFormat) tokens.getOrNull(13)?.let(::unquote)?.takeIf { it.isNotBlank() } else null

                val parsedDebts = mutableListOf<SplitDebtEntity>()
                if (!splitDetailsRaw.isNullOrBlank()) {
                    splitDetailsRaw.split(";").forEach { entry ->
                        val parts = entry.split(":")
                        if (parts.size >= 2) {
                            val debtor = parts[0].trim()
                            val owedPaise = parts[1].trim().toDoubleOrNull()?.let { round(it * 100.0).toLong() } ?: 0L
                            val status = parts.getOrNull(2)?.trim()?.let {
                                runCatching { DebtStatus.valueOf(it) }.getOrDefault(DebtStatus.OPEN)
                            } ?: DebtStatus.OPEN
                            if (debtor.isNotEmpty() && owedPaise > 0L) {
                                parsedDebts.add(
                                    SplitDebtEntity(
                                        transactionId = 0L,
                                        debtorName = debtor,
                                        amountOwed = owedPaise,
                                        originalAmount = owedPaise,
                                        createdAt = timestamp,
                                        settledAt = if (status != DebtStatus.OPEN) timestamp else null,
                                        status = status
                                    )
                                )
                            }
                        }
                    }
                } else if (mySplitSharePaise != null && mySplitSharePaise in 0L until amount && type == TransactionType.DEBIT) {
                    val owedByFriend = amount - mySplitSharePaise
                    if (owedByFriend > 0L) {
                        parsedDebts.add(
                            SplitDebtEntity(
                                transactionId = 0L,
                                debtorName = "Split Partner",
                                amountOwed = owedByFriend,
                                originalAmount = owedByFriend,
                                createdAt = timestamp
                            )
                        )
                    }
                }

                val idx = transactions.size
                if (parsedDebts.isNotEmpty()) {
                    splitsByIndex[idx] = parsedDebts
                }

                transactions.add(
                    TransactionEntity(
                        amount = amount,
                        payee = payee,
                        timestamp = timestamp,
                        type = type,
                        source = TransactionSource.MANUAL,
                        referenceId = referenceId,
                        rawContent = "Imported from CSV",
                        category = category,
                        note = notes,
                        bankName = bank,
                        accountNumber = account,
                        excludeFromSpend = excludeFromSpend,
                        paidBy = paidBy,
                        accountType = if (bank?.endsWith(" CC", ignoreCase = true) == true) "CREDIT_CARD" else "BANK"
                    )
                )
            }

            if (transactions.isEmpty()) {
                return@withContext CsvResult.Error(if (skipped > 0) "No valid rows found ($skipped skipped)." else "No transactions found.")
            }

            val inserted = if (onImportedWithDebts != null) {
                onImportedWithDebts(transactions, splitsByIndex)
            } else {
                onImported(transactions)
            }
            val duplicates = transactions.size - inserted
            if (inserted == 0) {
                return@withContext CsvResult.Error("Nothing imported: all ${transactions.size} rows are already in the app.")
            }
            if (skipped > 0 || duplicates > 0) {
                val notes = buildList {
                    if (duplicates > 0) add("skipped $duplicates already in the app")
                    if (skipped > 0) add("skipped $skipped invalid rows")
                }
                CsvResult.PartialSuccess("Imported $inserted transactions, ${notes.joinToString(", ")}.", transactions)
            } else CsvResult.Success("SUCCESS", transactions)

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

        if (inQuotes) throw IllegalStateException("Unterminated quote at EOF")

        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
