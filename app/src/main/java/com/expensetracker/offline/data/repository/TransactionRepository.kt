package com.expensetracker.offline.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.local.entity.CategoryRuleEntity
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.ReviewStatus
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.categorizer.NaiveBayesCategorizer
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import com.expensetracker.offline.widget.ExpenseWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class TransactionRepository(
    private val database: AppDatabase,
    private val context: Context? = null
) {
    private val transactionDao = database.transactionDao()
    private val reviewItemDao = database.reviewItemDao()
    private val categoryRuleDao = database.categoryRuleDao()
    private val splitDebtDao = database.splitDebtDao()
    private val settingsRepo = context?.let { SettingsRepository(it) }

    val allTransactionsWithDebts: Flow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> = transactionDao.getAllTransactionsWithDebtsFlow()
    val pendingReviewItems: Flow<List<ReviewItemEntity>> = reviewItemDao.getPendingReviewItemsFlow()
    val pendingReviewCount: Flow<Int> = reviewItemDao.getPendingReviewCountFlow()
    val latestBalance: Flow<Long?> = transactionDao.getLatestBalanceFlow()
    val totalMoneyOwed: Flow<Long?> = transactionDao.getTotalMoneyOwedToYouFlow()
    val totalMoneyYouOwe: Flow<Long?> = transactionDao.getTotalMoneyYouOweFlow()

    val unsettledSplitsWithDebts: Flow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> =
        splitDebtDao.getUnsettledTransactionsWithDebtsFlow()

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

    suspend fun saveMultiSplit(transactionId: Long, debts: List<SplitDebtEntity>, paidBy: String = "You") {
        database.withTransaction {
            transactionDao.updatePaidBy(transactionId, paidBy.trim().ifBlank { "You" })
            val existing = splitDebtDao.getDebtsForTransaction(transactionId)
            val existingMap = existing.associateBy { it.id }
            val keptIds = mutableSetOf<Long>()

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
                transactionDao.unlinkRepaymentsForDebts(removedIds)
                splitDebtDao.deleteDebtsByIds(removedIds)
            }
            if (toUpsert.isNotEmpty()) splitDebtDao.upsertDebts(toUpsert)

            splitDebtDao.getDebtsForTransaction(transactionId).forEach { reconcileDebt(it.id) }
        }
        refreshWidget()
    }

    suspend fun resetSplit(transactionId: Long) {
        database.withTransaction {
            transactionDao.updatePaidBy(transactionId, "You")
            val ids = splitDebtDao.getDebtsForTransaction(transactionId).map { it.id }
            if (ids.isNotEmpty()) {
                transactionDao.unlinkRepaymentsForDebts(ids)
                splitDebtDao.deleteDebtsForTransaction(transactionId)
            }
        }
        refreshWidget()
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
            if (creditTx == null || creditTx.type != TransactionType.CREDIT || creditTx.linkedDebtId != null) {
                return@withTransaction
            }

            transactionDao.linkRepayment(creditId, debtId)
            reconcileDebt(debtId)
        }
        refreshWidget()
    }

    suspend fun logCashRepayment(debtId: Long, amount: Long, timestamp: Long) {
        database.withTransaction {
            val debt = splitDebtDao.getDebtById(debtId) ?: return@withTransaction
            val parent = transactionDao.getTransactionById(debt.transactionId)
            val isYouOwe = parent?.paidBy != null && !parent.paidBy.equals("You", ignoreCase = true) &&
                    debt.debtorName.equals("You", ignoreCase = true)
            val repaid = splitDebtDao.getRepaidAmountForDebt(debtId)
            val outstanding = (debt.amountOwed - repaid).coerceAtLeast(0L)

            val capped = minOf(amount, outstanding)
            if (capped <= 0L) return@withTransaction

            // When settling a debt YOU owe to a friend, log a manual settlement entry or mark settled directly
            if (isYouOwe && capped >= outstanding) {
                splitDebtDao.markDebtSettled(debtId, timestamp)
            } else {
                transactionDao.insertTransaction(
                    TransactionEntity(
                        amount = capped,
                        payee = if (isYouOwe) (parent?.paidBy ?: debt.debtorName) else debt.debtorName,
                        timestamp = timestamp,
                        type = TransactionType.CREDIT,
                        source = TransactionSource.MANUAL,
                        referenceId = null,
                        rawContent = if (isYouOwe) "Manual debt settlement paid" else "Manual cash repayment",
                        category = "Split Settlement",
                        itemsSummary = if (isYouOwe) "Paid back ${parent?.paidBy ?: ""}".trim() else "Cash repayment",
                        linkedDebtId = debtId,
                        excludeFromSpend = true
                    )
                )
                reconcileDebt(debtId)
            }
        }
        refreshWidget()
    }

    suspend fun unlinkRepayment(creditId: Long) {
        database.withTransaction {
            val credit = transactionDao.getTransactionById(creditId) ?: return@withTransaction
            val debtId = credit.linkedDebtId ?: return@withTransaction

            transactionDao.unlinkRepayment(creditId)
            reconcileDebt(debtId)
        }
        refreshWidget()
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
        refreshWidget()
    }

    suspend fun updatePayeeAndCategoryBulk(oldPayee: String, newPayee: String, newCategory: String) {
        database.transactionDao().updatePayeeAndCategoryBulk(oldPayee, newPayee, newCategory)
        settingsRepo?.setMerchantAlias(oldPayee, newPayee)
        refreshWidget()
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
        settingsRepo?.setMerchantAlias(trimmedOld, trimmedNew)
        refreshWidget()
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
            NaiveBayesCategorizer.invalidate()
            CategoryClassifier.clearCache()
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
        transactionDao.clearAllTransactions()
        context?.let { ctx ->
            com.expensetracker.offline.util.BalanceCache.clearAll(ctx)
        }
        refreshWidget()
    }

    suspend fun deleteTransactionsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        database.withTransaction {
            val debtIds = ids.mapNotNull { transactionDao.getTransactionById(it)?.linkedDebtId }.toSet()
            transactionDao.deleteTransactionsByIds(ids)
            debtIds.forEach { reconcileDebt(it) }
        }
        refreshWidget()
    }

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

    suspend fun approveReviewItem(transaction: TransactionEntity, reviewItemId: Long): Long {
        val id = database.withTransaction {
            val newId = transactionDao.insertTransaction(transaction)
            reviewItemDao.updateStatus(reviewItemId, ReviewStatus.RESOLVED)
            newId
        }
        refreshWidget()
        return id
    }

    suspend fun bulkApproveReviewItems(approvals: List<Pair<TransactionEntity, Long>>) {
        if (approvals.isEmpty()) return
        database.withTransaction {
            approvals.forEach { (transaction, reviewItemId) ->
                transactionDao.insertTransaction(transaction)
                reviewItemDao.updateStatus(reviewItemId, ReviewStatus.RESOLVED)
            }
        }
        refreshWidget()
    }

    suspend fun discardReviewItem(itemId: Long) {
        reviewItemDao.updateStatus(itemId, ReviewStatus.DISCARDED)
    }

    suspend fun reassignCategory(oldCategory: String, newCategory: String) {
        database.withTransaction {
            transactionDao.reassignCategory(oldCategory, newCategory)
            categoryRuleDao.reassignRuleCategory(oldCategory, newCategory)
        }
        refreshWidget()
    }

    private data class TxnDedupeKey(
        val epochSecond: Long,
        val amount: Long,
        val type: TransactionType,
        val payee: String
    )

    suspend fun insertTransactionsBulk(transactions: List<TransactionEntity>): Int {
        if (transactions.isEmpty()) return 0

        var totalInserted = 0
        val sorted = transactions.sortedBy { it.timestamp }
        for (chunk in sorted.chunked(500)) {
            val minTime = chunk.first().timestamp - 1000L
            val maxTime = chunk.last().timestamp + 1000L

            val chunkInserted = database.withTransaction {
                val existing = transactionDao.getTransactionsInRangeSync(minTime, maxTime)
                val existingKeys = HashSet<TxnDedupeKey>(existing.size * 2)
                for (e in existing) {
                    existingKeys.add(
                        TxnDedupeKey(
                            epochSecond = Math.floorDiv(e.timestamp, 1000L),
                            amount = e.amount,
                            type = e.type,
                            payee = e.payee
                        )
                    )
                }

                val fresh = chunk.filter { t ->
                    val key = TxnDedupeKey(
                        epochSecond = Math.floorDiv(t.timestamp, 1000L),
                        amount = t.amount,
                        type = t.type,
                        payee = t.payee
                    )
                    !existingKeys.contains(key)
                }
                fresh.forEach { transactionDao.insertTransaction(it) }
                fresh.size
            }
            totalInserted += chunkInserted
        }
        if (totalInserted > 0) refreshWidget()
        return totalInserted
    }

    suspend fun renameAllPayees(oldPayee: String, newPayee: String) {
        transactionDao.renameAllPayees(oldPayee, newPayee)
        refreshWidget()
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
        if (amount != null && amount > 0L) {
            val dup = reviewItemDao.countMatchingReviewItems(
                amount, parsed.type, timestamp - DEDUPE_WINDOW_MS, timestamp + DEDUPE_WINDOW_MS,
                parsed.referenceId, parsed.bankName, parsed.accountNumber
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

        val skipReview = settingsRepo?.skipReviewQueue ?: false
        val fallbackPayee = settingsRepo?.fallbackPayee ?: "UPI Payment"
        val reviewThreshold = REVIEW_THRESHOLD_PAISE

        val result = database.withTransaction {
            val amount = parsed.amount
            val type = parsed.type
            if (amount == null || amount <= 0L || type == null) {
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }

            val existingMatch = transactionDao.findMatchingTransaction(
                amount = amount, type = type,
                startTime = timestamp - DEDUPE_WINDOW_MS, endTime = timestamp + DEDUPE_WINDOW_MS,
                targetTime = timestamp, bankName = parsed.bankName, accountNumber = parsed.accountNumber
            )

            if (existingMatch != null) {
                val bothRefs = existingMatch.referenceId != null && parsed.referenceId != null
                val sameRef = bothRefs && existingMatch.referenceId == parsed.referenceId

                when {
                    bothRefs && !sameRef -> Unit
                    sameRef || existingMatch.rawContent.contains(parsed.rawText) ->
                        return@withTransaction IngestResult.Duplicate
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
                    else -> Unit
                }
            }

            // Detect paired inter-account self-transfers (Debit on Account A + Credit of same amount on Account B)
            val oppositeType = if (type == TransactionType.DEBIT) TransactionType.CREDIT else TransactionType.DEBIT
            val oppositeLeg = if (parsed.bankName != null) {
                transactionDao.findOppositeTransferLeg(
                    amount = amount,
                    oppositeType = oppositeType,
                    startTime = timestamp - DEDUPE_WINDOW_MS,
                    endTime = timestamp + DEDUPE_WINDOW_MS,
                    targetTime = timestamp,
                    currentBank = parsed.bankName,
                    currentAccount = parsed.accountNumber
                )
            } else null

            if (oppositeLeg != null && !oppositeLeg.excludeFromSpend) {
                transactionDao.updateTransaction(
                    oppositeLeg.copy(
                        excludeFromSpend = true,
                        category = if (oppositeLeg.category == "Uncategorized") "Transfer" else oppositeLeg.category,
                        note = oppositeLeg.note ?: "Self-transfer (${parsed.bankName})"
                    )
                )
            }

            val isAutoTransfer = parsed.isTransfer || oppositeLeg != null
            val isHighValue = type == TransactionType.DEBIT && amount > reviewThreshold && !isAutoTransfer
            val isMissingPayee = (parsed.payee.isNullOrBlank() || parsed.payee.equals("Unknown", ignoreCase = true) || parsed.payee.matches(NUMERIC_PAYEE_REGEX)) && !isAutoTransfer

            if (!skipReview && (isHighValue || isMissingPayee)) {
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }

            val resolvedPayee = when {
                !parsed.payee.isNullOrBlank() && !parsed.payee.equals("Unknown", ignoreCase = true) -> parsed.payee
                oppositeLeg != null -> "Transfer (${oppositeLeg.bankName ?: "Own A/c"})"
                parsed.isTransfer -> "Self Transfer"
                else -> fallbackPayee
            }
            val aliasedPayee = settingsRepo?.getMerchantAlias(resolvedPayee) ?: resolvedPayee
            val category = if (isAutoTransfer) "Transfer" else CategoryClassifier.classify(aliasedPayee, parsed.rawText, categoryRuleDao)

            val id = transactionDao.insertTransaction(
                TransactionEntity(
                    amount = amount, payee = aliasedPayee, timestamp = timestamp, type = type,
                    source = source, referenceId = parsed.referenceId, rawContent = parsed.rawText,
                    category = category, note = parsed.note ?: if (oppositeLeg != null) "Self-transfer (${oppositeLeg.bankName})" else null,
                    balance = parsed.balance, bankName = parsed.bankName,
                    accountNumber = parsed.accountNumber, excludeFromSpend = parsed.excludeFromSpend || isAutoTransfer
                )
            )
            IngestResult.Inserted(id)
        }
        if (result is IngestResult.Inserted || result is IngestResult.Merged) refreshWidget()
        return result
    }

    companion object {
        const val REVIEW_THRESHOLD_PAISE = 100_000L
        private const val DEDUPE_WINDOW_MS = 300_000L
        private val NUMERIC_PAYEE_REGEX = Regex("""^\d{8,12}.*""")
    }
}