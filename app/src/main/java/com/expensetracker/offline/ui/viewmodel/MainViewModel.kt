package com.expensetracker.offline.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.dao.AccountBalanceInsight
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.ui.dashboard.SplitLinkInfo
import com.expensetracker.offline.util.BackupResult
import com.expensetracker.offline.util.RestoreResult
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.backup.SecurePassphraseStore
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.util.BackupManager
import com.expensetracker.offline.util.BalanceCache
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ExpenseTrackerApp).repository
    private val settingsRepo = SettingsRepository(application.applicationContext)

    // Settings-related properties
    val monthlyBudget = MutableStateFlow(settingsRepo.monthlyBudget)
    val startDayOfMonth = MutableStateFlow(settingsRepo.startDayOfMonth)
    val necessityChunkLimit = MutableStateFlow(settingsRepo.necessityChunkLimit)
    val isBudgetExplicitlySet = MutableStateFlow(settingsRepo.isBudgetExplicitlySet)

    val categoryBudgets = MutableStateFlow(settingsRepo.getCategoryBudgets())
    val dismissedCategoryWarnings = MutableStateFlow<Set<String>>(emptySet())
    val dismissedCategoryWarningLevels = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        val (cycleStart, _) = com.expensetracker.offline.util.BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
    }

    fun setCategoryBudget(category: String, amountInPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setCategoryBudget(category, amountInPaise)
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
        }
    }

    fun removeCategoryBudget(category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.removeCategoryBudget(category)
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
        }
    }

    fun dismissCategoryWarning(category: String, level: String = SettingsRepository.WARNING_LEVEL_OVER) {
        viewModelScope.launch(Dispatchers.IO) {
            val (cycleStart, _) = com.expensetracker.offline.util.BillingCycleHelper.getCycleRange(startDayOfMonth.value)
            settingsRepo.dismissCategoryWarning(category, cycleStart, level)
            val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
            dismissedCategoryWarningLevels.value = levels
            dismissedCategoryWarnings.value = levels.keys
        }
    }

    fun updateStartDayOfMonth(day: Int) {
        val clamped = day.coerceIn(1, 28)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.startDayOfMonth = clamped
        }
        startDayOfMonth.value = clamped
        val (cycleStart, _) = com.expensetracker.offline.util.BillingCycleHelper.getCycleRange(clamped)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
    }

    fun updateMonthlyBudget(newBudget: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.monthlyBudget = newBudget
        }
        monthlyBudget.value = newBudget
        isBudgetExplicitlySet.value = true
    }

    fun updateNecessityChunk(limit: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.necessityChunkLimit = limit
        }
        necessityChunkLimit.value = limit
    }

    fun refreshSettings() {
        monthlyBudget.value = settingsRepo.monthlyBudget
        startDayOfMonth.value = settingsRepo.startDayOfMonth
        necessityChunkLimit.value = settingsRepo.necessityChunkLimit
        isBudgetExplicitlySet.value = settingsRepo.isBudgetExplicitlySet
        categoryBudgets.value = settingsRepo.getCategoryBudgets()
    }



    private val searchQuery = MutableStateFlow("")
    private val filterCategory = MutableStateFlow("All")
    private val filterStartTime = MutableStateFlow(0L)
    private val transactionLimit = MutableStateFlow(100)

    fun renamePayeeForAll(oldPayee: String, newPayee: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameAllPayees(oldPayee, newPayee)
        }
    }

    private val _manualSyncBalance = MutableStateFlow<Long?>(null)
    val manualSyncBalance: StateFlow<Long?> = _manualSyncBalance

    val linkedAccounts: StateFlow<List<AccountInfo>> = repository.getLinkedAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val accountBalances: StateFlow<List<AccountBalanceInsight>> = repository.getLatestBalancesForAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())



    val allTransactions: StateFlow<List<TransactionEntity>> = allTransactionsWithDebts
        .map { list -> list.map { it.transaction } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())



    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val searchedTransactions: StateFlow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> = combine(
        searchQuery, filterCategory, filterStartTime, transactionLimit
    ) { q, c, s, l -> Triple(q, c, Pair(s, l)) }
        .flatMapLatest { (q, c, sl) -> repository.searchAndFilterTransactionsFlow(q, c, sl.first, sl.second) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                _manualSyncBalance.value = -1L
            }
        }
    }

    val recurringPayments: StateFlow<List<RecurringPayment>?> =
        repository.recurringPaymentsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun resetManualSync() {
        _manualSyncBalance.value = null
    }

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

    // Removed updateNecessityChunk

    fun addManualExpense(
        amount: Long, payee: String, category: String, type: TransactionType,
        timestamp: Long = System.currentTimeMillis(), itemsSummary: String? = null,
        receiptImageUri: String? = null, bankName: String? = null,
        accountNumber: String? = null, linkedDebtId: Long? = null,
        excludeFromSpend: Boolean = false,
        splitParticipants: List<com.expensetracker.offline.ui.components.SplitParticipant> = emptyList()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = TransactionEntity(
                amount = amount, payee = payee, category = category, type = type,
                source = TransactionSource.MANUAL, timestamp = timestamp,
                referenceId = null, rawContent = "Manual Entry",
                itemsSummary = itemsSummary, receiptImageUri = receiptImageUri,
                linkedDebtId = linkedDebtId, bankName = bankName,
                accountNumber = accountNumber,
                excludeFromSpend = excludeFromSpend || category.equals("Transfer", ignoreCase = true)
            )
            val newId = repository.insertTransaction(entity)
            if (newId > 0L && splitParticipants.isNotEmpty() && type == TransactionType.DEBIT) {
                val debtsToSave = splitParticipants
                    .filter { p ->
                        val amt = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                        amt > 0.0 && !p.isMe
                    }
                    .map { p ->
                        val rupees = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                        val amtPaise = round(rupees * 100.0).toLong()
                        SplitDebtEntity(
                            id = 0L,
                            transactionId = newId,
                            debtorName = p.name.trim(),
                            originalAmount = amtPaise,
                            amountOwed = amtPaise
                        )
                    }
                if (debtsToSave.isNotEmpty()) {
                    repository.saveMultiSplit(newId, debtsToSave, "You")
                }
            }
        }
    }

    fun addTransaction(transaction: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertTransaction(transaction)
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
            settingsRepo.reassignCategoryBudget(oldCategory, newCategory)
        }
    }

    fun onCategoryDeleted(deletedCategory: String, reassignTo: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (reassignTo != null) {
                repository.reassignCategory(deletedCategory, reassignTo)
            }
            settingsRepo.reassignCategoryBudget(deletedCategory, reassignTo)
        }
    }

    fun updateSearchFilters(query: String, category: String, start: Long, limit: Int) {
        searchQuery.value = query
        filterCategory.value = category
        filterStartTime.value = start
        transactionLimit.value = limit
    }




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


}
