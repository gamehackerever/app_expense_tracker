@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.insights

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.dao.CategoryInsight
import com.expensetracker.offline.data.local.dao.PayeeInsight
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.ml.SmartExpenseEngine
import com.expensetracker.offline.ui.components.DashboardTransactionCard
import com.expensetracker.offline.ui.components.TransactionGroup
import com.expensetracker.offline.ui.components.TransactionRowDivider
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.theme.hasCategoryIcon
import com.expensetracker.offline.util.tick
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt


@Composable
internal fun CycleSelector(
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
        !isCurrent -> "Past cycle · Tap to return to current"
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
                AnimatedContent(
                    targetState = startMillis to label,
                    transitionSpec = {
                        val movingForward = targetState.first >= initialState.first
                        val slideDir = if (movingForward) 1 else -1
                        (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { fullWidth -> slideDir * (fullWidth / 4) } +
                                fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { fullWidth -> -slideDir * (fullWidth / 4) } +
                                        fadeOut(tween(150))
                            )
                            .using(SizeTransform(clip = false))
                    },
                    label = "cycleLabelSlide"
                ) { (_, text) ->
                    Text(
                        text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedContent(
                    targetState = subtitle to isCurrent,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(140))
                    },
                    label = "cycleSubtitleFade"
                ) { (subText, current) ->
                    Text(
                        subText,
                        fontSize = 12.sp,
                        fontWeight = if (current) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (current) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onNext, enabled = !isCurrent) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next cycle")
            }
        }
    }
}

internal fun formatCycleRange(startMillis: Long, endMillis: Long): String {
    val start = Calendar.getInstance().apply { timeInMillis = startMillis }
    val end = Calendar.getInstance().apply { timeInMillis = (endMillis - 1).coerceAtLeast(startMillis) }
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val showYear = start.get(Calendar.YEAR) != thisYear || end.get(Calendar.YEAR) != thisYear
    val fmt = SimpleDateFormat(if (showYear) "d MMM yyyy" else "d MMM", Locale.getDefault())
    return "${fmt.format(start.time)} – ${fmt.format(end.time)}"
}


internal data class CycleTrendPoint(
    val label: String,
    val rangeLabel: String = "",
    val spentPaise: Long,
    val incomePaise: Long,
    val isSelected: Boolean
)

@Composable
internal fun CashFlowSummaryCard(
    totalIncomePaise: Long,
    discretionarySpendPaise: Long,
    investmentsPaise: Long,
    isAnimating: Boolean
) {
    val totalOutflow = discretionarySpendPaise + investmentsPaise
    val netSavingsPaise = totalIncomePaise - discretionarySpendPaise
    val savingsRatePct = if (totalIncomePaise > 0L) {
        ((netSavingsPaise.toDouble() / totalIncomePaise.toDouble()) * 100.0).roundToInt()
    } else null

    val expenseRatio = if (totalIncomePaise > 0L) {
        (discretionarySpendPaise.toFloat() / totalIncomePaise.toFloat()).coerceIn(0f, 1f)
    } else if (totalOutflow > 0L) {
        (discretionarySpendPaise.toFloat() / totalOutflow.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cash flow & wealth",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (savingsRatePct != null) {
                    InsightsBadge(
                        text = if (savingsRatePct >= 0) "$savingsRatePct% saved" else "${-savingsRatePct}% deficit",
                        color = if (savingsRatePct >= 0) InsightsColors.positive else InsightsColors.negative
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeroFact(
                    modifier = Modifier.weight(1f),
                    label = "Income",
                    value = formatRupees(totalIncomePaise),
                    amountPaise = totalIncomePaise,
                    caption = "total inflow",
                    valueColor = InsightsColors.positive
                )
                HeroFact(
                    modifier = Modifier.weight(1f),
                    label = "Discretionary",
                    value = formatRupees(discretionarySpendPaise),
                    amountPaise = discretionarySpendPaise,
                    caption = "living expenses",
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                if (investmentsPaise > 0L) {
                    HeroFact(
                        modifier = Modifier.weight(1f),
                        label = "Investments",
                        value = formatRupees(investmentsPaise),
                        amountPaise = investmentsPaise,
                        caption = "SIPs & wealth",
                        valueColor = Color(0xFF14B8A6)
                    )
                } else {
                    HeroFact(
                        modifier = Modifier.weight(1f),
                        label = "Net cash flow",
                        value = (if (netSavingsPaise >= 0L) "+" else "−") + formatRupees( kotlin.math.abs(netSavingsPaise) ),
                        amountPaise = netSavingsPaise,
                        caption = if (netSavingsPaise >= 0L) "surplus" else "net outflow",
                        valueColor = if (netSavingsPaise >= 0L) InsightsColors.positive else InsightsColors.negative
                    )
                }
            }

            InsightsProgressBar(
                progress = if (isAnimating) expenseRatio else 0f,
                color = if (totalIncomePaise > 0L && discretionarySpendPaise > totalIncomePaise) InsightsColors.negative else MaterialTheme.colorScheme.onSurface,
                trackColor = InsightsColors.positive.copy(alpha = 0.18f),
                height = 6.dp,
                sessionKey = "insights_cashflow_bar"
            )

            val overIncome = totalIncomePaise > 0L && discretionarySpendPaise > totalIncomePaise
            Text(
                text = when {
                    totalIncomePaise > 0L -> {
                        val spentPct = ((discretionarySpendPaise.toDouble() / totalIncomePaise.toDouble()) * 100.0).roundToInt()
                        if (overIncome) "Spent $spentPct% of income · ${formatRupees(discretionarySpendPaise - totalIncomePaise)} over"
                        else "Spent $spentPct% of income"
                    }
                    totalOutflow > 0L -> "No income recorded · bar shows living expenses as a share of outflow"
                    else -> "No income or spending yet"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (overIncome) InsightsColors.negative else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun MultiCycleTrendCard(
    allPoints: List<CycleTrendPoint>,
    monthlyBudgetPaise: Long,
    isAnimating: Boolean
) {
    if (allPoints.isEmpty()) return
    // Trim leading cycles that predate the user's data (keep the selected cycle even if it is empty),
    // so a new user sees real bars instead of a card that is mostly empty space.
    val points = remember(allPoints) {
        val firstUseful = allPoints.indexOfFirst { it.spentPaise > 0L || it.incomePaise > 0L || it.isSelected }
        if (firstUseful > 0) allPoints.drop(firstUseful) else allPoints
    }
    var selectedIdx by remember(points) { mutableIntStateOf(points.indexOfLast { it.isSelected }.coerceAtLeast(0)) }
    val selectedPoint = points.getOrNull(selectedIdx) ?: points.last()
    val maxVal = remember(points) {
        maxOf(
            points.maxOfOrNull { it.spentPaise } ?: 1L,
            100L
        )
    }
    val entered = rememberEntered(250)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (selectedPoint.rangeLabel.isNotBlank())
                            "${selectedPoint.label} cycle · ${selectedPoint.rangeLabel}"
                        else "${selectedPoint.label} cycle",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (selectedPoint.spentPaise == 0L && selectedPoint.incomePaise == 0L)
                            "No activity recorded"
                        else
                            "Spent ${formatRupees(selectedPoint.spentPaise)}" +
                                    (if (selectedPoint.incomePaise > 0L) " · In ${formatRupees(selectedPoint.incomePaise)}" else ""),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                ) {
                    Text(
                        if (points.size >= 2) "${points.size}-cycle trend" else "Trend",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                points.forEachIndexed { idx, pt ->
                    val isSel = idx == selectedIdx
                    val hasData = pt.spentPaise > 0L
                    val targetSpendRatio = if (hasData) {
                        (pt.spentPaise.toFloat() / maxVal.toFloat()).coerceIn(0.08f, 1f)
                    } else 0.03f
                    val animSpend by rememberAnimatedValue(
                        target = if (isAnimating && entered) targetSpendRatio else 0.03f,
                        key = pt.spentPaise,
                        sessionKey = "insights_trend_bar_$idx"
                    )
                    val barColor = when {
                        !hasData -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSel) 0.22f else 0.09f)
                        isSel -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                    }

                    val haptic = LocalHapticFeedback.current
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(AppRadius.small))
                            .quietClickable {
                                if (selectedIdx != idx) haptic.tap()
                                selectedIdx = idx
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                if (hasData) {
                                    Text(
                                        text = compactRupees(pt.spentPaise),
                                        fontSize = 10.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.68f)
                                        .widthIn(max = 48.dp)
                                        .fillMaxHeight(animSpend * 0.8f)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                        .background(barColor)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pt.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            if (points.size < 6) {
                Text(
                    "Earlier cycles will appear as you keep using the app",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
internal fun DailyCalendarHeatmapCard(
    cycleStartMillis: Long,
    cycleLengthDays: Int,
    dailySpendMap: Map<Int, Long>
) {
    val haptic = LocalHapticFeedback.current
    val maxDaySpend = remember(dailySpendMap) { (dailySpendMap.values.maxOrNull() ?: 0L).coerceAtLeast(1L) }
    var selectedDayIndex by remember(cycleStartMillis) { mutableStateOf<Int?>(null) }

    val zone = remember { ZoneId.systemDefault() }
    val cycleStartDate = remember(cycleStartMillis) {
        Instant.ofEpochMilli(cycleStartMillis).atZone(zone).toLocalDate()
    }
    val cycleEndDate = remember(cycleStartMillis, cycleLengthDays) {
        cycleStartDate.plusDays(cycleLengthDays.toLong() - 1)
    }

    val todayDate = remember { LocalDate.now(zone) }
    val todayDayIndex = remember(cycleStartDate, cycleLengthDays) {
        val daysBetween = ChronoUnit.DAYS.between(cycleStartDate, todayDate).toInt()
        if (daysBetween in 0 until cycleLengthDays) daysBetween else null
    }

    val startDayOfWeekOffset = remember(cycleStartDate) {
        cycleStartDate.dayOfWeek.value - 1 // 0 = Mon, 6 = Sun
    }

    val monthFmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val cycleLabelFmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val fullDateFmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Daily spending rhythm",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${cycleStartDate.format(cycleLabelFmt)} – ${cycleEndDate.format(cycleLabelFmt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val selIdx = selectedDayIndex
                if (selIdx != null) {
                    val selDate = cycleStartDate.plusDays(selIdx.toLong())
                    val dayAmt = dailySpendMap[selIdx] ?: 0L
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = AppColors.info.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AppColors.info.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.info)
                            )
                            Text(
                                "${selDate.format(fullDateFmt)} · ${formatRupees(dayAmt)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else if (todayDayIndex != null) {
                    val todayAmt = dailySpendMap[todayDayIndex] ?: 0L
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Today (${todayDate.format(monthFmt)}) · ${formatRupees(todayAmt)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else {
                    Text(
                        "Tap date to inspect",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Days of Week Header Row
            val weekDayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (label in weekDayLabels) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Heatmap Calendar Grid
            val totalCells = startDayOfWeekOffset + cycleLengthDays
            val rows = (totalCells + 6) / 7

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0 until rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (c in 0 until 7) {
                            val gridIdx = r * 7 + c
                            val dayIdx = gridIdx - startDayOfWeekOffset

                            if (gridIdx in startDayOfWeekOffset until totalCells) {
                                val cellDate = cycleStartDate.plusDays(dayIdx.toLong())
                                val dayNum = cellDate.dayOfMonth

                                val isToday = (dayIdx == todayDayIndex)
                                val spent = dailySpendMap[dayIdx] ?: 0L
                                val intensity = if (spent <= 0L) 0f else (spent.toFloat() / maxDaySpend.toFloat()).coerceIn(0.05f, 1f)
                                val isSel = (selectedDayIndex == dayIdx)
                                val isHighSpend = spent > 0L && intensity > 0.62f

                                val isDark = MaterialTheme.colorScheme.surface.red < 0.2f
                                // 4-tier contrast-safe spend intensity scale (preserved even when selected so
                                // selecting a low-spend day never looks like the highest-spend day of the cycle):
                                val cellBg = when {
                                    spent <= 0L -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (isToday) 0.08f else if (isDark) 0.03f else 0.035f)
                                    isHighSpend -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.90f else 0.86f)
                                    intensity > 0.30f -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.34f else 0.20f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.16f else 0.09f)
                                }

                                val textColor = when {
                                    isHighSpend -> MaterialTheme.colorScheme.surface
                                    isSel || isToday || spent > 0L -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(cellBg)
                                        .then(
                                            when {
                                                isSel -> Modifier.border(2.dp, AppColors.info, RoundedCornerShape(8.dp))
                                                isToday -> Modifier.border(
                                                    1.25.dp,
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                else -> Modifier
                                            }
                                        )
                                        .quietClickable {
                                            haptic.tap()
                                            selectedDayIndex = if (selectedDayIndex == dayIdx) null else dayIdx
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "$dayNum",
                                            fontSize = 12.sp,
                                            fontWeight = if (isToday || isSel || spent > 0L) FontWeight.Bold else FontWeight.Medium,
                                            color = textColor,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        if (isToday) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 1.dp)
                                                    .size(3.5.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        when {
                                                            isSel && !isHighSpend -> AppColors.info
                                                            isHighSpend -> MaterialTheme.colorScheme.surface
                                                            else -> MaterialTheme.colorScheme.onSurface
                                                        }
                                                    )
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f).height(38.dp))
                            }
                        }
                    }
                }
            }

            // Compact Intensity & Selection Legend
            val isDarkLegend = MaterialTheme.colorScheme.surface.red < 0.2f
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "Less",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        if (isDarkLegend) 0.04f else 0.04f,
                        if (isDarkLegend) 0.16f else 0.09f,
                        if (isDarkLegend) 0.34f else 0.20f,
                        if (isDarkLegend) 0.90f else 0.86f
                    ).forEach { alpha ->
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                        )
                    }
                    Text(
                        "More",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (todayDayIndex != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .border(1.25.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                            )
                            Text(
                                "Today",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .border(1.75.dp, AppColors.info, RoundedCornerShape(3.dp))
                        )
                        Text(
                            "Selected",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SmartInsightsCard(
    insights: List<SmartExpenseEngine.SmartInsight>,
    onTransactionClick: (Long) -> Unit = {}
) {
    if (insights.isEmpty()) return
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        "Learned patterns",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                ) {
                    Text(
                        "On-device",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            insights.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }

                val accentColor = when {
                    item.isWarning -> AppColors.warning
                    item.kind == SmartExpenseEngine.InsightKind.MICRO_SPEND -> Color(0xFFF59E0B)
                    item.kind == SmartExpenseEngine.InsightKind.TIME_PATTERN -> Color(0xFF3B82F6)
                    item.category != null && hasCategoryIcon(item.category) -> getCategoryColor(item.category)
                    else -> InsightsColors.positive
                }
                val icon = when {
                    item.kind == SmartExpenseEngine.InsightKind.MICRO_SPEND -> Icons.Default.ReceiptLong
                    item.kind == SmartExpenseEngine.InsightKind.TIME_PATTERN -> Icons.Default.Schedule
                    item.kind == SmartExpenseEngine.InsightKind.ANOMALY -> Icons.AutoMirrored.Filled.TrendingUp
                    item.category != null && hasCategoryIcon(item.category) -> getCategoryIcon(item.category)
                    item.kind == SmartExpenseEngine.InsightKind.HABIT -> Icons.Default.Autorenew
                    else -> Icons.Default.AutoAwesome
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.medium))
                        .then(
                            if (item.highlightTransactionId != null) {
                                Modifier.quietClickable {
                                    haptic.tap()
                                    onTransactionClick(item.highlightTransactionId)
                                }
                            } else Modifier
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(accentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            InsightsBadge(
                                text = item.badgeText,
                                color = if (item.isWarning) AppColors.warning else accentColor
                            )
                        }
                        Text(
                            text = item.detail,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.5.sp
                        )
                    }
                }
            }
        }
    }
}


/** 8,127 paise-rupees -> "₹8.1k"; keeps trend-bar labels short enough for six columns. */
internal fun compactRupees(paise: Long): String {
    val rupees = paise / 100.0
    return when {
        rupees >= 100_000.0 -> "₹" + String.format(Locale.US, "%.1fL", rupees / 100_000.0)
        rupees >= 1_000.0 -> "₹" + String.format(Locale.US, "%.1fk", rupees / 1_000.0)
        else -> "₹" + rupees.toLong()
    }
}
