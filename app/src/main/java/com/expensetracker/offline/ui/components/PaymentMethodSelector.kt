package com.expensetracker.offline.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.ui.theme.AppRadius

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentMethodSelector(
    linkedAccounts: List<AccountInfo>,
    selectedBankName: String?,
    selectedAccountNumber: String?,
    onBankSelected: (bankName: String?, accountNum: String?) -> Unit,
    nullLabel: String = "Cash"
) {
    if (linkedAccounts.isEmpty()) return

    val haptic = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Payment method",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isNullSelected = selectedBankName == null
            PaymentChip(
                label = nullLabel,
                icon = Icons.Default.Payments,
                selected = isNullSelected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBankSelected(null, null)
                }
            )

            linkedAccounts.forEach { account ->
                val isSelected = selectedBankName == account.bankName && selectedAccountNumber == account.accountNumber
                val label = if (account.accountNumber != null) "${account.bankName} •••• ${account.accountNumber}" else account.bankName
                PaymentChip(
                    label = label,
                    icon = Icons.Default.AccountBalance,
                    selected = isSelected,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBankSelected(account.bankName, account.accountNumber)
                    }
                )
            }
        }
    }
}

@Composable
private fun PaymentChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AppRadius.pill)

    // FIX: Solid neutral dark for selected state
    val bg by animateColorAsState(if (selected) colors.onSurface else colors.surface, tween(150), label = "payBg")
    val border by animateColorAsState(
        if (selected) Color.Transparent else colors.outlineVariant.copy(alpha = 0.3f), // Softer unselected border
        tween(150),
        label = "payBorder"
    )
    val fg by animateColorAsState(if (selected) colors.surface else colors.onSurfaceVariant, tween(150), label = "payText")

    Surface(
        shape = shape,
        color = bg,
        border = if (selected) null else BorderStroke(1.dp, border),
        modifier = Modifier
            .clip(shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.Check else icon,
                contentDescription = null,
                tint = fg, // Uses the high-contrast foreground
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, // Bumped weight for active
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}