package com.expensetracker.offline.ui.insights

import com.expensetracker.offline.ui.components.quietClickable
import com.expensetracker.offline.util.tap
import com.expensetracker.offline.ui.components.ResistantModalBottomSheet
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.engine.recurring.Cadence
import com.expensetracker.offline.engine.recurring.RecurringPayment
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryColor
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

// Uses HapticFeedback.tap() from util/HapticUtil.kt

private fun rupees(value: Long): String =
    "₹" + String.format(Locale.getDefault(), "%,.0f", value / 100.0)

private fun startOfDayMillis(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun daysUntil(payment: RecurringPayment, now: Long): Int =
    Math.round((startOfDayMillis(payment.nextExpected) - startOfDayMillis(now)) / RECURRING_DAY_MS.toDouble()).toInt()

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
 */
@Composable
fun RecurringPaymentsSection(
    payments: List<RecurringPayment>?,
    necessities: List<NecessityItem>,
    onAddToFixedCosts: (NecessityItem) -> Unit,
    onViewAllClick: (() -> Unit)? = null,
    hiddenCount: Int = 0
) {
    if (payments == null) return
    if (payments.isEmpty()) {
        RecurringEmptyState(hiddenCount = hiddenCount, onManageHidden = onViewAllClick)
    } else {
        RecurringPaymentsContent(payments, necessities, onAddToFixedCosts, onViewAllClick)
    }
}

// ─────────────────────────────── Empty state ───────────────────────────────

@Composable
private fun RecurringEmptyState(hiddenCount: Int = 0, onManageHidden: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .then(if (hiddenCount > 0 && onManageHidden != null) Modifier.quietClickable(pressedAlpha = 0.85f) { onManageHidden() } else Modifier),
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
                    if (hiddenCount > 0) "$hiddenCount hidden · tap to review"
                    else "Subscriptions and regular bills appear here automatically as you use the app.",
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
    onAddToFixedCosts: (NecessityItem) -> Unit,
    onViewAllClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val now = remember(payments) { System.currentTimeMillis() }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    val active = remember(payments) { payments.filter { it.isActive }.sortedBy { it.nextExpected } }
    val lapsed = remember(payments) { payments.filterNot { it.isActive }.sortedByDescending { it.lastSeen } }
    val monthlyTotal = remember(payments) { RecurringPaymentDetector.monthlyTotal(payments) }
    val dueThisWeek = remember(active, now) {
        active.filter { daysUntil(it, now) in 0..7 }.sumOf { it.amount }
    }
    val overdueCount = remember(active, now) { active.count { daysUntil(it, now) < 0 } }
    val dueSoonCount = remember(active, now) { active.count { daysUntil(it, now) in 0..7 } }

    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.large))
            .quietClickable(pressedAlpha = 0.85f) {
                haptic.tap()
                if (onViewAllClick != null) onViewAllClick() else showSheet = true
            },
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
                MoneyHero(amount = monthlyTotal, fractionDigits = 0, size = 28.sp, sessionKey = "insights_recurring_hero")
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "/ month",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
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
                val upcoming = active.take(UPCOMING_PREVIEW_COUNT)
                val trackedFlags = upcoming.map { p ->
                    necessities.any { NecessityManager.matches(it, p.payee, p.category) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Coming up",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // One legend for the whole list instead of repeating "Tracked" on every row
                    if (trackedFlags.any { it }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Tracked in fixed costs",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    upcoming.forEachIndexed { i, payment ->
                        RecurringCompactRow(payment, now, dateFormat, trackedFlags[i])
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
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
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
private fun RecurringCompactRow(
    payment: RecurringPayment,
    now: Long,
    dateFormat: SimpleDateFormat,
    isTracked: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val iconKey = payment.payee + " " + payment.category
    val categoryIcon = getCategoryIcon(iconKey)
    val categoryColor = getCategoryColor(iconKey)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = categoryColor.copy(alpha = 0.12f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(categoryIcon, contentDescription = null, tint = categoryColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    payment.payee,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isTracked) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Tracked in fixed costs",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            val baseDue = dueLabel(payment, now, lapsed = false, df = dateFormat)
            Text(
                baseDue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = dueColor(payment, now, lapsed = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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

private enum class RecurringTab { ACTIVE, LAPSED, HIDDEN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringPaymentsAllSheet(
    payments: List<RecurringPayment>,
    necessities: List<NecessityItem>,
    onAddToFixedCosts: (NecessityItem) -> Unit,
    onDismiss: () -> Unit,
    hidden: List<RecurringPayment> = emptyList(),
    onHide: (RecurringPayment) -> Unit = {},
    onRestore: (RecurringPayment) -> Unit = {}
) {
    val now = remember(payments) { System.currentTimeMillis() }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val active = remember(payments) { payments.filter { it.isActive }.sortedBy { it.nextExpected } }
    val lapsed = remember(payments) { payments.filterNot { it.isActive }.sortedByDescending { it.lastSeen } }
    val monthlyTotal = remember(payments) { RecurringPaymentDetector.monthlyTotal(payments) }
    RecurringPaymentsSheet(
        active = active,
        lapsed = lapsed,
        hidden = hidden,
        now = now,
        dateFormat = dateFormat,
        monthlyTotal = monthlyTotal,
        necessities = necessities,
        onAddToFixedCosts = onAddToFixedCosts,
        onDismiss = onDismiss,
        onHide = onHide,
        onRestore = onRestore
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringPaymentsSheet(
    active: List<RecurringPayment>,
    lapsed: List<RecurringPayment>,
    hidden: List<RecurringPayment> = emptyList(),
    now: Long,
    dateFormat: SimpleDateFormat,
    monthlyTotal: Long,
    necessities: List<NecessityItem>,
    onAddToFixedCosts: (NecessityItem) -> Unit,
    onDismiss: () -> Unit,
    onHide: (RecurringPayment) -> Unit = {},
    onRestore: (RecurringPayment) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Open on the cancelled tab only if there is nothing active to show
    var tab by remember { mutableStateOf(if (active.isEmpty() && lapsed.isNotEmpty()) RecurringTab.LAPSED else RecurringTab.ACTIVE) }
    val tabs = buildList {
        add(RecurringTab.ACTIVE to "Active (${active.size})")
        if (lapsed.isNotEmpty()) add(RecurringTab.LAPSED to "Cancelled? (${lapsed.size})")
        if (hidden.isNotEmpty()) add(RecurringTab.HIDDEN to "Hidden (${hidden.size})")
    }
    // A tab that just emptied (last hidden item restored) must not leave the user on a blank page.
    val currentTab = if (tabs.any { it.first == tab }) tab else RecurringTab.ACTIVE
    val list = when (currentTab) {
        RecurringTab.ACTIVE -> active
        RecurringTab.LAPSED -> lapsed
        RecurringTab.HIDDEN -> hidden
    }

    ResistantModalBottomSheet(
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
                fontSize = 22.sp, letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "${rupees(monthlyTotal)} / month · ${active.size} active",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Segmented control (same look as the Dashboard's time-range selector)
            if (tabs.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        tabs.forEach { (tabId, label) ->
                            val isSelected = currentTab == tabId
                            val segmentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.surfaceBright
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
                                        tab = tabId
                                    },
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = segmentColor,
                                shadowElevation = if (isSelected) 1.dp else 0.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val helper = when (currentTab) {
                RecurringTab.LAPSED -> "No charge seen for a while. These may have been cancelled."
                RecurringTab.HIDDEN -> "You marked these as not recurring. Restore any that were hidden by mistake."
                RecurringTab.ACTIVE -> null
            }
            if (helper != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(helper, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (list.isEmpty()) {
                Text(
                    "Nothing here yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = list,
                        key = { payment -> "${currentTab.name}_${RecurringPaymentDetector.groupKey(payment.payee)}_${payment.cadence.name}" }
                    ) { payment ->
                        RecurringPaymentCard(
                            modifier = Modifier.animateItem(),
                            payment = payment,
                            now = now,
                            dateFormat = dateFormat,
                            lapsed = currentTab == RecurringTab.LAPSED,
                            hiddenMode = currentTab == RecurringTab.HIDDEN,
                            tracked = necessities.any {
                                NecessityManager.matches(it, payment.payee, payment.category)
                            },
                            onAddToFixedCosts = { item ->
                                onDismiss()
                                onAddToFixedCosts(item)
                            },
                            onHide = { onHide(payment) },
                            onRestore = { onRestore(payment) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurringPaymentCard(
    modifier: Modifier = Modifier,
    payment: RecurringPayment,
    now: Long,
    dateFormat: SimpleDateFormat,
    lapsed: Boolean,
    hiddenMode: Boolean = false,
    tracked: Boolean,
    onAddToFixedCosts: (NecessityItem) -> Unit,
    onHide: () -> Unit = {},
    onRestore: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val iconKey = payment.payee + " " + payment.category
    val categoryIcon = getCategoryIcon(iconKey)
    val categoryColor = getCategoryColor(iconKey)
    val tentative = !lapsed && !hiddenMode && payment.confidence < TENTATIVE_BELOW

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (lapsed || hiddenMode) 0.75f else 1f),
        shape = RoundedCornerShape(AppRadius.medium),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = categoryColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(categoryIcon, contentDescription = null, tint = categoryColor, modifier = Modifier.size(22.dp))
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
                    Text(
                        (if (payment.isFixedAmount) "" else "~") + rupees(payment.amount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = scheme.onSurface
                    )
                    if (payment.cadence != Cadence.MONTHLY) {
                        Text(
                            "${rupees(payment.monthlyCost)}/mo",
                            fontSize = 12.sp,
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
                if (hiddenMode) {
                    Text(
                        "Last paid ${dateFormat.format(Date(payment.lastSeen))}",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = scheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable { haptic.tap(); onRestore() }
                    ) {
                        Text(
                            "Restore",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    RecurringBadge(
                        text = dueLabel(payment, now, lapsed, dateFormat),
                        color = dueColor(payment, now, lapsed)
                    )
                    // Low-emphasis escape hatch for a wrongly detected payment.
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Hide",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .quietClickable { haptic.tap(); onHide() }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        Text(
                            "Not recurring",
                            fontSize = 10.sp,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (!hiddenMode && !lapsed) {
                Spacer(modifier = Modifier.height(10.dp))
                if (tracked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AppColors.positive, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Counted in your fixed costs", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = scheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable {
                                haptic.tap()
                                onAddToFixedCosts(buildFixedCostDraft(payment))
                            }
                    ) {
                        Text(
                            "+ Add to fixed costs",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────── Shared bits ───────────────────────────────

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
 * Builds the prefilled Fixed Cost via NecessityManager.itemFrom, mapping 28-day recharges
 * to EVERY_N_DAYS (28d) and calendar cadences to their matching NecessityFrequency.
 */
private fun buildFixedCostDraft(payment: RecurringPayment): NecessityItem {
    val is28Day = payment.cadence == Cadence.RECHARGE_28D
    val matched = if (is28Day) NecessityFrequency.EVERY_N_DAYS
    else NecessityFrequency.entries.firstOrNull { it.name == payment.cadence.name }
    val frequency = matched ?: NecessityFrequency.MONTHLY
    val amount = if (matched != null) payment.amount else payment.monthlyCost

    return NecessityManager.itemFrom(
        NecessityManager.BillSuggestion(
            payee = payment.payee,
            amount = amount,
            category = payment.category.takeIf { !it.equals("Uncategorized", ignoreCase = true) },
            frequency = frequency,
            lastTimestamp = payment.lastSeen,
            txnCount = 0,
            validityDays = if (is28Day) 28 else null
        )
    )
}
