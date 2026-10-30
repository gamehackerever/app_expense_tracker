@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.insights

import com.expensetracker.offline.ui.components.quietClickable
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


internal const val DAY_MS = 86_400_000L
internal const val TOP_N_BY_DEFAULT = 5

// Same hues as the Dashboard's AppColors.
internal object InsightsColors {
    val positive @Composable get() = if (isSystemInDarkTheme()) Color(0xFF10B981) else Color(0xFF059669)
    val negative @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEF4444) else Color(0xFFDC2626)
}


internal fun formatRupees(value: Double): String =
    "₹" + String.format(Locale.getDefault(), "%,.0f", value / 100.0)

internal fun formatRupees(value: Long): String =
    formatRupees(value.toDouble())

/** Numbers that need a second pass over the data (previous cycle, biggest single expense, rhythm) in paise. */
internal data class CycleStats(
    val prevSpent: Long = 0L,
    val hasReliablePrevBaseline: Boolean = false,
    val largestAmount: Long = 0L,
    val largestPayee: String? = null,
    val largestTxnId: Long? = null,
    val spendFreeDays: Int = 0
)

internal fun matchesAccount(txn: TransactionEntity, filter: AccountInfo?): Boolean =
    filter == null || (
        (txn.bankName == filter.bankName || txn.bankName?.removeSuffix(" CC")?.removeSuffix(" cc")?.trim() == filter.bankName) &&
        txn.accountNumber == filter.accountNumber
    )

// Your own share of a bill = amount minus what other people owe (or your debt row when a friend paid).
internal fun myShareOf(item: TransactionWithDebts): Long {
    val paidBy = item.transaction.paidBy?.trim()
    return if (paidBy != null && paidBy.isNotEmpty() && !paidBy.equals("You", ignoreCase = true)) {
        item.debts.firstOrNull { it.debtorName.trim().equals("You", ignoreCase = true) }?.amountOwed ?: 0L
    } else {
        val othersSum = item.debts.filter { !it.debtorName.trim().equals("You", ignoreCase = true) }.sumOf { it.amountOwed.toLong() }
        (item.transaction.amount - othersSum).coerceAtLeast(0L)
    }
}

internal fun isSpendDebit(txn: TransactionEntity): Boolean =
    txn.type == TransactionType.DEBIT &&
            !txn.excludeFromSpend &&
            txn.category != "Split Settlement" &&
            !txn.category.equals("Transfer", ignoreCase = true)

internal fun isIncomeCredit(txn: TransactionEntity): Boolean =
    txn.type == TransactionType.CREDIT &&
            txn.linkedDebtId == null &&
            !txn.excludeFromSpend &&
            txn.category != "Split Settlement" &&
            !txn.category.equals("Transfer", ignoreCase = true) &&
            !com.expensetracker.offline.ui.dashboard.isRefundCredit(txn)

/**
 * Clean, gapless pill progress bar without Material3 LinearProgressIndicator's trailing stop-dot
 * or 4.dp active/track gap.
 */
/**
 * Tracks which entrance animations (hero counters, progress bars, chart bars) have already played
 * during the current app process session so each animation plays ONCE after the user actually
 * reaches the screen (after App Lock is unlocked), and never replays on LazyColumn scroll or
 * back-navigation.
 */
object AnimationSessionTracker {
    private val playedKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    fun hasPlayed(key: String): Boolean = key in playedKeys

    fun markPlayed(key: String) {
        playedKeys.add(key)
    }
}

val LocalAnimationReady = compositionLocalOf { true }

@Composable
internal fun rememberAnimatedValue(
    target: Float,
    key: Any? = target,
    sessionKey: String? = null
): State<Float> {
    val isReady = LocalAnimationReady.current
    val effectiveTarget = if (isReady) target else 0f
    return animateFloatAsState(
        targetValue = effectiveTarget,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = sessionKey ?: "animatedValue"
    )
}

@Composable
internal fun InsightsProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    height: androidx.compose.ui.unit.Dp = 4.dp,
    sessionKey: String? = null
) {
    val target = progress.coerceIn(0f, 1f)
    val animVal by rememberAnimatedValue(target, key = target, sessionKey = sessionKey)
    val clamped = animVal.coerceIn(0f, 1f)
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


/**
 * Shared header for every section on the Insights page.
 * Layout: [Title · count] ........ [optional action pill]. The count always sits inline after the title
 * (never right-aligned), and the row has a fixed minimum height so headers with and without an action
 * pill line up identically above their cards.
 */
@Composable
internal fun SectionHeader(
    title: String,
    trailing: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "· $trailing",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(AppRadius.pill),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyInsightState(text: String) {
    com.expensetracker.offline.ui.components.EmptyState(title = text, icon = Icons.Default.ReceiptLong)
}

/**
 * Hero figure shared by Insights and Recurring: big bold tabular digits, with the
 * currency symbol and decimals set smaller and softer so the whole number reads first.
 */
@Composable
internal fun MoneyHero(
    amount: Double,
    fractionDigits: Int,
    size: TextUnit,
    modifier: Modifier = Modifier,
    sessionKey: String = "money_hero_$fractionDigits"
) {
    val animVal by rememberAnimatedValue(amount.toFloat(), key = amount, sessionKey = sessionKey)
    val currentAmount = animVal.toDouble()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = String.format(Locale.getDefault(), "%,.${fractionDigits}f", currentAmount)
    val sep = java.text.DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    val cut = if (fractionDigits > 0) text.lastIndexOf(sep) else -1
    val whole = if (cut >= 0) text.substring(0, cut) else text
    val fraction = if (cut >= 0) text.substring(cut) else ""
    val small = SpanStyle(fontSize = size * 0.5f, fontWeight = FontWeight.SemiBold, color = muted)
    Text(
        text = buildAnnotatedString {
            withStyle(small) { append("₹") }
            // Tabular figures only on digits: with "tnum" on the whole string, some fonts give the
            // comma a full digit-width slot, which shows up as "8, 126".
            val tabularDigits = SpanStyle(fontFeatureSettings = "tnum")
            for (ch in whole) {
                if (ch.isDigit()) withStyle(tabularDigits) { append(ch) } else append(ch)
            }
            if (fraction.isNotEmpty()) withStyle(small) { append(fraction) }
        },
        modifier = modifier,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        letterSpacing = (-1).sp,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
internal fun MoneyHero(
    amount: Long,
    fractionDigits: Int,
    size: TextUnit,
    modifier: Modifier = Modifier,
    sessionKey: String = "money_hero_long_$fractionDigits"
) {
    MoneyHero(amount = amount / 100.0, fractionDigits = fractionDigits, size = size, modifier = modifier, sessionKey = sessionKey)
}

@Composable
internal fun HeroFact(
    modifier: Modifier,
    label: String,
    value: String,
    caption: String,
    valueColor: Color,
    amountPaise: Long? = null
) {
    val target = (amountPaise ?: 0L).toFloat()
    val animVal by rememberAnimatedValue(target, key = amountPaise, sessionKey = "insights_herofact_$label")
    val displayValue = if (amountPaise != null) formatRupees(animVal.toDouble()) else value
    var valueFontSize by remember(displayValue) { mutableFloatStateOf(18f) }
    var captionFontSize by remember(caption) { mutableFloatStateOf(11.5f) }

    Column(modifier = modifier) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            displayValue,
            fontSize = valueFontSize.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = valueColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { res ->
                if (res.hasVisualOverflow && valueFontSize > 13.5f) {
                    valueFontSize = (valueFontSize - 1f).coerceAtLeast(13.5f)
                }
            }
        )
        Text(
            caption,
            fontSize = captionFontSize.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { res ->
                if (res.hasVisualOverflow && captionFontSize > 9.5f) {
                    captionFontSize = (captionFontSize - 0.5f).coerceAtLeast(9.5f)
                }
            }
        )
    }
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
            maxLines = 1,
            softWrap = false,
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
    amountPaise: Long? = null,
    onClick: (() -> Unit)? = null
) {
    val target = (amountPaise ?: 0L).toFloat()
    val animVal by rememberAnimatedValue(target, key = amountPaise, sessionKey = "insights_tile_$label")
    val displayValue = if (amountPaise != null) formatRupees(animVal.toDouble()) else value

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
                displayValue,
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
        tween(200, easing = FastOutSlowInEasing), label = "chipBg"
    )
    val border by animateColorAsState(
        if (isSelected) colors.onSurface else colors.outlineVariant.copy(alpha = 0.5f),
        tween(200, easing = FastOutSlowInEasing), label = "chipBorder"
    )
    val textColor by animateColorAsState(
        if (isSelected) colors.surface else colors.onSurface,
        tween(200, easing = FastOutSlowInEasing), label = "chipText"
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
    val delay = index.coerceAtMost(4) * 30
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(tween(220, delayMillis = delay, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(220, delayMillis = delay, easing = FastOutSlowInEasing)) { it / 16 },
        exit = ExitTransition.None
    ) { content() }
}

/** Immediately true so lazy-loaded chart/category bars do not sit blank when scrolling into view. */
@Composable
internal fun rememberEntered(delayMs: Long = 0L): Boolean = true
