@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.ExperimentalFoundationApi

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
    val filteredTransactions by produceState(initialValue = emptyList<com.expensetracker.offline.data.local.dao.TransactionWithDebts>(), allTransactions, accountFilter, cycleStartMillis, cycleEndMillis, isCurrent) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            allTransactions.filter { item ->
                val t = item.transaction.timestamp
                matchesAccount(item.transaction, accountFilter) &&
                        t >= cycleStartMillis && (isCurrent || t < cycleEndMillis)
            }
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
                        totalSpent = txns.sumOf { myShareOf(it) },
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
                        totalSpent = txns.sumOf { myShareOf(it) },
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
                prevSpent = prevSpent,
                largestAmount = largest?.let { myShareOf(it) } ?: 0L,
                largestPayee = largest?.transaction?.payee
            )
        }
    }

    val merchants = payeeInsights
    val categories = categoryInsights
    val isLoaded = merchants != null && categories != null
    val grandTotalDebit: Long = merchants?.sumOf { it.totalSpent } ?: 0L
    val totalTransactions = merchants?.sumOf { it.transactionCount } ?: 0
    val hasSpending = totalTransactions > 0

    val dailyAverage: Double = if (elapsedDays > 0) grandTotalDebit.toDouble() / elapsedDays else 0.0
    val deltaPct: Double? = if (stats.prevSpent > 0L) ((grandTotalDebit - stats.prevSpent).toDouble() / stats.prevSpent.toDouble()) * 100.0 else null

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
                                value = formatRupees(stats.largestAmount),
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

