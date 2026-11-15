package com.expensetracker.offline.ui.dashboard

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.ui.components.fadingHorizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.ui.components.PremiumFilterChip
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing

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
    onAutoDetectBanks: () -> Unit,
    showCategoryChips: Boolean = true,
    selectedSort: String = "NEWEST",
    onSortSelected: (String) -> Unit = {}
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

        if (showCategoryChips) {
            Row(
                modifier = Modifier.fillMaxWidth().fadingHorizontalScroll().padding(end = AppSpacing.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PremiumFilterChip(
                    text = "Top spends",
                    isSelected = selectedSort == "HIGHEST",
                    compact = true,
                    secondarySelection = true,
                    onClick = { onSortSelected(if (selectedSort == "HIGHEST") "NEWEST" else "HIGHEST") }
                )
                PremiumFilterChip(text = "All", isSelected = selectedFilterCategory == "All", compact = true, onClick = { onCategorySelected("All") })
                PremiumFilterChip(text = "Split only", isSelected = selectedFilterCategory == "Split Only", compact = true, onClick = { onCategorySelected("Split Only") })
                CategoryFilterMenu(
                    selectedCategory = selectedFilterCategory,
                    categories = allAvailableCategories,
                    onCategorySelected = { category ->
                        haptic.tap()
                        onCategorySelected(category)
                    },
                    onManageCategories = {
                        haptic.tap()
                        onManageCategories()
                    }
                )
            }
        }

        if (linkedAccounts.size > 1 || selectedAccount != null) {
            if (showCategoryChips) {
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fadingHorizontalScroll()
                    .padding(end = AppSpacing.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PremiumFilterChip(
                    text = "All Accounts",
                    isSelected = selectedAccount == null,
                    compact = true,
                    onClick = { onAccountSelected(null) }
                )

                if (hasUnspecifiedAccount) {
                    val isUnspecifiedSelected = selectedAccount?.bankName == "Unspecified"
                    PremiumFilterChip(
                        text = "Unspecified",
                        isSelected = isUnspecifiedSelected,
                        compact = true,
                        onClick = {
                            onAccountSelected(AccountInfo("Unspecified", null))
                        }
                    )
                }

                linkedAccounts.forEach { account ->
                    val label = if (account.accountNumber != null) "${account.bankName} (..${account.accountNumber})" else account.bankName
                    PremiumFilterChip(
                        text = label,
                        isSelected = selectedAccount == account && account.bankName != "Unspecified",
                        compact = true,
                        onClick = { onAccountSelected(account) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterMenu(
    selectedCategory: String,
    categories: List<String>,
    onCategorySelected: (String) -> Unit,
    onManageCategories: () -> Unit
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    val isCustomCategorySelected = selectedCategory != "All" && !selectedCategory.equals("Split Only", ignoreCase = true)
    val label = if (isCustomCategorySelected) selectedCategory else "Category"
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AppRadius.pill)

    Box {
        Surface(
            shape = shape,
            color = if (isCustomCategorySelected) colors.onSurface else Color.Transparent,
            border = if (isCustomCategorySelected) null else BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .clip(shape)
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (isCustomCategorySelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCustomCategorySelected) colors.surface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 112.dp)
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Choose category",
                    tint = if (isCustomCategorySelected) colors.surface else colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All categories") },
                onClick = {
                    expanded = false
                    onCategorySelected("All")
                },
                trailingIcon = {
                    if (selectedCategory == "All") Icon(Icons.Default.Check, contentDescription = "Selected")
                }
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onCategorySelected(category)
                    },
                    trailingIcon = {
                        if (selectedCategory.equals(category, ignoreCase = true)) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        }
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage categories") },
                onClick = {
                    expanded = false
                    onManageCategories()
                }
            )
        }
    }
}
