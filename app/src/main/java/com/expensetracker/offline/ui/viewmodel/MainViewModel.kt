package com.expensetracker.offline.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.TransactionRepository
import com.expensetracker.offline.engine.parser.FinancialParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.expensetracker.offline.data.local.dao.AccountBalanceInsight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.recurring.RecurringPayment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.expensetracker.offline.util.BalanceCache
import com.expensetracker.offline.util.BalanceCacheKeys
import com.expensetracker.offline.util.NecessityItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlin.math.round

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TransactionRepository(
        database = (application as ExpenseTrackerApp).database,
        context = application.applicationContext
    )

    val pendingReviewItems: StateFlow<List<ReviewItemEntity>> = repository.pendingReviewItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingReviewCount: StateFlow<Int> = repository.pendingReviewCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unsettledSplitsWithDebts = repository.unsettledSplitsWithDebts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val startDayOfMonth = MutableStateFlow(1)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = application.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
            startDayOfMonth.value = prefs.getInt("key_start_day_of_month", 1)
        }
    }

    fun updateStartDayOfMonth(day: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("key_start_day_of_month", day)
                .commit()
        }
        startDayOfMonth.value = day
    }

    fun refreshSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getApplication<Application>().getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
            startDayOfMonth.value = prefs.getInt("key_start_day_of_month", 1)
        }
    }

    val searchQuery = MutableStateFlow("")
    val filterCategory = MutableStateFlow("All")
    val filterStartTime = MutableStateFlow(0L)
    val transactionLimit = MutableStateFlow(50)

    fun renameAllPayees(oldPayee: String, newPayee: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameAllPayees(oldPayee, newPayee)
        }
    }

    private val _manualSyncBalance = MutableStateFlow<Long?>(null)
    val manualSyncBalance: StateFlow<Long?> = _manualSyncBalance

    val linkedAccounts: StateFlow<List<AccountInfo>> = repository.getLinkedAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accountBalances: StateFlow<List<AccountBalanceInsight>> = repository.getLatestBalancesForAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedAccount = MutableStateFlow<AccountInfo?>(null)

    private val _pendingDeleteIds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val pendingDeleteIds: StateFlow<Map<Long, Long>> = _pendingDeleteIds

    @OptIn(ExperimentalCoroutinesApi::class)
    val latestBalance: StateFlow<Long?> = selectedAccount.flatMapLatest { account ->
        if (account != null) {
            repository.getLatestBalanceForAccountFlow(account.bankName, account.accountNumber)
        } else {
            repository.latestBalance
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allTransactionsWithDebts = repository.allTransactionsWithDebts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTransactions: StateFlow<List<TransactionEntity>> = allTransactionsWithDebts
        .map { list -> list.map { it.transaction } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalMoneyOwed = repository.totalMoneyOwed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val searchedTransactions: StateFlow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> = combine(
        searchQuery, filterCategory, filterStartTime, transactionLimit
    ) { q, c, s, l -> Triple(q, c, Pair(s, l)) }
        .flatMapLatest { (q, c, sl) -> repository.searchAndFilterTransactionsFlow(q, c, sl.first, sl.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    fun syncLatestBalanceFromSms() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            try {
                val cursor = context.contentResolver.query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(
                        android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.DATE
                    ),
                    null,
                    null,
                    "${android.provider.Telephony.Sms.DATE} DESC LIMIT 50"
                )

                var foundBalance: Long? = null
                cursor?.use { c ->
                    val bodyCol = c.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)
                    val addressCol = c.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)
                    val dateCol = c.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE)
                    while (c.moveToNext()) {
                        val body = c.getString(bodyCol).orEmpty()
                        val address = c.getString(addressCol).orEmpty()
                        if (!com.expensetracker.offline.util.SenderFilter.isAllowedSmsSender(address)) continue
                        val parsed = FinancialParser.parse(body, address)
                        val bal = parsed.balance
                        if (parsed.isFinancial && bal != null) {
                            // Newer-wins write (Long paise) so this can't roll the baseline back.
                            BalanceCache.updateIfNewer(
                                BalanceCache.prefs(context), parsed.bankName, parsed.accountNumber,
                                bal, c.getLong(dateCol)
                            )
                            foundBalance = bal
                            break
                        }
                    }
                }

                if (foundBalance != null) {
                    _manualSyncBalance.value = foundBalance
                } else {
                    _manualSyncBalance.value = -1L
                }
            } catch (e: SecurityException) {
                _manualSyncBalance.value = -1L // Gracefully show "No balance found"
            }
        }
    }

    val recurringPayments: StateFlow<List<RecurringPayment>?> =
        repository.recurringPaymentsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun resetManualSync() {
        _manualSyncBalance.value = null
    }

    // Hand-off from Insights ("Add to Fixed Costs") to the Fixed Costs form.
    private val _pendingNecessityPrefill = MutableStateFlow<NecessityItem?>(null)
    val pendingNecessityPrefill: StateFlow<NecessityItem?> = _pendingNecessityPrefill

    fun requestNecessityPrefill(item: NecessityItem) {
        _pendingNecessityPrefill.value = item
    }

    fun consumeNecessityPrefill() {
        _pendingNecessityPrefill.value = null
    }

    fun stageTransactionDelete(id: Long) {
        val startTime = System.currentTimeMillis()
        _pendingDeleteIds.update { it + (id to startTime) }

        viewModelScope.launch(Dispatchers.IO) {
            delay(5000)
            if (_pendingDeleteIds.value[id] == startTime) {
                repository.deleteTransaction(id)
                _pendingDeleteIds.update { it - id }
            }
        }
    }

    fun stageBulkTransactionDelete(ids: Set<Long>) {
        val startTime = System.currentTimeMillis()
        val newDeletes = ids.associateWith { startTime }
        _pendingDeleteIds.update { it + newDeletes }

        viewModelScope.launch(Dispatchers.IO) {
            delay(5000)
            val remaining = ids.filter { _pendingDeleteIds.value[it] == startTime }
            if (remaining.isNotEmpty()) {
                repository.deleteTransactionsByIds(remaining)
                _pendingDeleteIds.update { it - remaining.toSet() }
            }
        }
    }

    fun undoTransactionDelete(id: Long) {
        _pendingDeleteIds.update { it - id }
    }

    val necessityChunkLimit = MutableStateFlow(0L)

    // Load chunk limit asynchronously
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = application.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
            necessityChunkLimit.value = prefs.getFloat("key_necessity_chunk", 0f).toLong()
        }
    }

    fun updateNecessityChunk(limit: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
                .edit { putFloat("key_necessity_chunk", limit.toFloat()) }
        }
        necessityChunkLimit.value = limit
    }

    fun addManualExpense(
        amount: Long, payee: String, category: String, type: TransactionType,
        timestamp: Long = System.currentTimeMillis(), itemsSummary: String? = null,
        receiptImageUri: String? = null, bankName: String? = null,
        accountNumber: String? = null, linkedDebtId: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = TransactionEntity(
                amount = amount, payee = payee, category = category, type = type,
                source = TransactionSource.MANUAL, timestamp = timestamp,
                referenceId = null, rawContent = "Manual Entry",
                itemsSummary = itemsSummary, receiptImageUri = receiptImageUri,
                linkedDebtId = linkedDebtId, bankName = bankName,
                accountNumber = accountNumber
            )
            repository.insertTransaction(entity)
        }
    }

    fun addTransaction(transaction: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertTransaction(transaction)
        }
    }

    fun saveMultiSplit(transactionId: Long, participants: List<com.expensetracker.offline.ui.components.SplitParticipant>) {
        viewModelScope.launch(Dispatchers.IO) {
            val debtsToSave = participants
                .filter { !it.isMe && ((it.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0) > 0.0) }
                .map { p ->
                    val rupees = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                    val amtPaise = round(rupees * 100.0).toLong()
                    SplitDebtEntity(
                        id = p.debtId ?: 0L,
                        transactionId = transactionId,
                        debtorName = p.name.trim(),
                        originalAmount = amtPaise,
                        amountOwed = amtPaise
                    )
                }
            repository.saveMultiSplit(transactionId, debtsToSave)
        }
    }

    fun markDebtSettled(debtId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markDebtAsSettled(debtId, System.currentTimeMillis())
        }
    }

    fun forgiveDebt(debtId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.forgiveDebt(debtId, System.currentTimeMillis())
        }
    }

    fun logCashRepayment(debtId: Long, amount: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logCashRepayment(debtId, amount, System.currentTimeMillis())
        }
    }

    fun linkDebtToRepayment(debtId: Long, creditId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.linkDebtToRepayment(debtId, creditId)
        }
    }

    fun resetSplit(transactionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetSplit(transactionId)
        }
    }

    fun updateTransaction(transaction: TransactionEntity, learnRule: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateTransaction(transaction)
            if (learnRule) {
                repository.learnCategoryRule(transaction.payee, transaction.category)
            }
        }
    }

    fun updatePayeeAndCategoryForAll(oldPayee: String, newPayee: String, newCategory: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updatePayeeAndCategoryBulk(oldPayee, newPayee, newCategory)
            repository.learnCategoryRule(newPayee, newCategory)
        }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteTransaction(id) }
    }

    fun clearAllTransactions() {
        viewModelScope.launch(Dispatchers.IO) { repository.clearAllTransactions() }
    }

    fun reassignCategory(oldCategory: String, newCategory: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.reassignCategory(oldCategory, newCategory)
        }
    }

    fun resolveReview(
        reviewItem: ReviewItemEntity,
        amount: Long,
        payee: String,
        type: TransactionType,
        category: String,
        bankName: String? = null,
        accountNumber: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Review items now carry bank/account/balance/ref from the ONE header-aware parse.
            // Only legacy rows (before those columns existed) fall back to re-parsing, with the sender.
            val needsParse = reviewItem.bankName == null && reviewItem.balance == null && reviewItem.referenceId == null
            val parsed = if (needsParse) FinancialParser.parse(reviewItem.rawContent, reviewItem.sender) else null
            val bank = bankName ?: reviewItem.bankName ?: parsed?.bankName
            val account = accountNumber ?: reviewItem.accountNumber ?: parsed?.accountNumber
            val balance = reviewItem.balance ?: parsed?.balance

            val newTransaction = TransactionEntity(
                amount = amount,
                payee = payee,
                type = type,
                category = category,
                timestamp = reviewItem.timestamp,
                rawContent = reviewItem.rawContent,
                bankName = bank,
                accountNumber = account,
                balance = balance,
                referenceId = reviewItem.referenceId ?: parsed?.referenceId,
                note = parsed?.note,
                source = reviewItem.source,
                itemsSummary = null,
                receiptImageUri = null
            )
            // Insert + mark RESOLVED in one DB transaction (no half-approved items).
            repository.approveReviewItem(newTransaction, reviewItem.id)
            repository.learnCategoryRule(payee, category)

            balance?.let {
                val context = getApplication<Application>().applicationContext
                BalanceCache.updateIfNewer(BalanceCache.prefs(context), bank, account, it, reviewItem.timestamp)
            }
        }
    }

    fun discardReview(itemId: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.discardReviewItem(itemId) }
    }

    fun updateSearchFilters(query: String, category: String, start: Long, limit: Int) {
        searchQuery.value = query
        filterCategory.value = category
        filterStartTime.value = start
        transactionLimit.value = limit
    }

    /**
     * Approves review items in bulk using only what could be read from each message.
     *  - Never guesses a category: unknown merchants stay "Uncategorized" (the old code defaulted
     *    to "Food & Dining", which put wrong categories on the transactions).
     *  - Never teaches a category rule, because nobody confirmed the category (the old code saved
     *    a rule per item, so one bulk approve could poison future auto-categorisation).
     *  - Items with no readable amount are left in the queue.
     *
     * @param onDone called on the main thread with how many were approved / left in the queue
     */
    fun bulkResolveReviewItems(
        items: List<ReviewItemEntity>,
        onDone: (approved: Int, skipped: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var approved = 0
            var skipped = 0
            val approvals = mutableListOf<Pair<TransactionEntity, Long>>()
            val context = getApplication<Application>().applicationContext
            val prefs = BalanceCache.prefs(context)
            
            // Oldest first, so the newest balance is written last (newer-wins also guards this).
            items.sortedBy { it.timestamp }.forEach { item ->
                val needsParse = item.bankName == null && item.balance == null && item.referenceId == null && item.type == null
                val parsed = if (needsParse) FinancialParser.parse(item.rawContent, item.sender) else null
                val amt = item.extractedPartialAmount ?: parsed?.amount ?: 0L
                val payee = item.extractedPartialPayee ?: parsed?.payee ?: "Unknown"
                val type = item.type ?: parsed?.type

                // Never guess the type of a message we could not classify; leave it in the queue.
                if (amt > 0 && type != null) {
                    val category = repository.classifyCategory(payee, item.rawContent)
                    val bank = item.bankName ?: parsed?.bankName
                    val account = item.accountNumber ?: parsed?.accountNumber
                    val balance = item.balance ?: parsed?.balance

                    val newTransaction = TransactionEntity(
                        amount = amt,
                        payee = payee,
                        type = type,
                        category = category,
                        timestamp = item.timestamp,
                        rawContent = item.rawContent,
                        bankName = bank,
                        accountNumber = account,
                        balance = balance,
                        referenceId = item.referenceId ?: parsed?.referenceId,
                        note = parsed?.note,
                        source = item.source,
                        itemsSummary = null,
                        receiptImageUri = null
                    )

                    approvals.add(newTransaction to item.id)

                    balance?.let {
                        BalanceCache.updateIfNewer(prefs, bank, account, it, item.timestamp)
                    }
                    approved++
                } else {
                    skipped++
                }
            }
            
            repository.bulkApproveReviewItems(approvals)
            
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(approved, skipped) }
        }
    }

    fun bulkDiscardReviewItems(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { repository.discardReviewItem(it) }
        }
    }

    /** Returns how many rows were actually added (rows already in the app are skipped). */
    suspend fun importTransactions(transactions: List<TransactionEntity>): Int =
        withContext(Dispatchers.IO) { repository.insertTransactionsBulk(transactions) }

    fun triggerMockIncoming(
        rawText: String,
        sender: String,
        source: TransactionSource,
        timestamp: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = FinancialParser.parse(rawText, sender)
            repository.processIncomingEvent(parsed, sender, timestamp, source)
        }
    }

    fun undoScan(transactionIds: List<Long>, reviewIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (transactionIds.isNotEmpty()) repository.deleteTransactionsByIds(transactionIds)
            if (reviewIds.isNotEmpty()) {
                val db = getApplication<ExpenseTrackerApp>().database
                db.reviewItemDao().deleteReviewItemsByIds(reviewIds)
            }
        }
    }

    fun undoRepayment(creditTxn: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.undoRepayment(creditTxn)
        }
    }
}
