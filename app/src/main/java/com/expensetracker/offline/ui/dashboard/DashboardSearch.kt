package com.expensetracker.offline.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.ui.components.PremiumFilterChip
import com.expensetracker.offline.ui.theme.AppRadius

@Composable
fun DashboardSearchAndFilter(
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClear: () -> Unit,
    focusManager: FocusManager,
    haptic: HapticFeedback,
    selectedFilterCategory: String,
    onCategorySelected: (String) -> Unit,
    allAvailableCategories: List<String>,
    onManageCategories: () -> Unit,
    linkedAccounts: List<AccountInfo>,
    selectedAccount: AccountInfo?,
    onAccountSelected: (AccountInfo?) -> Unit,
    hasUnspecifiedAccount: Boolean,
    onAutoDetectBanks: () -> Unit
) {
    Column {
        AnimatedVisibility(visible = searchOpen || searchQuery.isNotEmpty()) {
            Column {
                Surface(
                    shape = RoundedCornerShape(AppRadius.small),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = {
                            Text(
                                "Search payee, category, notes…",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    onSearchClear()
                                    haptic.tap()
                                }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { focusManager.clearFocus() }
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PremiumFilterChip(text = "All", isSelected = selectedFilterCategory == "All", onClick = { haptic.tap(); onCategorySelected("All") })
            PremiumFilterChip(text = "Split only", isSelected = selectedFilterCategory == "Split Only", onClick = { haptic.tap(); onCategorySelected("Split Only") })
            allAvailableCategories.forEach { category ->
                PremiumFilterChip(text = category, isSelected = selectedFilterCategory == category, onClick = { haptic.tap(); onCategorySelected(category) })
            }
            PremiumFilterChip(text = "+ Manage", isSelected = false, onClick = { haptic.tap(); onManageCategories() })
        }

        if (linkedAccounts.isEmpty() || linkedAccounts.size > 1) {
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (linkedAccounts.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable { haptic.tap(); onAutoDetectBanks() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Auto-detect Banks",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    PremiumFilterChip(
                        text = "All Accounts",
                        isSelected = selectedAccount == null,
                        onClick = { haptic.tap(); onAccountSelected(null) }
                    )

                    if (hasUnspecifiedAccount) {
                        val isUnspecifiedSelected = selectedAccount?.bankName == "Unspecified"
                        PremiumFilterChip(
                            text = "Unspecified",
                            isSelected = isUnspecifiedSelected,
                            onClick = {
                                haptic.tap(); onAccountSelected(AccountInfo("Unspecified", null))
                            }
                        )
                    }

                    linkedAccounts.forEach { account ->
                        val label = if (account.accountNumber != null) "${account.bankName} (..${account.accountNumber})" else account.bankName
                        PremiumFilterChip(
                            text = label,
                            isSelected = selectedAccount == account && account.bankName != "Unspecified",
                            onClick = { haptic.tap(); onAccountSelected(account) }
                        )
                    }
                }
            }
        }
    }
}
