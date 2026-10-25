package com.expensetracker.offline.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.engine.split.ReceiptScanner
import com.expensetracker.offline.ui.theme.AppRadius
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemizedBillSplitCard(
    items: List<ReceiptScanner.ScannedItem>,
    taxPaise: Long? = null,
    receiptTotalPaise: Long? = null,
    currentBillAmountPaise: Long? = null,
    initialPeople: List<String> = emptyList(),
    onItemsModified: (List<ReceiptScanner.ScannedItem>) -> Unit,
    onSyncBillTotal: ((Long) -> Unit)? = null,
    onComputedSplitChanged: ((List<SplitParticipant>, Long) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme

    var people by remember {
        val base = (listOf("You") + initialPeople.map { it.trim() }.filter { it.isNotEmpty() && !it.equals("You", true) }).distinct()
        mutableStateOf(if (base.size == 1) listOf("You") else base)
    }
    var showAddPersonDialog by remember { mutableStateOf(false) }
    var newPersonName by remember { mutableStateOf("") }

    var showAddItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemPriceStr by remember { mutableStateOf("") }

    var assignmentTick by remember { mutableIntStateOf(0) }

    val itemsSubtotalPaise = remember(items, assignmentTick) { items.sumOf { it.pricePaise } }
    val effectiveBillTotalPaise = remember(receiptTotalPaise, itemsSubtotalPaise, taxPaise) {
        val withTax = itemsSubtotalPaise + (taxPaise ?: 0L)
        maxOf(receiptTotalPaise ?: 0L, withTax, itemsSubtotalPaise)
    }

    val perPersonShares = remember(items, people, effectiveBillTotalPaise, assignmentTick) {
        val anyAssigned = items.any { it.assignedTo.isNotEmpty() }
        if (!anyAssigned) emptyMap()
        else ReceiptScanner.calculateProRataShares(items, effectiveBillTotalPaise, people)
    }

    fun notifySplitUpdate(currentItems: List<ReceiptScanner.ScannedItem>, currentPeople: List<String>) {
        val anyAssigned = currentItems.any { it.assignedTo.isNotEmpty() }
        if (!anyAssigned) return
        val shares = ReceiptScanner.calculateProRataShares(currentItems, effectiveBillTotalPaise, currentPeople)
        val participants = currentPeople.map { person ->
            val sharePaise = shares[person] ?: 0L
            SplitParticipant(
                isMe = person.equals("You", ignoreCase = true),
                name = person,
                isIncludedInEqual = sharePaise > 0L,
                exactAmountStr = if (sharePaise > 0L) String.format(Locale.US, "%.2f", sharePaise / 100.0) else ""
            )
        }
        onComputedSplitChanged?.invoke(participants, effectiveBillTotalPaise)
    }

    if (showAddPersonDialog) {
        AppDialog(
            title = "Add person to bill",
            onDismiss = { showAddPersonDialog = false; newPersonName = "" },
            confirmText = "Add",
            confirmEnabled = newPersonName.trim().isNotEmpty(),
            onConfirm = {
                val trimmed = newPersonName.trim()
                if (trimmed.isNotEmpty() && people.none { it.equals(trimmed, ignoreCase = true) }) {
                    val updated = people + trimmed
                    people = updated
                    notifySplitUpdate(items, updated)
                }
                newPersonName = ""
                showAddPersonDialog = false
            }
        ) {
            OutlinedTextField(
                value = newPersonName,
                onValueChange = { newPersonName = it.take(25) },
                label = { Text("Name") },
                placeholder = { Text("e.g., Adhil, Rahul") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                colors = sheetFieldColors(),
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showAddItemDialog) {
        val parsedPricePaise = remember(newItemPriceStr) {
            newItemPriceStr.trim().replace(",", "").toDoubleOrNull()?.times(100.0)?.roundToLong()
        }
        AppDialog(
            title = "Add bill item",
            onDismiss = { showAddItemDialog = false; newItemName = ""; newItemPriceStr = "" },
            confirmText = "Add item",
            confirmEnabled = newItemName.trim().isNotEmpty() && (parsedPricePaise ?: 0L) > 0L,
            onConfirm = {
                val price = parsedPricePaise ?: 0L
                if (newItemName.trim().isNotEmpty() && price > 0L) {
                    val updated = items + ReceiptScanner.ScannedItem(name = newItemName.trim(), pricePaise = price)
                    onItemsModified(updated)
                }
                newItemName = ""
                newItemPriceStr = ""
                showAddItemDialog = false
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it.take(40) },
                    label = { Text("Food / Item name") },
                    placeholder = { Text("e.g., Al-fahm Chicken") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    colors = sheetFieldColors(),
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newItemPriceStr,
                    onValueChange = { newItemPriceStr = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Price (₹)") },
                    placeholder = { Text("e.g., 290") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = sheetFieldColors(),
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    SheetCard(
        modifier = Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Scanned Bill Items (${items.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                val taxNote = if (taxPaise != null && taxPaise > 0L) " + ₹${formatSheetAmount(taxPaise / 100.0)} tax" else ""
                Text(
                    "Items ₹${formatSheetAmount(itemsSubtotalPaise / 100.0)}$taxNote · Tap who ate each item",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant
                )
            }

            if (onSyncBillTotal != null && effectiveBillTotalPaise > 0L && currentBillAmountPaise != effectiveBillTotalPaise) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = scheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .clickable {
                            haptic.tap()
                            onSyncBillTotal(effectiveBillTotalPaise)
                        }
                ) {
                    Text(
                        "Use ₹${formatSheetAmount(effectiveBillTotalPaise / 100.0)}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.surface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "People on this bill",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = scheme.onSurface.copy(alpha = 0.06f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .clickable {
                            haptic.tap()
                            showAddPersonDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add person", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                people.forEach { person ->
                    val share = perPersonShares[person] ?: 0L
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = scheme.onSurface.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (share > 0L) "$person · ₹${formatSheetAmount(share / 100.0)}" else person,
                                fontSize = 12.sp,
                                fontWeight = if (share > 0L) FontWeight.Bold else FontWeight.Medium,
                                color = scheme.onSurface
                            )
                            if (!person.equals("You", ignoreCase = true)) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $person",
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            haptic.tap()
                                            val updatedPeople = people.filter { it != person }
                                            people = updatedPeople
                                            items.forEach { it.assignedTo.remove(person) }
                                            assignmentTick++
                                            onItemsModified(items)
                                            notifySplitUpdate(items, updatedPeople)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))

        key(assignmentTick) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(AppRadius.small),
                        color = scheme.onSurface.copy(alpha = 0.03f),
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (item.qty > 1) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(AppRadius.pill),
                                            color = scheme.onSurface.copy(alpha = 0.08f)
                                        ) {
                                            Text(
                                                "×${item.qty}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = scheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "₹${formatSheetAmount(item.pricePaise / 100.0)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove item",
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            haptic.tap()
                                            val updated = items.filter { it.id != item.id }
                                            onItemsModified(updated)
                                            notifySplitUpdate(updated, people)
                                        }
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val allSelected = people.isNotEmpty() && item.assignedTo.containsAll(people)
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = if (allSelected) scheme.onSurface else scheme.surface,
                                    border = if (allSelected) null else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .clickable {
                                            haptic.tap()
                                            if (allSelected) item.assignedTo.clear() else item.assignedTo.addAll(people)
                                            assignmentTick++
                                            onItemsModified(items)
                                            notifySplitUpdate(items, people)
                                        }
                                ) {
                                    Text(
                                        text = "All",
                                        fontSize = 11.5.sp,
                                        fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (allSelected) scheme.surface else scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                people.forEach { person ->
                                    val isAssigned = item.assignedTo.contains(person)
                                    Surface(
                                        shape = RoundedCornerShape(AppRadius.pill),
                                        color = if (isAssigned) scheme.onSurface else scheme.surface,
                                        border = if (isAssigned) null else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppRadius.pill))
                                            .clickable {
                                                haptic.tap()
                                                if (isAssigned) item.assignedTo.remove(person) else item.assignedTo.add(person)
                                                assignmentTick++
                                                onItemsModified(items)
                                                notifySplitUpdate(items, people)
                                            }
                                    ) {
                                        Text(
                                            text = person,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isAssigned) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isAssigned) scheme.surface else scheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(AppRadius.small),
            color = scheme.onSurface.copy(alpha = 0.04f),
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.small))
                .clickable {
                    haptic.tap()
                    showAddItemDialog = true
                }
        ) {
            Row(
                modifier = Modifier.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add missing item manually", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
        }
    }
}