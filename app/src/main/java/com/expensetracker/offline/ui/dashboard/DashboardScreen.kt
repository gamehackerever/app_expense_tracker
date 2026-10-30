@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.dashboard

import com.expensetracker.offline.ui.components.EmptyState
import com.expensetracker.offline.util.tap
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.ui.graphics.graphicsLayer
import com.expensetracker.offline.ui.insights.MoneyHero
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
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

import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete
import com.expensetracker.offline.ui.viewmodel.splitRolloverOverspend
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.offline.ui.components.BillingCycleDialog

@Composable
private fun RolloverOptionCard(
    title: String,
    description: String,
    outcome: String,
    outcomeDetail: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        tween(160), label = "rolloverBorder"
    )
    val fillColor by animateColorAsState(
// was: alpha = 0.04f
        if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent,
        tween(160), label = "rolloverFill"
    )
    val shape = RoundedCornerShape(AppRadius.medium)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .quietSelectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = fillColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.onSurface,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    ),
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    description,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    outcome,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    outcomeDetail,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

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

    val linkedAccounts by viewModel.linkedAccounts.collectAsStateWithLifecycle()
    val selectedAccount by viewModel.selectedAccount.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    var lastBackMs by remember { mutableLongStateOf(0L) }

    val transactionsWithDebts by viewModel.allTransactionsWithDebts.collectAsStateWithLifecycle()
    val unsettledSplitsWithDebts by viewModel.unsettledSplitsWithDebts.collectAsStateWithLifecycle()
    val moneyOwedToYouState by viewModel.totalMoneyOwed.collectAsStateWithLifecycle()
    val moneyOwedToYou: Long = moneyOwedToYouState ?: 0L
    val moneyYouOweState by viewModel.totalMoneyYouOwe.collectAsStateWithLifecycle()
    val moneyYouOwe: Long = moneyYouOweState ?: 0L

    val pendingDeleteIds by viewModel.pendingDeleteIds.collectAsStateWithLifecycle()

    LaunchedEffect(linkedAccounts, selectedAccount) {
        val current = selectedAccount
        if (current != null &&
            current.bankName != "Unspecified" &&
            linkedAccounts.none { it.bankName == current.bankName && it.accountNumber == current.accountNumber }
        ) {
            viewModel.selectedAccount.value = null
        }
    }

    val accountBalances by viewModel.accountBalances.collectAsStateWithLifecycle()
    var showAccountsDialog by remember { mutableStateOf(false) }
    var isBalanceRevealed by remember { mutableStateOf(false) }

    var isBalanceCardFlipped by remember { mutableStateOf(prefs.getBoolean("key_balance_card_flipped", false)) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val currentCycleRolloverDecision by viewModel.currentCycleRolloverDecision.collectAsStateWithLifecycle()

    fun showSnack(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val settingsRepo = remember { SettingsRepository(context) }
    val monthlyBudget by viewModel.monthlyBudget.collectAsStateWithLifecycle()
    val activeRolloverAmountPaise by viewModel.activeRolloverAmount.collectAsStateWithLifecycle()
    val incomingRolloverOriginalPaise by viewModel.incomingRolloverOriginal.collectAsStateWithLifecycle()
    val incomingRolloverMovedPaise by viewModel.incomingRolloverMoved.collectAsStateWithLifecycle()
    var showCarryOverAdjust by rememberSaveable { mutableStateOf(false) }
    val isBudgetExplicitlySet by viewModel.isBudgetExplicitlySet.collectAsStateWithLifecycle()
    val isManualModePreferred by viewModel.isManualModePreferred.collectAsStateWithLifecycle()
    val isAutoCaptureBannerDismissed by viewModel.isAutoCaptureBannerDismissed.collectAsStateWithLifecycle()
    var showAutoCaptureSheet by rememberSaveable { mutableStateOf(false) }
    var showDiscardAllReviewsDialog by remember { mutableStateOf(false) }

    var customCategories by remember { mutableStateOf(prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet()) }
    var isSetupDismissed by remember { mutableStateOf(prefs.getBoolean("key_setup_dismissed", false)) }

    var isSetupScanDone by remember { mutableStateOf(prefs.getBoolean("key_setup_scan_done", false)) }

    val transactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val latestBalance by viewModel.latestBalance.collectAsStateWithLifecycle()
    val pendingReviewCount by viewModel.pendingReviewCount.collectAsStateWithLifecycle()
    val pendingReviewItems by viewModel.pendingReviewItems.collectAsStateWithLifecycle()

    val chunkLimit by viewModel.necessityChunkLimit.collectAsStateWithLifecycle()
    var showNecessityBreakdownDialog by remember { mutableStateOf(false) }

    var isLoadingNecessities by remember { mutableStateOf(true) }
    val isRolloverEnabled by viewModel.isRolloverEnabled.collectAsStateWithLifecycle()
    var showRolloverSheet by rememberSaveable { mutableStateOf(false) }
    val rolloverMonths by viewModel.rolloverMonths.collectAsStateWithLifecycle()

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
    // "NEWEST" (default, grouped by day) or "HIGHEST" (biggest spends first, flat list)
    var selectedSort by rememberSaveable { mutableStateOf("NEWEST") }
    // Set at the moment the sort changes, so the rows that appear next can ease in instead of snapping.
    var sortChangeStamp by remember { mutableLongStateOf(0L) }
    val setSort: (String) -> Unit = { next ->
        if (next != selectedSort) {
            sortChangeStamp = System.currentTimeMillis()
            selectedSort = next
        }
    }
    var selectedTimeRange by rememberSaveable { mutableStateOf(TimeRange.THIS_MONTH) }

    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedTxnIds by remember { mutableStateOf(emptySet<Long>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    var showAddExpenseSheet by rememberSaveable { mutableStateOf(false) }
    var autoLaunchReceiptScan by rememberSaveable { mutableStateOf(false) }
    var isProTipsDismissed by remember { mutableStateOf(prefs.getBoolean("key_pro_tips_dismissed", false)) }

    var showBankInfoDialog by remember { mutableStateOf(false) }
    var showDismissConfirmDialog by remember { mutableStateOf(false) }
    var showSettleDebtDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showManageCategoryBudgetsDialog by remember { mutableStateOf(false) }
    var initialCategoryForBudgetEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var showStartDayDialog by remember { mutableStateOf(false) }
    var showManageCategoriesDialog by rememberSaveable { mutableStateOf(false) }
    var initialCategoryForManageCategories by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToCategoryInManageCategories by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToEditTransactionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryToDelete by rememberSaveable { mutableStateOf<String?>(null) }

    SecureScreenEffect(enabled = isBalanceRevealed || showAccountsDialog)
    var transactionPendingDelete by remember { mutableStateOf<Long?>(null) }

    var selectedBillImageUri by remember { mutableStateOf<String?>(null) }

    val categoryBudgets by viewModel.categoryBudgets.collectAsStateWithLifecycle()
    val dismissedCategoryWarnings by viewModel.dismissedCategoryWarnings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra("OPEN_ADD_EXPENSE", false) == true) {
            val scanBill = activity.intent?.getBooleanExtra("OPEN_RECEIPT_SCAN", false) == true
            showAddExpenseSheet = true
            autoLaunchReceiptScan = scanBill
            activity.intent = activity.intent?.apply {
                removeExtra("OPEN_ADD_EXPENSE")
                removeExtra("OPEN_RECEIPT_SCAN")
            }
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

    val manualSyncBalance by viewModel.manualSyncBalance.collectAsStateWithLifecycle()
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

    var hasNotificationPermission by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    var repaymentToUndo by remember { mutableStateOf<TransactionEntity?>(null) }

    val startDayOfMonth by viewModel.startDayOfMonth.collectAsStateWithLifecycle()

    val categoryBudgetStatuses = remember(transactionsWithDebts, categoryBudgets, startDayOfMonth, pendingDeleteIds) {
        computeCategoryBudgetStatuses(
            transactions = transactionsWithDebts,
            categoryBudgets = categoryBudgets,
            startDayOfMonth = startDayOfMonth,
            pendingDeleteIds = pendingDeleteIds.keys
        )
    }

    var isCategoryBudgetSetupDismissed by remember {
        mutableStateOf(settingsRepo.isCategoryBudgetSetupDismissed)
    }

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context, activity, prefs) {
        var isFirstResume = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (isFirstResume) {
                        isFirstResume = false
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val notifOk = isNotificationPermissionGranted(context)
                            val batteryOk = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                            val smsOk = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val cats = prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet()
                            val catDismissed = settingsRepo.isCategoryBudgetSetupDismissed
                            val flipped = prefs.getBoolean("key_balance_card_flipped", false)
                            withContext(Dispatchers.Main) {
                                viewModel.refreshSettings()
                                customCategories = cats
                                isCategoryBudgetSetupDismissed = catDismissed
                                hasNotificationPermission = notifOk
                                isIgnoringBatteryOptimizations = batteryOk
                                hasSmsPermission = smsOk
                                isBalanceCardFlipped = flipped
                            }
                        }
                    }

                    if (activity?.intent?.getBooleanExtra("OPEN_ADD_EXPENSE", false) == true) {
                        val scanBill = activity.intent?.getBooleanExtra("OPEN_RECEIPT_SCAN", false) == true
                        showAddExpenseSheet = true
                        autoLaunchReceiptScan = scanBill
                        activity.intent = activity.intent?.apply {
                            removeExtra("OPEN_ADD_EXPENSE")
                            removeExtra("OPEN_RECEIPT_SCAN")
                        }
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

    val previousCycleLabel = remember(selectedTimeRange, startDayOfMonth) {
        if (selectedTimeRange != TimeRange.LAST_MONTH) null
        else {
            val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            "${fmt.format(java.util.Date(startMillisFor(TimeRange.LAST_MONTH, startDayOfMonth)))} – " +
                fmt.format(java.util.Date(endMillisFor(TimeRange.LAST_MONTH, startDayOfMonth)))
        }
    }
    val currentMonthName = remember(startDayOfMonth, java.time.LocalDate.now()) {
        BillingCycleHelper.getCycleMonthName(startDayOfMonth)
    }
    val allAvailableCategories by produceState(
        initialValue = (CategoryClassifier.PREDEFINED_CATEGORIES + customCategories).distinct(),
        customCategories, transactions
    ) {
        value = withContext(Dispatchers.Default) {
            val standard = CategoryClassifier.PREDEFINED_CATEGORIES
            val lastUsedMap = HashMap<String, Long>(32)
            // transactions is ordered DESC; scanning the 1,000 most recent rows is O(1) bounded
            val sampleLimit = minOf(transactions.size, 1000)
            for (i in 0 until sampleLimit) {
                val txn = transactions[i]
                val cat = txn.category.trim()
                if (cat.isNotEmpty() && !lastUsedMap.containsKey(cat)) {
                    lastUsedMap[cat] = txn.timestamp
                }
            }
            val allDistinct = (standard + customCategories + lastUsedMap.keys).distinct()
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
        val startOfLastCycle = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, -1).first
        listOf(startOfDay, startOfWeek, startOfCycle, startOfYear, startOfLastCycle)
    }

    // ── Spend breakdown (leave categories out / single them out) ──
    val lensSettings = remember { SettingsRepository(context) }
    var lensKeys by remember { mutableStateOf(lensSettings.spendLensCategories) }
    var lensOnlyMode by remember { mutableStateOf(lensSettings.spendLensOnlyMode) }
    var showLensSheet by rememberSaveable { mutableStateOf(false) }
    var lensPinned by remember { mutableStateOf(lensSettings.spendLensPinned) }
    val customBudgets by viewModel.customBudgets.collectAsStateWithLifecycle()
    var showBudgetsSheet by rememberSaveable { mutableStateOf(false) }
    var budgetsStartInForm by rememberSaveable { mutableStateOf(false) }
    // The budget chosen in the Add sheet for the transaction being created (reset after saving).
    var pendingBudgetId by remember { mutableStateOf<String?>(null) }
    val lensCategories = remember(transactionsWithDebts, selectedTimeRange, startDayOfMonth, pendingDeleteIds) {
        val net = computeCycleCategoryNetSpend(
            transactions = transactionsWithDebts,
            cycleStart = startMillisFor(selectedTimeRange, startDayOfMonth),
            cycleEnd = endMillisFor(selectedTimeRange, startDayOfMonth),
            pendingDeleteIds = pendingDeleteIds.keys
        )
        val names = HashMap<String, String>()
        for (item in transactionsWithDebts) {
            val c = item.transaction.category.trim()
            if (c.isNotEmpty()) names.putIfAbsent(c.lowercase(), c)
        }
        buildLensCategories(net, names)
    }
    val applyLens: (Set<String>, Boolean) -> Unit = { keys, only ->
        lensKeys = keys
        lensOnlyMode = only
        lensSettings.spendLensCategories = keys
        lensSettings.spendLensOnlyMode = only
    }

    val filteredTransactions = remember(
        transactionsWithDebts, debouncedSearchQuery, selectedFilterCategory, selectedTimeRange, selectedAccount, timeBoundaries, selectedSort
    ) {
        val query = debouncedSearchQuery
        val categoryFilter = selectedFilterCategory
        val range = selectedTimeRange
        val currentAccount = selectedAccount
        val sortHighest = selectedSort == "HIGHEST"
        val minTimestamp = when (range) {
            TimeRange.TODAY -> timeBoundaries[0]
            TimeRange.THIS_WEEK -> timeBoundaries[1]
            TimeRange.THIS_MONTH -> timeBoundaries[2]
            TimeRange.THIS_YEAR -> timeBoundaries[3]
            TimeRange.ALL_TIME -> Long.MIN_VALUE
            TimeRange.LAST_MONTH -> timeBoundaries[4]
        }
        val maxTimestamp = if (range == TimeRange.LAST_MONTH) timeBoundaries[2] - 1L else Long.MAX_VALUE
        val isDesc = transactionsWithDebts.size < 2 ||
                transactionsWithDebts.first().transaction.timestamp >= transactionsWithDebts.last().transaction.timestamp
        val out = ArrayList<TransactionWithDebts>()
        for (txnWithDebts in transactionsWithDebts) {
            val txn = txnWithDebts.transaction
            if (txn.timestamp > maxTimestamp) continue
            if (txn.timestamp < minTimestamp) {
                if (isDesc) break else continue
            }
            val matchesAccount = currentAccount == null ||
                    (currentAccount.bankName == "Unspecified" && txn.bankName == null) ||
                    ((txn.bankName == currentAccount.bankName || txn.bankName?.removeSuffix(" CC")?.removeSuffix(" cc")?.trim() == currentAccount.bankName) && txn.accountNumber == currentAccount.accountNumber)
            if (!matchesAccount) continue

            val matchesCategory = when (categoryFilter) {
                "All" -> true
                "Split Only" -> txnWithDebts.debts.isNotEmpty()
                else -> txn.category.equals(categoryFilter, ignoreCase = true)
            }
            if (!matchesCategory) continue
            // "Top spends" only ranks real spending, so salary credits and own-account transfers never crowd the top.
            if (sortHighest && (txn.type != TransactionType.DEBIT || txn.excludeFromSpend)) continue

            val matchesSearch = query.isBlank() ||
                    txn.payee.contains(query, ignoreCase = true) ||
                    txn.category.contains(query, ignoreCase = true) ||
                    (txn.itemsSummary?.contains(query, ignoreCase = true) == true) ||
                    (txn.note?.contains(query, ignoreCase = true) == true) ||
                    txnWithDebts.debts.any { it.debtorName.contains(query, ignoreCase = true) }
            if (matchesSearch) {
                out.add(txnWithDebts)
            }
        }
        if (sortHighest) out.sortByDescending { it.transaction.amount }
        out
    }

    var visibleTransactionCount by rememberSaveable { mutableIntStateOf(50) }
    LaunchedEffect(debouncedSearchQuery, selectedFilterCategory, selectedTimeRange, selectedAccount, selectedSort) {
        visibleTransactionCount = 50
        if (isSelectionMode) { isSelectionMode = false; selectedTxnIds = emptySet() }
    }
    val visibleTransactions = remember(filteredTransactions, visibleTransactionCount) { filteredTransactions.take(visibleTransactionCount) }

    val splitLinkInfo by viewModel.splitLinkInfo.collectAsStateWithLifecycle()

    val periodSpent = remember(transactionsWithDebts, selectedTimeRange, pendingDeleteIds, startDayOfMonth) {
        val start = startMillisFor(selectedTimeRange, startDayOfMonth)
        val end = endMillisFor(selectedTimeRange, startDayOfMonth)
        val isDesc = transactionsWithDebts.size < 2 ||
                transactionsWithDebts.first().transaction.timestamp >= transactionsWithDebts.last().transaction.timestamp
        var gross = 0L
        var refunds = 0L
        for (item in transactionsWithDebts) {
            val txn = item.transaction
            if (txn.timestamp > end) continue
            if (txn.timestamp < start) {
                if (isDesc) break else continue
            }
            if (txn.id in pendingDeleteIds) continue
            if (txn.type == TransactionType.DEBIT && !txn.excludeFromSpend && txn.category != "Split Settlement" && !txn.category.equals("Transfer", ignoreCase = true)) {
                gross += mySpendShare(item)
            } else if (isRefundCredit(txn)) {
                refunds += txn.amount
            }
        }
        (gross - refunds).coerceAtLeast(0L)
    }

    val safeToSpendBreakdown = remember(effectiveBalance, transactionsWithDebts, necessitiesList, startDayOfMonth) {
        computeSafeToSpend(effectiveBalance, transactionsWithDebts, necessitiesList, startDayOfMonth)
    }

    // Pre-build a txn.id -> necessityName map so each card does O(1) lookup, not O(N) firstOrNull per-card
    val billLabelMap = remember(safeToSpendBreakdown, visibleTransactions, startDayOfMonth) {
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

    var activeTransactionForSplitId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeTransactionForSplit = remember(activeTransactionForSplitId, transactionsWithDebts) { transactionsWithDebts.find { it.transaction.id == activeTransactionForSplitId } }

    var activeTransactionForEditId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeTransactionForEdit = remember(activeTransactionForEditId, transactionsWithDebts) { transactionsWithDebts.find { it.transaction.id == activeTransactionForEditId }?.transaction }

    var activeCreditTransactionToLinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeCreditTransactionToLink = remember(activeCreditTransactionToLinkId, transactionsWithDebts) { transactionsWithDebts.find { it.transaction.id == activeCreditTransactionToLinkId }?.transaction }

    val isAnimationTriggered = !isAppLocked

    val shownSpent = periodSpent
    val shownOwed = moneyOwedToYou
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
            showRolloverSheet ||
            showAutoCaptureSheet ||
            showDiscardAllReviewsDialog ||
            showAccountsDialog ||
            showBudgetDialog ||
            showManageCategoryBudgetsDialog ||
            showStartDayDialog ||
            showNecessityBreakdownDialog ||
            showDismissConfirmDialog ||
            showBankInfoDialog ||
            showRestorePrompt ||
            showBulkDeleteConfirm ||
            showManageCategoriesDialog ||
            showSettleDebtDialog ||
            categoryToDelete != null ||
            transactionPendingDelete != null ||
            selectedBillImageUri != null ||
            repaymentToUndo != null ||
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
    } else if (!anyDialogOpen && (searchOpen || searchQuery.isNotEmpty())) {
        BackHandler {
            haptic.tap()
            searchOpen = false
            searchQuery = ""
            debouncedSearchQuery = ""
            focusManager.clearFocus()
        }
    } else if (!anyDialogOpen) {
        BackHandler {
            val now = System.currentTimeMillis()
            if (now - lastBackMs < 2000L) activity?.finish()
            else { lastBackMs = now; showSnack("Press back again to exit") }
        }
    }

    val todayKey = (System.currentTimeMillis() + TimeZone.getDefault().rawOffset) / 86_400_000L

    val isMonthlyView = selectedTimeRange == TimeRange.THIS_MONTH
    // This cycle's budget may be a one-off (e.g. 7,500 "this month only"). Next cycle starts from the USUAL budget,
    // so the carry-over preview must be computed from that, not from this month's one-off.
    val usualBudgetRupees by viewModel.baseMonthlyBudget.collectAsStateWithLifecycle()
    val thisCycleBudgetPaise = monthlyBudget * 100L
    val baseMonthlyBudgetPaise = (if (usualBudgetRupees > 0L) usualBudgetRupees else monthlyBudget) * 100L
    val effectiveBudgetPaise = (thisCycleBudgetPaise - activeRolloverAmountPaise).coerceAtLeast(0L)
    val isOverBudget = monthlyBudget > 0L && isMonthlyView && periodSpent > effectiveBudgetPaise
    val overspendPaise = if (monthlyBudget > 0L) (periodSpent - effectiveBudgetPaise).coerceAtLeast(0L) else 0L


    val (firstInstallmentPaise, secondInstallmentPaise) = splitRolloverOverspend(overspendPaise, rolloverMonths)
    val rolloverDeductionPaise = firstInstallmentPaise
    val projectedNextBudget = (baseMonthlyBudgetPaise - firstInstallmentPaise).coerceAtLeast(0L)
    val projectedFollowingBudget = (baseMonthlyBudgetPaise - secondInstallmentPaise).coerceAtLeast(0L)

    val totalCreditCardDebt by viewModel.totalCreditCardDebt.collectAsStateWithLifecycle()

    LaunchedEffect(overspendPaise, isRolloverEnabled, rolloverMonths, isMonthlyView, monthlyBudget, todayKey) {
        if (isMonthlyView && monthlyBudget > 0L) viewModel.syncRollover(overspendPaise)
    }
// Re-read stored rollover state if the cycle changes while the app stays open.
    LaunchedEffect(todayKey) { viewModel.refreshSettings() }

    val (nextCycleLabel, followingCycleLabel) = remember(startDayOfMonth, todayKey) {
        BillingCycleHelper.getCycleMonthName(startDayOfMonth, 1, "MMM") to
            BillingCycleHelper.getCycleMonthName(startDayOfMonth, 2, "MMM")
    }

    val isFabExpanded = filteredTransactions.isEmpty()
    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val isFabVisible by remember { derivedStateOf { !isFabScrolledAway || isAtTop } }


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
                    Text("Receipt", fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp)
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
                    Button(
                        onClick = { haptic.tap(); selectedBillImageUri = null },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(AppRadius.small),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) { Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (showCarryOverAdjust && incomingRolloverOriginalPaise > 0L) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val original = incomingRolloverOriginalPaise
        val mode = when {
            activeRolloverAmountPaise <= 0L -> 0
            incomingRolloverMovedPaise > 0L -> 2
            else -> 1
        }
        val halfNow = original / 2
        val pick = { picked: Int ->
            if (picked != mode) {
                haptic.tap()
                viewModel.adjustIncomingRollover(picked)
            }
            scope.launch { sheetState.hide() }.invokeOnCompletion { showCarryOverAdjust = false }
            Unit
        }
        ResistantModalBottomSheet(
            onDismissRequest = { showCarryOverAdjust = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Carry-over from last cycle", fontSize = 22.sp, letterSpacing = (-0.3).sp, fontWeight = FontWeight.Bold)
                Text(
                    "Last cycle's overspend was ${CurrencyFormat.withSymbol(original, 0)}. Choose how much of it comes out of this cycle's ${CurrencyFormat.withSymbol(thisCycleBudgetPaise, 0)} budget.",
                    fontSize = 14.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "This cycle's budget",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 17.dp)
                )
                RolloverOptionCard(
                    title = "Start fresh",
                    description = "Forget it. Spending less this cycle? Begin clean.",
                    outcome = CurrencyFormat.withSymbol(thisCycleBudgetPaise, 0),
                    outcomeDetail = "no deduction",
                    selected = mode == 0,
                    onClick = { pick(0) }
                )
                RolloverOptionCard(
                    title = "Deduct half, carry half",
                    description = "Half now, the other half comes out of $nextCycleLabel.",
                    outcome = CurrencyFormat.withSymbol((thisCycleBudgetPaise - halfNow).coerceAtLeast(0L), 0),
                    outcomeDetail = "−${CurrencyFormat.withSymbol(halfNow, 0)}",
                    selected = mode == 2,
                    onClick = { pick(2) }
                )
                RolloverOptionCard(
                    title = "Deduct all of it",
                    description = "Take the full amount from this cycle.",
                    outcome = CurrencyFormat.withSymbol((thisCycleBudgetPaise - original).coerceAtLeast(0L), 0),
                    outcomeDetail = "−${CurrencyFormat.withSymbol(original, 0)}",
                    selected = mode == 1,
                    onClick = { pick(1) }
                )
            }
        }
    }

    if (showRolloverSheet && isOverBudget) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val choice = if (isRolloverEnabled) rolloverMonths else 0
        val (halfFirst, halfSecond) = splitRolloverOverspend(overspendPaise, 2)
        val select = { picked: Int ->
            if (picked != choice) {
                haptic.tap()
                viewModel.setRolloverChoice(picked, overspendPaise)
            }
        }

        ResistantModalBottomSheet(
            onDismissRequest = { showRolloverSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Handle your overspend", fontSize = 22.sp, letterSpacing = (-0.3).sp, fontWeight = FontWeight.Bold)
                Text(
                    "You're ${CurrencyFormat.withSymbol(overspendPaise, 0)} over. Choose how it carries into $nextCycleLabel.",
                    fontSize = 14.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "$nextCycleLabel budget",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 17.dp)
                )
                RolloverOptionCard(
                    title = "Start fresh",
                    description = "Forget it and begin clean.",
                    outcome = CurrencyFormat.withSymbol(baseMonthlyBudgetPaise, 0),
                    outcomeDetail = "no change",
                    selected = choice == 0,
                    onClick = { select(0) }
                )
                RolloverOptionCard(
                    title = "Deduct in $nextCycleLabel",
                    description = "Take the full amount next cycle.",
                    outcome = CurrencyFormat.withSymbol((baseMonthlyBudgetPaise - overspendPaise).coerceAtLeast(0L), 0),
                    outcomeDetail = "−${CurrencyFormat.withSymbol(overspendPaise, 0)}",
                    selected = choice == 1,
                    onClick = { select(1) }
                )
                RolloverOptionCard(
                    title = "Split in two",
                    description = "Half in $nextCycleLabel, half in $followingCycleLabel.",
                    outcome = CurrencyFormat.withSymbol((baseMonthlyBudgetPaise - halfFirst).coerceAtLeast(0L), 0),
                    outcomeDetail = "then ${CurrencyFormat.withSymbol((baseMonthlyBudgetPaise - halfSecond).coerceAtLeast(0L), 0)} in $followingCycleLabel",
                    selected = choice == 2,
                    onClick = { select(2) }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Button(
                    onClick = {
                        haptic.tap()
                        scope.launch { sheetState.hide() }.invokeOnCompletion { showRolloverSheet = false }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(AppRadius.small),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text("Done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (showAddExpenseSheet) {
        val recentSplitPeople = remember(transactionsWithDebts) {
            val friends = transactionsWithDebts
                .asSequence()
                .take(300)
                .flatMap { it.debts.asSequence() }
                .map { it.debtorName.trim() }
                .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                .distinct()
                .take(6)
                .toList()
            listOf("You") + friends
        }
        var pendingNewExpenseSplit by remember { mutableStateOf<List<SplitParticipant>>(emptyList()) }
        AddExpenseSheet(
            availableCategories = allAvailableCategories,
            autoLaunchReceiptPicker = autoLaunchReceiptScan,
            onReceiptPickerLaunched = { autoLaunchReceiptScan = false },
            onDismiss = {
                showAddExpenseSheet = false
                autoLaunchReceiptScan = false
                pendingBudgetId = null
            },
            linkedAccounts = linkedAccounts,
            customBudgets = customBudgets,
            onBudgetSelected = { pendingBudgetId = it },
            transactionsHistory = transactions,
            initialSplitPeople = recentSplitPeople,
            onAddToFixedCosts = { suggestion ->
                addFixedCost(suggestion)
            },
            onSaveSplit = { participants ->
                pendingNewExpenseSplit = participants
            },
            onSave = { amount, payee, category, type, timestamp, itemsSummary, receiptUri, bank, acct, excludeFromSpend ->
                viewModel.addManualExpense(
                    amount = amount, payee = payee, category = category, type = type,
                    timestamp = timestamp, itemsSummary = itemsSummary, receiptImageUri = receiptUri,
                    bankName = bank, accountNumber = acct,
                    excludeFromSpend = excludeFromSpend,
                    splitParticipants = pendingNewExpenseSplit,
                    budgetId = if (type == TransactionType.DEBIT) pendingBudgetId else null
                )
                pendingNewExpenseSplit = emptyList()
                pendingBudgetId = null
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

    val creditTxn = activeCreditTransactionToLink
    if (creditTxn != null) {
        val dismissLinkDialog = {
            activeCreditTransactionToLinkId = null
            if (returnToEditTransactionId != null) {
                activeTransactionForEditId = returnToEditTransactionId
                returnToEditTransactionId = null
            }
        }
        Dialog(onDismissRequest = dismissLinkDialog) {
            Surface(modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp), shape = RoundedCornerShape(AppRadius.large), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Link Repayment", fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp)
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
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { haptic.tap(); dismissLinkDialog() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(AppRadius.small),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) { Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (showBudgetDialog) {
        val cycleOverride by viewModel.cycleBudgetOverride.collectAsStateWithLifecycle()
        val usualBudget by viewModel.baseMonthlyBudget.collectAsStateWithLifecycle()
        SelectTargetDialog(
            currentTarget = monthlyBudget,
            onDismiss = { showBudgetDialog = false },
            onSaveTarget = { newTarget ->
                haptic.confirm()
                viewModel.updateMonthlyBudget(newTarget, clearOverride = true)
                showBudgetDialog = false
            },
            onSaveForThisCycle = { newTarget ->
                haptic.confirm()
                viewModel.setBudgetForThisCycle(newTarget)
                showBudgetDialog = false
            },
            usualBudget = if (cycleOverride != null) usualBudget else null,
            onClearOverride = { viewModel.clearBudgetOverride() },
            onManageCategoryCaps = {
                initialCategoryForBudgetEdit = null
                showManageCategoryBudgetsDialog = true
            }
        )
    }

    if (showAutoCaptureSheet) {
        AutoCaptureSetupSheet(
            hasSmsPermission = hasSmsPermission,
            hasNotificationPermission = hasNotificationPermission,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            onOpenSmsPermissions = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
                runCatching { context.startActivity(intent) }
            },
            onOpenNotificationSettings = {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            onRequestBatteryUnrestricted = {
                val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
                val launched = runCatching { context.startActivity(appInfoIntent); true }.getOrDefault(false)
                if (!launched) {
                    showSnack("Open Android Settings > Apps > Expense Tracker > Battery")
                } else {
                    showSnack("In App Info, open Battery and choose Unrestricted if available")
                }
            },
            onSwitchToManualMode = {
                viewModel.setManualModePreferred(true)
                showAutoCaptureSheet = false
                showSnack("Switched to manual mode — auto-capture warnings hidden")
            },
            onDismiss = { showAutoCaptureSheet = false },
            onDone = {
                viewModel.setAutoCaptureBannerDismissed(true)
                showAutoCaptureSheet = false
            }
        )
    }

    if (showStartDayDialog) {
        BillingCycleDialog(
            currentDay = startDayOfMonth,
            onDismiss = { showStartDayDialog = false },
            onSave = { day ->
                viewModel.updateStartDayOfMonth(day)
                showStartDayDialog = false
            }
        )
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
            onDismiss = {
                showManageCategoriesDialog = false
                initialCategoryForManageCategories = null
            },
            allCategories = allAvailableCategories,
            transactions = transactionsWithDebts,
            categoryBudgets = categoryBudgets,
            startDayOfMonth = startDayOfMonth,
            pendingDeleteIds = pendingDeleteIds.keys,
            initialCategory = initialCategoryForManageCategories,
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
            onRenameCategory = { oldName, newName ->
                val trimmedNew = newName.trim()
                if (trimmedNew.isNotEmpty() && !oldName.equals(trimmedNew, ignoreCase = true)) {
                    val updated = (customCategories.filterNot { it.equals(oldName, ignoreCase = true) } + trimmedNew).toSet()
                    customCategories = updated
                    prefs.edit().putStringSet("key_custom_categories", updated).apply()
                    viewModel.renameCategory(oldName, trimmedNew)
                    if (necessitiesList.any { it.categoryName.equals(oldName, ignoreCase = true) }) {
                        updateNecessities(
                            necessitiesList.map { item ->
                                if (item.categoryName.equals(oldName, ignoreCase = true)) item.copy(categoryName = trimmedNew)
                                else item
                            }
                        )
                    }
                    if (selectedFilterCategory.equals(oldName, ignoreCase = true)) {
                        selectedFilterCategory = trimmedNew
                    }
                    showSnack("Renamed \"$oldName\" to \"$trimmedNew\"")
                }
            },
            onMergeCategory = { fromCategory, intoCategory ->
                if (!fromCategory.equals(intoCategory, ignoreCase = true)) {
                    viewModel.reassignCategory(fromCategory, intoCategory)
                    if (necessitiesList.any { it.categoryName.equals(fromCategory, ignoreCase = true) }) {
                        updateNecessities(
                            necessitiesList.map { item ->
                                if (item.categoryName.equals(fromCategory, ignoreCase = true)) item.copy(categoryName = intoCategory)
                                else item
                            }
                        )
                    }
                    if (selectedFilterCategory.equals(fromCategory, ignoreCase = true)) {
                        selectedFilterCategory = intoCategory
                    }
                    showSnack("Moved transactions from \"$fromCategory\" to \"$intoCategory\"")
                }
            },
            onOpenCategoryBudget = { catName ->
                returnToCategoryInManageCategories = catName
                showManageCategoriesDialog = false
                initialCategoryForBudgetEdit = catName
                showManageCategoryBudgetsDialog = true
            },
            onDeleteCategory = { catToDelete ->
                haptic.tap()
                categoryToDelete = catToDelete
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
                val targetCat = categoryToDelete!!
                val updated = customCategories.filterNot { it.equals(targetCat, ignoreCase = true) }.toSet()
                customCategories = updated
                prefs.edit().putStringSet("key_custom_categories", updated).apply()
                if (selectedFilterCategory.equals(targetCat, ignoreCase = true)) {
                    selectedFilterCategory = reassignTarget ?: "All"
                }
                if (necessitiesList.any { it.categoryName.equals(targetCat, ignoreCase = true) }) {
                    updateNecessities(
                        necessitiesList.map { item ->
                            if (item.categoryName.equals(targetCat, ignoreCase = true)) item.copy(categoryName = reassignTarget)
                            else item
                        }
                    )
                }

                viewModel.onCategoryDeleted(deletedCategory = targetCat, reassignTo = reassignTarget)
                showSnack("Category \"$targetCat\" deleted")
                categoryToDelete = null
            }
        )
    }

    if (showBudgetsSheet) {
        CustomBudgetsSheet(
            budgets = customBudgets,
            transactions = transactionsWithDebts,
            startInForm = budgetsStartInForm,
            onSave = { id, name, target, exclude -> viewModel.saveCustomBudget(id, name, target, exclude) },
            onDelete = { id -> viewModel.deleteCustomBudget(id) },
            onRemoveTransaction = { txnId -> viewModel.assignToBudget(listOf(txnId), null) },
            onDismiss = { showBudgetsSheet = false }
        )
    }

    if (showLensSheet && lensCategories.isNotEmpty()) {
        SpendLensSheet(
            categories = lensCategories,
            selectedKeys = lensKeys,
            onlyMode = lensOnlyMode,
            periodLabel = selectedTimeRange.spendLabel,
            onChange = applyLens,
            pinned = lensPinned,
            onPinnedChange = { lensPinned = it; lensSettings.spendLensPinned = it },
            onDismiss = { showLensSheet = false }
        )
    }

    if (showAccountsDialog) {
        AccountsDialog(
            accountBalances = accountBalances,
            selectedAccount = selectedAccount,
            contextLabel = selectedAccount?.bankName ?: "All Accounts",
            contextBalance = effectiveBalance,
            safeToSpendBreakdown = safeToSpendBreakdown,
            transactions = transactions,
            totalCreditCardDebt = totalCreditCardDebt, // Ensure you are collecting this state from the ViewModel
            onAccountSelected = { selected ->
                viewModel.selectedAccount.value = selected
            },
            onRefresh = {
                haptic.tap()
                viewModel.syncLatestBalanceFromSms()
                showSnack("Syncing latest SMS...")
            },
            onUpdateBalance = { bank, acc, bal ->
                // Triggered when fixing an existing account's balance
                viewModel.setManualAccountBalance(bank, acc, bal)
            },
            onSetCreditCard = { bank, acc, enabled ->
                viewModel.setAccountCreditCard(bank, acc, enabled)
            },
            onAddManualAccount = { bank, acc, bal, isCc, limit, stmt, due ->
                // Triggered from the new "Add Offline Account" dialog
                viewModel.addManualAccount(
                    bankName = bank,
                    accountNumber = acc,
                    initialBalancePaise = bal,
                    isCreditCard = isCc,
                    creditLimitPaise = limit,
                    statementDay = stmt,
                    dueDay = due
                )
            },
            onManageNecessities = {
                showAccountsDialog = false
                isBalanceRevealed = false
                isBalanceCardFlipped = prefs.getBoolean("key_balance_card_flipped", false)
                onNavigateToNecessities()
            },
            onDismiss = {
                showAccountsDialog = false
                isBalanceRevealed = false
                isBalanceCardFlipped = prefs.getBoolean("key_balance_card_flipped", false)
            }
        )
    }

    if (showDiscardAllReviewsDialog) {
        AppDialog(
            title = "Discard all $pendingReviewCount review items?",
            onDismiss = { haptic.tap(); showDiscardAllReviewsDialog = false },
            confirmText = "Discard All",
            destructive = true,
            onConfirm = {
                haptic.delete()
                val ids = pendingReviewItems.map { it.id }
                viewModel.bulkDiscardReviewItems(ids)
                showDiscardAllReviewsDialog = false
                showSnack("Discarded ${ids.size} review items")
            }
        ) {
            Text(
                "None of these $pendingReviewCount messages will be added to your expenses. This cannot be undone.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
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
    val txn = repaymentToUndo
    if (txn != null) {
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

    if (showManageCategoryBudgetsDialog) {
        ManageCategoryBudgetsSheet(
            availableCategories = allAvailableCategories,
            currentBudgets = categoryBudgets,
            onSaveBudget = { cat, limit ->
                viewModel.setCategoryBudget(cat, limit)
            },
            onRemoveBudget = { cat ->
                viewModel.removeCategoryBudget(cat)
            },
            onDismiss = {
                showManageCategoryBudgetsDialog = false
                initialCategoryForBudgetEdit = null
                val returnCat = returnToCategoryInManageCategories
                if (returnCat != null) {
                    returnToCategoryInManageCategories = null
                    initialCategoryForManageCategories = returnCat
                    showManageCategoriesDialog = true
                }
            },
            initialCategory = initialCategoryForBudgetEdit,
            transactions = transactionsWithDebts,
            pendingDeleteIds = pendingDeleteIds.keys,
            monthlyBudgetPaise = effectiveBudgetPaise,
            startDayOfMonth = startDayOfMonth
        )
    }



    data class DayGroup(val label: String, val rows: List<com.expensetracker.offline.data.local.dao.TransactionWithDebts>, val daySpentPaise: Long)

    val grouped = remember(visibleTransactions, pendingDeleteIds, selectedSort) {
        if (selectedSort == "HIGHEST") {
            if (visibleTransactions.isEmpty()) emptyList<DayGroup>()
            else listOf(
                DayGroup(
                    "Biggest spends first",
                    visibleTransactions,
                    visibleTransactions.filter { it.transaction.id !in pendingDeleteIds }.sumOf { mySpendShare(it) }
                )
            )
        } else {
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
                            !it.transaction.category.equals("Transfer", ignoreCase = true) &&
                            it.transaction.id !in pendingDeleteIds
                }
                .sumOf { r -> mySpendShare(r) }
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

    val activeTxnForEdit = activeTransactionForEdit
    if (activeTxnForEdit != null) {
        val txn = activeTxnForEdit
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
        val billSuggestion by produceState<NecessityManager.BillSuggestion?>(
            initialValue = null, txn, matchedBill, transactions
        ) {
            value = if (matchedBill != null || txn.type != TransactionType.DEBIT) null
            else withContext(Dispatchers.Default) {
                NecessityManager.suggestionFor(txn, transactions.take(500))
            }
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
            customBudgets = customBudgets,
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
                returnToEditTransactionId = txn.id
                activeTransactionForEditId = null
                activeTransactionForSplitId = txn.id
            },
            onLinkToBill = {
                returnToEditTransactionId = txn.id
                activeTransactionForEditId = null
                activeCreditTransactionToLinkId = txn.id
            },
            initialSplitPeople = remember(txn.id, transactionsWithDebts) {
                val existingFriends = transactionsWithDebts
                    .find { it.transaction.id == txn.id }
                    ?.debts
                    ?.map { it.debtorName.trim() }
                    ?.filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                    .orEmpty()
                val recentFriends = transactionsWithDebts
                    .asSequence()
                    .take(200)
                    .flatMap { it.debts.asSequence() }
                    .map { com.expensetracker.offline.ui.components.CurrencyFormat.cleanNote(it.debtorName) }
                    .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                    .distinct()
                    .take(5)
                    .toList()
                (listOf("You") + existingFriends + recentFriends).distinct()
            },
            onSaveSplit = { splitParticipants ->
                viewModel.saveMultiSplit(txn.id, splitParticipants, "You")
                showSnack("Saved itemized bill split")
            }
        )
    }

    val activeBillForSplit = activeTransactionForSplit
    if (activeBillForSplit != null) {
        val bill = activeBillForSplit

        // 1. Context-Aware: People you frequently split with at THIS specific merchant (bounded off-thread)
        val contextualSplitPartners by produceState(initialValue = emptyList<String>(), bill, transactionsWithDebts) {
            value = withContext(Dispatchers.Default) {
                val targetPayee = bill.transaction.payee.trim()
                transactionsWithDebts
                    .asSequence()
                    .take(1000)
                    .filter { it.transaction.payee.equals(targetPayee, ignoreCase = true) }
                    .flatMap { it.debts.asSequence() }
                    .map { com.expensetracker.offline.ui.components.CurrencyFormat.cleanNote(it.debtorName) }
                    .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                    .groupingBy { it }
                    .eachCount()
                    .entries.sortedByDescending { it.value }
                    .map { it.key }
            }
        }

        // 2. Fallback: Global frequently split partners across all merchants (bounded off-thread)
        val globalSplitPartners by produceState(initialValue = emptyList<String>(), bill, transactionsWithDebts) {
            value = withContext(Dispatchers.Default) {
                transactionsWithDebts
                    .asSequence()
                    .take(1000)
                    .flatMap { it.debts.asSequence() }
                    .map { com.expensetracker.offline.ui.components.CurrencyFormat.cleanNote(it.debtorName) }
                    .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                    .groupingBy { it }
                    .eachCount()
                    .entries.sortedByDescending { it.value }
                    .map { it.key }
            }
        }

        MultiSplitShareSheet(
            transaction = bill.transaction,
            initialDebts = bill.debts,
            onDismiss = {
                activeTransactionForSplitId = null
                if (returnToEditTransactionId != null) {
                    activeTransactionForEditId = returnToEditTransactionId
                    returnToEditTransactionId = null
                }
            },
            onSaveSplit = { participants, paidBy ->
                viewModel.saveMultiSplit(bill.transaction.id, participants, paidBy)
                haptic.confirm()
            },
            onResetSplit = { viewModel.resetSplit(bill.transaction.id) },
            contextualNames = contextualSplitPartners, // NEW
            recentNames = globalSplitPartners          // NEW
        )
    }

    CompositionLocalProvider(com.expensetracker.offline.ui.insights.LocalAnimationReady provides isAnimationTriggered) {
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
            val isScrolled by remember {
                derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 }
            }
            DashboardTopBar(
                isScrolled = isScrolled,
                isSelectionMode = isSelectionMode,
                selectedTxnIds = selectedTxnIds,
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                currentMonthName = currentMonthName,
                previousCycleLabel = previousCycleLabel,
                startDayOfMonth = startDayOfMonth,
                hasNotificationPermission = hasNotificationPermission,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                hasSmsPermission = hasSmsPermission,
                isManualModePreferred = isManualModePreferred,
                isProTipsDismissed = isProTipsDismissed,
                haptic = haptic,
                onCancelSelection = {
                    isSelectionMode = false
                    selectedTxnIds = emptySet()
                },
                onBulkDelete = { showBulkDeleteConfirm = true },
                onCycleClick = { showStartDayDialog = true },
                onSearchToggle = {
                    searchOpen = !searchOpen
                    if (!searchOpen) { searchQuery = ""; debouncedSearchQuery = "" }
                },
                onAnalytics = { onNavigateToInsights() },
                onSettings = { onNavigateToSettings(null) }
            )
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
            contentPadding = PaddingValues(bottom = 116.dp),
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
                    val isSetupAutoDone = hasSmsPermission || hasNotificationPermission
                    val isSetupFirstTxnDone = hasEverLoggedExpense

                    val completedSteps = listOf(
                        isSetupBudgetDone,
                        isSetupAutoDone,
                        isSetupFirstTxnDone
                    ).count { it }

                    val hasMissingAutoCapture = !(hasSmsPermission || hasNotificationPermission) || !isIgnoringBatteryOptimizations

                    if (!isManualModePreferred && !isAutoCaptureBannerDismissed && hasMissingAutoCapture) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .quietClickable {
                                    haptic.tap()
                                    showAutoCaptureSheet = true
                                },
                            shape = RoundedCornerShape(AppRadius.pill),
                            color = AppColors.warningContainer,
                            border = BorderStroke(1.dp, AppColors.warning.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = AppColors.warning,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Auto-capture paused · Tap to fix",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        haptic.tap()
                                        viewModel.setAutoCaptureBannerDismissed(true)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss auto-capture warning",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !isSetupDismissed && !isManualModePreferred && completedSteps < 3,
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
                                    onClick = { showAutoCaptureSheet = true }
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

                                    DashboardProgressBar(
                                        progress = if (isAnimationTriggered) onboardingTargetProgress else 0f,
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        height = 6.dp,
                                        sessionKey = "dashboard_onboarding_bar"
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

                    val timeRanges = TimeRange.entries.filter { it != TimeRange.LAST_MONTH }
                    val selectedRangeIdx = timeRanges.indexOf(
                        if (selectedTimeRange == TimeRange.LAST_MONTH) TimeRange.THIS_MONTH else selectedTimeRange
                    ).coerceAtLeast(0)
                    var rangeSegmentWidthPx by remember { mutableFloatStateOf(0f) }
                    val animatedRangeIdx by animateFloatAsState(
                        targetValue = selectedRangeIdx.toFloat(),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "timeRangeThumbIdx"
                    )
                    val pillShape = RoundedCornerShape(AppRadius.pill)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(pillShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp)
                            .onSizeChanged { size ->
                                rangeSegmentWidthPx = size.width.toFloat() / timeRanges.size.coerceAtLeast(1)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(1f / timeRanges.size.coerceAtLeast(1))
                                .fillMaxHeight()
                                .graphicsLayer { translationX = rangeSegmentWidthPx * animatedRangeIdx }
                                .shadow(1.dp, pillShape)
                                .background(MaterialTheme.colorScheme.surfaceBright, pillShape)
                        )
                        Row(modifier = Modifier.fillMaxSize()) {
                            timeRanges.forEach { range ->
                                // The Month tab stays lit while looking at the previous cycle (reached from the link below).
                                val isSelected = selectedTimeRange == range ||
                                    (range == TimeRange.THIS_MONTH && selectedTimeRange == TimeRange.LAST_MONTH)
                                val labelColor by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    animationSpec = tween(180),
                                    label = "timeRangeLabelColor"
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(pillShape)
                                        .quietSelectable(
                                            selected = isSelected,
                                            role = Role.Tab,
                                            onClick = {
                                                if (selectedTimeRange != range) haptic.tap()
                                                selectedTimeRange = range
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = range.tabLabel,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = labelColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (selectedTimeRange == TimeRange.THIS_MONTH || selectedTimeRange == TimeRange.LAST_MONTH) {
                        val viewingLast = selectedTimeRange == TimeRange.LAST_MONTH
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                            Text(
                                if (viewingLast) "Back to this cycle ›" else "‹ Previous cycle",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .quietClickable {
                                        haptic.tap()
                                        selectedTimeRange = if (viewingLast) TimeRange.THIS_MONTH else TimeRange.LAST_MONTH
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    val budgetProgress = if (isMonthlyView && monthlyBudget > 0L) {
                        if (effectiveBudgetPaise > 0L) (periodSpent.toFloat() / effectiveBudgetPaise.toFloat()).coerceIn(0f, 1f) else 1f
                    } else 0f

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
                                val heroSpendLabel = if (isMonthlyView && startDayOfMonth != 1) {
                                    "this cycle"
                                } else {
                                    selectedTimeRange.spendLabel
                                }
                                AnimatedContent(
                                    targetState = "Total spent $heroSpendLabel",
                                    transitionSpec = {
                                        (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 })
                                            .togetherWith(fadeOut(tween(140)))
                                    },
                                    label = "heroSpendTitleAnim"
                                ) { titleText ->
                                    Text(
                                        titleText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isMonthlyView) {
                                    Surface(
                                        shape = RoundedCornerShape(AppRadius.pill),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppRadius.pill))
                                            .quietClickable {
                                                haptic.tap()
                                                showStartDayDialog = true
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Resets Day $startDayOfMonth",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            MoneyHero(amount = shownSpent, fractionDigits = 2, size = 44.sp, sessionKey = "dashboard_hero_spend")

                            if (monthlyBudget > 0 && isMonthlyView) {
                                val (_, cycleEnd) = remember(startDayOfMonth) { BillingCycleHelper.getCycleRange(startDayOfMonth) }
                                val daysLeft = remember(cycleEnd) {
                                    (ceil((cycleEnd - System.currentTimeMillis()).toDouble() / 86_400_000.0).toInt()).coerceAtLeast(1)
                                }
                                val remaining = (effectiveBudgetPaise - periodSpent).coerceAtLeast(0L)

                                Spacer(modifier = Modifier.height(24.dp))
                                DashboardProgressBar(
                                    progress = if (isAnimationTriggered) budgetProgress else 0f,
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    height = 6.dp,
                                    sessionKey = "dashboard_budget_bar"
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                val usedPct = (budgetProgress * 100f).roundToInt()
                                val isNearLimit = !isOverBudget && budgetProgress >= 0.85f

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when {
                                        isOverBudget -> {
                                            Surface(shape = RoundedCornerShape(AppRadius.pill), color = AppColors.negative.copy(alpha = 0.10f)) {
                                                Text(
                                                    "${CurrencyFormat.withSymbol(overspendPaise, 0)} over budget",
                                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.negative,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                            Text(
                                                "of ${CurrencyFormat.withSymbol(effectiveBudgetPaise, 0)}",
                                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        isNearLimit -> {
                                            Surface(shape = RoundedCornerShape(AppRadius.pill), color = AppColors.warning.copy(alpha = 0.14f)) {
                                                Text(
                                                    "$usedPct% used · $daysLeft ${if (daysLeft == 1) "day" else "days"} left",
                                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.warning,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                            Text(
                                                "${CurrencyFormat.withSymbol(remaining, 0)} left",
                                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        else -> {
                                            Text(
                                                "${CurrencyFormat.withSymbol(remaining, 0)} left",
                                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                "of ${CurrencyFormat.withSymbol(effectiveBudgetPaise, 0)} · ${CurrencyFormat.withSymbol(remaining / daysLeft, 0)}/day",
                                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                if (isOverBudget) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .quietClickable { haptic.tap(); showRolloverSheet = true }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                when {
                                                    !isRolloverEnabled -> "Overspend not carried over"
                                                    rolloverMonths == 2 -> "Carry-over · $nextCycleLabel & $followingCycleLabel"
                                                    else -> "Carry-over to $nextCycleLabel"
                                                },
                                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                when {
                                                    !isRolloverEnabled -> "$nextCycleLabel budget stays ${CurrencyFormat.withSymbol(baseMonthlyBudgetPaise, 0)}"
                                                    rolloverMonths == 2 -> "$nextCycleLabel ${CurrencyFormat.withSymbol(projectedNextBudget, 0)} · $followingCycleLabel ${CurrencyFormat.withSymbol(projectedFollowingBudget, 0)}"
                                                    else -> "−${CurrencyFormat.withSymbol(rolloverDeductionPaise, 0)} · $nextCycleLabel budget ${CurrencyFormat.withSymbol(projectedNextBudget, 0)}"
                                                },
                                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Change carry-over",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (incomingRolloverOriginalPaise > 0L) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .quietClickable { haptic.tap(); showCarryOverAdjust = true }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (activeRolloverAmountPaise > 0L)
                                                "Budget reduced by ${CurrencyFormat.withSymbol(activeRolloverAmountPaise, 0)} due to last cycle's overspend."
                                            else "Last cycle's overspend was cleared. Budget is back to normal.",
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (activeRolloverAmountPaise > 0L) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Adjust carry-over",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (categoryBudgetStatuses.isNotEmpty()) {
                                    val overCats = remember(categoryBudgetStatuses) { categoryBudgetStatuses.filter { it.isOverBudget } }
                                    val nearCats = remember(categoryBudgetStatuses) { categoryBudgetStatuses.filter { !it.isOverBudget && (it.isNearLimit || it.isAtLimit) } }
                                    val alertCat = overCats.firstOrNull() ?: nearCats.firstOrNull()
                                    val dotColor = when {
                                        overCats.isNotEmpty() -> AppColors.negative
                                        nearCats.isNotEmpty() -> AppColors.warning
                                        else -> AppColors.positive
                                    }
                                    val statusSummary = when {
                                        overCats.size > 1 -> "${overCats.size} category caps exceeded"
                                        overCats.size == 1 -> {
                                            val c = overCats.first()
                                            "${c.category} · ${CurrencyFormat.withSymbol(c.overagePaise, 0)} over cap"
                                        }
                                        nearCats.size > 1 -> "${nearCats.size} category caps near limit"
                                        nearCats.size == 1 -> {
                                            val c = nearCats.first()
                                            val pct = (c.progress * 100f).roundToInt()
                                            if (c.isAtLimit) "${c.category} · Cap reached (100%)"
                                            else "${c.category} · ${CurrencyFormat.withSymbol(c.remainingPaise, 0)} left ($pct%)"
                                        }
                                        else -> "${categoryBudgetStatuses.size} ${if (categoryBudgetStatuses.size == 1) "category cap" else "category caps"} on track"
                                    }
                                    val summaryColor = when {
                                        overCats.isNotEmpty() -> AppColors.negative
                                        nearCats.isNotEmpty() -> AppColors.warning
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(AppRadius.small))
                                            .quietClickable {
                                                haptic.tap()
                                                initialCategoryForBudgetEdit = if (overCats.size + nearCats.size == 1) alertCat?.category else null
                                                showManageCategoryBudgetsDialog = true
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(dotColor, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = statusSummary,
                                                fontSize = 13.sp,
                                                fontWeight = if (alertCat != null) FontWeight.SemiBold else FontWeight.Medium,
                                                color = summaryColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Category caps →",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(AppRadius.small))
                                                .quietClickable {
                                                    haptic.tap()
                                                    initialCategoryForBudgetEdit = null
                                                    showManageCategoryBudgetsDialog = true
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            } else if (!isMonthlyView) {
                                val periodStart = remember(selectedTimeRange, startDayOfMonth) {
                                    startMillisFor(selectedTimeRange, startDayOfMonth)
                                }
                                val periodCount = remember(transactionsWithDebts, periodStart, pendingDeleteIds) {
                                    transactionsWithDebts.count {
                                        it.transaction.id !in pendingDeleteIds &&
                                                it.transaction.type == TransactionType.DEBIT &&
                                                !it.transaction.excludeFromSpend &&
                                                it.transaction.category != "Split Settlement" &&
                                                it.transaction.timestamp >= periodStart
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    if (periodCount > 0) "Across $periodCount ${if (periodCount == 1) "expense" else "expenses"} ${selectedTimeRange.spendLabel}"
                                    else "No expenses recorded ${selectedTimeRange.spendLabel}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

            // Category Budgets are now surfaced as a compact 1-line status strip inside the Monthly Hero Card
            // (or standalone only when no overall monthly budget is set), keeping Available Balance and
            // Transactions above the fold on the Dashboard.
            if (selectedTimeRange == TimeRange.THIS_MONTH && monthlyBudget <= 0L && categoryBudgetStatuses.isNotEmpty()) {
                item(key = "category_budgets_widget") {
                    CategoryBudgetsDashboardWidget(
                        statuses = categoryBudgetStatuses,
                        onManageClick = {
                            initialCategoryForBudgetEdit = null
                            showManageCategoryBudgetsDialog = true
                        },
                        onCategoryClick = { cat ->
                            initialCategoryForBudgetEdit = cat
                            showManageCategoryBudgetsDialog = true
                        }
                    )
                }
            }

            if (necessitiesList.isNotEmpty() || moneyOwedToYou > 0L || moneyYouOwe > 0L || linkedAccounts.isNotEmpty() || accountBalances.isNotEmpty() || latestBalance != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val hasOwed = moneyOwedToYou > 0L || moneyYouOwe > 0L
                        val showOwedToYouPrimary = moneyOwedToYou >= moneyYouOwe

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
                            val primaryAmt = if (showOwedToYouPrimary) shownOwed else moneyYouOwe
                            val primaryColor = if (showOwedToYouPrimary) AppColors.positive else AppColors.warning
                            val secondaryText = when {
                                moneyOwedToYou > 0L && moneyYouOwe > 0L ->
                                    if (showOwedToYouPrimary) "You owe ${CurrencyFormat.withSymbol(moneyYouOwe, 0)}"
                                    else "Owed ${CurrencyFormat.withSymbol(moneyOwedToYou, 0)}"
                                else -> "Tap to settle"
                            }
                            QuickStatCard(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                icon = Icons.Default.Handshake,
                                label = if (showOwedToYouPrimary) "Owed to you" else "You owe",
                                onClick = { haptic.tap(); showSettleDebtDialog = true }
                            ) {
                                val animatedAmt by com.expensetracker.offline.ui.insights.rememberAnimatedValue(
                                    target = primaryAmt.toFloat(),
                                    key = primaryAmt,
                                    sessionKey = "dashboard_owed_stat"
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("₹", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = primaryColor, modifier = Modifier.padding(bottom = 2.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(CurrencyFormat.amount(animatedAmt.toLong(), 0), fontWeight = FontWeight.Bold, fontSize = 24.sp, color = primaryColor)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.RateReview, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "$pendingReviewCount to review",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "Approve, edit, or discard",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = MaterialTheme.colorScheme.onSurface
                                ) {
                                    Text(
                                        "Review →",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }

                            if (pendingReviewItems.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            haptic.tap()
                                            showDiscardAllReviewsDialog = true
                                        },
                                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 42.dp),
                                        shape = RoundedCornerShape(AppRadius.small),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            "Discard all ($pendingReviewCount)",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.negative,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            haptic.confirm()
                                            viewModel.bulkResolveReviewItems(pendingReviewItems) { approved, skipped ->
                                                when {
                                                    approved > 0 && skipped == 0 ->
                                                        showSnack("Approved $approved ${if (approved == 1) "transaction" else "transactions"}")
                                                    approved > 0 && skipped > 0 -> {
                                                        showSnack("Approved $approved · $skipped need an amount")
                                                        onNavigateToReview()
                                                    }
                                                    approved == 0 && skipped > 0 -> {
                                                        showSnack("Please enter an amount to approve")
                                                        onNavigateToReview()
                                                    }
                                                    else ->
                                                        showSnack("Removed non-transaction alert")
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 42.dp),
                                        shape = RoundedCornerShape(AppRadius.small),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            "Approve all ($pendingReviewCount)",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Compact card for the user's own goal budgets. Absent until they create one.
            if (customBudgets.isNotEmpty()) {
                item(key = "custom_budgets") {
                    CustomBudgetsStrip(
                        budgets = customBudgets,
                        transactions = transactionsWithDebts,
                        onOpen = { budgetsStartInForm = false; showBudgetsSheet = true }
                    )
                }
            }

            // Only when the user pinned it; otherwise it opens on demand from the Transactions header.
            if (lensPinned && lensCategories.isNotEmpty()) {
                item(key = "spend_lens") {
                    SpendLensCard(
                        categories = lensCategories,
                        selectedKeys = lensKeys,
                        onlyMode = lensOnlyMode,
                        periodLabel = selectedTimeRange.spendLabel,
                        onOpen = { showLensSheet = true },
                        onRemoveKey = { key -> applyLens(lensKeys - key, lensOnlyMode) }
                    )
                }
            }

            item {
                val hasActiveListFilter = searchQuery.isNotBlank() || selectedFilterCategory != "All" || selectedAccount != null || selectedSort != "NEWEST"
                val activeRows = remember(filteredTransactions, pendingDeleteIds) {
                    filteredTransactions.filter { it.transaction.id !in pendingDeleteIds }
                }
                val visibleCount = activeRows.size
                val filteredSpendTotal = remember(activeRows, hasActiveListFilter) {
                    if (!hasActiveListFilter) 0L
                    else activeRows
                        .filter {
                            it.transaction.type == TransactionType.DEBIT &&
                                    !it.transaction.excludeFromSpend &&
                                    it.transaction.category != "Split Settlement" &&
                                    !it.transaction.category.equals("Transfer", ignoreCase = true)
                        }
                        // Same split-aware "my share" the hero and the day totals use.
                        .sumOf { item -> mySpendShare(item) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Transactions",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (hasActiveListFilter && filteredSpendTotal > 0L) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "· ${CurrencyFormat.smart(filteredSpendTotal)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.wrapContentWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .quietClickable {
                                        haptic.tap()
                                        budgetsStartInForm = customBudgets.isEmpty()
                                        showBudgetsSheet = true
                                    }
                            ) {
                                Icon(
                                    Icons.Default.Savings,
                                    contentDescription = "Custom budgets",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(16.dp)
                                )
                            }
                            if (lensCategories.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = if (lensKeys.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .quietClickable { haptic.tap(); showLensSheet = true }
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Spend breakdown",
                                        tint = if (lensKeys.isNotEmpty()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(16.dp)
                                    )
                                }
                            }
                            AnimatedContent(
                                targetState = "$visibleCount ${if (visibleCount == 1) "entry" else "entries"}",
                                transitionSpec = {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                                },
                                label = "visibleEntriesAnim"
                            ) { countText ->
                                Text(
                                    countText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (hasActiveListFilter) {
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .quietClickable {
                                            haptic.tap()
                                            searchQuery = ""
                                            debouncedSearchQuery = ""
                                            searchOpen = false
                                            selectedFilterCategory = "All"
                                            setSort("NEWEST")
                                            viewModel.selectedAccount.value = null
                                        }
                                ) {
                                    Text(
                                        "Reset",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    val showCategoryFilterChips = visibleCount >= 4 || searchOpen || searchQuery.isNotBlank() || selectedFilterCategory != "All" || selectedSort != "NEWEST"
                    val showAccountFilterChips = linkedAccounts.size > 1 || selectedAccount != null
                    if (showCategoryFilterChips || showAccountFilterChips) {
                        val hasUnspecified = remember(transactions) {
                            transactions.asSequence().take(1000).any { it.bankName == null }
                        }
                        DashboardSearchAndFilter(
                            searchOpen = searchOpen,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            onSearchClear = { searchQuery = ""; debouncedSearchQuery = "" },
                            focusManager = focusManager,
                            haptic = haptic,
                            selectedFilterCategory = selectedFilterCategory,
                            onCategorySelected = { selectedFilterCategory = it },
                            allAvailableCategories = allAvailableCategories,
                            onManageCategories = { showManageCategoriesDialog = true },
                            linkedAccounts = linkedAccounts,
                            selectedAccount = selectedAccount,
                            onAccountSelected = { viewModel.selectedAccount.value = it },
                            hasUnspecifiedAccount = hasUnspecified,
                            onAutoDetectBanks = { showBankInfoDialog = true },
                            showCategoryChips = showCategoryFilterChips,
                            selectedSort = selectedSort,
                            onSortSelected = { setSort(it) }
                        )
                    }
                }
            }

            if (filteredTransactions.isEmpty()) {
                item(key = "empty_state") {
                    AnimatedVisibility(visible = true, enter = fadeIn(animationSpec = tween(500))) {
                        // The default "Month" range is not a user filter: an empty cycle is simply an empty cycle.
                        val hasUserFilters = searchQuery.isNotBlank() || selectedFilterCategory != "All" ||
                                selectedSort != "NEWEST" || selectedAccount != null
                        val isFiltering = transactions.isNotEmpty() && hasUserFilters
                        val isEmptyPeriod = transactions.isNotEmpty() && !hasUserFilters && selectedTimeRange != TimeRange.ALL_TIME
                        when {
                            isFiltering -> EmptyState(title = "No transactions match", icon = Icons.Default.Search) {
                                TextButton(onClick = {
                                    haptic.tap()
                                    searchQuery = ""; debouncedSearchQuery = ""; searchOpen = false
                                    selectedFilterCategory = "All"
                                    setSort("NEWEST")
                                    viewModel.selectedAccount.value = null
                                }) { Text("Clear filters") }
                            }
                            isEmptyPeriod -> {
                                // A brand-new cycle must not look like the app lost your data: say what last cycle looked like.
                                val lastCycleSpent = remember(transactionsWithDebts, startDayOfMonth, pendingDeleteIds) {
                                    computeCycleCategoryNetSpend(
                                        transactions = transactionsWithDebts,
                                        cycleStart = startMillisFor(TimeRange.LAST_MONTH, startDayOfMonth),
                                        cycleEnd = endMillisFor(TimeRange.LAST_MONTH, startDayOfMonth),
                                        pendingDeleteIds = pendingDeleteIds.keys
                                    ).values.sumOf { it.first.coerceAtLeast(0L) }
                                }
                                val canViewLast = selectedTimeRange == TimeRange.THIS_MONTH
                                EmptyState(
                                    title = if (canViewLast) "A fresh cycle has started" else "No transactions in this period",
                                    subtitle = if (canViewLast && lastCycleSpent > 0L)
                                        "Last cycle you spent ${CurrencyFormat.withSymbol(lastCycleSpent, 0)}. Your history is safe, and new payments show up here automatically."
                                    else "New payments show up here automatically.",
                                    icon = Icons.Default.Receipt
                                ) {
                                    if (canViewLast) {
                                        Button(
                                            onClick = { haptic.tap(); selectedTimeRange = TimeRange.LAST_MONTH },
                                            shape = RoundedCornerShape(AppRadius.pill),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.onSurface,
                                                contentColor = MaterialTheme.colorScheme.surface
                                            ),
                                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                                        ) { Text("View previous cycle", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                                    }
                                    TextButton(onClick = { haptic.tap(); selectedTimeRange = TimeRange.ALL_TIME }) { Text("See all transactions") }
                                }
                            }
                            else -> EmptyState(
                                title = "All clean! Add your first expense to get started.",
                                emoji = "✨"
                            ) {
                                Button(
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
                        }
                    }
                }
            } else {
                grouped.forEach { dayGroup ->
                    dashboardDayGroup(
                        showDate = selectedSort != "NEWEST",
                        entranceStamp = sortChangeStamp,
                        staggerRows = selectedSort == "HIGHEST",
                        label = dayGroup.label,
                        rows = dayGroup.rows,
                        daySpentPaise = dayGroup.daySpentPaise,
                        pendingDeleteIds = pendingDeleteIds,
                        splitLinkInfo = splitLinkInfo,
                        billLabelMap = billLabelMap,
                        selectedTxnIds = selectedTxnIds,
                        isSelectionMode = isSelectionMode,
                        linkedAccountsSize = linkedAccounts.size,
                        haptic = haptic,
                        onUndoTransactionDelete = { id -> viewModel.undoTransactionDelete(id) },
                        onSelectionChange = { id ->
                            selectedTxnIds = if (selectedTxnIds.contains(id)) selectedTxnIds - id else selectedTxnIds + id
                            if (selectedTxnIds.isEmpty()) isSelectionMode = false
                        },
                        onEditTransaction = { id -> activeTransactionForEditId = id },
                        onEnableSelectionMode = { id ->
                            isSelectionMode = true
                            selectedTxnIds = setOf(id)
                        },
                        onNavigateToOriginalBill = { txn -> navigateToOriginalDebit(txn) }
                    )
                }
            }
        }
    }
    }

}

