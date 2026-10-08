package com.expensetracker.offline.ui.insights

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.engine.recurring.Cadence
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private const val RECURRING_DAY_MS = 86_400_000L
private const val UPCOMING_PREVIEW_COUNT = 3
private const val TENTATIVE_BELOW = 0.6

// Same hues as the Dashboard's private AppColors, so status colors read identically on both screens.
private object RecurringColors {
    val positive @Composable get() = if (isSystemInDarkTheme()) Color(0xFF10B981) else Color(0xFF059669)
    val warning @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF59E0B) else Color(0xFFD97706)
    val negative @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEF4444) else Color(0xFFDC2626)
}

private fun HapticFeedback.tap() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

private fun rupees(value: Long): String =
    "₹" + String.format(Locale.getDefault(), "%,.0f", value / 100.0)

private fun daysUntil(payment: RecurringPayment, now: Long): Int =
    ceil((payment.nextExpected - now).toDouble() / RECURRING_DAY_MS).toInt()

private fun dueLabel(payment: RecurringPayment, now: Long, lapsed: Boolean, df: SimpleDateFormat): String {
    if (lapsed) return "Last paid ${df.format(Date(payment.lastSeen))}"
    val days = daysUntil(payment, now)
    return when {
        days < 0 -> "${-days}d overdue"
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        days <= 7 -> "Due in $days days"
        else -> "Due ${df.format(Date(payment.nextExpected))}"
    }
}

@Composable
private fun dueColor(payment: RecurringPayment, now: Long, lapsed: Boolean): Color {
    if (lapsed) return MaterialTheme.colorScheme.onSurfaceVariant
    val days = daysUntil(payment, now)
    return when {
        days < 0 -> RecurringColors.negative
        days <= 3 -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * "Recurring payments" block for the Insights screen.
 *
 * On the page it is one compact card: monthly cost, what is due soon, and the next few payments.
 * Tapping it opens a bottom sheet (same pattern as the category breakdown) with the full list.
 *
 * @param payments null while still loading (renders nothing, avoids a flash of the empty message),
 *        empty when nothing was detected (renders a short explanation), otherwise the detected list.
 * @param necessities the user's current Fixed Costs, used to mark payments already tracked there.
 * @param onAddToFixedCosts called with a prefilled item when the user taps "+ Fixed costs".
 */
@Composable
fun RecurringPaymentsSection(
    payments: List<RecurringPayment>?,
    necessities: List<NecessityItem>,
    onAddToFixedCosts: (NecessityItem) -> Unit
) {
    if (payments == null) return
    if (payments.isEmpty()) {
        RecurringEmptyState()
    } else {
        RecurringPaymentsContent(payments, necessities, onAddToFixedCosts)
    }
}

// ─────────────────────────────── Empty state ───────────────────────────────

@Composable
private fun RecurringEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Autorenew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Recurring payments",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Subscriptions and regular bills appear here automatically after about 3 charges on a schedule.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────── Summary card ───────────────────────────────

@Composable
private fun RecurringPaymentsContent(
    payments: List<RecurringPayment>,
    necessities: List<NecessityItem>,
    onAddToFixedCosts: (NecessityItem) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val now = remember(payments) { System.currentTimeMillis() }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    val active = remember(payments) { payments.filter { it.isActive }.sortedBy { it.nextExpected } }
    val lapsed = remember(payments) { payments.filterNot { it.isActive }.sortedByDescending { it.lastSeen } }
    val monthlyTotal = remember(payments) { RecurringPaymentDetector.monthlyTotal(payments) }
    val dueThisWeek = remember(active, now) {
        active.filter { it.nextExpected <= now + 7 * RECURRING_DAY_MS }.sumOf { it.amount }
    }
    val overdueCount = remember(active, now) { active.count { daysUntil(it, now) < 0 } }
    val dueSoonCount = remember(active, now) { active.count { daysUntil(it, now) in 0..7 } }

    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.large))
            .quietClickable(pressedAlpha = 0.85f) { haptic.tap(); showSheet = true },
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {

            // Label + status pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recurring payments",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    overdueCount > 0 -> RecurringBadge("$overdueCount overdue", RecurringColors.negative)
                    dueSoonCount > 0 -> RecurringBadge("$dueSoonCount due this week", RecurringColors.warning)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Monthly cost, and what it adds up to over a year
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyHero(amount = monthlyTotal, fractionDigits = 0, size = 38.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "/ month",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${active.size} active · ≈ ${rupees(monthlyTotal * 12)} a year",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (dueThisWeek > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${rupees(dueThisWeek)} due in the next 7 days",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(14.dp))

            // Coming up (next few active payments)
            if (active.isEmpty()) {
                Text(
                    "No active recurring payments right now",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Coming up",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    active.take(UPCOMING_PREVIEW_COUNT).forEach { payment ->
                        RecurringCompactRow(payment, now, dateFormat)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            // Footer cue: the whole card is tappable
            val footerText = when {
                active.isEmpty() -> "View ${lapsed.size} possibly cancelled"
                lapsed.isEmpty() -> "View all ${active.size}"
                else -> "View all ${active.size} · ${lapsed.size} possibly cancelled"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    footerText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface // Changed from primary
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface, // Changed from primary
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    if (showSheet) {
        RecurringPaymentsSheet(
            active = active,
            lapsed = lapsed,
            now = now,
            dateFormat = dateFormat,
            monthlyTotal = monthlyTotal,
            necessities = necessities,
            onAddToFixedCosts = onAddToFixedCosts,
            onDismiss = { showSheet = false }
        )
    }
}

@Composable
private fun RecurringCompactRow(payment: RecurringPayment, now: Long, dateFormat: SimpleDateFormat) {
    val scheme = MaterialTheme.colorScheme
    val categoryIcon = getCategoryIcon(payment.category)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(10.dp), // (or 12.dp in the card)
            // FIX: Neutral background instead of categoryColor.copy(alpha = 0.12f)
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            modifier = Modifier.size(36.dp) // (or 44.dp in the card)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(categoryIcon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                payment.payee,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = scheme.onSurface
            )
            Text(
                dueLabel(payment, now, lapsed = false, df = dateFormat),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = dueColor(payment, now, lapsed = false)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            (if (payment.isFixedAmount) "" else "~") + rupees(payment.amount),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = scheme.onSurface
        )
    }
}

// ─────────────────────────────── Full list sheet ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringPaymentsSheet(
    active: List<RecurringPayment>,
    lapsed: List<RecurringPayment>,
    now: Long,
    dateFormat: SimpleDateFormat,
    monthlyTotal: Long,
    necessities: List<NecessityItem>,
    onAddToFixedCosts: (NecessityItem) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Open on the cancelled tab only if there is nothing active to show
    var showLapsed by remember { mutableStateOf(active.isEmpty()) }
    val list = if (showLapsed) lapsed else active

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
            Text(
                "Recurring payments",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "${rupees(monthlyTotal)} / month · ${active.size} active",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Segmented control (same look as the Dashboard's time-range selector)
            if (lapsed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        listOf(
                            false to "Active (${active.size})",
                            true to "Possibly cancelled (${lapsed.size})"
                        ).forEach { (isLapsedTab, label) ->
                            val isSelected = showLapsed == isLapsedTab
                            val segmentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                animationSpec = tween(200),
                                label = "segmentColor"
                            )
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .quietClickable(pressedAlpha = 0.8f) {
                                        if (!isSelected) haptic.tap()
                                        showLapsed = isLapsedTab
                                    },
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = segmentColor,
                                shadowElevation = if (isSelected) 1.dp else 0.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        // FIX: Pure neutral text instead of primary
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showLapsed) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No charge seen for a while. These may have been cancelled.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (list.isEmpty()) {
                Text(
                    "Nothing here yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.payee }) { payment ->
                        RecurringPaymentCard(
                            payment = payment,
                            now = now,
                            dateFormat = dateFormat,
                            lapsed = showLapsed,
                            tracked = necessities.any {
                                NecessityManager.matches(it, payment.payee, payment.category)
                            },
                            onAddToFixedCosts = { item ->
                                // This navigates to Fixed Costs, so close the sheet first
                                onDismiss()
                                onAddToFixedCosts(item)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurringPaymentCard(
    payment: RecurringPayment,
    now: Long,
    dateFormat: SimpleDateFormat,
    lapsed: Boolean,
    tracked: Boolean,
    onAddToFixedCosts: (NecessityItem) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val categoryIcon = getCategoryIcon(payment.category)
    val tentative = !lapsed && payment.confidence < TENTATIVE_BELOW

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (lapsed) 0.7f else 1f),
        shape = RoundedCornerShape(AppRadius.medium),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(categoryIcon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        payment.payee,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        payment.cadence.label + if (tentative) " · tentative" else "",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    // "~" = amount varies from bill to bill (e.g. electricity); this is the typical charge
                    Text(
                        (if (payment.isFixedAmount) "" else "~") + rupees(payment.amount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = scheme.onSurface
                    )
                    if (payment.cadence != Cadence.MONTHLY) {
                        Text(
                            "${rupees(payment.monthlyCost)}/mo",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecurringBadge(
                    text = dueLabel(payment, now, lapsed, dateFormat),
                    color = dueColor(payment, now, lapsed)
                )

                if (!lapsed) {
                    if (tracked) {
                        Surface(
                            color = RecurringColors.positive.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(AppRadius.pill)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = RecurringColors.positive,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "In fixed costs",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RecurringColors.positive
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(AppRadius.pill),
                            // FIX: Grounded neutral background
                            color = scheme.onSurface.copy(alpha = 0.06f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .quietClickable {
                                    haptic.tap()
                                    onAddToFixedCosts(buildFixedCostDraft(payment))
                                }
                        ) {
                            Text(
                                "+ Fixed costs",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface, // FIX: Pure neutral text
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────── Shared bits ───────────────────────────────

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
private fun RecurringBadge(text: String, color: Color) {
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

/**
 * Builds the prefilled Fixed Cost via NecessityManager.itemFrom, so it gets a fresh id,
 * a cleaned payee name, and a due date for non-monthly bills, like items created elsewhere.
 * Cadence is mapped to NecessityFrequency by enum name; anything without a match
 * (e.g. weekly) becomes a monthly bill at its monthly-equivalent cost.
 */
private fun buildFixedCostDraft(payment: RecurringPayment): NecessityItem {
    val matched = NecessityFrequency.values().firstOrNull { it.name == payment.cadence.name }
    val frequency = matched ?: NecessityFrequency.MONTHLY
    val amount = if (matched != null) payment.amount else payment.monthlyCost

    return NecessityManager.itemFrom(
        NecessityManager.BillSuggestion(
            payee = payment.payee,
            amount = amount,
            category = payment.category.takeIf { !it.equals("Uncategorized", ignoreCase = true) },
            frequency = frequency,
            lastTimestamp = payment.lastSeen,
            txnCount = 0
        )
    )
}
