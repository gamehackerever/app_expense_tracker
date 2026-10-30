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
import com.expensetracker.offline.engine.ml.SmartExpenseEngine
import com.expensetracker.offline.engine.ml.SmsPatternLearner
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import com.expensetracker.offline.ui.components.deleteReceiptFile
import com.expensetracker.offline.util.BalanceCache
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.CardPaymentReconciler
import com.expensetracker.offline.widget.ExpenseWidget
import kotlinx.coroutines.Dispatchers
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File

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

    fun getTotalCreditCardDebtFlow(): Flow<Long> = database.transactionDao().getTotalCreditCardDebtFlow()

    private fun refreshWidget() {
        context?.let { runCatching { ExpenseWidget.updateAll(it) } }
    }

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
        if (transaction.rawContent.isNotBlank() && transaction.rawContent != "Manual Entry") {
            SmsPatternLearner.learnApprovedReview(
                sender = transaction.bankName,
                rawContent = transaction.rawContent,
                resolvedPayee = transaction.payee,
                resolvedCategory = transaction.category,
                resolvedType = transaction.type,
                resolvedBankName = transaction.bankName
            )
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
            val effectiveTxn = if (transaction.balance == null && transaction.bankName != null) {
                val prevBalance = transactionDao.getLatestBalanceForAccountSync(transaction.bankName, transaction.accountNumber)
                if (prevBalance != null) {
                    val delta = if (transaction.type == TransactionType.DEBIT) -transaction.amount else transaction.amount
                    val nextBal = (prevBalance + delta).coerceAtLeast(0L)
                    context?.let { ctx ->
                        BalanceCache.updateIfNewer(
                            BalanceCache.prefs(ctx),
                            transaction.bankName,
                            transaction.accountNumber,
                            nextBal,
                            maxOf(transaction.timestamp, System.currentTimeMillis())
                        )
                    }
                    transaction.copy(balance = nextBal)
                } else transaction
            } else transaction

            val newId = transactionDao.insertTransaction(effectiveTxn)
            effectiveTxn.linkedDebtId?.let { reconcileDebt(it) }
            newId
        }
        refreshWidget()
        return id
    }

    suspend fun setManualAccountBalance(bankName: String, accountNumber: String?, newBalancePaise: Long) {
        val cleanBank = bankName.trim().ifBlank { "Cash Wallet" }
        val cleanAcc = accountNumber?.trim()?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val latest = transactionDao.getRecentTransactions(50).firstOrNull {
                it.bankName.equals(cleanBank, ignoreCase = true) &&
                        (it.accountNumber ?: "") == (cleanAcc ?: "")
            }
            if (latest != null && now - latest.timestamp < 60_000L && latest.category == "Transfer" && latest.amount == 0L) {
                transactionDao.updateTransaction(latest.copy(balance = newBalancePaise, timestamp = now))
            } else {
                transactionDao.insertTransaction(
                    TransactionEntity(
                        amount = 0L,
                        payee = "Balance Sync ($cleanBank)",
                        timestamp = now,
                        type = TransactionType.CREDIT,
                        source = TransactionSource.MANUAL,
                        referenceId = null,
                        rawContent = "Manual balance update",
                        category = "Transfer",
                        note = "Manual balance checkpoint",
                        balance = newBalancePaise,
                        bankName = cleanBank,
                        accountNumber = cleanAcc,
                        excludeFromSpend = true
                    )
                )
            }
        }
        context?.let { ctx ->
            BalanceCache.updateIfNewer(BalanceCache.prefs(ctx), cleanBank, cleanAcc, newBalancePaise, now)
        }
        refreshWidget()
    }

    /**
     * International card alerts only give the foreign amount, so the parser books an estimate. When this card's
     * previous available limit is known, "previous limit - new limit" is the exact INR the bank charged (including
     * its markup and GST), so use that instead, but only if it is close to the estimate. A payment or an
     * unseen spend in between would otherwise produce a nonsense difference.
     */
    private fun resolveForeignAmount(parsed: com.expensetracker.offline.engine.parser.ParsedTransaction, timestamp: Long): Long? {
        val estimate = parsed.amount ?: return null
        if (!parsed.amountIsApproximate) return estimate
        val bank = parsed.bankName
        val newLimit = parsed.availableLimit
        if (parsed.accountType != "CREDIT_CARD" || bank == null || newLimit == null) return estimate
        val previous = settingsRepo?.getCardAvailableLimit(bank, parsed.accountNumber) ?: return estimate
        if (previous.second >= timestamp) return estimate
        val delta = previous.first - newLimit
        return if (delta >= estimate * 7 / 10 && delta <= estimate * 15 / 10) delta else estimate
    }

    /** Tags [ids] to a custom budget (and keeps them out of the monthly total if that budget says so). */
    suspend fun tagToBudget(ids: List<Long>, budgetId: String, excludeFromMonthly: Boolean) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { transactionDao.tagToBudget(it, budgetId, if (excludeFromMonthly) 1 else 0) }
        refreshWidget()
    }

    /** Removes the budget tag from [ids]; [restoreCount] puts them back into the monthly total. */
    suspend fun untagFromBudget(ids: List<Long>, restoreCount: Boolean) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { transactionDao.untagFromBudget(it, if (restoreCount) 1 else 0) }
        refreshWidget()
    }

    suspend fun budgetIdsOf(ids: List<Long>): List<String> =
        ids.chunked(500).flatMap { transactionDao.budgetIdsOf(it) }.distinct()

    suspend fun clearBudget(budgetId: String, restoreCount: Boolean) {
        transactionDao.clearBudget(budgetId, if (restoreCount) 1 else 0)
        refreshWidget()
    }

    suspend fun setBudgetExclusion(budgetId: String, exclude: Boolean) {
        transactionDao.setBudgetExclusion(budgetId, if (exclude) 1 else 0)
        refreshWidget()
    }

    /** Categorises all uncategorised spends to [payee] at once and remembers it for future payments. */
    suspend fun categorizePayee(payee: String, category: String): Int {
        if (category.isBlank() || category.equals("Uncategorized", ignoreCase = true)) return 0
        val changed = transactionDao.categorizeUncategorizedByPayee(payee, category)
        learnCategoryRule(payee, category)
        refreshWidget()
        return changed
    }

    suspend fun learnCategoryRule(payee: String, category: String) {
        if (CategoryClassifier.isGenericPayee(payee)) return
        val pattern = payee.trim().lowercase()
            .replace("%", "\\%")
            .replace("_", "\\_")
        if (pattern.length >= 3 &&
            !CategoryClassifier.isGenericPayee(pattern) &&
            !category.equals("Uncategorized", ignoreCase = true) &&
            !category.equals("Transfer", ignoreCase = true) &&
            category != "Split Settlement"
        ) {
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

    /**
     * Self-heals poisoned categories ("Travel" stamped on generic/credit rows), recovers missing
     * payees from raw SMS text, removes generic rules from `category_rules`, and syncs clean rules.
     */
    suspend fun syncCategoryRulesFromHistory() {
        runCatching {
            context?.let { SmsPatternLearner.initialize(it) }
            SmsPatternLearner.sanitizePoisonedPatterns()
            categoryRuleDao.deleteGenericOrShortRules(CategoryClassifier.GENERIC_FALLBACK_PAYEES.toList())

            // Clean any poisoned "Travel" rules in category_rules where the merchant pattern is not actually Travel
            val allRules = categoryRuleDao.getAllRules()
            for (rule in allRules) {
                val pat = rule.merchantPattern.trim()
                if (CategoryClassifier.isGenericPayee(pat) || pat.length < 3) {
                    categoryRuleDao.deleteRuleByPattern(rule.merchantPattern)
                } else if (rule.category.equals("Travel", ignoreCase = true)) {
                    val syncCat = CategoryClassifier.classifySync(pat, pat)
                    if (syncCat != "Travel") {
                        categoryRuleDao.deleteRuleByPattern(rule.merchantPattern)
                    }
                }
            }
            CategoryClassifier.clearCache()

            // The heavy repair pass re-parses stored SMS, so it must not run on every launch. It runs after an app
            // update (a full pass, so parser/categoriser improvements reach old rows) and then at most every 20 min,
            // looking only at recent or still-uncategorised rows.
            val healNow = System.currentTimeMillis()
            val lastHeal = settingsRepo?.lastHealAt ?: 0L
            val fullPass = (settingsRepo?.healVersion ?: -1) != HEAL_VERSION
            val healDue = settingsRepo == null || fullPass || healNow - lastHeal > HEAL_INTERVAL_MS
            val healCutoff = lastHeal - 24L * 3600_000L
            if (healDue) {
            transactionDao.healFalseCreditCardAccounts()
            transactionDao.normalizeAccountNumbersToLast4()
            reviewItemDao.healFalseCreditCardReviewItems()
            CardPaymentReconciler.reconcileAll(transactionDao)

            val recentAll = transactionDao.getRecentTransactions(5000)
            val byBank = recentAll.filter { !it.bankName.isNullOrBlank() }.groupBy { it.bankName!! }
            for ((bank, txns) in byBank) {
                val distinctNonNullAccs = txns.mapNotNull { it.accountNumber?.trim()?.takeIf { a -> a.isNotEmpty() } }
                if (distinctNonNullAccs.isNotEmpty()) {
                    val primaryAcc = distinctNonNullAccs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    if (primaryAcc != null && (bank.equals("South Indian Bank", ignoreCase = true) || distinctNonNullAccs.distinct().size == 1)) {
                        transactionDao.unifyBankAccountNumber(bank, primaryAcc)
                    }
                }
            }

            // Self-heal existing transactions in DB:
            // 1. Re-parse generic payees ("Bank Transfer", "UPI Payment") and fix poisoned "Travel" on generic/credit rows
            // 2. Deduplicate generic SMS rows that duplicate an existing named transaction of the same amount within 12h
            // 3. Auto-resolve pending review_items that already exist in transactions within 12h
            var anyHealed = false
            val duplicateWindowMs = 12L * 3600_000L
            database.withTransaction {
                val namedTxns = recentAll.filter { !CategoryClassifier.isGenericPayee(it.payee) }
                // Amount buckets (per rupee) so each generic row is compared with a handful of rows, not all of them.
                val namedBuckets = namedTxns.groupBy { it.type to (it.amount / 100L) }
                val genericSmsIdsToDelete = mutableListOf<Long>()

                for (txn in recentAll) {
                    val currentPayee = txn.payee.trim()
                    val isCurrentGeneric = CategoryClassifier.isGenericPayee(currentPayee)
                    if (!fullPass && !isCurrentGeneric && txn.timestamp < healCutoff &&
                        !txn.category.equals("Uncategorized", ignoreCase = true)
                    ) continue

                    // Check if a generic SMS row ("UPI Payment" / "Bank Transfer") is a duplicate of a named transaction
                    if (isCurrentGeneric && txn.source == TransactionSource.SMS) {
                        val bucket = txn.amount / 100L
                        val matchingNamed = (bucket - 1..bucket + 1).asSequence()
                            .flatMap { namedBuckets[txn.type to it].orEmpty().asSequence() }
                            .firstOrNull { named ->
                            named.id != txn.id &&
                                named.type == txn.type &&
                                (named.amount == txn.amount || kotlin.math.abs(named.amount - txn.amount) <= 99L) &&
                                kotlin.math.abs(named.timestamp - txn.timestamp) <= duplicateWindowMs
                        }
                        if (matchingNamed != null && splitDebtDao.getDebtsForTransaction(txn.id).isEmpty()) {
                            if (matchingNamed.bankName == null && txn.bankName != null) {
                                transactionDao.updateBankDetails(matchingNamed.id, txn.bankName, txn.accountNumber)
                            }
                            if ((matchingNamed.balance == null && txn.balance != null) ||
                                (matchingNamed.referenceId == null && txn.referenceId != null)
                            ) {
                                transactionDao.updateTransaction(
                                    matchingNamed.copy(
                                        balance = matchingNamed.balance ?: txn.balance,
                                        referenceId = matchingNamed.referenceId ?: txn.referenceId,
                                        bankName = matchingNamed.bankName ?: txn.bankName,
                                        accountNumber = matchingNamed.accountNumber ?: txn.accountNumber
                                    )
                                )
                            }
                            genericSmsIdsToDelete.add(txn.id)
                            anyHealed = true
                            continue
                        }
                    }

                    if (txn.source == TransactionSource.MANUAL || txn.rawContent.isBlank() || txn.rawContent == "Manual Entry") {
                        // Still upgrade "Uncategorized" on named manual/restored entries if a known merchant keyword matches
                        if (!isCurrentGeneric && txn.type == TransactionType.DEBIT && txn.category.equals("Uncategorized", ignoreCase = true)) {
                            val trueCat = CategoryClassifier.classifySync(currentPayee, currentPayee)
                            if (trueCat != "Uncategorized") {
                                transactionDao.updateTransaction(txn.copy(category = trueCat))
                                anyHealed = true
                            }
                        }
                        continue
                    }

                    val parsed = com.expensetracker.offline.engine.parser.FinancialParser.parse(
                        rawText = txn.rawContent,
                        title = txn.bankName,
                        source = txn.source
                    )
                    val healedPayee = when {
                        !parsed.payee.isNullOrBlank() && !CategoryClassifier.isGenericPayee(parsed.payee) &&
                            (isCurrentGeneric || currentPayee.equals("Google I", ignoreCase = true)) -> parsed.payee
                        currentPayee.equals("Google I", ignoreCase = true) -> "Google India"
                        currentPayee.equals("Bank Transfer", ignoreCase = true) &&
                            txn.rawContent.contains("upi", ignoreCase = true) &&
                            txn.type == TransactionType.DEBIT -> "UPI Payment"
                        else -> currentPayee
                    }

                    val isStillGeneric = CategoryClassifier.isGenericPayee(healedPayee)
                    val currentCat = txn.category.trim()
                    val trueCat = CategoryClassifier.classifySync(healedPayee, txn.rawContent)

                    val healedCat = when {
                        currentCat.equals("Transfer", ignoreCase = true) || currentCat == "Split Settlement" -> currentCat
                        parsed.isTransfer -> "Transfer"
                        txn.type == TransactionType.CREDIT -> "Uncategorized"
                        currentCat.equals("Uncategorized", ignoreCase = true) && trueCat != "Uncategorized" -> {
                            trueCat
                        }
                        else -> currentCat // Always preserve existing user/restored categories
                    }

                    if (healedPayee != txn.payee || healedCat != txn.category) {
                        transactionDao.updateTransaction(
                            txn.copy(
                                payee = healedPayee,
                                category = healedCat
                            )
                        )
                        anyHealed = true
                    }
                }

                if (genericSmsIdsToDelete.isNotEmpty()) {
                    transactionDao.deleteTransactionsByIds(genericSmsIdsToDelete)
                }

                // Also clean up non-financial/missing-amount review items and resolve duplicates within 12h
                val remainingTxns = recentAll.filter { it.id !in genericSmsIdsToDelete }
                val pendingReviews = reviewItemDao.getPendingReviewItemsSync()
                for (rev in pendingReviews) {
                    val isSilentDeduction = rev.rawContent.startsWith("Silent Deduction", ignoreCase = true)
                    val parsedRev = com.expensetracker.offline.engine.parser.FinancialParser.parse(
                        rawText = rev.rawContent,
                        title = rev.sender,
                        source = rev.source
                    )
                    val revAmt = rev.extractedPartialAmount ?: parsedRev.amount
                    if (!isSilentDeduction && (!parsedRev.isFinancial || revAmt == null || revAmt <= 0L)) {
                        reviewItemDao.updateStatus(rev.id, ReviewStatus.DISCARDED)
                        anyHealed = true
                        continue
                    }
                    if (revAmt == null || revAmt <= 0L) continue
                    val revType = rev.type ?: parsedRev.type ?: continue
                    val alreadyLogged = remainingTxns.any { t ->
                        t.type == revType &&
                            (t.amount == revAmt || kotlin.math.abs(t.amount - revAmt) <= 99L) &&
                            kotlin.math.abs(t.timestamp - rev.timestamp) <= duplicateWindowMs &&
                            !(t.referenceId != null && rev.referenceId != null && t.referenceId != rev.referenceId)
                    }
                    if (alreadyLogged) {
                        reviewItemDao.updateStatus(rev.id, ReviewStatus.RESOLVED)
                        anyHealed = true
                    }
                }
            }
            if (anyHealed) {
                CategoryClassifier.clearCache()
                refreshWidget()
            }
            settingsRepo?.let { it.lastHealAt = healNow; it.healVersion = HEAL_VERSION }
            } // healDue
        }

        val recent = transactionDao.getRecentTransactions(500)
        if (recent.isEmpty()) return
        val existingRules = categoryRuleDao.getAllRules().associateBy { it.merchantPattern.lowercase() }
        val grouped = recent
            .filter {
                it.type == TransactionType.DEBIT &&
                        it.payee.trim().length >= 3 &&
                        !CategoryClassifier.isGenericPayee(it.payee) &&
                        it.category.isNotBlank() &&
                        !it.category.equals("Uncategorized", ignoreCase = true) &&
                        !it.category.equals("Transfer", ignoreCase = true) &&
                        it.category != "Split Settlement"
            }
            .groupBy {
                it.payee.trim().lowercase()
                    .replace("%", "\\%")
                    .replace("_", "\\_")
            }

        var anyUpserted = false
        for ((pattern, list) in grouped) {
            if (pattern.length < 3 || CategoryClassifier.isGenericPayee(pattern)) continue
            val dominantCat = list.groupingBy { it.category.trim() }.eachCount().maxByOrNull { it.value }?.key ?: continue
            val existing = existingRules[pattern]
            if (existing == null || existing.usageCount < list.size) {
                categoryRuleDao.upsertRule(
                    CategoryRuleEntity(
                        merchantPattern = pattern,
                        category = existing?.category ?: dominantCat,
                        usageCount = maxOf(existing?.usageCount ?: 0, list.size),
                        lastUpdated = list.maxOf { it.timestamp }
                    )
                )
                anyUpserted = true
            }
        }
        if (anyUpserted) {
            NaiveBayesCategorizer.invalidate()
            CategoryClassifier.clearCache()
        }
    }

    suspend fun autoPromoteParseableReviewItems(): Int {
        val pending = reviewItemDao.getPendingReviewItemsSync()
        if (pending.isEmpty()) return 0
        val fallback = settingsRepo?.scanFallbackPayee ?: "UPI Payment"
        var promotedCount = 0

        for (item in pending) {
            val parsed = com.expensetracker.offline.engine.parser.FinancialParser.parse(
                rawText = item.rawContent,
                title = item.sender,
                source = item.source
            )
            // If user learned to block this template, discard it from pending reviews
            if (!parsed.isFinancial) {
                val learnedEval = SmsPatternLearner.evaluate(item.sender, item.rawContent)
                if (learnedEval.isBlockedNonTransaction) {
                    reviewItemDao.updateStatus(item.id, ReviewStatus.DISCARDED)
                    continue
                }
            }
            val amt = item.extractedPartialAmount ?: parsed.amount ?: 0L
            val type = item.type ?: parsed.type
            if (amt <= 0L || type == null) continue

            val bank = (item.bankName ?: parsed.bankName)?.let { b ->
                if (b.endsWith(" CC", ignoreCase = true) && !parsed.accountType.equals("CREDIT_CARD", ignoreCase = true)) {
                    b.removeSuffix(" CC").removeSuffix(" cc").trim()
                } else b
            }
            val account = item.accountNumber ?: parsed.accountNumber
            val balance = item.balance ?: parsed.balance

            val existing = transactionDao.findMatchingTransaction(
                amount = amt,
                type = type,
                startTime = item.timestamp - DEDUPE_WINDOW_MS,
                endTime = item.timestamp + DEDUPE_WINDOW_MS,
                targetTime = item.timestamp,
                bankName = bank,
                accountNumber = account
            )
            if (existing != null) {
                reviewItemDao.updateStatus(item.id, ReviewStatus.RESOLVED)
                continue
            }

            val rawPayee = item.extractedPartialPayee ?: parsed.payee
            val isMissing = rawPayee.isNullOrBlank() ||
                rawPayee.equals("Unknown", ignoreCase = true) ||
                rawPayee.matches(NUMERIC_PAYEE_REGEX)

            val resolvedPayee = when {
                !isMissing && rawPayee != null -> rawPayee
                parsed.isTransfer -> "Self Transfer"
                else -> com.expensetracker.offline.engine.parser.FinancialParser.inferFallbackPayee(
                    item.rawContent,
                    type,
                    fallback
                )
            }
            val aliasedPayee = settingsRepo?.getMerchantAlias(resolvedPayee) ?: resolvedPayee
            val category = if (parsed.isTransfer) "Transfer" else classifyCategory(
                payee = aliasedPayee,
                rawContent = item.rawContent,
                amountPaise = amt,
                timestampMillis = item.timestamp,
                type = type
            )

            val newTxn = TransactionEntity(
                amount = amt,
                payee = aliasedPayee,
                timestamp = item.timestamp,
                type = type,
                source = item.source,
                referenceId = item.referenceId ?: parsed.referenceId,
                rawContent = item.rawContent,
                category = category,
                note = parsed.note,
                balance = balance,
                bankName = bank,
                accountNumber = account,
                excludeFromSpend = parsed.excludeFromSpend || parsed.isTransfer,
                accountType = resolveAccountType(bank, account, parsed.accountType)
            )
            approveReviewItem(newTxn, item.id)
            if (balance != null && context != null) {
                BalanceCache.updateIfNewer(BalanceCache.prefs(context), bank, account, balance, item.timestamp)
            }
            promotedCount++
        }
        if (promotedCount > 0) refreshWidget()
        return promotedCount
    }

    suspend fun classifyCategory(
        payee: String,
        rawContent: String,
        amountPaise: Long? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        type: TransactionType = TransactionType.DEBIT
    ): String {
        if (type == TransactionType.CREDIT) {
            return "Uncategorized"
        }
        val primary = CategoryClassifier.classify(
            payee = payee,
            rawContent = rawContent,
            ruleDao = categoryRuleDao
        )
        if (primary != "Uncategorized") return primary
        if (CategoryClassifier.isGenericPayee(payee)) return "Uncategorized"
        val recent = transactionDao.getRecentTransactions(300)
        val rules = categoryRuleDao.getAllRules()
        return SmartExpenseEngine.predictCategoryContextual(
            payee = payee,
            amountPaise = amountPaise,
            timestampMillis = timestampMillis,
            history = recent,
            learnedRules = rules,
            rawContent = rawContent
        )
    }

    fun recurringPaymentsFlow(): Flow<List<RecurringPayment>> =
        transactionDao.getDebitsSinceFlow(System.currentTimeMillis() - RecurringPaymentDetector.LOOKBACK_MS)
            .map { RecurringPaymentDetector.detect(it) }
            .flowOn(Dispatchers.Default)

    suspend fun deleteTransaction(id: Long) = deleteTransactionsByIds(listOf(id))

    suspend fun clearAllTransactions() {
        transactionDao.clearAllTransactions()
        context?.let { ctx ->
            BalanceCache.clearAll(ctx)
            runCatching {
                File(ctx.filesDir, "receipts").listFiles()?.forEach { it.delete() }
            }
        }
        refreshWidget()
    }

    suspend fun deleteTransactionsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val receiptUris = mutableListOf<String>()
        database.withTransaction {
            receiptUris += transactionDao.getReceiptUrisByIds(ids)
            val debtIds = ids.mapNotNull { transactionDao.getTransactionById(it)?.linkedDebtId }.toSet()
            transactionDao.deleteTransactionsByIds(ids)
            debtIds.forEach { reconcileDebt(it) }
        }
        context?.let { ctx ->
            receiptUris.forEach { uri -> runCatching { deleteReceiptFile(ctx, uri) } }
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

    /**
     * Approves a single review item and teaches `SmsPatternLearner` that this SMS/notification
     * template is a valid financial transaction.
     */
    suspend fun approveReviewItem(transaction: TransactionEntity, reviewItemId: Long): Long {
        val reviewItem = reviewItemDao.getReviewItemById(reviewItemId)
        SmsPatternLearner.learnApprovedReview(
            sender = reviewItem?.sender ?: transaction.bankName,
            rawContent = reviewItem?.rawContent ?: transaction.rawContent,
            resolvedPayee = transaction.payee,
            resolvedCategory = transaction.category,
            resolvedType = transaction.type,
            resolvedBankName = transaction.bankName
        )
        if (!reviewItem?.extractedPartialPayee.isNullOrBlank() &&
            !reviewItem!!.extractedPartialPayee.equals(transaction.payee, ignoreCase = true)
        ) {
            settingsRepo?.setMerchantAlias(reviewItem.extractedPartialPayee!!, transaction.payee)
        }

        val id = database.withTransaction {
            val newId = transactionDao.insertTransaction(transaction)
            reviewItemDao.updateStatus(reviewItemId, ReviewStatus.RESOLVED)
            newId
        }
        refreshWidget()
        // A CRED / card-bill debit that waited in the review queue can now be paired with its card credit.
        runCatching {
            CardPaymentReconciler.reconcileAll(transactionDao, transaction.timestamp - CardPaymentReconciler.WINDOW_MS)
        }
        return id
    }

    /**
     * Bulk-approves review items and teaches both `SmsPatternLearner` and `CategoryRuleDao`
     * from every approved transaction.
     */
    suspend fun bulkApproveReviewItems(approvals: List<Pair<TransactionEntity, Long>>) {
        if (approvals.isEmpty()) return
        for ((transaction, reviewItemId) in approvals) {
            val reviewItem = reviewItemDao.getReviewItemById(reviewItemId)
            SmsPatternLearner.learnApprovedReview(
                sender = reviewItem?.sender ?: transaction.bankName,
                rawContent = reviewItem?.rawContent ?: transaction.rawContent,
                resolvedPayee = transaction.payee,
                resolvedCategory = transaction.category,
                resolvedType = transaction.type,
                resolvedBankName = transaction.bankName
            )
            learnCategoryRule(transaction.payee, transaction.category)
        }
        database.withTransaction {
            approvals.forEach { (transaction, reviewItemId) ->
                transactionDao.insertTransaction(transaction)
                reviewItemDao.updateStatus(reviewItemId, ReviewStatus.RESOLVED)
            }
        }
        refreshWidget()
        runCatching {
            val since = approvals.minOf { it.first.timestamp } - CardPaymentReconciler.WINDOW_MS
            CardPaymentReconciler.reconcileAll(transactionDao, since)
        }
    }

    /**
     * Discards a review item and teaches `SmsPatternLearner` that this SMS/notification
     * template is NOT an actual transaction so similar messages won't reappear.
     */
    suspend fun discardReviewItem(itemId: Long) {
        val item = reviewItemDao.getReviewItemById(itemId)
        if (item != null) {
            SmsPatternLearner.learnDiscardedReview(
                sender = item.sender,
                rawContent = item.rawContent
            )
        }
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

    suspend fun insertTransactionsBulk(
        transactions: List<TransactionEntity>,
        splitsByIndex: Map<Int, List<SplitDebtEntity>> = emptyMap()
    ): Int {
        if (transactions.isEmpty()) return 0

        var totalInserted = 0
        val indexed = transactions.mapIndexed { idx, txn -> idx to txn }.sortedBy { it.second.timestamp }
        for (chunk in indexed.chunked(500)) {
            val minTime = chunk.first().second.timestamp - 1000L
            val maxTime = chunk.last().second.timestamp + 1000L

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

                val freshPairs = chunk.filter { (_, t) ->
                    val key = TxnDedupeKey(
                        epochSecond = Math.floorDiv(t.timestamp, 1000L),
                        amount = t.amount,
                        type = t.type,
                        payee = t.payee
                    )
                    existingKeys.add(key)
                }

                if (freshPairs.isNotEmpty()) {
                    val insertedIds = transactionDao.insertTransactions(freshPairs.map { it.second })
                    val allDebts = mutableListOf<SplitDebtEntity>()
                    freshPairs.forEachIndexed { i, (origIdx, _) ->
                        val newTxnId = insertedIds.getOrNull(i) ?: 0L
                        if (newTxnId > 0L) {
                            splitsByIndex[origIdx]?.forEach { debt ->
                                allDebts.add(debt.copy(id = 0L, transactionId = newTxnId))
                            }
                        }
                    }
                    if (allDebts.isNotEmpty()) {
                        splitDebtDao.upsertDebts(allDebts)
                    }
                }
                freshPairs.size
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

    private fun resolveAccountType(bank: String?, account: String?, parsedType: String?): String =
        settingsRepo?.resolveAccountType(bank, account, parsedType)
            ?: if (parsedType.equals("CREDIT_CARD", ignoreCase = true)) "CREDIT_CARD" else "BANK"

    private fun isCompatibleMatch(
        existing: TransactionEntity,
        parsed: com.expensetracker.offline.engine.parser.ParsedTransaction,
        timestamp: Long
    ): Boolean {
        if (existing.referenceId != null && existing.referenceId == parsed.referenceId) return true
        val wildcard = existing.bankName == null || parsed.bankName == null ||
            existing.accountNumber == null || parsed.accountNumber == null
        if (!wildcard) return true
        if (kotlin.math.abs(existing.timestamp - timestamp) > WILDCARD_MATCH_WINDOW_MS) return false
        val a = existing.payee.trim().lowercase()
        val b = parsed.payee?.trim()?.lowercase().orEmpty()
        if (a.isEmpty() || b.isEmpty()) return true
        if (CategoryClassifier.isGenericPayee(existing.payee) || CategoryClassifier.isGenericPayee(parsed.payee ?: "")) return true
        return a.contains(b) || b.contains(a)
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

        // Check active SmsPatternLearner evaluation
        val learnedEval = SmsPatternLearner.evaluate(sender, parsed.rawText)
        if (learnedEval.isBlockedNonTransaction) {
            return IngestResult.Failed("Blocked by learned user discard pattern")
        }

        val skipReview = settingsRepo?.skipReviewQueue ?: false
        val fallbackPayee = settingsRepo?.fallbackPayee ?: "UPI Payment"
        val reviewThreshold = REVIEW_THRESHOLD_PAISE

        val result = database.withTransaction {
            val amount = resolveForeignAmount(parsed, timestamp)
            val type = parsed.type ?: learnedEval.inferredType
            if (amount == null || amount <= 0L) {
                if (!parsed.rawText.startsWith("Silent Deduction", ignoreCase = true)) {
                    return@withTransaction IngestResult.Failed("No transaction amount found")
                }
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }
            if (type == null) {
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }

            val rawMatch = transactionDao.findMatchingTransaction(
                amount = amount, type = type,
                startTime = timestamp - DEDUPE_WINDOW_MS, endTime = timestamp + DEDUPE_WINDOW_MS,
                targetTime = timestamp, bankName = parsed.bankName, accountNumber = parsed.accountNumber
            )
            // Remember the card's latest available limit (true outstanding = limit - available).
            if (parsed.accountType == "CREDIT_CARD" && parsed.availableLimit != null && parsed.bankName != null) {
                settingsRepo?.recordCardAvailableLimit(parsed.bankName, parsed.accountNumber, parsed.availableLimit, timestamp)
            }
            // The DAO treats a missing bank/account as a wildcard. That is right for "GPay notification
            // + bank SMS" of ONE payment, but wrong for two different same-amount payments (e.g. a card
            // spend and a UPI debit). So with a wildcard, only merge if the match is tight in time and
            // the payees don't contradict each other. Identical reference ids always count.
            val existingMatch = rawMatch?.takeIf { isCompatibleMatch(it, parsed, timestamp) }

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

            // Detect paired inter-account self-transfers
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

            // Card bill payment pairing (CRED / BillDesk / BBPS / "credit card"): only treated as a
            // transfer when the other leg exists, so a CRED payment for something else stays spend.
            val isCardPaymentCredit = parsed.accountType == "CREDIT_CARD" && parsed.isTransfer && type == TransactionType.CREDIT
            val cardBillBankLeg = when {
                isCardPaymentCredit -> {
                    CardPaymentReconciler.markBankLeg(transactionDao, amount, timestamp)
                    false
                }
                parsed.accountType != "CREDIT_CARD" && type == TransactionType.DEBIT && !parsed.isTransfer ->
                    (CardPaymentReconciler.looksLikeCardBill(parsed.payee, parsed.rawText) &&
                        CardPaymentReconciler.hasCardCredit(transactionDao, amount, timestamp)) ||
                        CardPaymentReconciler.hasCardPaymentCredit(transactionDao, amount, timestamp)
                else -> false
            }

            val effectivePayee = parsed.payee?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
                ?: learnedEval.inferredPayee

            val isAutoTransfer = parsed.isTransfer || oppositeLeg != null || cardBillBankLeg
            val isMissingPayee = (effectivePayee.isNullOrBlank() || effectivePayee.equals("Unknown", ignoreCase = true) || effectivePayee.matches(NUMERIC_PAYEE_REGEX)) && !isAutoTransfer
            // If user already approved this template structure, don't force high-value items back into Review Queue
            val isHighValue = type == TransactionType.DEBIT && amount > reviewThreshold && !isAutoTransfer && !learnedEval.isConfirmedTransaction

            if (!skipReview && (isHighValue || isMissingPayee)) {
                return@withTransaction queueForReview(parsed, sender, timestamp, source)
            }

            val resolvedPayee = when {
                cardBillBankLeg -> "Credit Card Bill"
                !effectivePayee.isNullOrBlank() && !effectivePayee.equals("Unknown", ignoreCase = true) -> effectivePayee
                oppositeLeg != null -> "Transfer (${oppositeLeg.bankName ?: "Own A/c"})"
                parsed.isTransfer -> "Self Transfer"
                else -> com.expensetracker.offline.engine.parser.FinancialParser.inferFallbackPayee(parsed.rawText, type, fallbackPayee)
            }
            val aliasedPayee = settingsRepo?.getMerchantAlias(resolvedPayee) ?: resolvedPayee
            val category = when {
                isAutoTransfer -> "Transfer"
                type == TransactionType.CREDIT -> "Uncategorized"
                else -> {
                    learnedEval.inferredCategory?.takeIf { it.isNotBlank() && !it.equals("Uncategorized", ignoreCase = true) }
                        ?: classifyCategory(aliasedPayee, parsed.rawText, amount, timestamp, type)
                }
            }

            val id = transactionDao.insertTransaction(
                TransactionEntity(
                    amount = amount, payee = aliasedPayee, timestamp = timestamp, type = type,
                    source = source, referenceId = parsed.referenceId, rawContent = parsed.rawText,
                    category = category, note = parsed.note ?: if (oppositeLeg != null) "Self-transfer (${oppositeLeg.bankName})" else null,
                    balance = parsed.balance, bankName = parsed.bankName ?: learnedEval.inferredBankName,
                    accountNumber = parsed.accountNumber, excludeFromSpend = parsed.excludeFromSpend || isAutoTransfer,
                    accountType = resolveAccountType(parsed.bankName ?: learnedEval.inferredBankName, parsed.accountNumber, parsed.accountType)
                )
            )
            IngestResult.Inserted(id)
        }
        if (result is IngestResult.Inserted || result is IngestResult.Merged) refreshWidget()
        return result
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getUnbilledCreditCardSpend(bankName: String, accountNumber: String?): Flow<Long> {
        val settings = settingsRepo
        // Re-evaluates when the user edits the card config...
        val configChanges: Flow<Unit> = if (settings == null) flowOf(Unit) else callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == SettingsRepository.KEY_CREDIT_CARD_CONFIGS_JSON) trySend(Unit)
            }
            settings.prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        // ...and every midnight, so the cycle rolls over on the statement date without a restart.
        val dayTicks: Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                val now = System.currentTimeMillis()
                val nextMidnight = java.time.LocalDate.now().plusDays(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                delay((nextMidnight - now).coerceAtLeast(1_000L) + 1_000L)
            }
        }
        return combine(configChanges, dayTicks) { _, _ -> Unit }
            .map {
                val config = settings?.getCreditCardConfig(bankName, accountNumber)
                    ?: CreditCardConfig(settings?.accountKey(bankName, accountNumber) ?: "")
                BillingCycleHelper.getCreditCardCycleRange(config.statementDay).first
            }
            .distinctUntilChanged()
            .flatMapLatest { cycleStart ->
                database.transactionDao().getUnbilledCreditCardSpendFlow(bankName, accountNumber, cycleStart)
            }
    }

    /** Turns "Track as Credit Card" on/off for one account: saves the choice AND re-tags its history. */
    suspend fun setAccountCreditCard(bankName: String, accountNumber: String?, enabled: Boolean) {
        settingsRepo?.setCreditCardEnabled(bankName, accountNumber, enabled)
        database.withTransaction {
            transactionDao.setAccountType(bankName, accountNumber, if (enabled) "CREDIT_CARD" else "BANK")
        }
        refreshWidget()
    }

    suspend fun markAccountAsCreditCard(bankName: String, accountNumber: String?) {
        database.withTransaction {
            transactionDao.markAccountAsCreditCard(bankName, accountNumber)
        }
        refreshWidget()
    }


    companion object {
        /** Bump when the parser/categoriser changes so the next launch repairs old rows once. */
        private const val HEAL_VERSION = 2
        private const val HEAL_INTERVAL_MS = 20L * 60_000L

        const val REVIEW_THRESHOLD_PAISE = 100_000L
        private const val DEDUPE_WINDOW_MS = 300_000L
        private const val WILDCARD_MATCH_WINDOW_MS = 120_000L
        private val NUMERIC_PAYEE_REGEX = Regex("""^\d{8,12}.*""")
    }
}