@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.expensetracker.offline.ui.insights.MoneyHero
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import com.expensetracker.offline.ui.components.*
import androidx.core.content.edit
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.DebtStatus
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import com.expensetracker.offline.util.NecessityManager.KEY_NECESSITIES_MIGRATED
import com.expensetracker.offline.util.NecessityManager.saveNecessities
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

import com.expensetracker.offline.util.tick
import com.expensetracker.offline.util.click
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    isAppLocked: Boolean = false,
    onNavigateToReview: () -> Unit = {},
    onNavigateToSettings: (targetSection: String?) -> Unit = {},
    onNavigateToInsights: () -> Unit = {},
    onNavigateToNecessities: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val activity = context as? android.app.Activity
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val linkedAccounts by viewModel.linkedAccounts.collectAsState()
    val selectedAccount by viewModel.selectedAccount.collectAsState()

    val focusManager = LocalFocusManager.current

    var lastBackMs by remember { mutableLongStateOf(0L) }

    val transactionsWithDebts by viewModel.allTransactionsWithDebts.collectAsState()
    val unsettledSplitsWithDebts by viewModel.unsettledSplitsWithDebts.collectAsState()
    val moneyOwedToYouState = viewModel.totalMoneyOwed.collectAsState(initial = null)
    val moneyOwedToYou: Long = moneyOwedToYouState.value ?: 0L

    val pendingDeleteIds by viewModel.pendingDeleteIds.collectAsState()

    LaunchedEffect(linkedAccounts, selectedAccount) {
        val current = selectedAccount
        if (current != null &&
            current.bankName != "Unspecified" &&
            linkedAccounts.none { it.bankName == current.bankName && it.accountNumber == current.accountNumber }
        ) {
            viewModel.selectedAccount.value = null
        }
    }

    val accountBalances by viewModel.accountBalances.collectAsState()
    var showAccountsDialog by remember { mutableStateOf(false) }
    var isBalanceRevealed by remember { mutableStateOf(false) }

    var isBalanceCardFlipped by remember { mutableStateOf(prefs.getBoolean("key_balance_card_flipped", false)) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    fun showSnack(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val settingsRepo = remember { SettingsRepository(context) }
    var monthlyBudget by remember { mutableLongStateOf(settingsRepo.monthlyBudget) }
    var isBudgetExplicitlySet by remember { mutableStateOf(settingsRepo.isBudgetExplicitlySet) }

    var customCategories by remember { mutableStateOf(prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet()) }
    var isSetupDismissed by remember { mutableStateOf(prefs.getBoolean("key_setup_dismissed", false)) }

    var isSetupScanDone by remember { mutableStateOf(prefs.getBoolean("key_setup_scan_done", false)) }

    val transactions by viewModel.allTransactions.collectAsState()
    val latestBalance by viewModel.latestBalance.collectAsState()
    val pendingReviewCount by viewModel.pendingReviewCount.collectAsState()

    val chunkLimit by viewModel.necessityChunkLimit.collectAsState()
    var showNecessityBreakdownDialog by remember { mutableStateOf(false) }

    var isLoadingNecessities by remember { mutableStateOf(true) }
    var necessitiesList by remember { mutableStateOf(emptyList<NecessityItem>()) }
    LaunchedEffect(Unit) {
        necessitiesList = withContext(Dispatchers.IO) { NecessityManager.loadNecessities(prefs) }
        isLoadingNecessities = false
    }
    
    LaunchedEffect(chunkLimit, isLoadingNecessities) {
        if (!isLoadingNecessities && necessitiesList.isEmpty() && chunkLimit > 0L && !prefs.getBoolean(KEY_NECESSITIES_MIGRATED, false)) {
            val migrated = listOf(NecessityItem(java.util.UUID.randomUUID().toString(), "Fixed costs", chunkLimit))
            necessitiesList = migrated
            withContext(Dispatchers.IO) {
                saveNecessities(prefs, migrated)
                prefs.edit().putBoolean(KEY_NECESSITIES_MIGRATED, true).commit()
            }
        }
    }

    fun updateNecessities(newList: List<NecessityItem>) {
        necessitiesList = newList
        scope.launch(Dispatchers.IO) { saveNecessities(prefs, newList) }
        val totalMonthlyReserve = newList.sumOf { item ->
            if (item.frequency == NecessityFrequency.MONTHLY || item.isProrated) {
                NecessityManager.getMonthlyReserveAmount(item)
            } else 0L
        }
        viewModel.updateNecessityChunk(totalMonthlyReserve)
    }

    fun addFixedCost(suggestion: NecessityManager.BillSuggestion) {
        val item = NecessityManager.itemFrom(suggestion)
        if (necessitiesList.any { it.name.equals(item.name, ignoreCase = true) }) return
        updateNecessities(necessitiesList + item)
        showSnack("Added ${item.name} to Fixed Costs")
    }

    fun removeFixedCost(item: NecessityItem) {
        updateNecessities(necessitiesList.filter { it.id != item.id })
        showSnack("Removed ${item.name} from Fixed Costs")
    }

    fun offerFixedCost(suggestion: NecessityManager.BillSuggestion) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Add ${NecessityManager.cleanBillName(suggestion.payee)} to Fixed Costs?",
                actionLabel = "Add",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) addFixedCost(suggestion)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(250)
        debouncedSearchQuery = searchQuery
    }

    var selectedFilterCategory by remember { mutableStateOf("All") }
    var selectedTimeRange by rememberSaveable { mutableStateOf(TimeRange.THIS_MONTH) }

    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedTxnIds by remember { mutableStateOf(emptySet<Long>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    var showAddExpenseSheet by rememberSaveable { mutableStateOf(false) }
    var isProTipsDismissed by remember { mutableStateOf(prefs.getBoolean("key_pro_tips_dismissed", false)) }

    var showBankInfoDialog by remember { mutableStateOf(false) }
    var showDismissConfirmDialog by remember { mutableStateOf(false) }
    var showSettleDebtDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showStartDayDialog by remember { mutableStateOf(false) }
    var showManageCategoriesDialog by remember { mutableStateOf(false) }
    var categoryToDelete by remember { mutableStateOf<String?>(null) }
    var transactionPendingDelete by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra("OPEN_ADD_EXPENSE", false) == true) {
            showAddExpenseSheet = true
            activity.intent = activity.intent?.apply { removeExtra("OPEN_ADD_EXPENSE") }
        }
    }

    var hasCheckedRestorePrompt by rememberSaveable { mutableStateOf(false) }
    var showRestorePrompt by remember { mutableStateOf(false) }
    var hasEverLoggedExpense by remember { mutableStateOf(prefs.getBoolean("key_has_ever_logged_expense", false)) }

    LaunchedEffect(Unit) {
        if (!hasCheckedRestorePrompt) {
            withTimeoutOrNull(2000) {
                snapshotFlow { transactions to linkedAccounts }
                    .drop(1)
                    .first()
            }
            hasCheckedRestorePrompt = true
            val alreadyDismissed = prefs.getBoolean("key_restore_prompt_dismissed", false)
            if (!alreadyDismissed && transactions.isEmpty() && linkedAccounts.isEmpty()) {
                showRestorePrompt = true
            }
        }
    }

    LaunchedEffect(transactions.size) {
        if (transactions.isNotEmpty()) {
            showRestorePrompt = false
            if (!hasEverLoggedExpense) {
                hasEverLoggedExpense = true
                prefs.edit().putBoolean("key_has_ever_logged_expense", true).apply()
            }
        }
    }

    val manualSyncBalance by viewModel.manualSyncBalance.collectAsState()
    var isRefreshingBalance by remember { mutableStateOf(false) }
    var liveDialogBalance by remember { mutableStateOf<Long?>(null) }

    val effectiveBalance by remember(selectedAccount, accountBalances, latestBalance) {
        derivedStateOf {
            if (selectedAccount == null) {
                val total = accountBalances.mapNotNull { it.latestBalance }.sum()
                if (accountBalances.isNotEmpty() && total > 0L) total else latestBalance
            } else {
                accountBalances.find {
                    it.bankName == selectedAccount?.bankName && it.accountNumber == selectedAccount?.accountNumber
                }?.latestBalance ?: latestBalance
            }
        }
    }

    var pendingBalanceReveal by remember { mutableStateOf(false) }
    var isCheckingBalance by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val isFabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // Hide the FAB while scrolling down so it never sits on top of an amount; it returns on scroll up,
    // at the top of the list, or once the end is reached (the list has bottom padding to clear it).
    var isFabScrolledAway by remember { mutableStateOf(false) }
    val fabScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f) isFabScrolledAway = true
                else if (available.y > 8f) isFabScrolledAway = false
                return Offset.Zero
            }
        }
    }
    val isFabVisible by remember { derivedStateOf { !isFabScrolledAway || isFabExpanded || !listState.canScrollForward } }

    var hasNotificationPermission by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    var repaymentToUndo by remember { mutableStateOf<TransactionEntity?>(null) }

    val startDayOfMonth by viewModel.startDayOfMonth.collectAsState()

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context, activity, prefs) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshSettings()
                    hasNotificationPermission = isNotificationPermissionGranted(context)
                    isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                    hasSmsPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    isBalanceCardFlipped = prefs.getBoolean("key_balance_card_flipped", false)

                    if (activity?.intent?.getBooleanExtra("OPEN_ADD_EXPENSE", false) == true) {
                        showAddExpenseSheet = true
                        activity.intent = activity.intent?.apply { removeExtra("OPEN_ADD_EXPENSE") }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    isBalanceRevealed = false
                    showAccountsDialog = false
                    showNecessityBreakdownDialog = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentMonthName = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }
    val allAvailableCategories by produceState(
        initialValue = (CategoryClassifier.PREDEFINED_CATEGORIES + customCategories).distinct(),
        customCategories, transactions
    ) {
        value = withContext(Dispatchers.Default) {
            val standard = CategoryClassifier.PREDEFINED_CATEGORIES
            val fromTxns = transactions.map { it.category }.filter { it.isNotBlank() }
            val allDistinct = (standard + customCategories + fromTxns).distinct()

            val lastUsedMap = mutableMapOf<String, Long>()
            for (txn in transactions) {
                val cat = txn.category
                if (cat.isNotBlank()) {
                    val currentMax = lastUsedMap[cat] ?: 0L
                    if (txn.timestamp > currentMax) lastUsedMap[cat] = txn.timestamp
                }
            }
            allDistinct.sortedWith(compareByDescending<String> { lastUsedMap[it] ?: 0L }.thenBy { it.lowercase() })
        }
    }

    val timeBoundaries = remember(selectedTimeRange, startDayOfMonth) {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfWeek = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfCycle = BillingCycleHelper.getCycleRange(startDayOfMonth).first
        val startOfYear = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        listOf(startOfDay, startOfWeek, startOfCycle, startOfYear)
    }

    val filteredTransactions = remember(
        transactionsWithDebts, debouncedSearchQuery, selectedFilterCategory, selectedTimeRange, selectedAccount, timeBoundaries
    ) {
        val query = debouncedSearchQuery
        val categoryFilter = selectedFilterCategory
        val range = selectedTimeRange
        val currentAccount = selectedAccount
        val startOfDayMillis = timeBoundaries[0]
        val startOfWeekMillis = timeBoundaries[1]
        val startOfMonthMillis = timeBoundaries[2]
        val startOfYearMillis = timeBoundaries[3]

        transactionsWithDebts.filter { txnWithDebts ->
            val txn = txnWithDebts.transaction
            val matchesSearch = query.isBlank() ||
                    txn.payee.contains(query, ignoreCase = true) ||
                    txn.category.contains(query, ignoreCase = true) ||
                    (txn.itemsSummary?.contains(query, ignoreCase = true) == true) ||
                    txnWithDebts.debts.any { it.debtorName.contains(query, ignoreCase = true) }

            val matchesCategory = when (categoryFilter) {
                "All" -> true
                "Split Only" -> txnWithDebts.debts.isNotEmpty()
                else -> txn.category.equals(categoryFilter, ignoreCase = true)
            }

            val matchesTime = when (range) {
                TimeRange.TODAY -> txn.timestamp >= startOfDayMillis
                TimeRange.THIS_WEEK -> txn.timestamp >= startOfWeekMillis
                TimeRange.THIS_MONTH -> txn.timestamp >= startOfMonthMillis
                TimeRange.THIS_YEAR -> txn.timestamp >= startOfYearMillis
                TimeRange.ALL_TIME -> true
            }

            val matchesAccount = currentAccount == null ||
                    (currentAccount.bankName == "Unspecified" && txn.bankName == null) ||
                    (txn.bankName == currentAccount.bankName && txn.accountNumber == currentAccount.accountNumber)

            matchesSearch && matchesCategory && matchesTime && matchesAccount
        }
    }

    var visibleTransactionCount by rememberSaveable { mutableIntStateOf(50) }
    LaunchedEffect(debouncedSearchQuery, selectedFilterCategory, selectedTimeRange, selectedAccount) {
        visibleTransactionCount = 50
        if (isSelectionMode) { isSelectionMode = false; selectedTxnIds = emptySet() }
    }
    val visibleTransactions = remember(filteredTransactions, visibleTransactionCount) { filteredTransactions.take(visibleTransactionCount) }

    val splitLinkInfo by produceState(initialValue = SplitLinkInfo(emptyMap(), emptyMap()), transactionsWithDebts) {
        val allTxns = transactionsWithDebts
        value = withContext(Dispatchers.Default) {
            val creditsWithLinks = allTxns.map { it.transaction }.filter { it.type == TransactionType.CREDIT && it.linkedDebtId != null }
            val linkedCreditsByDebtId = creditsWithLinks.groupBy { it.linkedDebtId!! }
            val linkedDebtIdByCreditId = creditsWithLinks.associate { it.id to it.linkedDebtId!! }
            SplitLinkInfo(linkedCreditsByDebtId, linkedDebtIdByCreditId)
        }
    }

    val periodSpent by produceState(initialValue = 0L, transactionsWithDebts, selectedTimeRange, pendingDeleteIds, startDayOfMonth) {
        val allTxns = transactionsWithDebts
        val range = selectedTimeRange
        val pendingIds = pendingDeleteIds
        val cycleDay = startDayOfMonth
        value = withContext(Dispatchers.Default) {
            val start = startMillisFor(range, cycleDay)
            val end = endOfTodayMillis()
            val activeTxns = allTxns.filter { it.transaction.timestamp in start..end && it.transaction.id !in pendingIds }

            val gross = activeTxns.filter {
                it.transaction.type == TransactionType.DEBIT &&
                        !it.transaction.excludeFromSpend &&
                        it.transaction.category != "Split Settlement"
            }.sumOf { item ->
                val parentAmt = item.transaction.amount
                val debtsSum = item.debts.sumOf { it.amountOwed.toLong() }
                maxOf(0L, parentAmt - debtsSum)
            }
            val refunds = activeTxns.filter { it.transaction.type == TransactionType.CREDIT && it.transaction.linkedDebtId == null }.sumOf {
                if (it.transaction.note?.contains("refund", ignoreCase = true) == true || it.transaction.itemsSummary?.contains("refund", ignoreCase = true) == true) it.transaction.amount else 0L
            }
            (gross - refunds).coerceAtLeast(0L)
        }
    }

    val safeToSpendBreakdown by produceState(
        initialValue = SafeToSpendBreakdown(
            balance = effectiveBalance, reservedTotal = 0L, paidTowardNecessities = 0L, 
            remainingReserve = 0L, safeToSpend = effectiveBalance ?: 0L, isShortOnReserve = false, 
            hasBalanceData = effectiveBalance != null, itemStatuses = emptyList(), upcomingNextMonth = emptyList()
        ),
        effectiveBalance, transactionsWithDebts, necessitiesList, startDayOfMonth
    ) {
        value = withContext(Dispatchers.Default) {
            computeSafeToSpend(effectiveBalance, transactionsWithDebts, necessitiesList, startDayOfMonth)
        }
    }

    // Pre-build a txn.id -> necessityName map so each card does O(1) lookup, not O(N) firstOrNull per-card
    val billLabelMap by produceState(initialValue = emptyMap<Long, String>(), safeToSpendBreakdown, visibleTransactions, startDayOfMonth) {
        value = withContext(Dispatchers.Default) {
            val cycleStart = startOfCurrentMonthMillis(startDayOfMonth)
            val statuses = safeToSpendBreakdown.itemStatuses
            buildMap {
                visibleTransactions.forEach { txnWithDebts ->
                    val txn = txnWithDebts.transaction
                    if (txn.type == TransactionType.DEBIT && txn.timestamp >= cycleStart) {
                        val match = statuses.firstOrNull { NecessityManager.matches(it.item, txn.payee, txn.category) }
                        if (match != null) put(txn.id, match.item.name)
                    }
                }
            }
        }
    }

    var activeTransactionForSplitId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeTransactionForSplit = remember(activeTransactionForSplitId, transactionsWithDebts) { transactionsWithDebts.find { it.transaction.id == activeTransactionForSplitId } }

    var activeTransactionForEditId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeTransactionForEdit = remember(activeTransactionForEditId, transactionsWithDebts) { transactionsWithDebts.find { it.transaction.id == activeTransactionForEditId }?.transaction }

    var activeCreditTransactionToLinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeCreditTransactionToLink = remember(activeCreditTransactionToLinkId, transactionsWithDebts) { transactionsWithDebts.find { it.transaction.id == activeCreditTransactionToLinkId }?.transaction }

    var isAnimationTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTimeRange, isAppLocked) {
        if (!isAppLocked) {
            isAnimationTriggered = false
            delay(180)
            isAnimationTriggered = true
        } else {
            isAnimationTriggered = false
        }
    }

    val animatedSpentTotal by animateValueAsState(
        targetValue = if (isAnimationTriggered) periodSpent else 0L,
        typeConverter = LongConverter,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "spentAnimation"
    )
    val shownSpent = animatedSpentTotal

    val animatedOwedToYou by animateValueAsState(
        targetValue = if (isAnimationTriggered) moneyOwedToYou else 0L,
        typeConverter = LongConverter,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "owedAnimation"
    )
    val shownOwed = animatedOwedToYou
    LaunchedEffect(showAccountsDialog) { if (showAccountsDialog) liveDialogBalance = effectiveBalance }

    LaunchedEffect(manualSyncBalance) {
        if (manualSyncBalance != null) {
            isRefreshingBalance = false
            if (manualSyncBalance == -1L) showSnack("No balance found in recent SMS")
            else {
                liveDialogBalance = manualSyncBalance
                showSnack("Balance updated from latest SMS")
                haptic.confirm()
            }
            viewModel.resetManualSync()
        }
    }

    LaunchedEffect(pendingBalanceReveal) {
        if (pendingBalanceReveal) {
            val current = latestBalance ?: com.expensetracker.offline.util.BalanceCache.readLong(prefs, KEY_LATEST_BANK_BALANCE)
            if (current != null) {
                isBalanceRevealed = true
                pendingBalanceReveal = false
                isCheckingBalance = false
                showAccountsDialog = true
            } else {
                isCheckingBalance = true
                delay(1500)
                if (pendingBalanceReveal) {
                    isBalanceRevealed = true
                    pendingBalanceReveal = false
                    isCheckingBalance = false
                    showAccountsDialog = true
                }
            }
        }
    }

    val anyDialogOpen = showAddExpenseSheet ||
            showAccountsDialog ||
            showBudgetDialog ||
            showManageCategoriesDialog ||
            showSettleDebtDialog ||
            categoryToDelete != null ||
            transactionPendingDelete != null ||
            activeTransactionForEditId != null ||
            activeTransactionForSplitId != null ||
            activeCreditTransactionToLinkId != null

    if (isSelectionMode) {
        BackHandler {
            haptic.tap()
            isSelectionMode = false
            selectedTxnIds = emptySet()
        }
    } else if (isBalanceRevealed) {
        BackHandler {
            haptic.tap()
            isBalanceRevealed = false
        }
    } else if (!anyDialogOpen) {
        BackHandler {
            val now = System.currentTimeMillis()
            if (now - lastBackMs < 2000L) activity?.finish()
            else { lastBackMs = now; showSnack("Press back again to exit") }
        }
    }

    fun navigateToOriginalDebit(creditTxn: TransactionEntity) {
        val debtId = creditTxn.linkedDebtId
        val match = debtId?.let { id -> transactionsWithDebts.find { bill -> bill.debts.any { it.id == id } }?.transaction }

        if (match != null) {
            activeTransactionForEditId = match.id
            showSnack("Opened original bill: ${match.payee}")
        } else {
            showSnack("Original bill not found in records")
        }
    }

    fun requestBalanceUnlock() {
        val activityContext = context.findFragmentActivity()

        if (activityContext == null) {
            showSnack("Biometric verification is unavailable in this context")
            return
        }

        val biometricManager = BiometricManager.from(context)
        val allowedAuthenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        when (biometricManager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> { }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                showSnack("Set up a screen lock or fingerprint to view your balance")
                return
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                showSnack("No biometric hardware available on this device")
                return
            }
            else -> {
                showSnack("Biometric verification is currently unavailable")
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(activityContext, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                pendingBalanceReveal = true
                haptic.confirm()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    showSnack(errString.toString())
                }
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Bank Balance").setSubtitle("Confirm fingerprint or device lock to view balance").setAllowedAuthenticators(allowedAuthenticators).build()
        biometricPrompt.authenticate(promptInfo)
    }

    LaunchedEffect(listState, filteredTransactions.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                val totalItems = listState.layoutInfo.totalItemsCount
                if (last >= totalItems - 5 && visibleTransactionCount < filteredTransactions.size) {
                    visibleTransactionCount += 50
                }
            }
    }

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }

    var selectedBillImageUri by remember { mutableStateOf<String?>(null) }

    if (selectedBillImageUri != null) {
        var billBitmap by remember(selectedBillImageUri) { mutableStateOf<Bitmap?>(null) }
        var isLoadingBillImage by remember(selectedBillImageUri) { mutableStateOf(true) }

        LaunchedEffect(selectedBillImageUri) {
            val uri = selectedBillImageUri
            if (uri == null) {
                billBitmap = null
                return@LaunchedEffect
            }
            isLoadingBillImage = true
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageUtil.loadScaledBitmap(context, uri, 1024) }
                billBitmap = bitmap
            } catch (e: CancellationException) {
                billBitmap?.recycle()
                throw e
            } finally {
                isLoadingBillImage = false
            }
        }

        DisposableEffect(selectedBillImageUri) {
            onDispose {
                billBitmap?.recycle()
            }
        }

        Dialog(onDismissRequest = { selectedBillImageUri = null }) {
            Surface(modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp), shape = RoundedCornerShape(AppRadius.medium), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Receipt", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 400.dp), contentAlignment = Alignment.Center) {
                        when {
                            isLoadingBillImage -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            billBitmap != null -> Image(bitmap = billBitmap!!.asImageBitmap(), contentDescription = "Bill Photo", modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.small)), contentScale = ContentScale.Fit)
                            else -> Text("Could not load image", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { selectedBillImageUri = null }, modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp), shape = RoundedCornerShape(AppRadius.small)) { Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (showAddExpenseSheet) {
        AddExpenseSheet(
            availableCategories = allAvailableCategories,
            onDismiss = { showAddExpenseSheet = false },
            linkedAccounts = linkedAccounts,
            transactionsHistory = transactions,
            onAddToFixedCosts = { suggestion ->
                addFixedCost(suggestion)
            },
            onSave = { amount, payee, category, type, timestamp, itemsSummary, receiptUri, bank, acct ->
                viewModel.addManualExpense(
                    amount = amount, payee = payee, category = category, type = type,
                    timestamp = timestamp, itemsSummary = itemsSummary, receiptImageUri = receiptUri,
                    bankName = bank, accountNumber = acct
                )
                haptic.confirm()
                if (type == TransactionType.DEBIT &&
                    necessitiesList.none { NecessityManager.matches(it, payee, category) }
                ) {
                    val justSaved = TransactionEntity(
                        amount = amount, payee = payee, timestamp = timestamp, type = type,
                        source = TransactionSource.MANUAL, referenceId = null,
                        rawContent = "Manual Entry", category = category
                    )
                    val history = transactionsWithDebts.map { it.transaction } + justSaved
                    NecessityManager.recurringSuggestion(payee, history)?.let { offerFixedCost(it) }
                }
            }
        )
    }

    val settleList = remember(unsettledSplitsWithDebts, splitLinkInfo) {
        unsettledSplitsWithDebts.flatMap { parent ->
            parent.debts.filter { it.status == DebtStatus.OPEN && it.settledAt == null }.map { debt ->
                val repaid = splitLinkInfo.linkedCreditsByDebtId[debt.id]?.sumOf { it.amount } ?: 0L
                val outstanding = maxOf(0L, debt.amountOwed.toLong() - repaid)
                DebtInfo(
                    debt = debt,
                    parentTransaction = parent.transaction,
                    totalOwed = debt.amountOwed.toLong(),
                    repaidSoFar = repaid,
                    outstanding = if (outstanding < 0L) 0L else outstanding
                )
            }
        }.filter { it.outstanding > 0L }
    }

    if (showSettleDebtDialog) {
        SettleDebtDialog(
            activeDebts = settleList,
            onDismiss = { showSettleDebtDialog = false },
            onRepaymentLogged = { debtId, amount, isForgiveness ->
                val debt = settleList.find { it.debt.id == debtId }
                if (debt != null) {
                    if (isForgiveness) {
                        viewModel.forgiveDebt(debtId)
                        showSnack("Remaining debt forgiven")
                    } else if (amount > 0L) {
                        val cappedAmount = minOf(amount, debt.outstanding)
                        val wasCapped = amount > debt.outstanding + SETTLEMENT_EPSILON
                        viewModel.logCashRepayment(debtId, cappedAmount)
                        val capNote = if (wasCapped) " (capped to what's owed)" else ""
                        if (cappedAmount >= debt.outstanding - SETTLEMENT_EPSILON) {
                            showSnack("Debt fully settled!$capNote")
                        } else {
                            showSnack("Partial repayment of ${CurrencyFormat.withSymbol(cappedAmount, 0)} logged$capNote")
                        }
                    }
                }
                showSettleDebtDialog = false
            }
        )
    }

    activeCreditTransactionToLink?.let { creditTxn ->
        Dialog(onDismissRequest = { activeCreditTransactionToLinkId = null }) {
            Surface(modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp), shape = RoundedCornerShape(AppRadius.large), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Link Repayment", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Received ${CurrencyFormat.withSymbol(creditTxn.amount, 0)} from ${creditTxn.payee}. Select whose share this payment settled:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (settleList.isEmpty()) {
                        Text("No pending split bills available to settle.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    } else {
                        LazyColumn(modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(settleList, key = { it.debt.id }) { split ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(AppRadius.small))
                                        .quietClickable {
                                            haptic.confirm()
                                            viewModel.linkDebtToRepayment(split.debt.id, creditTxn.id)
                                            activeCreditTransactionToLinkId = null
                                            showSnack("Linked to ${split.debt.debtorName} · ${split.parentTransaction.payee}")
                                        },
                                    shape = RoundedCornerShape(AppRadius.small),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                ) {
                                    Row(modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(split.debt.debtorName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                            Text("${split.parentTransaction.payee} · Owes ${CurrencyFormat.withSymbol(split.outstanding, 0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(onClick = { activeCreditTransactionToLinkId = null }, modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp), shape = RoundedCornerShape(AppRadius.small)) { Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (showBudgetDialog) {
        SelectTargetDialog(
            currentTarget = monthlyBudget,
            onDismiss = { showBudgetDialog = false },
            onSaveTarget = { newTarget ->
                haptic.confirm()
                monthlyBudget = newTarget
                isBudgetExplicitlySet = true
                settingsRepo.monthlyBudget = newTarget
                showBudgetDialog = false
            }
        )
    }

    if (showStartDayDialog) {
        var tempDay by remember { mutableFloatStateOf(startDayOfMonth.toFloat()) }
        AppDialog(
            title = "Billing Cycle",
            onDismiss = { haptic.tap(); showStartDayDialog = false },
            confirmText = "Save",
            onConfirm = {
                haptic.confirm() // Haptic confirm is perfect here
                viewModel.updateStartDayOfMonth(tempDay.toInt()) // Route the update through ViewModel
                showStartDayDialog = false
            }
        ) {
            Text(
                text = "Select the day your monthly spending cycle resets:",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Resets on Day: ${tempDay.toInt()}",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = tempDay,
                onValueChange = { rawValue ->
                    val snapped = kotlin.math.round(rawValue)
                    if (tempDay != snapped) {
                        tempDay = snapped
                        haptic.tap() // Snappy haptic feedback as you drag
                    }
                },
                valueRange = 1f..28f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Capped at the 28th to ensure consistent cycles across short months like February.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showNecessityBreakdownDialog) {
        NecessityBreakdownDialog(
            breakdown = safeToSpendBreakdown,
            items = necessitiesList,
            onDismiss = { showNecessityBreakdownDialog = false },
            onManage = {
                showNecessityBreakdownDialog = false
                onNavigateToNecessities()
            }
        )
    }

    if (showManageCategoriesDialog) {
        ManageCategoriesDialog(
            customCategories = customCategories,
            onDismiss = { showManageCategoriesDialog = false },
            onAddCategory = { newCategory ->
                val trimmed = newCategory.trim()
                if (allAvailableCategories.any { it.equals(trimmed, ignoreCase = true) }) {
                    showSnack("Category \"$trimmed\" already exists")
                } else {
                    haptic.tap()
                    val updated = customCategories + trimmed
                    customCategories = updated
                    prefs.edit().putStringSet("key_custom_categories", updated).apply()
                    showSnack("Category \"$trimmed\" created")
                }
            },
            onDeleteCategory = { catToDelete ->
                val count = transactions.count { it.category.equals(catToDelete, ignoreCase = true) }
                if (count > 0) {
                    haptic.tap()
                    categoryToDelete = catToDelete
                } else {
                    haptic.confirm()
                    val updated = customCategories - catToDelete
                    customCategories = updated
                    prefs.edit().putStringSet("key_custom_categories", updated).apply()
                    if (selectedFilterCategory == catToDelete) selectedFilterCategory = "All"
                    showSnack("Category deleted")
                }
            }
        )
    }

    if (categoryToDelete != null) {
        val count = transactions.count { it.category.equals(categoryToDelete, ignoreCase = true) }
        DeleteCategoryDialog(
            categoryToDelete = categoryToDelete!!,
            transactionCount = count,
            availableCategories = allAvailableCategories,
            onDismiss = { categoryToDelete = null },
            onConfirm = { reassignTarget ->
                haptic.delete()
                val updated = customCategories - categoryToDelete!!
                customCategories = updated
                prefs.edit().putStringSet("key_custom_categories", updated).apply()
                if (selectedFilterCategory == categoryToDelete) selectedFilterCategory = "All"

                if (reassignTarget != null) {
                    viewModel.reassignCategory(oldCategory = categoryToDelete!!, newCategory = reassignTarget)
                }
                showSnack("Category removed")
                categoryToDelete = null
            }
        )
    }

    if (showAccountsDialog) {
        AccountsDialog(
            accountBalances = accountBalances,
            selectedAccount = selectedAccount,
            contextLabel = selectedAccount?.bankName ?: "All Accounts",
            contextBalance = effectiveBalance,
            safeToSpendBreakdown = safeToSpendBreakdown,
            onAccountSelected = { selected ->
                viewModel.selectedAccount.value = selected
            },
            onRefresh = {
                haptic.tap()
                viewModel.syncLatestBalanceFromSms()
                showSnack("Syncing latest SMS...")
            },
            onManageNecessities = {
                showAccountsDialog = false
                isBalanceRevealed = false
                isBalanceCardFlipped = prefs.getBoolean("key_balance_card_flipped", false) // <-- ADDED
                onNavigateToNecessities()
            },
            onDismiss = {
                showAccountsDialog = false
                isBalanceRevealed = false
                isBalanceCardFlipped = prefs.getBoolean("key_balance_card_flipped", false) // <-- ADDED
            }
        )
    }

    if (showBulkDeleteConfirm) {
        AppDialog(
            title = "Delete ${selectedTxnIds.size} transactions?",
            onDismiss = { haptic.tap(); showBulkDeleteConfirm = false },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                haptic.delete()
                viewModel.stageBulkTransactionDelete(selectedTxnIds)
                isSelectionMode = false
                selectedTxnIds = emptySet()
                showBulkDeleteConfirm = false
            }
        ) {
            Text("This will permanently remove the selected transactions. This action cannot be undone.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }

    if (showBankInfoDialog) {
        AppDialog(
            title = "Auto-detect Banks",
            onDismiss = { haptic.tap(); showBankInfoDialog = false },
            dismissText = "Later",
            confirmText = "Settings",
            onConfirm = {
                haptic.confirm()
                showBankInfoDialog = false
                onNavigateToSettings(null)
            }
        ) {
            Text("Expense Tracker automatically detects and links your bank accounts as new payment alerts arrive.\n\nWant to see them right now? Head to Settings and run an Inbox Scan to pull in your existing accounts.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }

    // Dialogs live here (not inside a LazyColumn item) so scrolling never disposes them.
    repaymentToUndo?.let { txn ->
        AppDialog(
            title = "Undo this repayment?",
            onDismiss = { repaymentToUndo = null },
            confirmText = "Undo",
            onConfirm = {
                haptic.confirm()
                viewModel.undoRepayment(txn)
                showSnack("Repayment undone")
                repaymentToUndo = null
            }
        ) {
            Text(
                "${CurrencyFormat.withSymbol(txn.amount, 0)} from ${txn.payee} will be marked as owed again.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    if (showRestorePrompt) {
        AppDialog(
            title = "Restore from backup?",
            onDismiss = {
                haptic.tap()
                showRestorePrompt = false
                prefs.edit().putBoolean("key_restore_prompt_dismissed", true).apply()
            },
            dismissText = "Not now",
            confirmText = "Restore",
            onConfirm = {
                haptic.confirm()
                showRestorePrompt = false
                prefs.edit().putBoolean("key_restore_prompt_dismissed", true).apply()
                onNavigateToSettings("Backup")
            }
        ) {
            Text(
                "If you've used Expense Tracker before, you can restore your transactions, categories, and budget from a previous backup. You'll be taken to Settings to select your backup file.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    data class DayGroup(val label: String, val rows: List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>, val daySpentPaise: Long)

    val grouped by produceState(initialValue = emptyList<DayGroup>(), visibleTransactions, pendingDeleteIds) {
        value = withContext(Dispatchers.Default) {
            val order = linkedMapOf<String, MutableList<com.expensetracker.offline.data.local.dao.TransactionWithDebts>>()
            visibleTransactions.forEach { txn ->
                order.getOrPut(dayLabel(txn.transaction.timestamp)) { mutableListOf() }.add(txn)
            }
            order.map { (label, rows) ->
                val daySpent = rows
                    .filter {
                        it.transaction.type == TransactionType.DEBIT &&
                                !it.transaction.excludeFromSpend &&
                                it.transaction.category != "Split Settlement" &&
                                it.transaction.id !in pendingDeleteIds
                    }
                    .sumOf { r -> (r.transaction.amount - r.debts.sumOf { d -> d.amountOwed.toLong() }).coerceAtLeast(0L) }
                DayGroup(label, rows, daySpent)
            }
        }
    }

    if (transactionPendingDelete != null) {
        val txnId = transactionPendingDelete!!
        val txn = transactions.find { it.id == txnId }
        AppDialog(
            title = "Delete transaction?",
            onDismiss = { haptic.tap(); transactionPendingDelete = null },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                haptic.delete()
                transactionPendingDelete = null
                activeTransactionForEditId = null
                viewModel.stageTransactionDelete(txnId)
            }
        ) {
            Text(
                if (txn != null) "This will permanently remove \"${txn.payee}\" (${CurrencyFormat.withSymbol(txn.amount, 2)}). This can't be undone."
                else "This will permanently remove this transaction. This can't be undone.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
            )
        }
    }

    activeTransactionForEdit?.let { txn ->
        val editCategories = remember(allAvailableCategories, txn.category) {
            val list = allAvailableCategories.toMutableList()
            list.remove(txn.category)
            listOf(txn.category) + list
        }

        val matchedBill = remember(txn, necessitiesList) {
            necessitiesList.firstOrNull { NecessityManager.matches(it, txn.payee, txn.category) }
        }
        val countedBill = matchedBill?.takeIf { bill ->
            txn.type == TransactionType.DEBIT &&
                    txn.timestamp >= startOfCurrentMonthMillis(startDayOfMonth) &&
                    safeToSpendBreakdown.itemStatuses.any { it.item.id == bill.id }
        }
        val billSuggestion = remember(txn, matchedBill, transactionsWithDebts) {
            if (matchedBill != null || txn.type != TransactionType.DEBIT) null
            else NecessityManager.suggestionFor(txn, transactionsWithDebts.map { it.transaction })
        }

        val activeCreditSuggestion = remember(txn, settleList) {
            if (txn.type == TransactionType.CREDIT && txn.linkedDebtId == null) {
                settleList.firstOrNull {
                    val debtor = it.debt.debtorName.trim().lowercase()
                    val payee = txn.payee.trim().lowercase()
                    // Fast fuzzy match: Did UPI Nidhi send money? Does Nidhi owe us?
                    debtor.isNotEmpty() && payee.isNotEmpty() && (payee.contains(debtor) || debtor.contains(payee))
                }
            } else null
        }

        EditTransactionSheet(
            transaction = txn,
            fixedCostLabel = countedBill?.name,
            fixedCostSuggestion = billSuggestion,
            suggestedSettlementName = activeCreditSuggestion?.debt?.debtorName,
            suggestedSettlementAmount = activeCreditSuggestion?.outstanding,
            onSettleSuggested = if (activeCreditSuggestion != null) {
                {
                    viewModel.linkDebtToRepayment(activeCreditSuggestion.debt.id, txn.id)
                    showSnack("Linked repayment to ${activeCreditSuggestion.debt.debtorName}")
                }
            } else null,
            onAddToFixedCosts = { billSuggestion?.let { addFixedCost(it) } },
            onRemoveFromFixedCosts = { matchedBill?.let { removeFixedCost(it) } },
            totalOwedByOthers = transactionsWithDebts.find { it.transaction.id == txn.id }?.debts?.sumOf { it.amountOwed.toLong() } ?: 0L,
            availableCategories = editCategories,
            onDismiss = { activeTransactionForEditId = null },
            onSave = { updatedTxn ->
                viewModel.updateTransaction(updatedTxn, learnRule = true)
                haptic.confirm()
                activeTransactionForEditId = null
            },
            linkedAccounts = linkedAccounts,
            onDelete = { id ->
                haptic.confirm()
                activeTransactionForEditId = null
                viewModel.stageTransactionDelete(id)
            },
            onDuplicate = { txnToDuplicate ->
                haptic.confirm()
                activeTransactionForEditId = null
                val newTxn = txnToDuplicate.copy(
                    id = 0,
                    timestamp = System.currentTimeMillis()
                )
                viewModel.addTransaction(newTxn)
                showSnack("Transaction duplicated")
            },
            onUpdateAllPast = { oldPayee, newPayee, newCategory ->
                viewModel.updatePayeeAndCategoryForAll(oldPayee, newPayee, newCategory)
            },
            onUndoRepayment = { id ->
                viewModel.undoRepayment(txn)
                showSnack("Repayment undone")
            },
            onSplit = {
                activeTransactionForEditId = null
                activeTransactionForSplitId = txn.id
            },
            onLinkToBill = {
                activeTransactionForEditId = null
                activeCreditTransactionToLinkId = txn.id
            }
        )
    }

    activeTransactionForSplit?.let { bill ->

        // 1. Context-Aware: People you frequently split with at THIS specific merchant
        val contextualSplitPartners = remember(bill, transactionsWithDebts) {
            val targetPayee = bill.transaction.payee.trim()
            transactionsWithDebts
                .filter { it.transaction.payee.equals(targetPayee, ignoreCase = true) }
                .flatMap { it.debts }
                .map { it.debtorName.trim() }
                .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                .groupingBy { it }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }
        }

        // 2. Fallback: Global frequently split partners across all merchants
        val globalSplitPartners = remember(bill, transactionsWithDebts) {
            transactionsWithDebts
                .flatMap { it.debts }
                .map { it.debtorName.trim() }
                .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                .groupingBy { it }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }
        }

        MultiSplitShareSheet(
            transaction = bill.transaction,
            initialDebts = bill.debts,
            onDismiss = { activeTransactionForSplitId = null },
            onSaveSplit = { participants ->
                viewModel.saveMultiSplit(bill.transaction.id, participants)
                haptic.confirm()
            },
            onResetSplit = { viewModel.resetSplit(bill.transaction.id) },
            contextualNames = contextualSplitPartners, // NEW
            recentNames = globalSplitPartners          // NEW
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                val hasAction = data.visuals.actionLabel != null
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(bottom = 24.dp).padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = if (hasAction) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (hasAction) Icons.Default.Info else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (hasAction) MaterialTheme.colorScheme.inversePrimary else AppColors.positive,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = data.visuals.message,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (hasAction) {
                            Text(
                                text = data.visuals.actionLabel!!.uppercase(),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.inversePrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .quietClickable { data.performAction() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        topBar = {
            // Hairline divider fades in once content scrolls under the header (Vercel-style)
            val isScrolled by remember {
                derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 }
            }
            val dividerAlpha by animateFloatAsState(if (isScrolled) 1f else 0f, tween(180), label = "headerDivider")
            val captureHealthy = hasNotificationPermission && isIgnoringBatteryOptimizations
            val statusColor = if (captureHealthy) AppColors.positive else AppColors.warning

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Crossfade(targetState = isSelectionMode, animationSpec = tween(150), label = "headerMode") { selecting ->
                    if (selecting) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                haptic.tap()
                                isSelectionMode = false
                                selectedTxnIds = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                            }
                            Text(
                                "${selectedTxnIds.size} selected",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            IconButton(onClick = {
                                if (selectedTxnIds.isNotEmpty()) {
                                    haptic.tap()
                                    showBulkDeleteConfirm = true
                                }
                            }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete selected",
                                    tint = if (selectedTxnIds.isNotEmpty()) AppColors.negative else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        DashboardHeader(
                            monthLabel = currentMonthName,
                            cycleLabel = BillingCycleHelper.getCycleLabel(startDayOfMonth),
                            statusColor = statusColor,
                            searchActive = searchOpen || searchQuery.isNotEmpty(),
                            showSettingsBadge = !isProTipsDismissed,
                            onCycleClick = { haptic.tap(); showStartDayDialog = true },
                            onSearch = {
                                haptic.tap()
                                searchOpen = !searchOpen
                                if (!searchOpen) { searchQuery = ""; debouncedSearchQuery = "" }
                            },
                            onAnalytics = { haptic.tap(); onNavigateToInsights() },
                            onSettings = { haptic.tap(); onNavigateToSettings(null) }
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f * dividerAlpha)
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = isFabVisible,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                ExtendedFloatingActionButton(
                    onClick = { haptic.tap(); showAddExpenseSheet = true },
                    expanded = isFabExpanded,
                    containerColor = MaterialTheme.colorScheme.onSurface, // Stark black/dark gray
                    contentColor = MaterialTheme.colorScheme.surface,     // Pure white
                    shape = CircleShape,                                  // Forces a perfect pill shape
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    icon = { Icon(Icons.Default.Add, contentDescription = if (isFabExpanded) null else "Add expense", modifier = Modifier.size(20.dp)) },
                    text = { Text("Add expense", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(fabScrollConnection)
                .padding(padding)
                .padding(horizontal = AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            item {
                Column {
                    val isSetupBudgetDone = isBudgetExplicitlySet
                    val isSetupAutoDone = hasNotificationPermission
                    val isSetupFirstTxnDone = hasEverLoggedExpense

                    val completedSteps = listOf(
                        isSetupBudgetDone,
                        isSetupAutoDone,
                        isSetupFirstTxnDone
                    ).count { it }

                    val needsHealthFix = !hasNotificationPermission || !isIgnoringBatteryOptimizations

                    if (needsHealthFix && isSetupDismissed) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.medium))
                                .quietClickable {
                                    haptic.tap()
                                    onNavigateToSettings("Health")
                                },
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Action Required",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        if (!hasNotificationPermission) "Notification access is disabled. Live capture will fail."
                                        else "Battery restrictions are active. The OS may kill the app in the background.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (!hasSmsPermission) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .clip(RoundedCornerShape(AppRadius.medium))
                                .quietClickable {
                                    haptic.tap()
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                },
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = AppColors.warningContainer,
                            border = BorderStroke(1.dp, AppColors.warning.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = AppColors.warning,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Please allow SMS access",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = AppColors.warning
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "1. Tap this box to open Settings.\n2. Tap 'Permissions' and allow SMS.",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 20.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "If the setting is blocked or grayed out:",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Look for three dots (⋮) at the top right of the screen, tap them, and choose 'Allow restricted settings' first.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !isSetupDismissed && completedSteps < 3,
                        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                    ) {
                        val tasks = remember(
                            isSetupBudgetDone,
                            isSetupAutoDone,
                            isSetupFirstTxnDone
                        ) {
                            listOf(
                                OnboardingTask(
                                    icon = Icons.Default.Savings,
                                    title = "Set monthly budget",
                                    isDone = isSetupBudgetDone,
                                    onClick = { showBudgetDialog = true }
                                ),
                                OnboardingTask(
                                    icon = Icons.Default.FlashOn,
                                    title = "Enable auto-capture",
                                    isDone = isSetupAutoDone,
                                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                ),
                                OnboardingTask(
                                    icon = Icons.Default.Add,
                                    title = "Record your first expense",
                                    subtitle = "Just make a normal UPI/bank payment, or tap + to log cash.",
                                    isDone = isSetupFirstTxnDone,
                                    onClick = { haptic.tap(); showAddExpenseSheet = true }
                                ),
                            )
                        }

                        val progress by animateFloatAsState(
                            targetValue = completedSteps / tasks.size.toFloat(),
                            label = "setupProgress"
                        )

                        Column {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(AppRadius.large),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Icon(
                                                    Icons.Default.Settings,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(8.dp).size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    "Setup Guide",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    "$completedSteps of ${tasks.size} done",
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                haptic.tap()
                                                if (completedSteps < tasks.size) {
                                                    showDismissConfirmDialog = true
                                                } else {
                                                    isSetupDismissed = true
                                                    prefs.edit().putBoolean("key_setup_dismissed", true).apply()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Dismiss setup guide",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    val onboardingTargetProgress = (completedSteps / tasks.size.toFloat()).coerceIn(0f, 1f)
                                    val animatedOnboardingProgress by animateFloatAsState(
                                        targetValue = if (isAnimationTriggered) onboardingTargetProgress else 0f,
                                        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                                        label = "onboardingProgress"
                                    )

                                    LinearProgressIndicator(
                                        progress = { animatedOnboardingProgress },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(AppRadius.pill)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        strokeCap = StrokeCap.Round
                                    )

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val nextTask = tasks.firstOrNull { !it.isDone }
                                        tasks.forEach { task ->
                                            OnboardingTaskItem(
                                                icon = task.icon,
                                                title = task.title,
                                                subtitle = task.subtitle,
                                                isDone = task.isDone,
                                                onClick = task.onClick,
                                                isNext = task === nextTask
                                            )
                                        }
                                    }
                                }
                                if (showDismissConfirmDialog) {
                                    AppDialog(
                                        title = "Dismiss setup guide?",
                                        onDismiss = {
                                            haptic.tap(); showDismissConfirmDialog = false
                                        },
                                        dismissText = "Keep it",
                                        confirmText = "Dismiss",
                                        onConfirm = {
                                            haptic.confirm()
                                            showDismissConfirmDialog = false
                                            isSetupDismissed = true
                                            prefs.edit().putBoolean("key_setup_dismissed", true).apply()
                                        }
                                    ) {
                                        Text(
                                            "You still have ${tasks.size - completedSteps} step${if (tasks.size - completedSteps == 1) "" else "s"} left. You can always finish these later from Settings.",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                        ) {
                            TimeRange.entries.forEach { range ->
                                val isSelected = selectedTimeRange == range
                                val tabColor by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                    animationSpec = tween(200),
                                    label = "timeRangeTab"
                                )
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .quietSelectable(
                                            selected = isSelected,
                                            role = Role.Tab,
                                            onClick = {
                                                if (!isSelected) haptic.tap()
                                                selectedTimeRange = range
                                            }
                                        ),
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = tabColor,
                                    shadowElevation = if (isSelected) 1.dp else 0.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = range.tabLabel,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val isMonthlyView = selectedTimeRange == TimeRange.THIS_MONTH
                    val monthlyBudgetPaise = monthlyBudget * 100L
                    val budgetProgress = if (monthlyBudgetPaise > 0L && isMonthlyView) (periodSpent.toFloat() / monthlyBudgetPaise.toFloat()).coerceIn(0f, 1f) else 0f
                    val isOverBudget = monthlyBudgetPaise > 0L && isMonthlyView && periodSpent > monthlyBudgetPaise
                    val animatedBudgetProgress by animateFloatAsState(
                        targetValue = if (isAnimationTriggered) budgetProgress else 0f,
                        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                        label = "progressAnimation"
                    )

                    val usedPct = if (monthlyBudgetPaise > 0L) ((periodSpent.toDouble() / monthlyBudgetPaise.toDouble()) * 100).toInt() else 0
                    val barColor = when {
                        isOverBudget -> AppColors.negative
                        budgetProgress >= 0.85f -> AppColors.warning
                        else -> MaterialTheme.colorScheme.primary
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.large))
                            .quietClickable { haptic.tap(); showBudgetDialog = true },
                        shape = RoundedCornerShape(AppRadius.large),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(AppSpacing.cardPadding)
                                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Total spent ${selectedTimeRange.spendLabel}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (isMonthlyView) {
                                    Surface(
                                        shape = RoundedCornerShape(AppRadius.pill),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppRadius.pill))
                                            .quietClickable {
                                                haptic.tap()
                                                onNavigateToSettings("Cycle")
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Day $startDayOfMonth",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            MoneyHero(amount = shownSpent, fractionDigits = 2, size = 44.sp)

                            if (monthlyBudget > 0 && isMonthlyView) {
                                val (cycleStart, cycleEnd) = remember(startDayOfMonth) { BillingCycleHelper.getCycleRange(startDayOfMonth) }
                                val daysLeft = remember(cycleEnd) {
                                    (ceil((cycleEnd - System.currentTimeMillis()).toDouble() / 86_400_000.0).toInt()).coerceAtLeast(1)
                                }
                                val remaining = (monthlyBudgetPaise - periodSpent).coerceAtLeast(0L)

                                Spacer(modifier = Modifier.height(24.dp))
                                LinearProgressIndicator(
                                    progress = { animatedBudgetProgress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(AppRadius.pill)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    strokeCap = StrokeCap.Round
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Budget: ${CurrencyFormat.withSymbol(monthlyBudgetPaise, 0)} · $usedPct% used",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (!isOverBudget && daysLeft > 0) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "≈ ${CurrencyFormat.withSymbol(remaining / daysLeft, 0)} / day left",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        if (isOverBudget) "${CurrencyFormat.withSymbol(periodSpent - monthlyBudgetPaise, 0)} exceeded"
                                        else "${CurrencyFormat.withSymbol(remaining, 0)} left",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isOverBudget) AppColors.negative else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else if (monthlyBudget <= 0 && transactions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(AppRadius.small),
                                    color = Color.Transparent,
                                    // Premium dashed border look for empty states
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Set a monthly budget",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (necessitiesList.isNotEmpty() || moneyOwedToYou > 0L || linkedAccounts.isNotEmpty() || accountBalances.isNotEmpty() || latestBalance != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val hasOwed = moneyOwedToYou > 0L

                    SafeToSpendCard(
                        modifier = if (hasOwed) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxWidth().fillMaxHeight(),
                        breakdown = safeToSpendBreakdown,
                        revealed = isBalanceRevealed,
                        isFlipped = isBalanceCardFlipped,
                        checking = isCheckingBalance,
                        onClick = {
                            haptic.tap()
                            if (isBalanceRevealed) showAccountsDialog = true else requestBalanceUnlock()
                        }
                    )

                    if (hasOwed) {
                        QuickStatCard(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            icon = Icons.Default.Handshake,
                            label = "Owed to you",
                            onClick = { haptic.tap(); showSettleDebtDialog = true }
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("₹", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.positive, modifier = Modifier.padding(bottom = 2.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(CurrencyFormat.amount(shownOwed, 0), fontWeight = FontWeight.Bold, fontSize = 24.sp, color = AppColors.positive)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tap to settle", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            }

            if (pendingReviewCount > 0) {
                item(key = "review_banner") {
                    Surface(
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.medium))
                            .quietClickable { haptic.tap(); onNavigateToReview() },
                        shape = RoundedCornerShape(AppRadius.medium),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.RateReview, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text("$pendingReviewCount to review", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Confirm payee or amount", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Replace the primaryContainer Surface with this:
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = MaterialTheme.colorScheme.onSurface // Stark neutral background
                            ) {
                                Text(
                                    "Review →",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.surface, // Pure white text
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                // No spacedBy here: its gap would vanish in one frame when the search field leaves
                // composition (the jerk). The gap lives inside AnimatedVisibility and animates with it.
                Column {
                    AnimatedVisibility(visible = searchOpen || searchQuery.isNotEmpty()) {
                        Column {
                            Surface(
                                shape = RoundedCornerShape(AppRadius.small),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            "Search payee, category, notes…",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = {
                                                searchQuery = ""; haptic.tap()
                                            }) {
                                                Icon(
                                                    Icons.Default.Clear,
                                                    contentDescription = "Clear",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Search
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSearch = { focusManager.clearFocus() }
                                    ),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PremiumFilterChip(text = "All", isSelected = selectedFilterCategory == "All", onClick = { haptic.tap(); selectedFilterCategory = "All" })
                        PremiumFilterChip(text = "Split only", isSelected = selectedFilterCategory == "Split Only", onClick = { haptic.tap(); selectedFilterCategory = "Split Only" })
                        allAvailableCategories.forEach { category ->
                            PremiumFilterChip(text = category, isSelected = selectedFilterCategory == category, onClick = { haptic.tap(); selectedFilterCategory = category })
                        }
                        PremiumFilterChip(text = "+ Manage", isSelected = false, onClick = { haptic.tap(); showManageCategoriesDialog = true })
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (linkedAccounts.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .quietClickable { haptic.tap(); showBankInfoDialog = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Auto-detect Banks",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else if (linkedAccounts.size > 1) {
                            PremiumFilterChip(
                                text = "All Accounts",
                                isSelected = selectedAccount == null,
                                onClick = { haptic.tap(); viewModel.selectedAccount.value = null }
                            )

                            val hasUnspecified = transactions.any { it.bankName == null }
                            if (hasUnspecified) {
                                val isUnspecifiedSelected = selectedAccount?.bankName == "Unspecified"
                                PremiumFilterChip(
                                    text = "Unspecified",
                                    isSelected = isUnspecifiedSelected,
                                    onClick = {
                                        haptic.tap(); viewModel.selectedAccount.value = AccountInfo("Unspecified", null)
                                    }
                                )
                            }

                            linkedAccounts.forEach { account ->
                                val label = if (account.accountNumber != null) "${account.bankName} (..${account.accountNumber})" else account.bankName
                                PremiumFilterChip(
                                    text = label,
                                    isSelected = selectedAccount == account && account.bankName != "Unspecified",
                                    onClick = { haptic.tap(); viewModel.selectedAccount.value = account }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Transactions", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    val visibleCount = filteredTransactions.count { it.transaction.id !in pendingDeleteIds }
                    Text("$visibleCount ${if (visibleCount == 1) "entry" else "entries"}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (filteredTransactions.isEmpty()) {
                item {
                    AnimatedVisibility(visible = true, enter = fadeIn(animationSpec = tween(500))) {
                        Surface(modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp), shape = RoundedCornerShape(AppRadius.medium), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val isFiltering = transactions.isNotEmpty() &&
                                        (searchQuery.isNotBlank() || selectedFilterCategory != "All" ||
                                                selectedTimeRange != TimeRange.ALL_TIME || selectedAccount != null)

                                if (isFiltering) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                                    Text(
                                        "Try a different search or clear your filters.",
                                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        text = "✨", // Playful emoji instead of a gray icon
                                        fontSize = 48.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        "All clean! Add your first expense\nto get started.",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                if (!isFiltering) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        // FIX: Pass "SMS" to trigger the scrolling and highlighting
                                        onClick = { haptic.tap(); onNavigateToSettings("SMS") },
                                        shape = RoundedCornerShape(AppRadius.pill),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onSurface,
                                            contentColor = MaterialTheme.colorScheme.surface
                                        ),
                                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                                    ) {
                                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Import Past SMS", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                }

                                if (isFiltering) {
                                    TextButton(onClick = {
                                        haptic.tap()
                                        searchQuery = ""; debouncedSearchQuery = ""; searchOpen = false
                                        selectedFilterCategory = "All"
                                        selectedTimeRange = TimeRange.ALL_TIME
                                        viewModel.selectedAccount.value = null
                                    }) { Text("Clear filters") }
                                }
                            }
                        }
                    }
                }
            } else {
                grouped.forEach { dayGroup ->
                    val label = dayGroup.label
                    val rows = dayGroup.rows
                    val daySpent = dayGroup.daySpentPaise
                    stickyHeader(key = "hdr_$label", contentType = "day_header") {
                        DayHeader(label, if (daySpent > 0) "Spent ${CurrencyFormat.smart(daySpent)}" else null)
                    }
                    // One lazy item per day: the rows share a single rounded surface separated by hairlines.
                    item(key = "day_$label", contentType = "day_group") {
                        TransactionGroup(modifier = Modifier.animateItem()) {
                            rows.forEachIndexed { index, bill ->
                                key(bill.transaction.id) {
                                    if (index > 0) TransactionRowDivider()

                                    val txn = bill.transaction
                                    val deleteStartTime = pendingDeleteIds[txn.id]

                                    if (deleteStartTime != null) {
                                        TransactionUndoBar(
                                            label = "\"${txn.payee}\" deleted",
                                            startTimeMs = deleteStartTime,
                                            modifier = Modifier,
                                            onUndo = { viewModel.undoTransactionDelete(txn.id) }
                                        )
                                    } else {
                                        val (isSettled, settledBy, outstandingAmount) = remember(bill, splitLinkInfo) {
                                            when (txn.type) {
                                                TransactionType.DEBIT -> {
                                                    val owed = outstandingOwed(bill, splitLinkInfo)
                                                    val settled = bill.debts.isNotEmpty() && owed <= 0L
                                                    Triple(settled, if (settled) settledByLabel(bill) else null, owed)
                                                }
                                                TransactionType.CREDIT -> Triple(isRepaymentCredit(txn), null, null)
                                                else -> Triple(false, null, null)
                                            }
                                        }

                                        val billLabel = billLabelMap[txn.id]

                                        val isSelected = selectedTxnIds.contains(txn.id)

                                        DashboardTransactionCard(
                                            grouped = true,
                                            transaction = txn,
                                            debts = bill.debts,
                                            isSettled = isSettled,
                                            settledBy = settledBy,
                                            outstandingAmount = outstandingAmount,
                                            selectionMode = isSelectionMode,
                                            showBankBadge = linkedAccounts.size > 1,
                                            isSelected = isSelected,
                                            billLabel = billLabel,
                                            onCardClick = {
                                                if (isSelectionMode) {
                                                    haptic.tap()
                                                    selectedTxnIds = if (isSelected) selectedTxnIds - txn.id else selectedTxnIds + txn.id
                                                    if (selectedTxnIds.isEmpty()) isSelectionMode = false
                                                } else {
                                                    haptic.tap()
                                                    activeTransactionForEditId = txn.id
                                                }
                                            },
                                            onLongClick = {
                                                if (!isSelectionMode) {
                                                    haptic.confirm()
                                                    isSelectionMode = true
                                                    selectedTxnIds = setOf(txn.id)
                                                }
                                            },
                                            onNavigateToOriginalBill = { haptic.tap(); navigateToOriginalDebit(txn) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

