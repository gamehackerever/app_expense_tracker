@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.insights

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.dao.CategoryInsight
import com.expensetracker.offline.data.local.dao.PayeeInsight
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.engine.ml.SmartExpenseEngine
import com.expensetracker.offline.ui.components.AppDialog
import com.expensetracker.offline.ui.components.EditTransactionSheet
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityManager
import com.expensetracker.offline.util.confirm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: MainViewModel,
    isAppLocked: Boolean = false,
    onBack: () -> Unit,
    onNavigateToSettings: (String?) -> Unit = {},
    onNavigateToNecessities: () -> Unit = {}
) {
    val allTransactions by viewModel.allTransactionsWithDebts.collectAsState()
    val linkedAccounts by viewModel.linkedAccounts.collectAsState()
    val recurringPayments by viewModel.recurringPayments.collectAsState()

    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    val startDayOfMonth by viewModel.startDayOfMonth.collectAsState()
    var necessities by remember { mutableStateOf(NecessityManager.loadNecessities(prefs)) }

    val categoryBudgets by viewModel.categoryBudgets.collectAsState()
    val dismissedCategoryWarningLevels by viewModel.dismissedCategoryWarningLevels.collectAsState()
    val pendingDeleteIds by viewModel.pendingDeleteIds.collectAsState()
    val monthlyBudget by viewModel.monthlyBudget.collectAsState()

    var showManageCategoryBudgetsSheet by remember { mutableStateOf(false) }
    var initialCategoryForBudgetSheet by remember { mutableStateOf<String?>(null) }
    var showStartDayDialog by remember { mutableStateOf(false) }
    var activeTransactionForEditId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeTransactionForEdit = remember(activeTransactionForEditId, allTransactions) {
        allTransactions.find { it.transaction.id == activeTransactionForEditId }?.transaction
    }

    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) {
                    firstResume = false
                } else {
                    scope.launch(Dispatchers.IO) {
                        val loaded = NecessityManager.loadNecessities(prefs)
                        withContext(Dispatchers.Main) {
                            viewModel.refreshSettings()
                            necessities = loaded
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedAccountFilter by remember { mutableStateOf<AccountInfo?>(null) }
    val accountFilter = selectedAccountFilter

    // Current billing cycle bounds
    var cycleOffset by rememberSaveable { mutableIntStateOf(0) }
    val isCurrent = cycleOffset == 0

    val (cycleStartMillis, cycleEndMillis) = remember(startDayOfMonth, cycleOffset) {
        BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, cycleOffset)
    }

    val earliestTxnMillis = remember(allTransactions) {
        if (allTransactions.isEmpty()) null
        else {
            val firstTs = allTransactions.first().transaction.timestamp
            val lastTs = allTransactions.last().transaction.timestamp
            if (firstTs >= lastTs) lastTs else allTransactions.minOfOrNull { it.transaction.timestamp }
        }
    }

    val canGoBack = remember(earliestTxnMillis, cycleStartMillis) {
        val earliest = earliestTxnMillis
        earliest != null && cycleStartMillis > earliest
    }

    val now = remember(allTransactions, cycleStartMillis) { System.currentTimeMillis() }
    val effectiveNow = minOf(now, cycleEndMillis)   // past cycles use their full length
    val cycleLengthDays = ceil((cycleEndMillis - cycleStartMillis).toDouble() / DAY_MS).toInt().coerceAtLeast(1)
    val elapsedDays = ceil((effectiveNow - cycleStartMillis).toDouble() / DAY_MS).toInt().coerceIn(1, cycleLengthDays)
    val daysLeft = if (isCurrent) ceil((cycleEndMillis - now).toDouble() / DAY_MS).toInt().coerceAtLeast(0) else 0

    // Single-pass synchronous snapshot so Frame 1 of Dashboard -> Insights has 100% of data ready
    // without 8 async coroutines popping cards into the LazyColumn mid-transition.
    val filteredTransactions = remember(allTransactions, accountFilter, cycleStartMillis, cycleEndMillis, pendingDeleteIds) {
        val isDesc = allTransactions.size < 2 ||
                allTransactions.first().transaction.timestamp >= allTransactions.last().transaction.timestamp
        val result = ArrayList<TransactionWithDebts>()
        for (item in allTransactions) {
            val t = item.transaction.timestamp
            if (t > cycleEndMillis) continue
            if (t < cycleStartMillis) {
                if (isDesc) break else continue
            }
            if (item.transaction.id !in pendingDeleteIds && matchesAccount(item.transaction, accountFilter)) {
                result.add(item)
            }
        }
        result
    }

    val merchants = remember(filteredTransactions) {
        filteredTransactions
            .filter { isSpendDebit(it.transaction) }
            .groupBy { it.transaction.payee }
            .map { (payee, txns) ->
                PayeeInsight(
                    payee = payee,
                    totalSpent = txns.sumOf { myShareOf(it) },
                    transactionCount = txns.size,
                    lastSpentTimestamp = txns.maxOf { it.transaction.timestamp }
                )
            }
            .sortedByDescending { it.totalSpent }
    }

    val categories = remember(filteredTransactions) {
        val refundsByCat = filteredTransactions
            .filter { com.expensetracker.offline.ui.dashboard.isRefundCredit(it.transaction) }
            .groupBy { it.transaction.category.trim().lowercase() }
            .mapValues { (_, list) -> list.sumOf { it.transaction.amount } }

        filteredTransactions
            .filter { isSpendDebit(it.transaction) }
            .groupBy { it.transaction.category.trim() }
            .mapNotNull { (category, txns) ->
                val gross = txns.sumOf { myShareOf(it) }
                val refund = refundsByCat[category.lowercase()] ?: 0L
                val net = (gross - refund).coerceAtLeast(0L)
                if (net > 0L || txns.isNotEmpty()) {
                    CategoryInsight(
                        category = category,
                        totalSpent = net,
                        transactionCount = txns.size
                    )
                } else null
            }
            .sortedByDescending { it.totalSpent }
    }

    val stats = remember(filteredTransactions, allTransactions, accountFilter, cycleStartMillis, cycleEndMillis, elapsedDays, startDayOfMonth, cycleOffset, pendingDeleteIds, earliestTxnMillis) {
        val elapsed = (minOf(System.currentTimeMillis(), cycleEndMillis) - cycleStartMillis).coerceAtLeast(0L)
        val (prevStart, _) = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, cycleOffset - 1)
        val prevEnd = minOf(prevStart + elapsed, cycleStartMillis)

        val isDesc = allTransactions.size < 2 ||
                allTransactions.first().transaction.timestamp >= allTransactions.last().transaction.timestamp
        var prevSpent = 0L
        val prevActiveDays = HashSet<Int>()
        for (item in allTransactions) {
            val t = item.transaction.timestamp
            if (t >= prevEnd) continue
            if (t < prevStart) {
                if (isDesc) break else continue
            }
            if (item.transaction.id !in pendingDeleteIds &&
                isSpendDebit(item.transaction) &&
                matchesAccount(item.transaction, accountFilter)
            ) {
                val share = myShareOf(item)
                if (share > 0L) {
                    prevSpent += share
                    prevActiveDays.add(((t - prevStart) / DAY_MS).toInt())
                }
            }
        }

        // Only treat prior-cycle spend as a valid comparison baseline when the user's transaction
        // history actually spans back to the start of the previous cycle and has at least 3 active
        // spend days (and >= ₹500 spent), preventing partial/onboarding history from producing
        // misleading "+2169%" spikes.
        val hasPriorHistoryCoverage = earliestTxnMillis != null && earliestTxnMillis <= (prevStart + 5 * DAY_MS)
        val hasReliablePrev = hasPriorHistoryCoverage && prevSpent >= 500_00L && prevActiveDays.size >= 3

        val spendTxns = filteredTransactions.filter { isSpendDebit(it.transaction) && myShareOf(it) > 0L }
        val largest = spendTxns.maxByOrNull { myShareOf(it) }

        val activeDaysCount = spendTxns
            .map { ((it.transaction.timestamp - cycleStartMillis) / DAY_MS).toInt() }
            .distinct()
            .count()
        val spendFree = (elapsedDays - activeDaysCount).coerceAtLeast(0)

        CycleStats(
            prevSpent = prevSpent,
            hasReliablePrevBaseline = hasReliablePrev,
            largestAmount = largest?.let { myShareOf(it) } ?: 0L,
            largestPayee = largest?.transaction?.payee,
            largestTxnId = largest?.transaction?.id,
            spendFreeDays = spendFree
        )
    }

    val cycleIncomePaise = remember(filteredTransactions) {
        filteredTransactions
            .filter { isIncomeCredit(it.transaction) }
            .sumOf { it.transaction.amount }
    }

    val cycleInvestmentsPaise = remember(filteredTransactions) {
        filteredTransactions
            .filter { isSpendDebit(it.transaction) && it.transaction.category.equals("Investments", ignoreCase = true) }
            .sumOf { myShareOf(it) }
    }

    val dailySpendMap = remember(filteredTransactions, cycleStartMillis) {
        val map = HashMap<Int, Long>()
        for (item in filteredTransactions) {
            if (isSpendDebit(item.transaction)) {
                val share = myShareOf(item)
                if (share > 0L) {
                    val dayIdx = ((item.transaction.timestamp - cycleStartMillis) / DAY_MS).toInt().coerceAtLeast(0)
                    map[dayIdx] = (map[dayIdx] ?: 0L) + share
                }
            }
        }
        map
    }

    val trendPoints = remember(allTransactions, accountFilter, startDayOfMonth, cycleOffset, pendingDeleteIds) {
        val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())
        val bounds = ((-5)..0).map { rel ->
            val (cStart, cEnd) = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, cycleOffset + rel)
            Triple(rel, cStart, cEnd)
        }
        val earliestStart = bounds.first().second
        val latestEnd = bounds.last().third
        val spentByIdx = LongArray(6)
        val incomeByIdx = LongArray(6)
        val isDesc = allTransactions.size < 2 ||
                allTransactions.first().transaction.timestamp >= allTransactions.last().transaction.timestamp

        for (item in allTransactions) {
            val t = item.transaction.timestamp
            if (t > latestEnd) continue
            if (t < earliestStart) {
                if (isDesc) break else continue
            }
            if (item.transaction.id in pendingDeleteIds || !matchesAccount(item.transaction, accountFilter)) continue
            for (i in 0 until 6) {
                val b = bounds[i]
                if (t in b.second..b.third) {
                    if (isSpendDebit(item.transaction)) spentByIdx[i] += myShareOf(item)
                    else if (isIncomeCredit(item.transaction)) incomeByIdx[i] += item.transaction.amount
                    break
                }
            }
        }

        bounds.mapIndexed { i, (rel, cStart, cEnd) ->
            // Use the cycle midpoint so a cycle like "28 Sept – 27 Oct" (where 24 days are in Oct)
            // is labeled "Oct" rather than "Sept".
            val midMillis = cStart + (cEnd - cStart) / 2L
            CycleTrendPoint(
                label = monthFmt.format(java.util.Date(midMillis)),
                rangeLabel = formatCycleRange(cStart, cEnd),
                spentPaise = spentByIdx[i],
                incomePaise = incomeByIdx[i],
                isSelected = rel == 0
            )
        }
    }

    val activeWarnings = remember(isCurrent, selectedAccountFilter, allTransactions, categoryBudgets, startDayOfMonth, pendingDeleteIds, dismissedCategoryWarningLevels) {
        if (isCurrent && selectedAccountFilter == null) {
            com.expensetracker.offline.ui.dashboard.computeCategoryBudgetStatuses(
                transactions = allTransactions,
                categoryBudgets = categoryBudgets,
                startDayOfMonth = startDayOfMonth,
                pendingDeleteIds = pendingDeleteIds.keys
            ).filter { status ->
                if (!status.isOverBudget && !status.isAtLimit && !status.isNearLimit) return@filter false
                val dismissedLevel = dismissedCategoryWarningLevels.entries
                    .firstOrNull { it.key.equals(status.category, ignoreCase = true) }
                    ?.value
                when {
                    status.isOverBudget -> dismissedLevel != com.expensetracker.offline.data.repository.SettingsRepository.WARNING_LEVEL_OVER
                    else -> dismissedLevel == null
                }
            }
        } else emptyList()
    }

    val isLoaded = true
    val grandTotalDebit: Long = remember(categories) { categories.sumOf { it.totalSpent } }
    val totalTransactions = remember(categories) { categories.sumOf { it.transactionCount } }
    val hasSpending = totalTransactions > 0

    val dailyAverage: Double = if (elapsedDays > 0) grandTotalDebit.toDouble() / elapsedDays else 0.0
    val deltaPct: Double? = if (stats.prevSpent > 0L) ((grandTotalDebit - stats.prevSpent).toDouble() / stats.prevSpent.toDouble()) * 100.0 else null

    // Winsorized EWMA + Day-of-Week Seasonality Forecaster (dampens one-off large spikes)
    val smartProjectedPaise: Long = remember(filteredTransactions, allTransactions, cycleStartMillis, cycleEndMillis, elapsedDays, cycleLengthDays, grandTotalDebit) {
        if (!hasSpending) 0L
        else {
            val rawForecast = SmartExpenseEngine.forecastCycleSpendPaise(
                cycleTransactions = filteredTransactions,
                allTransactions = allTransactions,
                cycleStartMillis = cycleStartMillis,
                cycleEndMillis = cycleEndMillis,
                elapsedDays = elapsedDays,
                cycleLengthDays = cycleLengthDays,
                myShareFn = { item -> if (isSpendDebit(item.transaction)) myShareOf(item) else 0L }
            )
            // Ensure net refunds are respected in the baseline
            val grossSum = filteredTransactions.sumOf { if (isSpendDebit(it.transaction)) myShareOf(it) else 0L }
            val refundOffset = (grossSum - grandTotalDebit).coerceAtLeast(0L)
            (rawForecast - refundOffset).coerceAtLeast(grandTotalDebit)
        }
    }

    // MAD Anomaly & Behavioral Habit Discovery Engine
    val smartInsights = remember(filteredTransactions, cycleStartMillis, elapsedDays) {
        SmartExpenseEngine.discoverSmartInsights(
            cycleTransactions = filteredTransactions,
            cycleStartMillis = cycleStartMillis,
            elapsedDays = elapsedDays,
            myShareFn = { item -> if (isSpendDebit(item.transaction)) myShareOf(item) else 0L },
            formatRupeesFn = { paise -> formatRupees(paise) }
        )
    }

    var selectedCategoryForBreakdown by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMerchantForBreakdown by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToCategoryBreakdown by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToMerchantBreakdown by rememberSaveable { mutableStateOf<String?>(null) }

    val cycleProgress = (elapsedDays.toFloat() / cycleLengthDays).coerceIn(0f, 1f)

    var isInsightsReady by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isAppLocked) {
        if (isAppLocked) {
            isInsightsReady = false
        } else if (!isInsightsReady) {
            delay(120)
            isInsightsReady = true
        }
    }
    val startBarAnimations = !isAppLocked && isInsightsReady
    val seenSections = remember { mutableSetOf<String>() }

    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 }
    }
    val headerDividerAlpha by animateFloatAsState(if (isScrolled) 1f else 0f, tween(180), label = "insightsHeaderDivider")

    CompositionLocalProvider(LocalAnimationReady provides startBarAnimations) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = {
                        Text("Insights", fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp)
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.tap()
                            onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f * headerDividerAlpha)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 48.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            item(key = "cycle") {
                EntranceItem("cycle", 0, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                    CycleSelector(
                        startMillis = cycleStartMillis,
                        endMillis = cycleEndMillis,
                        isCurrent = isCurrent,
                        daysLeft = daysLeft,
                        canGoBack = canGoBack,
                        onPrevious = { haptic.tap(); cycleOffset-- },
                        onNext = { haptic.tap(); cycleOffset++ },
                        onJumpToCurrent = { haptic.tap(); cycleOffset = 0 }
                    )
                }
            }

            // ── Account filter: scopes everything down to "Top merchants" ──
            if (linkedAccounts.size > 1) {
                item(key = "accounts") {
                    EntranceItem(
                        "accounts",
                        1,
                        seenSections,
                        Modifier.animateItem(fadeInSpec = null)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InsightsAccountChip(
                                label = "All Accounts",
                                isSelected = selectedAccountFilter == null,
                                onClick = {
                                    haptic.tap()
                                    selectedAccountFilter = null
                                }
                            )
                            linkedAccounts.forEach { account ->
                                val label = if (account.accountNumber != null)
                                    "${account.bankName} (..${account.accountNumber})"
                                else account.bankName
                                InsightsAccountChip(
                                    label = label,
                                    isSelected = selectedAccountFilter == account,
                                    onClick = {
                                        haptic.tap()
                                        selectedAccountFilter = account
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // --- CATEGORY BUDGET WARNINGS (Active current cycle only, two-stage dismissal) ---

            if (activeWarnings.isNotEmpty()) {
                item(key = "budget_warnings") {
                    EntranceItem("budget_warnings", 1, seenSections, Modifier.animateItem()) {
                        com.expensetracker.offline.ui.dashboard.CategoryBudgetWarningBanner(
                            statuses = activeWarnings,
                            onManageClick = {
                                initialCategoryForBudgetSheet = null
                                showManageCategoryBudgetsSheet = true
                            },
                            onDismissClick = { cats: List<String> ->
                                cats.forEach { catName ->
                                    val st = activeWarnings.firstOrNull { it.category.equals(catName, ignoreCase = true) }
                                    val lvl = if (st?.isOverBudget == true) {
                                        com.expensetracker.offline.data.repository.SettingsRepository.WARNING_LEVEL_OVER
                                    } else {
                                        com.expensetracker.offline.data.repository.SettingsRepository.WARNING_LEVEL_NEAR
                                    }
                                    viewModel.dismissCategoryWarning(catName, lvl)
                                }
                            }
                        )
                    }
                }
            }

            // ── Hero: this cycle's spend, with a trend against the same point last cycle ──
            item(key = "hero") {
                EntranceItem("hero", 2, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppRadius.large),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (isCurrent) "Net spending this cycle" else "Net spending",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Clickable pill opens the Billing Cycle dialog directly in-place
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
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            MoneyHero(amount = grandTotalDebit / 100.0, fractionDigits = 2, size = 44.sp, sessionKey = "insights_hero_spend")

                            Spacer(modifier = Modifier.height(4.dp))
                            val budgetContextSuffix = if (hasSpending && monthlyBudget > 0L && selectedAccountFilter == null) {
                                val pctOfBudget = ((grandTotalDebit.toDouble() / (monthlyBudget * 100.0)) * 100.0).roundToInt()
                                " · $pctOfBudget% of ${formatRupees(monthlyBudget * 100L)} budget"
                            } else ""

                            Text(
                                if (hasSpending)
                                    "Your share across $totalTransactions ${if (totalTransactions == 1) "transaction" else "transactions"}$budgetContextSuffix"
                                else if (isCurrent) "No spending yet this cycle" else "No spending in this cycle",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Cycle clock: where we are in the billing cycle
                            Spacer(modifier = Modifier.height(18.dp))
                            InsightsProgressBar(
                                progress = if (startBarAnimations) cycleProgress else 0f,
                                color = MaterialTheme.colorScheme.onSurface,
                                sessionKey = "insights_cycle_progress_bar"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    if (isCurrent) "Day $elapsedDays of $cycleLengthDays" else "$cycleLengthDays-day cycle",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    when {
                                        !isCurrent -> "Closed"
                                        daysLeft == 0 -> "Resets today"
                                        daysLeft == 1 -> "1 day left"
                                        else -> "$daysLeft days left"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val monthlyBudgetPaise = monthlyBudget * 100L
                            val hasActiveBudgetHere = monthlyBudgetPaise > 0L && selectedAccountFilter == null
                            val showProjected = hasSpending && isCurrent && daysLeft > 0 && elapsedDays >= 3
                            val showSafeAllowance = hasSpending && isCurrent && hasActiveBudgetHere && daysLeft > 0
                            val showPrevComparison = hasSpending && !showSafeAllowance && stats.hasReliablePrevBaseline
                            val showNoSpend = hasSpending && stats.spendFreeDays > 0
                            if (showProjected || showSafeAllowance || showPrevComparison || showNoSpend) {
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (showProjected) {
                                        val projPaise = smartProjectedPaise
                                        val overBudget = hasActiveBudgetHere && projPaise > monthlyBudgetPaise
                                        val projCaption = when {
                                            overBudget -> "+${formatRupees(projPaise - monthlyBudgetPaise)} over cap"
                                            hasActiveBudgetHere -> "${formatRupees(monthlyBudgetPaise - projPaise)} under cap"
                                            else -> "by cycle end"
                                        }
                                        HeroFact(
                                            modifier = Modifier.weight(1f),
                                            label = "Projected",
                                            value = formatRupees(projPaise),
                                            amountPaise = projPaise,
                                            caption = projCaption,
                                            valueColor = if (overBudget) InsightsColors.negative else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (showSafeAllowance) {
                                        val remainingPaise = monthlyBudgetPaise - grandTotalDebit
                                        if (remainingPaise > 0L) {
                                            val safePerDayPaise = remainingPaise / daysLeft
                                            HeroFact(
                                                modifier = Modifier.weight(1f),
                                                label = "Safe daily pace",
                                                value = "${formatRupees(safePerDayPaise)}/day",
                                                caption = "${formatRupees(remainingPaise)} left · ${daysLeft}d",
                                                valueColor = if (grandTotalDebit.toDouble() / monthlyBudgetPaise.toDouble() >= 0.90) MaterialTheme.colorScheme.onSurface else InsightsColors.positive
                                            )
                                        } else {
                                            val overPaise = grandTotalDebit - monthlyBudgetPaise
                                            HeroFact(
                                                modifier = Modifier.weight(1f),
                                                label = "Budget status",
                                                value = "+${formatRupees(overPaise)}",
                                                caption = "above ${formatRupees(monthlyBudgetPaise)} cap",
                                                valueColor = InsightsColors.negative
                                            )
                                        }
                                    } else if (showPrevComparison) {
                                        val diffPaise = grandTotalDebit - stats.prevSpent
                                        val muted = MaterialTheme.colorScheme.onSurfaceVariant
                                        val (trendText, trendColor) = when {
                                            abs(diffPaise) < 50_00L -> "Same pace" to muted
                                            diffPaise > 0L -> "+${formatRupees(diffPaise)}" to InsightsColors.negative
                                            else -> "−${formatRupees(abs(diffPaise))}" to InsightsColors.positive
                                        }
                                        HeroFact(
                                            modifier = Modifier.weight(1f),
                                            label = if (isCurrent) "vs last cycle (${elapsedDays}d)" else "vs prior cycle",
                                            value = trendText,
                                            caption = "was ${formatRupees(stats.prevSpent)} last cycle",
                                            valueColor = trendColor
                                        )
                                    }
                                    if (showNoSpend) {
                                        HeroFact(
                                            modifier = Modifier.weight(1f),
                                            label = "Zero-spend",
                                            value = "${stats.spendFreeDays} ${if (stats.spendFreeDays == 1) "day" else "days"}",
                                            caption = "of $elapsedDays elapsed",
                                            valueColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Quick stats ──
            if (hasSpending) {
                item(key = "stats") {
                    EntranceItem(
                        "stats",
                        3,
                        seenSections,
                        Modifier.animateItem(fadeInSpec = null)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            InsightsStatTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.CalendarToday,
                                label = "Daily average",
                                value = formatRupees(dailyAverage),
                                amountPaise = dailyAverage.toLong(),
                                caption = "over $elapsedDays ${if (elapsedDays == 1) "day" else "days"}",
                                accent = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            InsightsStatTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ReceiptLong,
                                label = "Largest",
                                value = formatRupees(stats.largestAmount),
                                amountPaise = stats.largestAmount,
                                caption = stats.largestPayee ?: "—",
                                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = stats.largestTxnId?.let { id ->
                                    {
                                        haptic.tap()
                                        activeTransactionForEditId = id
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ── Cash Flow & Wealth (Income vs Discretionary vs Investments) ──
            if (hasSpending || cycleIncomePaise > 0L) {
                item(key = "cash_flow") {
                    EntranceItem("cash_flow", 3, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                        CashFlowSummaryCard(
                            totalIncomePaise = cycleIncomePaise,
                            discretionarySpendPaise = (grandTotalDebit - cycleInvestmentsPaise).coerceAtLeast(0L),
                            investmentsPaise = cycleInvestmentsPaise,
                            isAnimating = startBarAnimations
                        )
                    }
                }
            }

            // ── On-Device ML Learned Patterns (Habits, Time-of-Day Rhythm, MAD Anomalies) ──
            if (smartInsights.isNotEmpty()) {
                item(key = "smart_patterns") {
                    EntranceItem("smart_patterns", 3, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                        SmartInsightsCard(
                            insights = smartInsights,
                            onTransactionClick = { txnId ->
                                activeTransactionForEditId = txnId
                            }
                        )
                    }
                }
            }

            // ── 6-Cycle Spending & Income Trend Bar Chart ──
            if (trendPoints.any { it.spentPaise > 0L || it.incomePaise > 0L }) {
                item(key = "multi_cycle_trend") {
                    EntranceItem("multi_cycle_trend", 4, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                        MultiCycleTrendCard(
                            points = trendPoints,
                            monthlyBudgetPaise = if (selectedAccountFilter == null) monthlyBudget * 100L else 0L,
                            isAnimating = startBarAnimations
                        )
                    }
                }
            }

            // ── Daily Calendar Heatmap ──
            if (hasSpending) {
                item(key = "daily_heatmap") {
                    EntranceItem("daily_heatmap", 4, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                        DailyCalendarHeatmapCard(
                            cycleStartMillis = cycleStartMillis,
                            cycleLengthDays = cycleLengthDays,
                            dailySpendMap = dailySpendMap
                        )
                    }
                }
            }

            // ── Spending breakdown (reacts to the filter above) ──
            if (isLoaded && !hasSpending) {
                item(key = "empty") {
                    EntranceItem("empty", 3, seenSections, Modifier.animateItem(fadeInSpec = null)) {
                        EmptyInsightState(text = "No spending recorded in this cycle")
                    }
                }
            }

            if (isLoaded && hasSpending) {
                item(key = "categories") {
                    EntranceItem(
                        "categories",
                        4,
                        seenSections,
                        Modifier.animateItem(fadeInSpec = null)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionHeader(
                                title = "By category",
                                trailing = if (categories.size == 1) "1 category" else "${categories.size} categories",
                                actionIcon = if (categoryBudgets.isEmpty()) Icons.Default.Add else Icons.Default.Edit,
                                actionLabel = if (categoryBudgets.isEmpty()) "Set limits"
                                else "${categoryBudgets.size} ${if (categoryBudgets.size == 1) "limit" else "limits"}",
                                onActionClick = {
                                    haptic.tap()
                                    initialCategoryForBudgetSheet = null
                                    showManageCategoryBudgetsSheet = true
                                }
                            )
                            CategoryBreakdownCard(
                                categories = categories,
                                categoryBudgets = categoryBudgets,
                                grandTotal = grandTotalDebit,
                                isAnimating = startBarAnimations,
                                onCategoryClick = { selectedCategoryForBreakdown = it },
                                onManageBudgetsClick = {
                                    initialCategoryForBudgetSheet = null
                                    showManageCategoryBudgetsSheet = true
                                },
                                haptic = haptic
                            )
                        }
                    }
                }

                item(key = "merchants") {
                    EntranceItem(
                        "merchants",
                        5,
                        seenSections,
                        Modifier.animateItem(fadeInSpec = null)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionHeader(
                                title = "Top merchants",
                                trailing = if (merchants.size == 1) "1 merchant" else "${merchants.size} merchants"
                            )
                            MerchantsCard(
                                merchants = merchants,
                                grandTotal = grandTotalDebit,
                                isAnimating = startBarAnimations,
                                onMerchantClick = { selectedMerchantForBreakdown = it }
                            )
                        }
                    }
                }
            }

            // ── Commitments (NOT affected by the account filter or the cycle) ──
            item(key = "commitments") {
                EntranceItem(
                    "commitments",
                    6,
                    seenSections,
                    Modifier.animateItem(fadeInSpec = null)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(title = "Commitments")
                        RecurringPaymentsSection(
                            payments = recurringPayments,
                            necessities = necessities,
                            onAddToFixedCosts = { draft ->
                                haptic.tap()
                                viewModel.requestNecessityPrefill(draft)
                                onNavigateToNecessities()
                            }
                        )
                        val totalMonthlyReservePaise = remember(necessities) {
                            necessities.sumOf { item ->
                                if (item.frequency == com.expensetracker.offline.util.NecessityFrequency.MONTHLY || item.isProrated)
                                    NecessityManager.getMonthlyReserveAmount(item)
                                else 0L
                            }
                        }
                        FixedCostsRow(
                            trackedCount = necessities.size,
                            reservedMonthlyPaise = totalMonthlyReservePaise,
                            onClick = {
                                haptic.tap()
                                onNavigateToNecessities()
                            }
                        )
                    }
                }
            }

            item(key = "bottomSpacer") { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
    }

    val catName = selectedCategoryForBreakdown
    if (catName != null) {
        val catBudget = categoryBudgets.entries.firstOrNull { it.key.equals(catName, ignoreCase = true) }?.value
        CategoryTransactionsSheet(
            categoryName = catName,
            allTransactions = filteredTransactions,
            onDismiss = { selectedCategoryForBreakdown = null },
            budgetPaise = catBudget,
            onManageBudgetClick = {
                returnToCategoryBreakdown = catName
                selectedCategoryForBreakdown = null
                initialCategoryForBudgetSheet = catName
                showManageCategoryBudgetsSheet = true
            },
            onTransactionClick = { txn ->
                returnToCategoryBreakdown = catName
                selectedCategoryForBreakdown = null
                activeTransactionForEditId = txn.id
            }
        )
    }

    val merchantName = selectedMerchantForBreakdown
    if (merchantName != null) {
        MerchantTransactionsSheet(
            merchantName = merchantName,
            allTransactions = filteredTransactions,
            onDismiss = { selectedMerchantForBreakdown = null },
            onTransactionClick = { txn ->
                returnToMerchantBreakdown = merchantName
                selectedMerchantForBreakdown = null
                activeTransactionForEditId = txn.id
            }
        )
    }

    if (showStartDayDialog) {
        var tempDay by remember(startDayOfMonth) { mutableFloatStateOf(startDayOfMonth.toFloat()) }
        AppDialog(
            title = "Billing Cycle",
            onDismiss = { haptic.tap(); showStartDayDialog = false },
            confirmText = "Save",
            onConfirm = {
                haptic.confirm()
                viewModel.updateStartDayOfMonth(tempDay.toInt())
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
                        haptic.tap()
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

    val activeTxnForEdit = activeTransactionForEdit
    if (activeTxnForEdit != null) {
        val txn = activeTxnForEdit
        val customCats = remember { prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet() }
        val availableCats = remember(customCats, allTransactions) {
            (com.expensetracker.offline.engine.categorizer.CategoryClassifier.PREDEFINED_CATEGORIES +
                    customCats +
                    allTransactions.map { it.transaction.category })
                .filter { it.isNotBlank() }
                .distinct()
        }
        val returnFromEdit = {
            activeTransactionForEditId = null
            if (returnToCategoryBreakdown != null) {
                selectedCategoryForBreakdown = returnToCategoryBreakdown
                returnToCategoryBreakdown = null
            } else if (returnToMerchantBreakdown != null) {
                selectedMerchantForBreakdown = returnToMerchantBreakdown
                returnToMerchantBreakdown = null
            }
        }
        EditTransactionSheet(
            transaction = txn,
            availableCategories = availableCats,
            onDismiss = returnFromEdit,
            onSave = { updatedTxn ->
                viewModel.updateTransaction(updatedTxn, learnRule = true)
                haptic.confirm()
                returnFromEdit()
            },
            onDelete = { id ->
                haptic.confirm()
                viewModel.stageTransactionDelete(id)
                returnFromEdit()
            },
            onDuplicate = { txnToDuplicate ->
                haptic.confirm()
                activeTransactionForEditId = null
                viewModel.addTransaction(
                    txnToDuplicate.copy(id = 0, timestamp = System.currentTimeMillis())
                )
            },
            linkedAccounts = linkedAccounts,
            totalOwedByOthers = allTransactions.find { it.transaction.id == txn.id }?.debts?.sumOf { it.amountOwed.toLong() } ?: 0L,
            onUndoRepayment = { viewModel.undoRepayment(txn) },
            onUpdateAllPast = { oldPayee, newPayee, newCategory ->
                viewModel.updatePayeeAndCategoryForAll(oldPayee, newPayee, newCategory)
            },
            initialSplitPeople = remember(txn.id, allTransactions) {
                val existingFriends = allTransactions
                    .find { it.transaction.id == txn.id }
                    ?.debts
                    ?.map { it.debtorName.trim() }
                    ?.filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                    .orEmpty()
                val recentFriends = allTransactions
                    .asSequence()
                    .take(200)
                    .flatMap { it.debts.asSequence() }
                    .map { it.debtorName.trim() }
                    .filter { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
                    .distinct()
                    .take(5)
                    .toList()
                (listOf("You") + existingFriends + recentFriends).distinct()
            },
            onSaveSplit = { splitParticipants ->
                viewModel.saveMultiSplit(txn.id, splitParticipants, "You")
            }
        )
    }

    if (showManageCategoryBudgetsSheet) {
        val customCats = remember {
            prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet()
        }
        val availableCats = remember(customCats, allTransactions) {
            (com.expensetracker.offline.engine.categorizer.CategoryClassifier.PREDEFINED_CATEGORIES +
                    customCats +
                    allTransactions.map { it.transaction.category })
                .filter { it.isNotBlank() }
                .distinct()
        }
        com.expensetracker.offline.ui.dashboard.ManageCategoryBudgetsSheet(
            availableCategories = availableCats,
            currentBudgets = categoryBudgets,
            onSaveBudget = { cat, limit -> viewModel.setCategoryBudget(cat, limit) },
            onRemoveBudget = { cat -> viewModel.removeCategoryBudget(cat) },
            onDismiss = {
                showManageCategoryBudgetsSheet = false
                initialCategoryForBudgetSheet = null
                if (returnToCategoryBreakdown != null) {
                    selectedCategoryForBreakdown = returnToCategoryBreakdown
                    returnToCategoryBreakdown = null
                }
            },
            initialCategory = initialCategoryForBudgetSheet,
            transactions = allTransactions,
            pendingDeleteIds = pendingDeleteIds.keys,
            monthlyBudgetPaise = monthlyBudget * 100L,
            startDayOfMonth = startDayOfMonth
        )
    }
}
