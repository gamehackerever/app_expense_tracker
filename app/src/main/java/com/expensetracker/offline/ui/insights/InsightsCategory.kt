@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.insights

import com.expensetracker.offline.ui.components.quietClickable
import com.expensetracker.offline.util.tap
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
internal fun CategoryGlyphBox(
    category: String,
    categoryColor: Color,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    @Suppress("UNUSED_PARAMETER") cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
    @Suppress("UNUSED_PARAMETER") iconSize: androidx.compose.ui.unit.Dp = 20.dp
) {
    // Same glyph as everywhere else; tinted to match the chart colour.
    com.expensetracker.offline.ui.components.CategoryAvatar(
        category = category,
        size = size,
        tint = categoryColor
    )
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

    val reveal by rememberAnimatedValue(
        target = if (isAnimating) 1f else 0f,
        key = grandTotal,
        sessionKey = "insights_category_stacked_bar"
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
                val legendScroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(legendScroll),
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                // Right-edge fade: hints that more categories are off-screen, disappears once scrolled to the end
                if (legendScroll.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(36.dp)
                            .height(24.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                                )
                            )
                    )
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
    val share = if (grandTotal > 0L) (insight.totalSpent.toDouble() / grandTotal.toDouble()) else 0.0
    val percentage = share * 100.0
    val avgPerTxn = if (insight.transactionCount > 0) insight.totalSpent.toDouble() / insight.transactionCount else 0.0

    val targetProgress = if (isAnimating) share.toFloat().coerceIn(0f, 1f) else 0f

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
            progress = targetProgress,
            color = categoryColor.copy(alpha = 0.85f),
            sessionKey = "insights_cat_row_${insight.category}"
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

    val targetProgress = if (isAnimating) pct.coerceIn(0f, 1f) else 0f

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
            progress = targetProgress,
            color = barColor,
            sessionKey = "insights_cat_budget_${insight.category}"
        )
    }
}

@Composable
internal fun FixedCostsRow(
    trackedCount: Int,
    reservedMonthlyPaise: Long = 0L,
    onClick: () -> Unit
) {
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
                        when {
                            trackedCount == 0 -> "Track rent, bills & sinking funds"
                            reservedMonthlyPaise > 0L -> "$trackedCount tracked · ${formatRupees(reservedMonthlyPaise)}/mo"
                            else -> "$trackedCount tracked · Reserved from balance"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
