package com.expensetracker.offline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.theme.hasCategoryIcon
import com.expensetracker.offline.util.NecessityManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SheetCategoryPicker(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onAddCustom: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showPicker by remember { mutableStateOf(false) }
    val selectedIcon = getCategoryIcon(selected)

    SheetCard(
        onClick = {
            haptic.tap()
            showPicker = true
        },
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center
            ) {
                if (hasCategoryIcon(selected) || isNoCategory(selected)) {
                    Icon(selectedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                } else {
                    Text(categoryInitial(selected), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = getCategoryColor(selected))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Category", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (isNoCategory(selected)) "No category" else selected,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var query by remember { mutableStateOf("") }
                val visible = remember(categories, query) {
                    val q = query.trim()
                    if (q.isEmpty()) categories else categories.filter { it.contains(q, ignoreCase = true) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Choose category",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // Pinned at the top so it never hides below a long list of chips.
                    TextButton(onClick = {
                        haptic.tap()
                        showPicker = false
                        onAddCustom()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (categories.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(40) },
                        placeholder = { Text("Search categories") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = sheetFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (visible.isEmpty()) {
                    Text(
                        "No category matches \"${query.trim()}\". Tap New to create it.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                val searching = query.isNotBlank()
                // Input order is most-recently-used first. Show the first few as "Recent", the rest
                // A-Z, and park "Uncategorized" last as an explicit "No category" option.
                val named = remember(visible) { visible.filterNot { isNoCategory(it) } }
                val noneValue = remember(visible) { visible.firstOrNull { isNoCategory(it) } }
                val recent = remember(named, searching) { if (searching) emptyList() else named.take(RECENT_COUNT) }
                val rest = remember(named, recent) {
                    named.drop(recent.size).let { if (searching) it else it.sortedBy { c -> c.lowercase() } }
                }

                @Composable
                fun CategoryChip(cat: String) {
                    val isSelected = selected.equals(cat, ignoreCase = true)
                    val noIcon = !hasCategoryIcon(cat)
                    SheetChip(
                        text = cat,
                        isSelected = isSelected,
                        onClick = {
                            onSelect(cat)
                            showPicker = false
                        },
                        accent = getCategoryColor(cat),
                        icon = when {
                            isSelected -> Icons.Default.Check
                            noIcon -> null
                            else -> getCategoryIcon(cat)
                        },
                        monogram = if (!isSelected && noIcon) categoryInitial(cat) else null
                    )
                }

                @Composable
                fun Section(title: String?, items: List<String>) {
                    if (items.isEmpty()) return
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (title != null) {
                            Text(
                                title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.6.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) { items.forEach { CategoryChip(it) } }
                    }
                }

                Section(if (recent.isNotEmpty()) "RECENT" else null, recent)
                Section(if (recent.isNotEmpty() && rest.isNotEmpty()) "ALL CATEGORIES" else null, rest)

                if (noneValue != null) {
                    val noneSelected = isNoCategory(selected)
                    SheetChip(
                        text = "No category",
                        isSelected = noneSelected,
                        onClick = {
                            onSelect(noneValue)
                            showPicker = false
                        },
                        icon = if (noneSelected) Icons.Default.Check else Icons.Default.Close,
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun isNoCategory(c: String) =
    c.equals("Uncategorized", ignoreCase = true) || c.equals("No category", ignoreCase = true)

private const val RECENT_COUNT = 6

private fun categoryInitial(category: String): String =
    category.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

@Composable
fun NewCategoryDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AppDialog(
        title = "New Category",
        onDismiss = onDismiss,
        confirmText = "Add",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { if (name.isNotBlank()) onAdd(name.trim()) }
    ) {
        Text("Create and assign a new category.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("e.g., Gym, Books, Gifts") },
            singleLine = true,
            shape = RoundedCornerShape(AppRadius.small),
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SheetFixedCostSuggestion(suggestion: NecessityManager.BillSuggestion, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val onSurface = MaterialTheme.colorScheme.onSurface

    SheetCard(
        onClick = {
            haptic.tap()
            onClick()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    ) {
        SheetInfoRow(
            icon = Icons.Default.Event,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = "Add ${NecessityManager.cleanBillName(suggestion.payee)} to Fixed Costs",
            subtitle = "₹${formatSheetAmount(suggestion.amount / 100.0)} · ${suggestion.frequency.label}",
            trailing = {
                Surface(
                    color = onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(AppRadius.pill)
                ) {
                    Text(
                        "Add",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        )
    }
}