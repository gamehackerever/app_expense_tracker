package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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

/** A compact selected-value field with a dedicated, accessible account picker. */
@OptIn(ExperimentalMaterial3Api::class)
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
    var showPicker by remember(selectedBankName, selectedAccountNumber) { mutableStateOf(false) }
    val selectedAccount = linkedAccounts.firstOrNull {
        it.bankName == selectedBankName && it.accountNumber == selectedAccountNumber
    }
    val selectedBankLabel = selectedAccount?.bankName ?: selectedBankName ?: nullLabel
    val selectedAccountSuffix = paymentAccountSuffix(selectedAccount?.accountNumber ?: selectedAccountNumber)
    val selectedIcon = if (selectedBankName == null) Icons.Default.Payments else Icons.Default.AccountBalance

    SheetCard(
        onClick = {
            haptic.tap()
            showPicker = true
        },
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SheetIconTile(
                icon = selectedIcon,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Payment method",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedBankLabel,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selectedBankName != null && selectedAccountSuffix != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "•••• $selectedAccountSuffix",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Choose payment method",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showPicker) {
        ResistantModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SheetHeader(
                    title = "Payment method",
                    subtitle = "Choose where this transaction was paid from"
                )

                PaymentMethodOption(
                    label = nullLabel,
                    supportingText = "No bank account assigned",
                    icon = Icons.Default.Payments,
                    selected = selectedBankName == null,
                    onClick = {
                        haptic.tap()
                        onBankSelected(null, null)
                        showPicker = false
                    }
                )

                linkedAccounts.forEach { account ->
                    val isSelected = selectedBankName == account.bankName && selectedAccountNumber == account.accountNumber
                    PaymentMethodOption(
                        label = account.bankName,
                        supportingText = paymentAccountSuffix(account.accountNumber)
                            ?.let { "Account ending in $it" } ?: "Bank account",
                        icon = Icons.Default.AccountBalance,
                        selected = isSelected,
                        onClick = {
                            haptic.tap()
                            onBankSelected(account.bankName, account.accountNumber)
                            showPicker = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodOption(
    label: String,
    supportingText: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AppRadius.medium)
    val activeBorder = colors.primary.copy(alpha = 0.5f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = if (selected) colors.primary.copy(alpha = 0.06f) else colors.surface,
        border = BorderStroke(
            1.dp,
            if (selected) activeBorder else colors.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SheetIconTile(
                icon = icon,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    supportingText,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = colors.primary)
            }
        }
    }
}

private fun paymentAccountSuffix(accountNumber: String?): String? =
    accountNumber?.filter { it.isLetterOrDigit() }?.takeLast(4)?.takeIf { it.isNotEmpty() }
