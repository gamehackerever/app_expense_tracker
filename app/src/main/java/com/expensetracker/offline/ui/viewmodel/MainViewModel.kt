package com.expensetracker.offline.ui.viewmodel

import android.app.Application
import android.content.Context
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
import com.expensetracker.offline.data.repository.CreditCardConfig
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.backup.SecurePassphraseStore
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import com.expensetracker.offline.ui.dashboard.SplitLinkInfo
import com.expensetracker.offline.util.BackupManager
import com.expensetracker.offline.util.BackupResult
import com.expensetracker.offline.util.BalanceCache
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.round

data class OnboardingScanSummary(
    val isScanning: Boolean = false,
    val hasCompleted: Boolean = false,
    val scannedSmsCount: Int = 0,
    val importedTxnCount: Int = 0,
    val reviewCount: Int = 0,
    val detectedAccountsCount: Int = 0,
    val typicalMonthlySpendRupees: Long = 0L,
    val suggestedBudgetRupees: Long = 15000L,
    val permissionGranted: Boolean = false
)

/** First/second installment. Lossless: first + second == overspend. Months 1 => (all, 0). */
fun splitRolloverOverspend(overspendPaise: Long, months: Int): Pair<Long, Long> {
    val total = overspendPaise.coerceAtLeast(0L)
    return if (months >= 2) (total / 2).let { it to (total - it) } else total to 0L
}


class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ExpenseTrackerApp
        get() = getApplication()

    private val repository
        get() = app.repository

    private val settingsRepo = SettingsRepository(application.applicationContext)

    /** Budget the dashboard measures against: this cycle's one-off budget if set, else the usual one. */
    val monthlyBudget = MutableStateFlow(settingsRepo.effectiveMonthlyBudget)
    /** The usual (every-month) budget, shown and edited in Settings. */
    val baseMonthlyBudget = MutableStateFlow(settingsRepo.monthlyBudget)
    /** One-off budget for the current cycle, or null. */
    val cycleBudgetOverride = MutableStateFlow(settingsRepo.currentCycleBudgetOverride())
    val startDayOfMonth = MutableStateFlow(settingsRepo.startDayOfMonth)
    val necessityChunkLimit = MutableStateFlow(settingsRepo.necessityChunkLimit)
    val isBudgetExplicitlySet = MutableStateFlow(settingsRepo.isBudgetExplicitlySet)
    val isManualModePreferred = MutableStateFlow(settingsRepo.isManualModePreferred)
    val isAutoCaptureBannerDismissed = MutableStateFlow(settingsRepo.isAutoCaptureBannerDismissed)

    private val _onboardingScanSummary = MutableStateFlow(OnboardingScanSummary())
    val onboardingScanSummary: StateFlow<OnboardingScanSummary> = _onboardingScanSummary

    private val dbEpoch = MutableStateFlow(0)

    val categoryBudgets = MutableStateFlow(settingsRepo.getCategoryBudgets())
    val dismissedCategoryWarnings = MutableStateFlow<Set<String>>(emptySet())
    val dismissedCategoryWarningLevels = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeRolloverAmount = MutableStateFlow(0L)
    /** What last cycle's overspend originally deducted from THIS cycle, and how much of it the user moved on to the next cycle. */
    val incomingRolloverOriginal = MutableStateFlow(0L)
    val incomingRolloverMoved = MutableStateFlow(0L)
    val currentCycleRolloverDecision = MutableStateFlow<Boolean?>(null)
    val isRolloverEnabled = MutableStateFlow(false)
    val rolloverMonths = MutableStateFlow(1)
    private val rolloverMutex = Mutex()
    private val rolloverPrefs = application.getSharedPreferences("rollover_plans", Context.MODE_PRIVATE)

    @OptIn(ExperimentalCoroutinesApi::class)
    val unsettledSplitsWithDebts = dbEpoch.flatMapLatest { repository.unsettledSplitsWithDebts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.syncCategoryRulesFromHistory() }
        }
        loadRolloverState()
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
            val (cycleStart, _) = BillingCycleHelper.getCycleRange(startDayOfMonth.value)
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
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(clamped)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
    }

    /** @param clearOverride true when the user says "every month": a leftover one-off must not hide the new number. */
    fun updateMonthlyBudget(newBudget: Long, clearOverride: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.monthlyBudget = newBudget
            if (clearOverride) settingsRepo.setCurrentCycleBudgetOverride(null)
        }
        baseMonthlyBudget.value = newBudget
        if (clearOverride) cycleBudgetOverride.value = null
        monthlyBudget.value = cycleBudgetOverride.value ?: newBudget
        isBudgetExplicitlySet.value = true
    }

    /** Unforeseen month: change this cycle's budget only. The usual budget returns automatically next cycle. */
    fun setBudgetForThisCycle(rupees: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setCurrentCycleBudgetOverride(rupees)
        }
        cycleBudgetOverride.value = rupees
        monthlyBudget.value = rupees
        isBudgetExplicitlySet.value = true
    }

    fun clearBudgetOverride() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setCurrentCycleBudgetOverride(null)
        }
        cycleBudgetOverride.value = null
        monthlyBudget.value = baseMonthlyBudget.value
    }

    fun refreshSettings() {
        baseMonthlyBudget.value = settingsRepo.monthlyBudget
        cycleBudgetOverride.value = settingsRepo.currentCycleBudgetOverride()
        monthlyBudget.value = settingsRepo.effectiveMonthlyBudget
        startDayOfMonth.value = settingsRepo.startDayOfMonth
        necessityChunkLimit.value = settingsRepo.necessityChunkLimit
        isBudgetExplicitlySet.value = settingsRepo.isBudgetExplicitlySet
        isManualModePreferred.value = settingsRepo.isManualModePreferred
        isAutoCaptureBannerDismissed.value = settingsRepo.isAutoCaptureBannerDismissed
        categoryBudgets.value = settingsRepo.getCategoryBudgets()
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
        val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
        dismissedCategoryWarningLevels.value = levels
        dismissedCategoryWarnings.value = levels.keys
        loadRolloverState()
    }

    private fun getCycleKey(cycleStartMs: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM_yyyy", java.util.Locale.US)
        return sdf.format(java.util.Date(cycleStartMs))
    }

    /** Key of the cycle [offset] months after the current one (0 = current). */
    private fun cycleKeyAt(offset: Int): String {
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = cycleStart
            add(java.util.Calendar.MONTH, offset)
        }
        return getCycleKey(cal.timeInMillis)
    }

    private fun loadRolloverState() {
        val key = cycleKeyAt(0)
        activeRolloverAmount.value = settingsRepo.rolloverBudgetMap[key] ?: 0L
        incomingRolloverOriginal.value = rolloverPrefs.getLong("orig|$key", -1L).let { if (it < 0L) activeRolloverAmount.value else it }
        incomingRolloverMoved.value = rolloverPrefs.getLong("moved|$key", 0L)
        currentCycleRolloverDecision.value = settingsRepo.getRolloverDecision(key)
        isRolloverEnabled.value = settingsRepo.getRolloverEnabled(key)
        rolloverMonths.value = rolloverPrefs.getInt("months|$key", 1).coerceIn(1, 2)
    }

    /**
     * Writes this cycle's contribution to the next two cycles. Each target's total is
     * (existing total − this source's previous contribution + new contribution), so
     * several source cycles can safely feed the same target. Idempotent.
     * Must be called inside [rolloverMutex] on an IO thread.
     */
    private fun writePlan(overspendPaise: Long, months: Int) {
        val source = cycleKeyAt(0)
        val (first, second) = splitRolloverOverspend(overspendPaise, months)
        val totals = settingsRepo.rolloverBudgetMap
        val edit = rolloverPrefs.edit()
        listOf(cycleKeyAt(1) to first, cycleKeyAt(2) to second).forEach { (target, amount) ->
            val planKey = "c|$source|$target"
            val previous = rolloverPrefs.getLong(planKey, 0L)
            if (previous != amount) {
                val total = ((totals[target] ?: 0L) - previous + amount).coerceAtLeast(0L)
                settingsRepo.setRolloverBudget(target, total)
                edit.putLong(planKey, amount)
            }
        }
        edit.commit()
    }

    /** choice: 0 = start fresh, 1 = deduct next cycle, 2 = split across next two cycles. */
    fun setRolloverChoice(choice: Int, overspendPaise: Long) {
        val picked = choice.coerceIn(0, 2)
        isRolloverEnabled.value = picked != 0
        if (picked != 0) rolloverMonths.value = picked
        viewModelScope.launch(Dispatchers.IO) {
            rolloverMutex.withLock {
                withContext(NonCancellable) {
                    val key = cycleKeyAt(0)
                    settingsRepo.setRolloverEnabled(key, picked != 0)
                    if (picked != 0) rolloverPrefs.edit().putInt("months|$key", picked).commit()
                    writePlan(if (picked != 0) overspendPaise.coerceAtLeast(0L) else 0L, maxOf(picked, 1))
                }
            }
        }
    }

    /** Keeps the saved plan in step with live overspend. Reads enabled/months from storage. */
    fun syncRollover(overspendPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverMutex.withLock {
                withContext(NonCancellable) {
                    val key = cycleKeyAt(0)
                    val enabled = settingsRepo.getRolloverEnabled(key)
                    val months = rolloverPrefs.getInt("months|$key", 1).coerceIn(1, 2)
                    writePlan(if (enabled) overspendPaise.coerceAtLeast(0L) else 0L, months)
                }
            }
        }
    }

    // Kept so existing callers still compile.
    fun toggleRollover(enabled: Boolean, currentOverspendPaise: Long) =
        setRolloverChoice(if (enabled) rolloverMonths.value else 0, currentOverspendPaise)

    fun makeRolloverDecision(accepted: Boolean?, overspendPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverMutex.withLock {
                withContext(NonCancellable) {
                    val key = cycleKeyAt(0)
                    settingsRepo.markRolloverDecision(key, accepted)
                    currentCycleRolloverDecision.value = accepted
                    val months = rolloverPrefs.getInt("months|$key", 1).coerceIn(1, 2)
                    writePlan(if (accepted == true) overspendPaise.coerceAtLeast(0L) else 0L, months)
                }
            }
        }
    }

    fun applyBudgetRollover(overspendPaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverMutex.withLock {
                withContext(NonCancellable) {
                    val key = cycleKeyAt(0)
                    writePlan(overspendPaise.coerceAtLeast(0L), rolloverPrefs.getInt("months|$key", 1).coerceIn(1, 2))
                }
            }
        }
    }

    /**
     * Changes how much of LAST cycle's overspend is deducted from THIS cycle's budget, after the fact.
     * mode 0 = start fresh (deduct nothing), 1 = deduct all of it, 2 = deduct half now and carry the other half
     * into the next cycle. Fully reversible: the original amount is remembered.
     */
    fun adjustIncomingRollover(mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverMutex.withLock {
                withContext(NonCancellable) {
                    val key = cycleKeyAt(0)
                    val nextKey = cycleKeyAt(1)
                    val totals = settingsRepo.rolloverBudgetMap
                    val current = totals[key] ?: 0L
                    val original = rolloverPrefs.getLong("orig|$key", -1L).let { if (it < 0L) current else it }
                    val previouslyMoved = rolloverPrefs.getLong("moved|$key", 0L)

                    // Undo any earlier split first, then apply the new choice from the original amount.
                    if (previouslyMoved > 0L) {
                        settingsRepo.setRolloverBudget(nextKey, ((settingsRepo.rolloverBudgetMap[nextKey] ?: 0L) - previouslyMoved).coerceAtLeast(0L))
                    }
                    var moved = 0L
                    val deductNow = when (mode) {
                        0 -> 0L
                        2 -> original / 2
                        else -> original
                    }
                    if (mode == 2) {
                        moved = original - deductNow
                        settingsRepo.setRolloverBudget(nextKey, (settingsRepo.rolloverBudgetMap[nextKey] ?: 0L) + moved)
                    }
                    settingsRepo.setRolloverBudget(key, deductNow)
                    rolloverPrefs.edit().putLong("orig|$key", original).putLong("moved|$key", moved).commit()
                    activeRolloverAmount.value = deductNow
                    incomingRolloverOriginal.value = original
                    incomingRolloverMoved.value = moved
                }
            }
        }
    }

    fun refreshAfterRestore() {
        refreshSettings()
        dbEpoch.value += 1
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.syncCategoryRulesFromHistory() }
        }
    }

    fun setManualModePreferred(manual: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.isManualModePreferred = manual
            if (manual) {
                settingsRepo.isAutoCaptureBannerDismissed = true
            }
        }
        isManualModePreferred.value = manual
        if (manual) {
            isAutoCaptureBannerDismissed.value = true
        }
    }

    fun setAutoCaptureBannerDismissed(dismissed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.isAutoCaptureBannerDismissed = dismissed
        }
        isAutoCaptureBannerDismissed.value = dismissed
    }

    fun runOnboardingSmsScan() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasReadSms) {
                _onboardingScanSummary.value = OnboardingScanSummary(
                    isScanning = false,
                    hasCompleted = true,
                    permissionGranted = false
                )
                return@launch
            }

            _onboardingScanSummary.value = OnboardingScanSummary(
                isScanning = true,
                hasCompleted = false,
                permissionGranted = true
            )

            val now = System.currentTimeMillis()
            val ninetyDaysAgo = now - 90L * 86_400_000L
            val scanner = com.expensetracker.offline.engine.scanner.SmsHistoryScanner(context, app.database)
            val scanResp = scanner.scanSmsSince(ninetyDaysAgo)

            syncLatestBalanceFromSms()

            val recentTxns = runCatching {
                app.database.transactionDao().getTransactionsInRangeSync(ninetyDaysAgo, now)
            }.getOrDefault(emptyList())

            val thirtyDaysAgo = now - 30L * 86_400_000L
            val debits90d = recentTxns.filter {
                it.type == TransactionType.DEBIT &&
                    !it.excludeFromSpend &&
                    !it.category.equals("Transfer", ignoreCase = true) &&
                    !it.category.equals("Split Settlement", ignoreCase = true)
            }
            val debits30dPaise = debits90d.filter { it.timestamp >= thirtyDaysAgo }.sumOf { it.amount }
            val total90dPaise = debits90d.sumOf { it.amount }

            val typicalMonthlyPaise = when {
                debits30dPaise > 0L -> debits30dPaise
                total90dPaise > 0L -> total90dPaise / 3L
                else -> 0L
            }
            val typicalMonthlyRupees = (typicalMonthlyPaise / 100L).coerceAtLeast(0L)
            val suggestedRupees = if (typicalMonthlyRupees > 0L) {
                (((typicalMonthlyRupees + 999L) / 1000L) * 1000L).coerceIn(5000L, 500_000L)
            } else {
                15000L
            }

            val accountsCount = recentTxns
                .mapNotNull { t -> t.bankName?.trim()?.takeIf { it.isNotEmpty() }?.let { b -> "$b|${t.accountNumber ?: ""}" } }
                .distinct()
                .size

            when (scanResp) {
                is com.expensetracker.offline.engine.scanner.ScanResponse.Success -> {
                    val r = scanResp.result
                    settingsRepo.lastImportedTxnIds = r.importedTransactionIds
                    settingsRepo.lastImportedReviewIds = r.importedReviewIds
                    settingsRepo.prefs.edit().putBoolean("key_setup_scan_done", true).apply()
                    _onboardingScanSummary.value = OnboardingScanSummary(
                        isScanning = false,
                        hasCompleted = true,
                        scannedSmsCount = r.scannedCount,
                        importedTxnCount = r.directImportedCount,
                        reviewCount = r.reviewCount,
                        detectedAccountsCount = accountsCount,
                        typicalMonthlySpendRupees = typicalMonthlyRupees,
                        suggestedBudgetRupees = suggestedRupees,
                        permissionGranted = true
                    )
                }
                else -> {
                    _onboardingScanSummary.value = OnboardingScanSummary(
                        isScanning = false,
                        hasCompleted = true,
                        detectedAccountsCount = accountsCount,
                        typicalMonthlySpendRupees = typicalMonthlyRupees,
                        suggestedBudgetRupees = suggestedRupees,
                        permissionGranted = true
                    )
                }
            }
        }
    }

    fun exportBackupToUri(uri: Uri, password: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val result = BackupManager.createBackup(context, uri.toString(), password ?: "")
            withContext(Dispatchers.Main) { onResult(result is BackupResult.Success) }
        }
    }

    fun importBackupFromUri(uri: Uri, password: String? = null, onResult: (RestoreResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val result = BackupManager.restoreBackup(context, uri.toString(), password ?: "")
            if (result is RestoreResult.SuccessRestart) {
                refreshAfterRestore()
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun configureAutoBackupPassphrase(passphrase: String?) {
        val context = getApplication<Application>().applicationContext
        if (passphrase != null) {
            SecurePassphraseStore.save(context, passphrase)
        } else {
            SecurePassphraseStore.clear(context)
        }
        AutoBackupWorker.schedule(context)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingReviewItems: StateFlow<List<ReviewItemEntity>> = dbEpoch.flatMapLatest { repository.pendingReviewItems }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingReviewCount: StateFlow<Int> = dbEpoch.flatMapLatest { repository.pendingReviewCount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val searchQuery = MutableStateFlow("")
    private val filterCategory = MutableStateFlow("All")
    private val filterStartTime = MutableStateFlow(0L)
    private val transactionLimit = MutableStateFlow(100)

    // ───────────── Custom budgets (goal budgets such as "New laptop") ─────────────
    private val customBudgetStore = com.expensetracker.offline.data.repository.CustomBudgetStore(settingsRepo.prefs)
    val customBudgets = MutableStateFlow(customBudgetStore.load())

    fun saveCustomBudget(id: String?, name: String, targetPaise: Long, excludeFromMonthly: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || targetPaise <= 0L) return
        val existing = customBudgets.value.firstOrNull { it.id == id }
        val budget = com.expensetracker.offline.data.repository.CustomBudget(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = trimmed,
            targetPaise = targetPaise,
            excludeFromMonthly = excludeFromMonthly,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        customBudgets.value = customBudgetStore.upsert(budget)
        // Flipping "keep out of monthly" applies to everything already in the budget.
        if (existing != null && existing.excludeFromMonthly != excludeFromMonthly) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { repository.setBudgetExclusion(budget.id, excludeFromMonthly) } }
        }
    }

    fun deleteCustomBudget(id: String) {
        val budget = customBudgets.value.firstOrNull { it.id == id } ?: return
        customBudgets.value = customBudgetStore.delete(id)
        viewModelScope.launch(Dispatchers.IO) { runCatching { repository.clearBudget(id, budget.excludeFromMonthly) } }
    }

    /** Puts [ids] into [budgetId] (or takes them out of any budget when null). */
    fun assignToBudget(ids: List<Long>, budgetId: String?) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val budget = customBudgets.value.firstOrNull { it.id == budgetId }
                if (budget != null) {
                    repository.tagToBudget(ids, budget.id, budget.excludeFromMonthly)
                } else {
                    val excludingIds = customBudgets.value.filter { it.excludeFromMonthly }.map { it.id }.toSet()
                    val restore = repository.budgetIdsOf(ids).any { it in excludingIds }
                    repository.untagFromBudget(ids, restore)
                }
            }
        }
    }

    /** "Sort by payee": categorise every uncategorised spend to this payee and learn the rule. */
    fun categorizePayee(payee: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.categorizePayee(payee, category) }
        }
    }

    fun renamePayeeForAll(oldPayee: String, newPayee: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameAllPayees(oldPayee, newPayee)
        }
    }

    private val _manualSyncBalance = MutableStateFlow<Long?>(null)
    val manualSyncBalance: StateFlow<Long?> = _manualSyncBalance

    @OptIn(ExperimentalCoroutinesApi::class)
    val linkedAccounts: StateFlow<List<AccountInfo>> = dbEpoch.flatMapLatest { repository.getLinkedAccountsFlow() }
        .map { list ->
            val normalized = list.map { acc ->
                val cleanBank = acc.bankName.removeSuffix(" CC").removeSuffix(" cc").trim()
                val cleanAcc = acc.accountNumber?.trim()?.takeLast(4)?.takeIf { it.isNotEmpty() }
                AccountInfo(cleanBank, cleanAcc)
            }
            val byBank = normalized.groupBy { it.bankName.lowercase(Locale.ROOT) }
            byBank.flatMap { (_, group) ->
                val withAcc = group.filter { it.accountNumber != null }.distinctBy { it.accountNumber }
                when {
                    withAcc.isEmpty() -> listOf(group.first())
                    group.first().bankName.equals("South Indian Bank", ignoreCase = true) -> listOf(withAcc.first())
                    else -> withAcc
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val accountBalances: StateFlow<List<AccountBalanceInsight>> = dbEpoch.flatMapLatest { repository.getLatestBalancesForAllAccountsFlow() }
        .map { list ->
            val normalized = list.map { item ->
                val cleanBank = item.bankName.removeSuffix(" CC").removeSuffix(" cc").trim()
                val cleanAcc = item.accountNumber?.trim()?.takeLast(4)?.takeIf { it.isNotEmpty() }
                item.copy(bankName = cleanBank, accountNumber = cleanAcc)
            }
            val byBank = normalized.groupBy { it.bankName.lowercase(Locale.ROOT) }
            byBank.flatMap { (_, bankGroup) ->
                val primaryAcc = bankGroup.mapNotNull { it.accountNumber }.firstOrNull()
                val unifiedGroup = if (bankGroup.first().bankName.equals("South Indian Bank", ignoreCase = true) && primaryAcc != null) {
                    bankGroup.map { it.copy(accountNumber = primaryAcc) }
                } else {
                    bankGroup.map { if (it.accountNumber == null && primaryAcc != null) it.copy(accountNumber = primaryAcc) else it }
                }
                unifiedGroup
                    .groupBy { "${it.bankName.lowercase(Locale.ROOT)}|${it.accountNumber ?: ""}" }
                    .map { (_, g) ->
                        val newestWithBal = g.filter { it.latestBalance != null }.maxByOrNull { it.lastUpdated }
                        val newestOverall = g.maxByOrNull { it.lastUpdated } ?: g.first()
                        newestOverall.copy(latestBalance = newestWithBal?.latestBalance ?: newestOverall.latestBalance)
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedAccount = MutableStateFlow<AccountInfo?>(null)

    private val _pendingDeleteIds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val pendingDeleteIds: StateFlow<Map<Long, Long>> = _pendingDeleteIds

    @OptIn(ExperimentalCoroutinesApi::class)
    val latestBalance: StateFlow<Long?> = combine(selectedAccount, dbEpoch) { account, _ -> account }.flatMapLatest { account ->
        if (account != null) {
            repository.getLatestBalanceForAccountFlow(account.bankName, account.accountNumber)
        } else {
            repository.latestBalance
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val totalCreditCardDebt: StateFlow<Long> = dbEpoch.flatMapLatest {
        repository.getTotalCreditCardDebtFlow()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedAccountUnbilledSpend: StateFlow<Long?> = combine(selectedAccount, dbEpoch) { account, _ -> account }
        .flatMapLatest { account ->
            if (account != null) {
                val ccConfig = settingsRepo.getCreditCardConfig(account.bankName, account.accountNumber)
                if (ccConfig?.isCreditCard == true) {
                    repository.getUnbilledCreditCardSpend(account.bankName, account.accountNumber)
                } else {
                    kotlinx.coroutines.flow.flowOf(null)
                }
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val allTransactionsWithDebts = dbEpoch.flatMapLatest { repository.allTransactionsWithDebts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val splitLinkInfo: StateFlow<SplitLinkInfo> = allTransactionsWithDebts.map { list: List<com.expensetracker.offline.data.local.dao.TransactionWithDebts> ->
        val creditsWithLinks = ArrayList<TransactionEntity>()
        for (i in 0 until list.size) {
            val item = list[i]
            val txn = item.transaction
            if (txn.type == TransactionType.CREDIT && txn.linkedDebtId != null) {
                creditsWithLinks.add(txn)
            }
        }
        val linkedCreditsByDebtId = HashMap<Long, List<TransactionEntity>>()
        val linkedDebtIdByCreditId = HashMap<Long, Long>()
        for (i in 0 until creditsWithLinks.size) {
            val txn = creditsWithLinks[i]
            val debtId = txn.linkedDebtId ?: continue
            val subList = (linkedCreditsByDebtId[debtId] as? MutableList<TransactionEntity>) ?: ArrayList<TransactionEntity>().also { linkedCreditsByDebtId[debtId] = it }
            subList.add(txn)
            linkedDebtIdByCreditId[txn.id] = debtId
        }
        SplitLinkInfo(linkedCreditsByDebtId, linkedDebtIdByCreditId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SplitLinkInfo(emptyMap(), emptyMap()))

    val allTransactions: StateFlow<List<TransactionEntity>> = allTransactionsWithDebts
        .map { list -> list.map { it.transaction } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val totalMoneyOwed: StateFlow<Long?> = dbEpoch.flatMapLatest { repository.totalMoneyOwed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val totalMoneyYouOwe: StateFlow<Long?> = dbEpoch.flatMapLatest { repository.totalMoneyYouOwe }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val searchedTransactions: StateFlow<List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>> = combine(
        searchQuery, filterCategory, filterStartTime, transactionLimit, dbEpoch
    ) { q, c, s, l, _ -> Triple(q, c, Pair(s, l)) }
        .flatMapLatest { (q, c, sl) -> repository.searchAndFilterTransactionsFlow(q, c, sl.first, sl.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAccountCreditCard(bankName: String, accountNumber: String?, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setAccountCreditCard(bankName, accountNumber, enabled)
        }
    }

    fun setManualAccountBalance(bankName: String, accountNumber: String?, newBalancePaise: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setManualAccountBalance(bankName, accountNumber, newBalancePaise)
        }
    }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val detectedRecurring: StateFlow<List<RecurringPayment>?> =
        dbEpoch.flatMapLatest { repository.recurringPaymentsFlow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val hiddenRecurringKeys = MutableStateFlow(settingsRepo.hiddenRecurringKeys)

    /** Detected recurring payments minus the ones the user marked "not recurring". */
    val recurringPayments: StateFlow<List<RecurringPayment>?> =
        combine(detectedRecurring, hiddenRecurringKeys) { list, hidden ->
            list?.filterNot { RecurringPaymentDetector.groupKey(it.payee) in hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The ones the user hid, so they can be restored. */
    val hiddenRecurringPayments: StateFlow<List<RecurringPayment>> =
        combine(detectedRecurring, hiddenRecurringKeys) { list, hidden ->
            list.orEmpty().filter { RecurringPaymentDetector.groupKey(it.payee) in hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun hideRecurring(payee: String) {
        val updated = settingsRepo.hiddenRecurringKeys + RecurringPaymentDetector.groupKey(payee)
        settingsRepo.hiddenRecurringKeys = updated
        hiddenRecurringKeys.value = updated
    }

    fun restoreRecurring(payee: String) {
        val updated = settingsRepo.hiddenRecurringKeys - RecurringPaymentDetector.groupKey(payee)
        settingsRepo.hiddenRecurringKeys = updated
        hiddenRecurringKeys.value = updated
    }

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
        settingsRepo.addPendingDeleteIds(listOf(id))

        viewModelScope.launch(Dispatchers.IO) {
            delay(5000)
            withContext(NonCancellable) {
                if (_pendingDeleteIds.value[id] == startTime) {
                    repository.deleteTransaction(id)
                    settingsRepo.removePendingDeleteIds(listOf(id))
                    _pendingDeleteIds.update { it - id }
                }
            }
        }
    }

    fun stageBulkTransactionDelete(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val startTime = System.currentTimeMillis()
        val newDeletes = ids.associateWith { startTime }
        _pendingDeleteIds.update { it + newDeletes }
        settingsRepo.addPendingDeleteIds(ids)

        viewModelScope.launch(Dispatchers.IO) {
            delay(5000)
            withContext(NonCancellable) {
                val remaining = ids.filter { _pendingDeleteIds.value[it] == startTime }
                if (remaining.isNotEmpty()) {
                    repository.deleteTransactionsByIds(remaining)
                    settingsRepo.removePendingDeleteIds(remaining)
                    _pendingDeleteIds.update { it - remaining.toSet() }
                }
            }
        }
    }

    fun undoTransactionDelete(id: Long) {
        _pendingDeleteIds.update { it - id }
        settingsRepo.removePendingDeleteIds(listOf(id))
    }

    fun updateNecessityChunk(limit: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.necessityChunkLimit = limit
        }
        necessityChunkLimit.value = limit
    }

    fun addManualAccount(
        bankName: String,
        accountNumber: String?,
        initialBalancePaise: Long,
        isCreditCard: Boolean = false,
        creditLimitPaise: Long = 0L, // 0 = not set
        statementDay: Int = 15,
        dueDay: Int = 5
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Create the account footprint with an initial balance sync
            repository.setManualAccountBalance(bankName, accountNumber, initialBalancePaise)

            // 2. If it's a CC, configure its limits/dates and tag it in the database
            if (isCreditCard) {
                val config = CreditCardConfig(
                    accountKey = settingsRepo.accountKey(bankName, accountNumber),
                    isCreditCard = true,
                    creditLimitPaise = creditLimitPaise,
                    statementDay = statementDay,
                    dueDay = dueDay
                )
                settingsRepo.saveCreditCardConfig(bankName, accountNumber, config)
                repository.markAccountAsCreditCard(bankName, accountNumber) // Assuming you expose the DAO helper in repo
            }
        }
    }

    fun addManualExpense(
        amount: Long, payee: String, category: String, type: TransactionType,
        timestamp: Long = System.currentTimeMillis(), itemsSummary: String? = null,
        receiptImageUri: String? = null, bankName: String? = null,
        accountNumber: String? = null, linkedDebtId: Long? = null,
        excludeFromSpend: Boolean = false,
        isCreditCard: Boolean = false, // Add this new UI toggle parameter
        splitParticipants: List<com.expensetracker.offline.ui.components.SplitParticipant> = emptyList(),
        budgetId: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val budget = customBudgets.value.firstOrNull { it.id == budgetId }
            val ccConfig = if (bankName != null) settingsRepo.getCreditCardConfig(bankName, accountNumber) else null
            val isCcAccount = isCreditCard || ccConfig?.isCreditCard == true

            val entity = TransactionEntity(
                amount = amount, payee = payee, category = category, type = type,
                source = TransactionSource.MANUAL, timestamp = timestamp,
                referenceId = null, rawContent = "Manual Entry",
                itemsSummary = itemsSummary, receiptImageUri = receiptImageUri,
                linkedDebtId = linkedDebtId, bankName = bankName,
                accountNumber = accountNumber,
                accountType = if (isCcAccount) "CREDIT_CARD" else "BANK", // Pass the flag
                excludeFromSpend = excludeFromSpend || category.equals("Transfer", ignoreCase = true) ||
                    (budget?.excludeFromMonthly == true),
                budgetId = budget?.id
            )
            val newId = repository.insertTransaction(entity)
            if (newId > 0L && !excludeFromSpend) {
                repository.learnCategoryRule(payee, category)
            }
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

    fun saveMultiSplit(
        transactionId: Long,
        participants: List<com.expensetracker.offline.ui.components.SplitParticipant>,
        paidBy: String = "You"
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPayer = paidBy.trim().ifBlank { "You" }
            val isPaidByMe = normalizedPayer.equals("You", ignoreCase = true)
            val debtsToSave = participants
                .filter { p ->
                    val amt = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                    if (amt <= 0.0) false
                    else if (isPaidByMe) !p.isMe
                    else {
                        val pName = if (p.isMe) "You" else p.name.trim()
                        !pName.equals(normalizedPayer, ignoreCase = true)
                    }
                }
                .map { p ->
                    val rupees = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                    val amtPaise = round(rupees * 100.0).toLong()
                    val debtorName = if (p.isMe) "You" else p.name.trim()
                    SplitDebtEntity(
                        id = p.debtId ?: 0L,
                        transactionId = transactionId,
                        debtorName = debtorName,
                        originalAmount = amtPaise,
                        amountOwed = amtPaise
                    )
                }
            repository.saveMultiSplit(transactionId, debtsToSave, normalizedPayer)
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
            settingsRepo.reassignCategoryBudget(oldCategory, newCategory)
            com.expensetracker.offline.engine.categorizer.CategoryClassifier.clearCache()
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
            val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
            val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
            dismissedCategoryWarningLevels.value = levels
            dismissedCategoryWarnings.value = levels.keys
        }
    }

    fun renameCategory(oldCategory: String, newCategory: String) {
        val cleanOld = oldCategory.trim()
        val cleanNew = newCategory.trim()
        if (cleanOld.isEmpty() || cleanNew.isEmpty() || cleanOld.equals(cleanNew, ignoreCase = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.reassignCategory(cleanOld, cleanNew)
            settingsRepo.reassignCategoryBudget(cleanOld, cleanNew)
            com.expensetracker.offline.engine.categorizer.CategoryClassifier.clearCache()
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
            val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
            val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
            dismissedCategoryWarningLevels.value = levels
            dismissedCategoryWarnings.value = levels.keys
        }
    }

    fun onCategoryDeleted(deletedCategory: String, reassignTo: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (reassignTo != null) {
                repository.reassignCategory(deletedCategory, reassignTo)
            }
            settingsRepo.reassignCategoryBudget(deletedCategory, reassignTo)
            categoryBudgets.value = settingsRepo.getCategoryBudgets()
            val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
            val levels = settingsRepo.getDismissedCategoryWarningLevels(cycleStart)
            dismissedCategoryWarningLevels.value = levels
            dismissedCategoryWarnings.value = levels.keys
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
            // Always re-parse: the review row doesn't store accountType / transfer flags, and losing
            // them turned approved credit-card spends into BANK rows and bill payments into spend.
            val parsed = FinancialParser.parse(reviewItem.rawContent, reviewItem.sender, reviewItem.source)
                .takeIf { it.isFinancial }
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
                receiptImageUri = null,
                excludeFromSpend = (parsed?.excludeFromSpend ?: false) || category.equals("Transfer", ignoreCase = true),
                accountType = settingsRepo.resolveAccountType(bank, account, parsed?.accountType)
            )
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

            items.sortedBy { it.timestamp }.forEach { item ->
                val parsed = FinancialParser.parse(item.rawContent, item.sender, item.source)
                val amt = item.extractedPartialAmount?.takeIf { it > 0L } ?: parsed.amount ?: 0L
                val type = item.type ?: parsed.type ?: if (amt > 0L) TransactionType.DEBIT else null
                val rawPayee = item.extractedPartialPayee ?: parsed.payee
                val payee = if (!rawPayee.isNullOrBlank() && !rawPayee.equals("Unknown", ignoreCase = true)) {
                    rawPayee
                } else {
                    FinancialParser.inferFallbackPayee(item.rawContent, type, settingsRepo.fallbackPayee)
                }

                if (amt > 0L && type != null) {
                    val category = if (parsed.isTransfer) "Transfer" else repository.classifyCategory(payee, item.rawContent, amt, item.timestamp, type)
                    val bank = item.bankName ?: parsed.bankName
                    val account = item.accountNumber ?: parsed.accountNumber
                    val balance = item.balance ?: parsed.balance

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
                        referenceId = item.referenceId ?: parsed.referenceId,
                        note = parsed.note,
                        source = item.source,
                        itemsSummary = null,
                        receiptImageUri = null,
                        excludeFromSpend = parsed.excludeFromSpend,
                        accountType = settingsRepo.resolveAccountType(bank, account, parsed.accountType)
                    )

                    approvals.add(newTransaction to item.id)

                    balance?.let {
                        BalanceCache.updateIfNewer(prefs, bank, account, it, item.timestamp)
                    }
                    approved++
                } else if (!parsed.isFinancial && !item.rawContent.startsWith("Silent Deduction", ignoreCase = true)) {
                    // Automatically discard non-monetary promo/data alerts instead of leaving them stuck
                    repository.discardReviewItem(item.id)
                } else {
                    skipped++
                }
            }

            repository.bulkApproveReviewItems(approvals)

            withContext(Dispatchers.Main) { onDone(approved, skipped) }
        }
    }

    fun bulkDiscardReviewItems(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { repository.discardReviewItem(it) }
        }
    }

    fun scanCurrentCycleSms(importDirectly: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasReadSms) return@launch

            settingsRepo.skipScanReview = importDirectly
            val (cycleStart, _) = BillingCycleHelper.getCycleRange(settingsRepo.startDayOfMonth)
            val scanner = com.expensetracker.offline.engine.scanner.SmsHistoryScanner(context, app.database)
            val resp = scanner.scanSmsSince(cycleStart)
            if (resp is com.expensetracker.offline.engine.scanner.ScanResponse.Success) {
                settingsRepo.lastImportedTxnIds = resp.result.importedTransactionIds
                settingsRepo.lastImportedReviewIds = resp.result.importedReviewIds
                settingsRepo.prefs.edit().putBoolean("key_setup_scan_done", true).apply()
            }
            syncLatestBalanceFromSms()
        }
    }

    suspend fun importTransactions(
        transactions: List<TransactionEntity>,
        splitsByIndex: Map<Int, List<SplitDebtEntity>> = emptyMap()
    ): Int = withContext(Dispatchers.IO) {
        repository.insertTransactionsBulk(transactions, splitsByIndex)
    }

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
