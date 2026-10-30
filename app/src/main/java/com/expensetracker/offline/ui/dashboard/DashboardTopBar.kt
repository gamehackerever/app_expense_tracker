package com.expensetracker.offline.ui.dashboard

import com.expensetracker.offline.util.tap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppColors

@Composable
fun DashboardTopBar(
    isScrolled: Boolean,
    isSelectionMode: Boolean,
    selectedTxnIds: Set<Long>,
    searchOpen: Boolean,
    searchQuery: String,
    currentMonthName: String,
    /** Non-null while the previous billing cycle is shown: its date range replaces the current cycle's. */
    previousCycleLabel: String? = null,
    startDayOfMonth: Int,
    hasNotificationPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    hasSmsPermission: Boolean = true,
    isManualModePreferred: Boolean = false,
    isProTipsDismissed: Boolean,
    haptic: HapticFeedback,
    onCancelSelection: () -> Unit,
    onBulkDelete: () -> Unit,
    onCycleClick: () -> Unit,
    onSearchToggle: () -> Unit,
    onAnalytics: () -> Unit,
    onSettings: () -> Unit
) {
    val dividerAlpha by animateFloatAsState(if (isScrolled) 1f else 0f, tween(180), label = "headerDivider")
    val captureHealthy = isManualModePreferred || ((hasSmsPermission || hasNotificationPermission) && isIgnoringBatteryOptimizations)
    val statusColor = if (captureHealthy) AppColors.positive else AppColors.warning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Crossfade(targetState = isSelectionMode, animationSpec = tween(150), label = "headerMode") { selecting ->
            if (selecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        haptic.tap()
                        onCancelSelection()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        "${selectedTxnIds.size} selected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = {
                        if (selectedTxnIds.isNotEmpty()) {
                            haptic.tap()
                            onBulkDelete()
                        }
                    }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete selected",
                            tint = if (selectedTxnIds.isNotEmpty()) AppColors.negative else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                DashboardHeader(
                    monthLabel = if (previousCycleLabel != null) "Previous cycle" else currentMonthName,
                    cycleLabel = previousCycleLabel ?: com.expensetracker.offline.util.BillingCycleHelper.getCycleLabel(startDayOfMonth),
                    statusColor = statusColor,
                    searchActive = searchOpen || searchQuery.isNotEmpty(),
                    showSettingsBadge = !isProTipsDismissed,
                    onCycleClick = { haptic.tap(); onCycleClick() },
                    onSearch = {
                        haptic.tap()
                        onSearchToggle()
                    },
                    onAnalytics = { haptic.tap(); onAnalytics() },
                    onSettings = { haptic.tap(); onSettings() }
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f * dividerAlpha)
        )
    }
}
