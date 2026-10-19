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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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

internal const val DAY_MS = 86_400_000L
internal const val TOP_N_BY_DEFAULT = 5

// Same hues as the Dashboard's AppColors.
internal object InsightsColors {
    val positive @Composable get() = if (isSystemInDarkTheme()) Color(0xFF10B981) else Color(0xFF059669)
    val negative @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEF4444) else Color(0xFFDC2626)
}

internal fun HapticFeedback.tap() = tick()

internal fun formatRupees(value: Double): String =
    "₹" + String.format(Locale.getDefault(), "%,.0f", value / 100.0)

internal fun formatRupees(value: Long): String =
    formatRupees(value.toDouble())

/** Numbers that need a second pass over the data (previous cycle, biggest single expense, rhythm) in paise. */
internal data class CycleStats(
    val prevSpent: Long = 0L,
    val largestAmount: Long = 0L,
    val largestPayee: String? = null,
    val largestTxnId: Long? = null,
    val spendFreeDays: Int = 0
)

internal fun matchesAccount(txn: TransactionEntity, filter: AccountInfo?): Boolean =
    filter == null || (txn.bankName == filter.bankName && txn.accountNumber == filter.accountNumber)

// Your own share of a bill = amount minus what other people owe (same rule the Dashboard uses).
internal fun myShareOf(item: TransactionWithDebts): Long =
    (item.transaction.amount - item.debts.sumOf { it.amountOwed.toLong() }).coerceAtLeast(0L)

internal fun isSpendDebit(txn: TransactionEntity): Boolean =
    txn.type == TransactionType.DEBIT &&
            !txn.excludeFromSpend &&
            txn.category != "Split Settlement"

/**
 * Clean, gapless pill progress bar without Material3 LinearProgressIndicator's trailing stop-dot
 * or 4.dp active/track gap.
 */
@Composable
internal fun InsightsProgressBar(
    progress: Float,
    color: Color,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    height: androidx.compose.ui.unit.Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(trackColor)
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(color)
            )
        }
    }
}

@Composable
internal fun CategoryGlyphBox(
    category: String,
    categoryColor: Color,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp
) {
    val hasIcon = remember(category) { hasCategoryIcon(category) }
    val initial = remember(category) {
        category.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (hasIcon) {
                Icon(
                    getCategoryIcon(category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize)
                )
            } else {
                Text(
                    text = initial,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────── Category breakdown ───────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryBreakdownCard(
    categories: List<CategoryInsight>,
    categoryBudgets: Map<String, Long>,
    grandTotal: Long,
    isAnimating: Boolean,
    onCategoryClick: (String) -> Unit,
    onManageBudgetsClick: () -> Unit,
    haptic: HapticFeedback
) {
    var showAll by rememberSaveable { mutableStateOf(false) }

    val entered = rememberEntered(250)
    val reveal by animateFloatAsState(
        targetValue = if (isAnimating && entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 800,
            delayMillis = 100,
            easing = FastOutSlowInEasing
        ),
        label = "categoryDistributionReveal"
    )

    val positiveCategories = remember(categories) { categories.filter { it.totalSpent > 0L } }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = AppSpacing.cardPadding, vertical = 16.dp)
        ) {
            if (positiveCategories.isNotEmpty() && grandTotal > 0L) {
                // Stacked distribution bar showing all positive categories in their true colors
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(reveal)
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp)
                    ) {
                        positiveCategories.forEach { cat ->
                            val share = (cat.totalSpent.toDouble() / grandTotal.toDouble()).toFloat()
                            Box(
                                modifier = Modifier
                                    .weight(share.coerceAtLeast(0.018f))
                                    .fillMaxHeight()
                                    .background(getCategoryColor(cat.category))
                            )
                        }
                    }
                }

                // Horizontally scrollable category legend pills under the stacked bar for all active categories
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    positiveCategories.forEach { cat ->
                        val rawPct = cat.totalSpent.toDouble() / grandTotal.toDouble() * 100.0
                        val pctLabel = if (rawPct >= 1.0) "${rawPct.roundToInt()}%" else "<1%" 
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .quietClickable {
                                    haptic.tap()
                                    onCategoryClick(cat.category)
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(getCategoryColor(cat.category), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "${cat.category} $pctLabel",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            categories.forEachIndexed { index, cat ->
                AnimatedVisibility(
                    visible = showAll || index < TOP_N_BY_DEFAULT,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                ) {
                    Column {
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        val limit = categoryBudgets.entries
                            .firstOrNull { it.key.equals(cat.category, ignoreCase = true) }
                            ?.value
                        if (limit != null && limit > 0L) {
                            CategoryRowWithBudget(
                                insight = cat,
                                budgetPaise = limit,
                                isAnimating = isAnimating,
                                onClick = { haptic.tap(); onCategoryClick(cat.category) }
                            )
                        } else {
                            CategoryRow(
                                insight = cat,
                                grandTotal = grandTotal,
                                isAnimating = isAnimating,
                                onClick = { haptic.tap(); onCategoryClick(cat.category) }
                            )
                        }
                    }
                }
            }

            if (categories.size > TOP_N_BY_DEFAULT) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
internal fun CategoryRow(
    insight: CategoryInsight,
    grandTotal: Long,
    isAnimating: Boolean = true,
    onClick: () -> Unit
) {
    val categoryColor = getCategoryColor(insight.category)
    val categoryIcon = getCategoryIcon(insight.category)
    val share = if (grandTotal > 0L) (insight.totalSpent.toDouble() / grandTotal.toDouble()) else 0.0
    val percentage = share * 100.0
    val avgPerTxn = if (insight.transactionCount > 0) insight.totalSpent.toDouble() / insight.transactionCount else 0.0

    val entered = rememberEntered(250)
    val animatedProgress by animateFloatAsState(
        targetValue = if (isAnimating && entered) share.toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "categoryShareGrowth"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryGlyphBox(category = insight.category, categoryColor = categoryColor)
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
                    "${insight.transactionCount} ${if (insight.transactionCount == 1) "txn" else "txns"} · avg ${formatRupees(avgPerTxn)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

        Spacer(modifier = Modifier.height(10.dp))
        InsightsProgressBar(
            progress = animatedProgress,
            color = categoryColor.copy(alpha = 0.85f)
        )
    }
}

@Composable
internal fun CategoryRowWithBudget(
    insight: CategoryInsight,
    budgetPaise: Long,
    isAnimating: Boolean,
    onClick: () -> Unit
) {
    val categoryIcon = getCategoryIcon(insight.category)
    val categoryColor = getCategoryColor(insight.category)
    val pct = if (budgetPaise > 0L) (insight.totalSpent.toFloat() / budgetPaise).coerceAtLeast(0f) else 0f
    val isOver = budgetPaise > 0L && insight.totalSpent > budgetPaise
    val isAtLimit = budgetPaise > 0L && insight.totalSpent == budgetPaise
    val isNear = budgetPaise > 0L && !isOver && (pct >= 0.8f || isAtLimit)
    val barColor = when {
        isOver -> AppColors.negative
        isNear -> AppColors.warning
        else -> MaterialTheme.colorScheme.onSurface
    }
    val statusNote = when {
        isOver -> "${formatRupees(insight.totalSpent - budgetPaise)} over"
        isAtLimit -> "limit reached"
        else -> "${formatRupees(budgetPaise - insight.totalSpent)} left"
    }
    val statusColor = when {
        isOver -> AppColors.negative
        isAtLimit || isNear -> AppColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val entered = rememberEntered(250)
    val animatedProgress by animateFloatAsState(
        targetValue = if (isAnimating && entered) pct.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "categoryBudgetGrowth"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CategoryGlyphBox(category = insight.category, categoryColor = categoryColor)
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
                    "${insight.transactionCount} ${if (insight.transactionCount == 1) "txn" else "txns"} · $statusNote",
                    fontSize = 12.sp,
                    fontWeight = if (isOver || isAtLimit) FontWeight.Medium else FontWeight.Normal,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatRupees(insight.totalSpent),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (isOver) AppColors.negative else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "of ${formatRupees(budgetPaise)} · ${(pct * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        InsightsProgressBar(
            progress = animatedProgress,
            color = barColor
        )
    }
}

// ─────────────────────────────── Top merchants ───────────────────────────────

@Composable
internal fun MerchantsCard(
    merchants: List<PayeeInsight>,
    grandTotal: Long,
    isAnimating: Boolean,
    onMerchantClick: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var showAll by rememberSaveable { mutableStateOf(false) }

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
                        MerchantRow(
                            insight = insight,
                            grandTotal = grandTotal,
                            isAnimating = isAnimating,
                            onClick = {
                                haptic.tap()
                                onMerchantClick(insight.payee)
                            }
                        )
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
internal fun MerchantRow(
    insight: PayeeInsight,
    grandTotal: Long,
    isAnimating: Boolean,
    onClick: () -> Unit = {}
) {
    val share = if (grandTotal > 0L) (insight.totalSpent.toDouble() / grandTotal.toDouble()) else 0.0
    val avgPerVisit = if (insight.transactionCount > 0) insight.totalSpent.toDouble() / insight.transactionCount else 0.0

    val entered = rememberEntered(250)
    val animatedProgress by animateFloatAsState(
        targetValue = if (isAnimating && entered) share.toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "merchantBarGrowth"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        insight.payee.trim().take(1).uppercase().ifBlank { "?" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        InsightsProgressBar(
            progress = animatedProgress,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─────────────────────────────── Commitments ───────────────────────────────

@Composable
internal fun FixedCostsRow(trackedCount: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .quietClickable(onClick = onClick),
        shape = RoundedCornerShape(AppRadius.medium),
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
                        "Fixed costs & bills",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (trackedCount == 0) "Track rent, bills & sinking funds"
                        else "$trackedCount tracked · Kept out of Safe to Spend",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(AppRadius.pill),
                color = MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    "Manage →",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ─────────────────────────────── Category & Merchant drill-down sheets ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsSheet(
    categoryName: String,
    allTransactions: List<TransactionWithDebts>,
    onDismiss: () -> Unit,
    budgetPaise: Long? = null,
    onManageBudgetClick: (() -> Unit)? = null,
    onTransactionClick: ((TransactionEntity) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val categoryTxns = remember(allTransactions, categoryName) {
        allTransactions.filter { it.transaction.category.equals(categoryName, ignoreCase = true) && isSpendDebit(it.transaction) }
            .sortedByDescending { it.transaction.timestamp }
    }

    val totalSpent = remember(categoryTxns) { categoryTxns.sumOf { myShareOf(it) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasBudget = budgetPaise != null && budgetPaise > 0L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        categoryName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${categoryTxns.size} ${if (categoryTxns.size == 1) "transaction" else "transactions"} • ₹${String.format(Locale.getDefault(), "%,.2f", totalSpent / 100.0)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onManageBudgetClick != null && !categoryName.equals("Uncategorized", ignoreCase = true)) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable {
                                haptic.tap()
                                onManageBudgetClick()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                if (hasBudget) Icons.Default.Edit else Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (hasBudget) "Cap: ${formatRupees(budgetPaise!!)}" else "Set cap",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

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
                                    showActions = false,
                                    grouped = true,
                                    showCategory = false,
                                    showDate = true,
                                    onCardClick = {
                                        if (onTransactionClick != null) {
                                            haptic.tap()
                                            onTransactionClick(item.transaction)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantTransactionsSheet(
    merchantName: String,
    allTransactions: List<TransactionWithDebts>,
    onDismiss: () -> Unit,
    onTransactionClick: ((TransactionEntity) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val merchantTxns = remember(allTransactions, merchantName) {
        allTransactions.filter { it.transaction.payee.equals(merchantName, ignoreCase = true) && isSpendDebit(it.transaction) }
            .sortedByDescending { it.transaction.timestamp }
    }

    val totalSpent = remember(merchantTxns) { merchantTxns.sumOf { myShareOf(it) } }
    val avgPerVisit = remember(merchantTxns, totalSpent) {
        if (merchantTxns.isNotEmpty()) totalSpent.toDouble() / merchantTxns.size else 0.0
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        merchantName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${merchantTxns.size} ${if (merchantTxns.size == 1) "visit" else "visits"} • ₹${String.format(Locale.getDefault(), "%,.2f", totalSpent / 100.0)} • avg ${formatRupees(avgPerVisit)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn {
                item {
                    TransactionGroup {
                        merchantTxns.forEachIndexed { index, item ->
                            if (index > 0) TransactionRowDivider()
                            key(item.transaction.id) {
                                DashboardTransactionCard(
                                    transaction = item.transaction,
                                    debts = item.debts,
                                    isSettled = false,
                                    settledBy = null,
                                    showActions = false,
                                    grouped = true,
                                    showCategory = true,
                                    showDate = true,
                                    onCardClick = {
                                        if (onTransactionClick != null) {
                                            haptic.tap()
                                            onTransactionClick(item.transaction)
                                        }
                                    }
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
internal fun SectionHeader(
    title: String,
    trailing: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (trailing != null && actionLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "· $trailing",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null && onActionClick != null) {
            Surface(
                shape = RoundedCornerShape(AppRadius.pill),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .quietClickable(onClick = onActionClick)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    if (actionIcon != null) {
                        Icon(
                            actionIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        actionLabel,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else if (trailing != null) {
            Text(
                trailing,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun EmptyInsightState(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
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
internal fun HeroFact(modifier: Modifier, label: String, value: String, caption: String, valueColor: Color) {
    Column(modifier = modifier) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(caption, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Click handler with no ripple; presses fade the element slightly instead. */
@Composable
internal fun Modifier.quietClickable(
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
internal fun InsightsBadge(text: String, color: Color) {
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
internal fun InsightsStatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    caption: String,
    accent: Color,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.medium))
            .then(if (onClick != null) Modifier.quietClickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(6.dp).size(16.dp), tint = accent)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
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
internal fun ShowMoreToggle(text: String, expanded: Boolean, onClick: () -> Unit) {
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
internal fun InsightsAccountChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (isSelected) colors.onSurface else colors.surfaceVariant.copy(alpha = 0.35f),
        tween(250), label = "chipBg"
    )
    val border by animateColorAsState(
        if (isSelected) colors.onSurface else colors.outlineVariant.copy(alpha = 0.5f),
        tween(250), label = "chipBorder"
    )
    val textColor by animateColorAsState(
        if (isSelected) colors.surface else colors.onSurface,
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

internal fun formatCycleRange(startMillis: Long, endMillis: Long): String {
    val start = Calendar.getInstance().apply { timeInMillis = startMillis }
    val end = Calendar.getInstance().apply { timeInMillis = (endMillis - 1).coerceAtLeast(startMillis) }
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val showYear = start.get(Calendar.YEAR) != thisYear || end.get(Calendar.YEAR) != thisYear
    val fmt = SimpleDateFormat(if (showYear) "d MMM yyyy" else "d MMM", Locale.getDefault())
    return "${fmt.format(start.time)} – ${fmt.format(end.time)}"
}

/** Fade + slight rise, staggered by index. Plays once per key, so scrolling back doesn't replay it. */
@Composable
internal fun EntranceItem(
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
internal fun rememberEntered(delayMs: Long = 0L): Boolean {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs)
        entered = true
    }
    return entered
}
