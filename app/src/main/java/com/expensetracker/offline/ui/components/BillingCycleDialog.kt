package com.expensetracker.offline.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.tick

/**
 * The ONE billing-cycle picker used by the Dashboard, Insights and Settings, so the three entry
 * points look, feel and behave identically (previously three different dialogs).
 *
 *  - live preview of the exact date range the choice produces
 *  - slider that snaps to whole days, with a haptic tick per day
 *  - quick-pick chips for the common salary days
 *  - "Reset to 1st" shortcut that only appears when it is useful
 */
@Composable
fun BillingCycleDialog(
    currentDay: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    var day by remember { mutableIntStateOf(currentDay.coerceIn(1, 28)) }
    val rangeLabel = remember(day) { BillingCycleHelper.getCycleLabel(day) }

    AppDialog(
        title = "Billing Cycle",
        onDismiss = { haptic.tick(); onDismiss() },
        confirmText = "Save",
        onConfirm = { haptic.confirm(); onSave(day) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pick the day your monthly spending cycle resets, usually your salary day.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(AppRadius.medium),
                color = colors.onSurface.copy(alpha = 0.05f),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Crossfade(targetState = day, animationSpec = tween(120), label = "cycleDay") { d ->
                        Text(
                            text = if (d == 1) "Resets on the 1st" else "Resets on Day $d",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = colors.onSurface
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = rangeLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Slider(
                value = day.toFloat(),
                onValueChange = { raw ->
                    val snapped = raw.toInt().coerceIn(1, 28)
                    if (snapped != day) {
                        day = snapped
                        haptic.tick()
                    }
                },
                valueRange = 1f..28f,
                steps = 26, // 28 stops: exactly one per selectable day
                colors = SliderDefaults.colors(
                    thumbColor = colors.onSurface,
                    activeTrackColor = colors.onSurface,
                    inactiveTrackColor = colors.onSurface.copy(alpha = 0.12f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1st", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                Text("28th", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))

            // Common salary days in one tap
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 5, 10, 15, 25, 28).forEach { preset ->
                    val selected = day == preset
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = if (selected) colors.onSurface else colors.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .clickable {
                                if (day != preset) { day = preset; haptic.tick() }
                            }
                    ) {
                        Text(
                            text = "$preset",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) colors.surface else colors.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = day != 1,
                enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(100))
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = colors.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .clickable { day = 1; haptic.tick() }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = colors.onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Reset to 1st of month",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Capped at the 28th so every month, including February, has the same cycle.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
    }
}
