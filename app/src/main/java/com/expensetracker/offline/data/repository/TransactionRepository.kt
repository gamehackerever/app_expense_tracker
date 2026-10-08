package com.expensetracker.offline.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.local.dao.CategoryInsight
import com.expensetracker.offline.data.local.dao.PayeeInsight
import com.expensetracker.offline.data.local.entity.CategoryRuleEntity
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.ReviewStatus
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.categorizer.NaiveBayesCategorizer
import com.expensetracker.offline.engine.notification.LowBalanceNotifier
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.widget.ExpenseWidget
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Calendar

class TransactionRepository(
    private val database: AppDatabase,
    private val context: Context? = null
) {
    private val transactionDao = database.transactionDao()
    private val reviewItemDao = database.reviewItemDao()
    private val categoryRuleDao = database.categoryRuleDao()
    private val splitDebtDao = database.splitDebtDao()

    val allTransactionsWithDebts: Flow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> = transactionDao.getAllTransactionsWithDebtsFlow()
    val pendingReviewItems: Flow<List<ReviewItemEntity>> = reviewItemDao.getPendingReviewItemsFlow()
    val pendingReviewCount: Flow<Int> = reviewItemDao.getPendingReviewCountFlow()
    val latestBalance: Flow<Long?> = transactionDao.getLatestBalanceFlow()
    val totalMoneyOwed: Flow<Double?> = transactionDao.getTotalMoneyOwedToYouFlow()

    val unsettledSplitsWithDebts: Flow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> =
        splitDebtDao.getUnsettledTransactionsWithDebtsFlow()

    val categoryInsights: Flow<List<CategoryInsight>> = transactionDao.getCategoryInsightsFlow()
    val payeeInsights: Flow<List<PayeeInsight>> = transactionDao.getPayeeInsightsFlow()

    // ── Split debts ──────────────────────────────────────────────────────────

    private fun refreshWidget() {
        context?.let { runCatching { ExpenseWidget.updateAll(it) } }
    }

    /**
     * DEBT MODEL: `amountOwed` (== `originalAmount`) is the person's share of the bill and is NEVER
     * changed by repayments. Outstanding = amountOwed - sum(linked credits), computed on read.
     * This keeps "my spend" (bill - shares) stable when someone repays. Only status/settledAt move.
     */
    suspend fun reconcileDebt(debtId: Long) {
        database.withTransaction {
            val debt = splitDebtDao.getDebtById(debtId) ?: return@withTransaction
            if (debt.status == com.expensetracker.offline.data.local.entity.DebtStatus.FORGIVEN) return@withTransaction

            val repaid = splitDebtDao.getRepaidAmountForDebt(debtId)
            val outstanding = debt.amountOwed - repaid

            val newStatus = if (outstanding <= 0L) {
                com.expensetracker.offline.data.local.entity.DebtStatus.SETTLED
            } else {
                com.expensetracker.offline.data.local.entity.DebtStatus.OPEN
            }
            val newSettledAt = if (newStatus == com.expensetracker.offline.data.local.entity.DebtStatus.SETTLED) {
                debt.settledAt ?: System.currentTimeMillis()
            } else null

            if (newStatus != debt.status || newSettledAt != debt.settledAt || debt.originalAmount != debt.amountOwed) {
                splitDebtDao.updateDebt(debt.copy(status = newStatus, settledAt = newSettledAt, originalAmount = debt.amountOwed))
            }
        }
    }

    suspend fun saveMultiSplit(transactionId: Long, debts: List<SplitDebtEntity>) {
        database.withTransaction {
            val existing = splitDebtDao.getDebtsForTransaction(transactionId)
            val existingMap = existing.associateBy { it.id }
            val keptIds = mutableSetOf<Long>()

            // The unique index on (transactionId, debtorName) would throw on duplicates, so fold
            // rows that share a name (case-insensitive) into one before writing.
            val folded = debts
                .map { it.copy(debtorName = it.debtorName.trim()) }
                .filter { it.debtorName.isNotEmpty() && it.amountOwed > 0L }
                .groupBy { it.debtorName.lowercase() }
                .map { (_, group) ->
                    val keep = group.firstOrNull { it.id != 0L && existingMap.containsKey(it.id) } ?: group.first()
                    keep.copy(amountOwed = group.sumOf { it.amountOwed })
                }

            val toUpsert = folded.map { incoming ->
                if (incoming.id != 0L && existingMap.containsKey(incoming.id)) {
                    keptIds += incoming.id
                    existingMap.getValue(incoming.id).copy(
                        debtorName = incoming.debtorName,
                        amountOwed = incoming.amountOwed,
                        originalAmount = incoming.amountOwed
                    )
                } else {
                    incoming.copy(id = 0L, transactionId = transactionId, originalAmount = incoming.amountOwed)
                }
            }

            val removedIds = existing.filter { it.id !in keptIds }.map { it.id }
            if (removedIds.isNotEmpty()) {
                // UI warns first when repayments exist; repayments get unlinked (kept as income).
                transactionDao.unlinkRepaymentsForDebts(removedIds)
                splitDebtDao.deleteDebtsByIds(removedIds)
            }
            if (toUpsert.isNotEmpty()) splitDebtDao.upsertDebts(toUpsert)

            // Recompute status for every debt on this bill (covers raised/lowered amounts and new rows).
            splitDebtDao.getDebtsForTransaction(transactionId).forEach { reconcileDebt(it.id) }
        }
    }

    suspend fun resetSplit(transactionId: Long) {
        database.withTransaction {
            val ids = splitDebtDao.getDebtsForTransaction(transactionId).map { it.id }
            if (ids.isNotEmpty()) {
                transactionDao.unlinkRepaymentsForDebts(ids)
                splitDebtDao.deleteDebtsForTransaction(transactionId)
            }
        }
    }

    suspend fun markDebtAsSettled(debtId: Long, timestamp: Long) {
        database.withTransaction { splitDebtDao.markDebtSettled(debtId, timestamp) }
    }

    suspend fun markDebtAsUnsettled(debtId: Long) {
        database.withTransaction {
            splitDebtDao.markDebtUnsettled(debtId)
            reconcileDebt(debtId)
        }
    }

    suspend fun forgiveDebt(debtId: Long, timestamp: Long) {
        database.withTransaction { splitDebtDao.forgiveDebt(debtId, timestamp) }
    }

    suspend fun linkDebtToRepayment(debtId: Long, creditId: Long) {
        database.withTransaction {
            val creditTx = transactionDao.getTransactionById(creditId)
            // FIXED: Ensure we don't link a Debit, and ensure the credit isn't already claimed
            if (creditTx == null || creditTx.type != TransactionType.CREDIT || creditTx.linkedDebtId != null) {
                return@withTransaction
            }

            transactionDao.linkRepayment(creditId, debtId)
            reconcileDebt(debtId)
        }
    }

    suspend fun logCashRepayment(debtId: Long, amount: Long, timestamp: Long) {
        database.withTransaction {
            val debt = splitDebtDao.getDebtById(debtId) ?: return@withTransaction
            val repaid = splitDebtDao.getRepaidAmountForDebt(debtId)
            val outstanding = (debt.amountOwed - repaid).coerceAtLeast(0L)

            // FIXED: Cap the repayment so you can't overpay a debt and inflate income (PO-D)
            val capped = minOf(amount, outstanding)
            if (capped <= 0L) return@withTransaction

            transactionDao.insertTransaction(
                TransactionEntity(
                    amount = capped,
                    payee = debt.debtorName,
                    timestamp = timestamp,
                    type = TransactionType.CREDIT,
                    source = TransactionSource.MANUAL,
                    referenceId = null,
                    rawContent = "Manual cash repayment",
                    category = "Split Settlement",
                    itemsSummary = "Cash repayment",
                    linkedDebtId = debtId
                )
            )
            reconcileDebt(debtId)
        }
    }

    suspend fun unlinkRepayment(creditId: Long) {
        database.withTransaction {
            val credit = transactionDao.getTransactionById(creditId) ?: return@withTransaction
            val debtId = credit.linkedDebtId ?: return@withTransaction

            transactionDao.unlinkRepayment(creditId)
            reconcileDebt(debtId) // This will reopen the debt automatically
        }
    }

    suspend fun undoRepayment(credit: TransactionEntity) {
        val debtId = credit.linkedDebtId ?: return
        database.withTransaction {
            transactionDao.unlinkRepayment(credit.id)
            reconcileDebt(debtId)
            if (credit.source == TransactionSource.MANUAL && credit.category == "Split Settlement") {
                transactionDao.deleteTransactionById(credit.id)
            }
        }
    }

    fun getMonthlyDebitSum(
        startOfMonth: Long = getCurrentMonthStart(),
        endOfMonth: Long = getCurrentMonthEnd()
    ): Flow<Double?> {
        return transactionDao.getMonthlyDebitSumFlow(startOfMonth, endOfMonth)
    }

    suspend fun updatePayeeAndCategoryBulk(oldPayee: String, newPayee: String, newCategory: String) {
        database.transactionDao().updatePayeeAndCategoryBulk(oldPayee, newPayee, newCategory)

        val trimmedOld = oldPayee.trim()
        val trimmedNew = newPayee.trim()
        val appPrefs = context?.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        appPrefs?.edit()?.putString("merchant_alias_${trimmedOld.lowercase()}", trimmedNew)?.apply()
    }

    fun searchAndFilterTransactionsFlow(search: String, category: String, startTime: Long, limit: Int): Flow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> {
        val escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return transactionDao.searchAndFilterTransactionsFlow(escaped, category, startTime, limit)
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        database.withTransaction {
            val before = transactionDao.getTransactionById(transaction.id)
            transactionDao.updateTransaction(transaction)
            setOfNotNull(before?.linkedDebtId, transaction.linkedDebtId).forEach { reconcileDebt(it) }
        }
        refreshWidget()
    }

    suspend fun renamePayeeForAll(oldPayee: String, newPayee: String) {
        val trimmedOld = oldPayee.trim()
        val trimmedNew = newPayee.trim()
        transactionDao.renamePayeeForAll(trimmedOld, trimmedNew)

        val appPrefs = context?.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        appPrefs?.edit()?.putString("merchant_alias_${trimmedOld.lowercase()}", trimmedNew)?.apply()
    }

    suspend fun insertTransaction(transaction: TransactionEntity): Long {
        val id = database.withTransaction {
            val newId = transactionDao.insertTransaction(transaction)
            transaction.linkedDebtId?.let { reconcileDebt(it) }
            newId
        }
        refreshWidget()
        return id
    }

    suspend fun learnCategoryRule(payee: String, category: String) {
        val pattern = payee.trim().lowercase()
            .replace("%", "\\%")
            .replace("_", "\\_")
        if (pattern.length >= 2 && category != "Uncategorized") {
            val existing = categoryRuleDao.getExactRule(pattern)
            val count = (existing?.usageCount ?: 0) + 1
            categoryRuleDao.upsertRule(
                CategoryRuleEntity(
                    merchantPattern = pattern,
                    category = category,
                    usageCount = count,
                    lastUpdated = System.currentTimeMillis()
                )
            )
            NaiveBayesCategorizer.invalidate()   // <-- add
        }
    }

    suspend fun classifyCategory(payee: String, rawContent: String): String =
        CategoryClassifier.classify(
            payee = payee,
            rawContent = rawContent,
            ruleDao = categoryRuleDao
        )

    fun recurringPaymentsFlow(): Flow<List<RecurringPayment>> =
        transactionDao.getDebitsSinceFlow(System.currentTimeMillis() - RecurringPaymentDetector.LOOKBACK_MS)
            .map { RecurringPaymentDetector.detect(it) }
            .flowOn(Dispatchers.Default)

    suspend fun deleteTransaction(id: Long) = deleteTransactionsByIds(listOf(id))

    suspend fun clearAllTransactions() {
        transactionDao.clearAllTransactions() // split_debts cascade away with their bills
        context?.let { ctx ->
            // Balance baselines no longer match the data; drop them so the ghost check re-learns.
            com.expensetracker.offline.util.BalanceCache.clearAll(ctx)
        }
        refreshWidget()
    }

    suspend fun deleteTransactionsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        database.withTransaction {
            val debtIds = ids.mapNotNull { transactionDao.getTransactionById(it)?.linkedDebtId }.toSet()
            transactionDao.deleteTransactionsByIds(ids)
            debtIds.forEach { reconcileDebt(it) } // deleting a repayment reopens the debt
        }
        refreshWidget()
    }

    /** Returns the most frequently split participants for a given merchant. */
    suspend fun predictSplitPartners(payee: String): List<String> {
        return database.transactionDao().searchAndFilterTransactionsFlow(payee, "Split Only", 0L, 10)
            .firstOrNull()
            ?.flatMap { it.debts }
            ?.map { it.debtorName }
            ?.groupingBy { it }
            ?.eachCount()
            ?.entries
            ?.sortedByDescending { it.value }
            ?.map { it.key }
            ?: emptyList()
    }

    // FIX: Deleted resolveReviewItem (Code logic was duplicated natively inside MainViewModel instead)

    /** Insert the approved transaction and close the review item in ONE transaction. */
    suspend fun approveReviewItem(transaction: TransactionEntity, reviewItemId: Long): Long {
        val id = database.withTransaction {
            val newId = transactionDao.insertTransaction(transaction)
            reviewItemDao.updateStatus(reviewItemId, ReviewStatus.RESOLVED)
            newId
        }
        refreshWidget()
        return id
    }

    suspend fun discardReviewItem(itemId: Long) {
        reviewItemDao.updateStatus(itemId, ReviewStatus.DISCARDED)
    }

    suspend fun reassignCategory(oldCategory: String, newCategory: String) {
        database.withTransaction {
            transactionDao.reassignCategory(oldCategory, newCategory)
            categoryRuleDao.reassignRuleCategory(oldCategory, newCategory)
        }
    }

    suspend fun insertTransactionsBulk(transactions: List<TransactionEntity>) {
        database.withTransaction {
            transactions.forEach { transactionDao.insertTransaction(it) }
        }
    }

    suspend fun renameAllPayees(oldPayee: String, newPayee: String) {
        transactionDao.renameAllPayees(oldPayee, newPayee)
    }

    fun getLinkedAccountsFlow() = transactionDao.getLinkedAccountsFlow()

    fun getLatestBalanceForAccountFlow(bankName: String, accountNumber: String?) =
        transactionDao.getLatestBalanceForAccountFlow(bankName, accountNumber)

    fun getLatestBalancesForAllAccountsFlow() =
        transactionDao.getLatestBalancesForAllAccountsFlow()

    sealed class IngestResult {
        data class Inserted(val transactionId: Long) : IngestResult()
        data class Merged(val transactionId: Long) : IngestResult()
        data class QueuedForReview(val reviewItemId: Long) : IngestResult()
        object Duplicate : IngestResult()
        data class Failed(val reason: String) : IngestResult()
    }

    private suspend fun queueForReview(
        parsed: com.expensetracker.offline.engine.parser.ParsedTransaction,
        sender: String, timestamp: Long, source: TransactionSource
    ): IngestResult {
        val amount = parsed.amount
        // Same debit often arrives twice (SMS + notification, or a repost with another timestamp).
        if (amount != null && amount > 0L) {
            val dup = reviewItemDao.countMatchingReviewItems(
                amount, parsed.type, timestamp - DEDUPE_WINDOW_MS, timestamp + DEDUPE_WINDOW_MS
            )
            if (dup > 0) return IngestResult.Duplicate
        } else if (reviewItemDao.countExactReviewItems(parsed.rawText, timestamp) > 0) {
            return IngestResult.Duplicate
        }
        val id = reviewItemDao.insertReviewItem(
            ReviewItemEntity(
                rawContent = parsed.rawText, sender = sender, timestamp = timestamp, source = source,
                extractedPartialAmount = amount, extractedPartialPayee = parsed.payee,
                type = parsed.type, bankName = parsed.bankName, accountNumber = parsed.accountNumber,
                balance = parsed.balance, referenceId = parsed.referenceId
            )
        )
        return IngestResult.QueuedForReview(id)
    }

    suspend fun processIncomingEvent(
        parsed: com.expensetracker.offline.engine.parser.ParsedTransaction,
        sender: String,
        timestamp: Long,
        source: TransactionSource
    ): IngestResult {
        if (!parsed.isFinancial) {
            return IngestResult.Failed(parsed.rejectReason ?: "Not a financial message")
        }

        val appPrefs = context?.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        val skipReview = appPrefs?.getBoolean("key_skip_review_queue", false) ?: false
        val fallbackPayee = appPrefs?.getString("key_fallback_payee", "UPI Payment")?.ifBlank { "UPI Payment" } ?: "UPI Payment"
        val reviewThreshold = REVIEW_THRESHOLD_PAISE

        val result = database.withTransaction {
            val amount = parsed.amount
            val type = parsed.type
            // Unreadable financial messages are ALWAYS kept (review queue), whatever the skip setting.
            if (amount == null || amount <= 0L || type == null) {
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }

            val existingMatch = transactionDao.findMatchingTransaction(
                amount = amount.toDouble(), type = type,
                startTime = timestamp - DEDUPE_WINDOW_MS, endTime = timestamp + DEDUPE_WINDOW_MS,
                targetTime = timestamp, bankName = parsed.bankName, accountNumber = parsed.accountNumber
            )

            if (existingMatch != null) {
                val bothRefs = existingMatch.referenceId != null && parsed.referenceId != null
                val sameRef = bothRefs && existingMatch.referenceId == parsed.referenceId

                when {
                    // Different non-null refs => genuinely different transactions. Fall through to insert.
                    bothRefs && !sameRef -> Unit
                    // Same ref, or this exact message text is already stored (carrier duplicate,
                    // repeat of an already-merged row) => duplicate.
                    sameRef || existingMatch.rawContent.contains(parsed.rawText) ->
                        return@withTransaction IngestResult.Duplicate
                    // SMS <-> notification for the same payment: merge, but only into a row that
                    // came from the OTHER channel (never into MANUAL or already-MERGED rows).
                    (existingMatch.source == TransactionSource.SMS && source == TransactionSource.NOTIFICATION) ||
                    (existingMatch.source == TransactionSource.NOTIFICATION && source == TransactionSource.SMS) -> {
                        val bestPayee = if (source == TransactionSource.NOTIFICATION && !parsed.payee.isNullOrBlank()) {
                            parsed.payee
                        } else if (existingMatch.source == TransactionSource.NOTIFICATION && existingMatch.payee.isNotBlank() && existingMatch.payee != "Unknown") {
                            existingMatch.payee
                        } else {
                            parsed.payee ?: existingMatch.payee
                        }
                        val mergedTxn = existingMatch.copy(
                            payee = bestPayee,
                            source = TransactionSource.MERGED,
                            referenceId = parsed.referenceId ?: existingMatch.referenceId,
                            balance = parsed.balance ?: existingMatch.balance,
                            bankName = parsed.bankName ?: existingMatch.bankName,
                            accountNumber = parsed.accountNumber ?: existingMatch.accountNumber,
                            rawContent = "${existingMatch.rawContent} | [MERGED]: ${parsed.rawText}".take(600)
                        )
                        transactionDao.updateTransaction(mergedTxn)
                        return@withTransaction IngestResult.Merged(mergedTxn.id)
                    }
                    // Same channel, different text, no refs: two real payments of the same amount
                    // (e.g. two Rs 20 chai). Insert both.
                    else -> Unit
                }
            }

            val isHighValue = type == TransactionType.DEBIT && amount > reviewThreshold
            val isMissingPayee = parsed.payee.isNullOrBlank() || parsed.payee.equals("Unknown", ignoreCase = true) || parsed.payee.matches(Regex("""^\d{8,12}.*"""))

            if (!skipReview && (isHighValue || isMissingPayee)) {
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }

            val resolvedPayee = if (parsed.payee.isNullOrBlank() || parsed.payee.equals("Unknown", ignoreCase = true)) fallbackPayee else parsed.payee
            val aliasedPayee = appPrefs?.getString("merchant_alias_${resolvedPayee.trim().lowercase()}", null) ?: resolvedPayee
            val category = CategoryClassifier.classify(aliasedPayee, parsed.rawText, categoryRuleDao)

            val id = transactionDao.insertTransaction(
                TransactionEntity(
                    amount = amount, payee = aliasedPayee, timestamp = timestamp, type = type,
                    source = source, referenceId = parsed.referenceId, rawContent = parsed.rawText,
                    category = category, balance = parsed.balance, bankName = parsed.bankName,
                    accountNumber = parsed.accountNumber
                )
            )
            IngestResult.Inserted(id)
        }
        if (result is IngestResult.Inserted || result is IngestResult.Merged) refreshWidget()
        return result
    }

    companion object {
        /** Debits above Rs 1,000 (amounts are stored in PAISE) go to the review queue. */
        const val REVIEW_THRESHOLD_PAISE = 100_000L
        private const val DEDUPE_WINDOW_MS = 300_000L

        private fun getCurrentMonthStart(): Long {
            return Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun getCurrentMonthEnd(): Long {
            return Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, 1)
            }.timeInMillis
        }
    }
}