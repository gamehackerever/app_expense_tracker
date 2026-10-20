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
                Icon(selectedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Category", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    selected,
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
                Text(
                    "Choose category",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = selected.equals(cat, ignoreCase = true)
                        SheetChip(
                            text = cat,
                            isSelected = isSelected,
                            onClick = {
                                onSelect(cat)
                                showPicker = false
                            },
                            accent = getCategoryColor(cat),
                            icon = if (isSelected) Icons.Default.Check else getCategoryIcon(cat)
                        )
                    }
                    SheetChip(
                        text = "New category",
                        isSelected = false,
                        onClick = {
                            showPicker = false
                            onAddCustom()
                        },
                        icon = Icons.Default.Add,
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

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