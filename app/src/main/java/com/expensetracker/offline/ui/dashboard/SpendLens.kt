package com.expensetracker.offline.ui.dashboard

import com.expensetracker.offline.ui.components.quietClickable
import com.expensetracker.offline.util.tap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.components.CategoryAvatar
import com.expensetracker.offline.ui.components.appSwitchColors
import com.expensetracker.offline.ui.components.CurrencyFormat
import com.expensetracker.offline.ui.components.ResistantModalBottomSheet
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryColor

/**
 * "Spend breakdown": the period's total spend with some categories left out ("everything except rent and
 * investments") or singled out ("only Food & Dining"). It only changes what this card shows; the budget,
 * safe-to-spend and insights keep counting every category so those numbers stay honest.
 */
internal data class LensCategory(val key: String, val name: String, val paise: Long, val count: Int)

/** Net spend per category (my share, minus refunds) turned into rows, biggest first. */
internal fun buildLensCategories(
    netByKey: Map<String, Pair<Long, Int>>,
    displayNames: Map<String, String>
): List<LensCategory> =
    netByKey
        .filter { it.value.first > 0L }
        .map { (key, value) ->
            LensCategory(
                key = key,
                name = displayNames[key] ?: key.replaceFirstChar { it.uppercase() },
                paise = value.first,
                count = value.second
            )
        }
        .sortedByDescending { it.paise }

/** What the card shows. Nothing selected means "all categories". */
internal fun lensTotal(all: List<LensCategory>, selectedKeys: Set<String>, onlyMode: Boolean): Long = when {
    selectedKeys.isEmpty() -> all.sumOf { it.paise }
    onlyMode -> all.filter { it.key in selectedKeys }.sumOf { it.paise }
    else -> all.filter { it.key !in selectedKeys }.sumOf { it.paise }
}

private fun rupees0(paise: Long): String = CurrencyFormat.withSymbol(paise, 0)

private fun namesLabel(all: List<LensCategory>, keys: Set<String>): String {
    val names = all.filter { it.key in keys }.map { it.name }
    return when {
        names.isEmpty() -> ""
        names.size <= 2 -> names.joinToString(" and ")
        else -> names.take(2).joinToString(", ") + " +${names.size - 2}"
    }
}

// ───────────────────────────────── Card ─────────────────────────────────

@Composable
internal fun SpendLensCard(
    categories: List<LensCategory>,
    selectedKeys: Set<String>,
    onlyMode: Boolean,
    periodLabel: String,
    onOpen: () -> Unit,
    onRemoveKey: (String) -> Unit
) {
    if (categories.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    val activeKeys = remember(categories, selectedKeys) { selectedKeys.filter { k -> categories.any { it.key == k } }.toSet() }
    val allTotal = remember(categories) { categories.sumOf { it.paise } }
    val total = remember(categories, activeKeys, onlyMode) { lensTotal(categories, activeKeys, onlyMode) }
    val shownTotal by animateValueAsState(
        targetValue = total,
        typeConverter = LongConverter,
        animationSpec = tween(450),
        label = "lensTotal"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.large))
            .quietClickable(pressedAlpha = 0.9f) { haptic.tap(); onOpen() },
        shape = RoundedCornerShape(AppRadius.large),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Spend breakdown",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = scheme.onSurface.copy(alpha = 0.06f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (activeKeys.isEmpty()) "Customize" else "Edit",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                rupees0(shownTotal),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    activeKeys.isEmpty() -> "All categories · $periodLabel"
                    onlyMode -> {
                        val pct = if (allTotal > 0L) (total * 100.0 / allTotal).toInt() else 0
                        "Only ${namesLabel(categories, activeKeys)} · $pct% of ${rupees0(allTotal)}"
                    }
                    else -> "Excluding ${namesLabel(categories, activeKeys)} · ${rupees0(allTotal - total)} left out"
                },
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant
            )

            if (activeKeys.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.filter { it.key in activeKeys }.forEach { cat ->
                        Surface(
                            shape = RoundedCornerShape(AppRadius.pill),
                            color = getCategoryColor(cat.name).copy(alpha = 0.12f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .quietClickable { haptic.tap(); onRemoveKey(cat.key) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Text(cat.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Close, contentDescription = "Remove ${cat.name}", tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────────── Sheet ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpendLensSheet(
    categories: List<LensCategory>,
    selectedKeys: Set<String>,
    onlyMode: Boolean,
    periodLabel: String,
    onChange: (selected: Set<String>, onlyMode: Boolean) -> Unit,
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allTotal = remember(categories) { categories.sumOf { it.paise } }
    val total = remember(categories, selectedKeys, onlyMode) { lensTotal(categories, selectedKeys, onlyMode) }
    val shownTotal by animateValueAsState(
        targetValue = total,
        typeConverter = LongConverter,
        animationSpec = tween(350),
        label = "lensSheetTotal"
    )

    ResistantModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = scheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp, top = 8.dp)
        ) {
            Text("Spend breakdown", fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp, color = scheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(periodLabel.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, color = scheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Text(rupees0(shownTotal), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            Text(
                when {
                    selectedKeys.isEmpty() -> "All categories"
                    onlyMode -> "${selectedKeys.size} selected · ${if (allTotal > 0L) (total * 100.0 / allTotal).toInt() else 0}% of ${rupees0(allTotal)}"
                    else -> "${rupees0(allTotal - total)} left out of ${rupees0(allTotal)}"
                },
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(AppRadius.pill),
                color = scheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    listOf(false to "Leave out selected", true to "Only selected").forEach { (modeIsOnly, label) ->
                        val isSelected = onlyMode == modeIsOnly
                        val segmentColor by animateColorAsState(
                            targetValue = if (isSelected) scheme.surfaceBright else scheme.surface.copy(alpha = 0f),
                            animationSpec = tween(200),
                            label = "lensSegment"
                        )
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .quietClickable(pressedAlpha = 0.8f) {
                                    if (!isSelected) haptic.tap()
                                    onChange(selectedKeys, modeIsOnly)
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
                                    color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (onlyMode) "Tap categories to count only those." else "Tap categories to leave them out, for example rent or investments.",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(categories, key = { it.key }) { cat ->
                    val picked = cat.key in selectedKeys
                    // Dim what is NOT counted: picked rows in "leave out" mode, unpicked rows in "only" mode.
                    val counted = selectedKeys.isEmpty() || (picked == onlyMode)
                    val rowAlpha by animateFloatAsState(if (counted) 1f else 0.45f, tween(180), label = "lensRowAlpha")
                    val color = getCategoryColor(cat.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.small))
                            .quietClickable(pressedAlpha = 0.85f, pressedScale = 0.99f) {
                                haptic.tap()
                                onChange(if (picked) selectedKeys - cat.key else selectedKeys + cat.key, onlyMode)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .alpha(rowAlpha),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryAvatar(category = cat.name, size = 36.dp, tint = color)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                cat.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textDecoration = if (picked && !onlyMode) TextDecoration.LineThrough else null,
                                color = scheme.onSurface
                            )
                            Text(
                                "${cat.count} ${if (cat.count == 1) "payment" else "payments"}",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Text(rupees0(cat.paise), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            if (picked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (picked) "Selected" else "Not selected",
                            tint = if (picked) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show on dashboard", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Text("Off by default. Reopen this from the list header any time.", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = pinned, onCheckedChange = { haptic.tap(); onPinnedChange(it) }, colors = appSwitchColors())
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectedKeys.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = scheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable { haptic.tap(); onChange(emptySet(), onlyMode) }
                    ) {
                        Text(
                            "Clear",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = scheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .quietClickable { haptic.tap(); onDismiss() }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 12.dp)) {
                        Text("Done", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.surface)
                    }
                }
            }
        }
    }
}
