package com.expensetracker.offline.ui.insights

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.dao.CategoryInsight
import com.expensetracker.offline.data.local.dao.PayeeInsight
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.components.DashboardTransactionCard
import com.expensetracker.offline.ui.components.TransactionGroup
import com.expensetracker.offline.ui.components.TransactionRowDivider
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

import com.expensetracker.offline.util.tick
import com.expensetracker.offline.util.click
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete

private const val DAY_MS = 86_400_000L
private const val TOP_N_BY_DEFAULT = 5

// Same hues as the Dashboard's private AppColors.
private object InsightsColors {
    val positive @Composable get() = if (isSystemInDarkTheme()) Color(0xFF10B981) else Color(0xFF059669)
    val negative @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEF4444) else Color(0xFFDC2626)
}

private fun HapticFeedback.tap() = tick()

private fun formatRupees(value: Double): String =
    "₹" + String.format(Locale.getDefault(), "%,.0f", value / 100.0)

/** Numbers that need a second pass over the data (previous cycle, biggest single expense). */
private data class CycleStats(
    val prevSpent: Double = 0.0,
    val largestAmount: Double = 0.0,
    val largestPayee: String? = null
)

private fun matchesAccount(txn: TransactionEntity, filter: AccountInfo?): Boolean =
    filter == null || (txn.bankName == filter.bankName && txn.accountNumber == filter.accountNumber)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: MainViewModel,
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
    var startDayOfMonth by remember { mutableIntStateOf(prefs.getInt("key_start_day_of_month", 1)) }
    // Fixed Costs, to mark which recurring payments are already tracked there
    var necessities by remember { mutableStateOf(NecessityManager.loadNecessities(prefs)) }

    // Refresh settings instantly if the user just changed them elsewhere
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                startDayOfMonth = prefs.getInt("key_start_day_of_month", 1)
                necessities = NecessityManager.loadNecessities(prefs)
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
        val base = BillingCycleHelper.getCycleRange(startDayOfMonth)
        if (cycleOffset == 0) {
            base
        } else {
            fun shift(ms: Long) = Calendar.getInstance().apply {
                timeInMillis = ms
                add(Calendar.MONTH, cycleOffset)
            }.timeInMillis
            shift(base.first) to shift(base.second)
        }
    }

    val earliestTxnMillis by produceState<Long?>(initialValue = null, allTransactions) {
        value = withContext(Dispatchers.Default) {
            allTransactions.minOfOrNull { it.transaction.timestamp }
        }
    }

// FIX: Capture to a local variable first so the compiler can smart-cast it
    val canGoBack = remember(earliestTxnMillis, cycleStartMillis) {
        val earliest = earliestTxnMillis
        earliest != null && cycleStartMillis > earliest
    }

    val now = remember(allTransactions, cycleStartMillis) { System.currentTimeMillis() }
    val effectiveNow = minOf(now, cycleEndMillis)   // past cycles use their full length
    val cycleLengthDays = ceil((cycleEndMillis - cycleStartMillis).toDouble() / DAY_MS).toInt().coerceAtLeast(1)
    val elapsedDays = ceil((effectiveNow - cycleStartMillis).toDouble() / DAY_MS).toInt().coerceIn(1, cycleLengthDays)
    val daysLeft = if (isCurrent) ceil((cycleEndMillis - now).toDouble() / DAY_MS).toInt().coerceAtLeast(0) else 0

// Filter by account AND the viewed cycle (with an end bound for past cycles)
    val filteredTransactions = remember(allTransactions, accountFilter, cycleStartMillis, cycleEndMillis, isCurrent) {
        allTransactions.filter { item ->
            val t = item.transaction.timestamp
            matchesAccount(item.transaction, accountFilter) &&
                    t >= cycleStartMillis && (isCurrent || t < cycleEndMillis)
        }
    }

    // Heavy aggregations run off the main thread. null = still computing (renders nothing,
    // so the empty message never flashes before the real data).
    val payeeInsights by produceState<List<PayeeInsight>?>(initialValue = null, filteredTransactions) {
        value = withContext(Dispatchers.Default) {
            filteredTransactions
                .filter { it.transaction.type == TransactionType.DEBIT }
                .groupBy { it.transaction.payee }
                .map { (payee, txns) ->
                    PayeeInsight(
                        payee = payee,
                        totalSpent = txns.sumOf { myShareOf(it) }.toDouble(),
                        transactionCount = txns.size,
                        lastSpentTimestamp = txns.maxOf { it.transaction.timestamp }
                    )
                }
                .sortedByDescending { it.totalSpent }
        }
    }

    val categoryInsights by produceState<List<CategoryInsight>?>(initialValue = null, filteredTransactions) {
        value = withContext(Dispatchers.Default) {
            filteredTransactions
                .filter { it.transaction.type == TransactionType.DEBIT }
                .groupBy { it.transaction.category }
                .map { (category, txns) ->
                    CategoryInsight(
                        category = category,
                        totalSpent = txns.sumOf { myShareOf(it) }.toDouble(),
                        transactionCount = txns.size
                    )
                }
                .sortedByDescending { it.totalSpent }
        }
    }

    // Previous cycle up to the same point in time, plus the single biggest expense
    val stats by produceState(
        initialValue = CycleStats(),
        filteredTransactions, allTransactions, accountFilter, cycleStartMillis, cycleEndMillis
    ) {
        value = withContext(Dispatchers.Default) {
            val elapsed = (minOf(System.currentTimeMillis(), cycleEndMillis) - cycleStartMillis).coerceAtLeast(0L)
            val prevStart = Calendar.getInstance().apply {
                timeInMillis = cycleStartMillis
                add(Calendar.MONTH, -1)
            }.timeInMillis
            val prevEnd = minOf(prevStart + elapsed, cycleStartMillis)

            val prevSpent = allTransactions
                .filter {
                    it.transaction.type == TransactionType.DEBIT &&
                            matchesAccount(it.transaction, accountFilter) &&
                            it.transaction.timestamp >= prevStart &&
                            it.transaction.timestamp < prevEnd
                }
                .sumOf { myShareOf(it) }

            val largest = filteredTransactions
                .filter { it.transaction.type == TransactionType.DEBIT }
                .maxByOrNull { myShareOf(it) }

            CycleStats(
                prevSpent = prevSpent.toDouble(),
                largestAmount = (largest?.let { myShareOf(it) } ?: 0L).toDouble(),
                largestPayee = largest?.transaction?.payee
            )
        }
    }

    val merchants = payeeInsights
    val categories = categoryInsights
    val isLoaded = merchants != null && categories != null
    val grandTotalDebit = merchants?.sumOf { it.totalSpent } ?: 0.0
    val totalTransactions = merchants?.sumOf { it.transactionCount } ?: 0
    val hasSpending = totalTransactions > 0

    val dailyAverage = if (elapsedDays > 0) grandTotalDebit / elapsedDays else 0.0
    val deltaPct: Double? = if (stats.prevSpent > 0.0) ((grandTotalDebit - stats.prevSpent) / stats.prevSpent) * 100.0 else null

    var selectedCategoryForBreakdown by remember { mutableStateOf<String?>(null) }

    val animatedGrandTotal by animateFloatAsState(
        targetValue = grandTotalDebit.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "grandTotalAnimation"
    )

    val animatedDaily by animateFloatAsState(
        targetValue = dailyAverage.toFloat(),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "dailyAverageAnimation"
    )
    val animatedLargest by animateFloatAsState(
        targetValue = stats.largestAmount.toFloat(),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "largestExpenseAnimation"
    )

    val cycleProgress = (elapsedDays.toFloat() / cycleLengthDays).coerceIn(0f, 1f)
    val animatedCycle by animateFloatAsState(
        targetValue = cycleProgress,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "cycleProgressAnimation"
    )

    var startBarAnimations by remember { mutableStateOf(false) }
    val seenSections = remember { mutableSetOf<String>() }


    // Re-trigger the bar animations every time the account filter changes
    LaunchedEffect(selectedAccountFilter, cycleOffset) {
        startBarAnimations = false
        delay(50) // Tiny delay to allow the UI to reset to 0
        startBarAnimations = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
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
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            // ── Account filter: first, because it scopes everything down to "Top merchants" ──
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

                                // Clickable pill showing the current reset day
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), // Stark neutral
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .quietClickable {
                                            haptic.tap()
                                            onNavigateToSettings("Cycle")
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Resets Day $startDayOfMonth", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            MoneyHero(amount = animatedGrandTotal / 100.0, fractionDigits = 2, size = 44.sp)

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (hasSpending)
                                    "Your share across $totalTransactions ${if (totalTransactions == 1) "transaction" else "transactions"}"
                                else if (isCurrent) "No spending yet this cycle" else "No spending in this cycle",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Cycle clock: where we are in the billing cycle
                            Spacer(modifier = Modifier.height(18.dp))
                            LinearProgressIndicator(
                                progress = { animatedCycle },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(AppRadius.pill)),
                                // FIX: Stark neutral to match the merchant bars
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                strokeCap = StrokeCap.Round
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

                            // The two numbers that answer "am I okay?": where this is heading, and how it compares
                            val showProjected = hasSpending && isCurrent && daysLeft > 0 && elapsedDays >= 4
                            if (showProjected || deltaPct != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    if (showProjected) {
                                        HeroFact(
                                            modifier = Modifier.weight(1f),
                                            label = "Projected",
                                            value = formatRupees(dailyAverage * cycleLengthDays),
                                            caption = "by cycle end",
                                            valueColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (deltaPct != null) {
                                        val muted = MaterialTheme.colorScheme.onSurfaceVariant
                                        val (trendText, trendColor) = when {
                                            abs(deltaPct) < 1.0 -> "On par" to muted
                                            deltaPct > 0 -> "▲ ${deltaPct.toInt()}%" to InsightsColors.negative
                                            else -> "▼ ${abs(deltaPct).toInt()}%" to InsightsColors.positive
                                        }
                                        HeroFact(
                                            modifier = Modifier.weight(1f),
                                            label = "vs last cycle",
                                            value = trendText,
                                            caption = if (isCurrent) "at this point" else "previous cycle",
                                            valueColor = trendColor
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
                                caption = "over $elapsedDays ${if (elapsedDays == 1) "day" else "days"}",
                                accent = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            InsightsStatTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ReceiptLong,
                                label = "Largest",
                                value = formatRupees(stats.largestAmount.toDouble()),
                                caption = stats.largestPayee ?: "—",
                                accent = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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

            if (isLoaded && hasSpending && categories != null && merchants != null) {
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
                                trailing = if (categories.size == 1) "1 category" else "${categories.size} categories"
                            )
                            CategoryBreakdownCard(
                                categories = categories,
                                grandTotal = grandTotalDebit,
                                isAnimating = startBarAnimations,
                                onCategoryClick = { selectedCategoryForBreakdown = it },
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
                                isAnimating = startBarAnimations
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
                        FixedCostsRow(
                            trackedCount = necessities.size,
                            onClick = {
                                haptic.tap()
                                onNavigateToNecessities()
                            }
                        )
                    }
                }
            }

            item(key = "bottomSpacer") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    selectedCategoryForBreakdown?.let { catName ->
        CategoryTransactionsSheet(
            categoryName = catName,
            allTransactions = filteredTransactions,
            onDismiss = { selectedCategoryForBreakdown = null }
        )
    }
}

// Your own share of a bill = amount minus what other people owe (same rule the Dashboard uses).
private fun myShareOf(item: TransactionWithDebts): Long =
    (item.transaction.amount - item.debts.sumOf { it.amountOwed.toLong() }).coerceAtLeast(0L)

// ─────────────────────────────── Category breakdown ───────────────────────────────

@Composable
private fun CategoryBreakdownCard(
    categories: List<CategoryInsight>,
    grandTotal: Double,
    isAnimating: Boolean,
    onCategoryClick: (String) -> Unit,
    haptic: HapticFeedback
) {
    var showAll by remember { mutableStateOf(false) }

    val entered = rememberEntered(250)
    val reveal by animateFloatAsState(
        targetValue = if (isAnimating && entered) 1f else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "categoryDistributionReveal"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
        ) {
            // One stacked bar showing the whole split at a glance
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(reveal).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    categories.filter { it.totalSpent > 0 }.forEach { cat ->
                        val share = if (grandTotal > 0) (cat.totalSpent / grandTotal).toFloat() else 0f
                        Box(
                            modifier = Modifier
                                .weight(share.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(getCategoryColor(cat.category))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            categories.forEachIndexed { index, cat ->
                AnimatedVisibility(
                    visible = showAll || index < TOP_N_BY_DEFAULT,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                ) {
                    CategoryRow(
                        insight = cat,
                        grandTotal = grandTotal,
                        onClick = { haptic.tap(); onCategoryClick(cat.category) }
                    )
                }
            }

            if (categories.size > TOP_N_BY_DEFAULT) {
                Spacer(modifier = Modifier.height(4.dp))
                ShowMoreToggle(
                    text = if (showAll) "Show less" else "Show all ${categories.size}",
                    expanded = showAll,
                    onClick = {
                        haptic.tap()
                        showAll = !showAll
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(insight: CategoryInsight, grandTotal: Double, onClick: () -> Unit) {
    val categoryColor = getCategoryColor(insight.category)
    val categoryIcon = getCategoryIcon(insight.category)
    val percentage = if (grandTotal > 0.0) insight.totalSpent / grandTotal * 100.0 else 0.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(categoryIcon, contentDescription = null, tint = categoryColor, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                insight.category,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${insight.transactionCount} ${if (insight.transactionCount == 1) "txn" else "txns"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatRupees(insight.totalSpent),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${String.format(Locale.getDefault(), "%.1f", percentage)}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────── Top merchants ───────────────────────────────

@Composable
private fun MerchantsCard(
    merchants: List<PayeeInsight>,
    grandTotal: Double,
    isAnimating: Boolean
) {
    val haptic = LocalHapticFeedback.current
    var showAll by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = AppSpacing.cardPadding, vertical = 8.dp)
        ) {
            merchants.forEachIndexed { index, insight ->
                AnimatedVisibility(
                    visible = showAll || index < TOP_N_BY_DEFAULT,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                ) {
                    Column {
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        MerchantRow(insight = insight, grandTotal = grandTotal, isAnimating = isAnimating)
                    }
                }
            }

            if (merchants.size > TOP_N_BY_DEFAULT) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(4.dp))
                ShowMoreToggle(
                    text = if (showAll) "Show less" else "Show all ${merchants.size}",
                    expanded = showAll,
                    onClick = {
                        haptic.tap()
                        showAll = !showAll
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MerchantRow(insight: PayeeInsight, grandTotal: Double, isAnimating: Boolean) {
    val share = if (grandTotal > 0.0) (insight.totalSpent / grandTotal) else 0.0
    val avgPerVisit = if (insight.transactionCount > 0) insight.totalSpent / insight.transactionCount else 0.0

    val entered = rememberEntered(250)
    val animatedProgress by animateFloatAsState(
        targetValue = if (isAnimating && entered) share.toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "merchantBarGrowth"
    )

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(insight.payee.trim().take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    insight.payee,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${insight.transactionCount} ${if (insight.transactionCount == 1) "visit" else "visits"} · avg ${formatRupees(avgPerVisit)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatRupees(insight.totalSpent),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${String.format(Locale.getDefault(), "%.1f", share * 100)}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface // Changed from primary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(AppRadius.pill)),
            // FIX: Black/White data bars instead of primary green
            color = MaterialTheme.colorScheme.onSurface,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {}
        )
    }
}

// ─────────────────────────────── Commitments ───────────────────────────────

@Composable
private fun FixedCostsRow(trackedCount: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .quietClickable(onClick = onClick),
        shape = RoundedCornerShape(AppRadius.medium),
        // FIX: Neutral card background and border instead of primary.copy
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    // FIX: Neutral background
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Fixed costs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (trackedCount == 0) "Track rent, bills & sinking funds"
                        else "$trackedCount tracked",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(AppRadius.pill),
                // FIX: Stark neutral pill
                color = MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    "Manage →", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface, // White text
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ─────────────────────────────── Category sheet (unchanged) ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsSheet(
    categoryName: String,
    allTransactions: List<TransactionWithDebts>,
    onDismiss: () -> Unit
) {
    val categoryTxns = remember(allTransactions) {
        allTransactions.filter { it.transaction.category == categoryName && it.transaction.type == TransactionType.DEBIT }
            .sortedByDescending { it.transaction.timestamp }
    }

    val totalSpent = remember(categoryTxns) { categoryTxns.sumOf { myShareOf(it) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(categoryName, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${categoryTxns.size} transactions • ₹${String.format(Locale.getDefault(), "%,.2f", totalSpent / 100.0)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn {
                item {
                    TransactionGroup {
                        categoryTxns.forEachIndexed { index, item ->
                            if (index > 0) TransactionRowDivider()
                            key(item.transaction.id) {
                                DashboardTransactionCard(
                                    transaction = item.transaction,
                                    debts = item.debts,
                                    isSettled = false,
                                    settledBy = null,
                                    showActions = false, // THIS REMOVES THE ACTION ROW
                                    grouped = true,
                                    showCategory = false,
                                    showDate = true,
                                    onCardClick = { },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────── Shared bits ───────────────────────────────

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        if (trailing != null) {
            Text(trailing, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyInsightState(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
    }
}

/**
 * Hero figure shared by Insights and Recurring: big bold tabular digits, with the
 * currency symbol and decimals set smaller and softer so the whole number reads first.
 */
@Composable
internal fun MoneyHero(amount: Double, fractionDigits: Int, size: TextUnit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = String.format(Locale.getDefault(), "%,.${fractionDigits}f", amount)
    val sep = java.text.DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    val cut = if (fractionDigits > 0) text.lastIndexOf(sep) else -1
    val whole = if (cut >= 0) text.substring(0, cut) else text
    val fraction = if (cut >= 0) text.substring(cut) else ""
    val small = SpanStyle(fontSize = size * 0.5f, fontWeight = FontWeight.SemiBold, color = muted)
    Text(
        text = buildAnnotatedString {
            withStyle(small) { append("₹") }
            append(whole)
            if (fraction.isNotEmpty()) withStyle(small) { append(fraction) }
        },
        modifier = modifier,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        letterSpacing = (-1).sp,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(fontFeatureSettings = "tnum")
    )
}

@Composable
internal fun MoneyHero(amount: Long, fractionDigits: Int, size: TextUnit, modifier: Modifier = Modifier) {
    MoneyHero(amount = amount / 100.0, fractionDigits = fractionDigits, size = size, modifier = modifier)
}

@Composable
private fun HeroFact(modifier: Modifier, label: String, value: String, caption: String, valueColor: Color) {
    Column(modifier = modifier) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(caption, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Click handler with no ripple; presses fade the element slightly instead. */
@Composable
private fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.6f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { this.alpha = alpha }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

// Same look as the Dashboard's StatusBadge
@Composable
private fun InsightsBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(AppRadius.pill)) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// Same look as the Dashboard's QuickStatCard
@Composable
private fun InsightsStatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    caption: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    // FIX: Neutral background instead of accent.copy(alpha = 0.12f)
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(6.dp).size(16.dp), tint = accent) // Keep glyph accent
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                caption,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShowMoreToggle(text: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            // FIX: Remove primary green from text links
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun InsightsAccountChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // FIX: Grounded neutral states instead of primary green
    val bg by animateColorAsState(
        if (isSelected) colors.onSurface else colors.surfaceVariant.copy(alpha = 0.35f),
        tween(250), label = "chipBg"
    )
    val border by animateColorAsState(
        if (isSelected) colors.onSurface else colors.outlineVariant.copy(alpha = 0.5f),
        tween(250), label = "chipBorder"
    )
    val textColor by animateColorAsState(
        if (isSelected) colors.surface else colors.onSurface, // White text when active
        tween(250), label = "chipText"
    )
    Surface(
        shape = RoundedCornerShape(AppRadius.pill),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun CycleSelector(
    startMillis: Long,
    endMillis: Long,
    isCurrent: Boolean,
    daysLeft: Int,
    canGoBack: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpToCurrent: () -> Unit
) {
    val label = remember(startMillis, endMillis) { formatCycleRange(startMillis, endMillis) }
    val subtitle = when {
        !isCurrent -> "Past cycle · Back to current"
        daysLeft == 0 -> "Current cycle · Resets today"
        daysLeft == 1 -> "Current cycle · 1 day left"
        else -> "Current cycle · $daysLeft days left"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous cycle")
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(AppRadius.small))
                    .quietClickable(enabled = !isCurrent, onClick = onJumpToCurrent)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Crossfade(targetState = label, label = "cycleLabel") { text ->
                    Text(
                        text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    fontWeight = if (isCurrent) FontWeight.Normal else FontWeight.SemiBold,
                    // FIX: Stark neutral instead of primary
                    color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onNext, enabled = !isCurrent) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next cycle")
            }
        }
    }
}

private fun formatCycleRange(startMillis: Long, endMillis: Long): String {
    val start = Calendar.getInstance().apply { timeInMillis = startMillis }
    val end = Calendar.getInstance().apply { timeInMillis = (endMillis - 1).coerceAtLeast(startMillis) }
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val showYear = start.get(Calendar.YEAR) != thisYear || end.get(Calendar.YEAR) != thisYear
    val fmt = SimpleDateFormat(if (showYear) "d MMM yyyy" else "d MMM", Locale.getDefault())
    return "${fmt.format(start.time)} – ${fmt.format(end.time)}"
}

/** Fade + slight rise, staggered by index. Plays once per key, so scrolling back doesn't replay it. */
@Composable
private fun EntranceItem(
    key: String,
    index: Int,
    seen: MutableSet<String>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = remember(key) { MutableTransitionState(key in seen).apply { targetState = true } }
    SideEffect { seen.add(key) }
    val delay = index * 60
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(tween(450, delayMillis = delay, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(450, delayMillis = delay, easing = FastOutSlowInEasing)) { it / 12 },
        exit = ExitTransition.None
    ) { content() }
}

/** Flips to true shortly after first composition, so progress bars always animate from 0. */
@Composable
private fun rememberEntered(delayMs: Long = 0L): Boolean {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs)
        entered = true
    }
    return entered
}
