package com.expensetracker.offline.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.tick
import com.expensetracker.offline.util.click
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    transaction: TransactionEntity,
    availableCategories: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (TransactionEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onDuplicate: (TransactionEntity) -> Unit = {},
    linkedAccounts: List<AccountInfo> = emptyList(),
    totalOwedByOthers: Long = 0L,
    onUndoRepayment: (Long) -> Unit = {},
    fixedCostLabel: String? = null,
    fixedCostSuggestion: NecessityManager.BillSuggestion? = null,
    onAddToFixedCosts: () -> Unit = {},
    onRemoveFromFixedCosts: () -> Unit = {},
    onSplit: (() -> Unit)? = null,
    onLinkToBill: (() -> Unit)? = null,
    onUpdateAllPast: (oldPayee: String, newPayee: String, newCategory: String) -> Unit = { _, _, _ -> },
    suggestedSettlementName: String? = null,
    suggestedSettlementAmount: Long? = null,
    onSettleSuggested: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var savedCustomCategories by remember {
        mutableStateOf(prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet())
    }

    val defaultCategories = listOf("Food & Dining", "Groceries", "Shopping", "Travel", "Bills & Utilities", "Entertainment", "Health")

    val allCategories = remember(availableCategories, savedCustomCategories, transaction.category) {
        val all = (defaultCategories + availableCategories + savedCustomCategories + listOf(transaction.category))
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()

        all.remove(transaction.category)
        listOf(transaction.category) + all
    }

    var payee by rememberSaveable { mutableStateOf(transaction.payee) }
    var note by rememberSaveable { mutableStateOf(transaction.note.orEmpty()) }

    var amountStr by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", transaction.amount / 100.0)) }

    var selectedCategory by rememberSaveable { mutableStateOf(transaction.category) }
    var transactionType by rememberSaveable { mutableStateOf(transaction.type) }
    var itemsSummary by rememberSaveable { mutableStateOf(transaction.itemsSummary.orEmpty()) }
    var receiptImageUri by rememberSaveable { mutableStateOf(transaction.receiptImageUri) }
    var applyToAllPast by rememberSaveable { mutableStateOf(false) }

    var editedBankName by rememberSaveable { mutableStateOf(transaction.bankName) }
    var editedAccountNumber by rememberSaveable { mutableStateOf(transaction.accountNumber) }

    var transactionDateMillis by rememberSaveable { mutableLongStateOf(transaction.timestamp) }

    var showNewCategoryDialog by remember { mutableStateOf(false) }

    val calculatedAmount = remember(amountStr) { evaluateBasicMath(amountStr) }

    val isPayeeRenamed = payee.trim().isNotBlank() && payee.trim() != transaction.payee.trim()
    val isCategoryChanged = selectedCategory != transaction.category
    val shouldApplyToAllPast = applyToAllPast && (isPayeeRenamed || isCategoryChanged)

    val hasUnsavedChanges by remember {
        derivedStateOf {
            val amtDiff = abs((evaluateBasicMath(amountStr) ?: 0L) - transaction.amount) > 0.01
            amtDiff ||
                    payee.trim() != transaction.payee.trim() ||
                    selectedCategory != transaction.category ||
                    transactionType != transaction.type ||
                    itemsSummary.trim() != (transaction.itemsSummary ?: "").trim() ||
                    note.trim() != (transaction.note ?: "").trim() ||
                    transactionDateMillis != transaction.timestamp ||
                    receiptImageUri != transaction.receiptImageUri ||
                    editedBankName != transaction.bankName ||
                    editedAccountNumber != transaction.accountNumber
        }
    }

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val dirty by rememberUpdatedState(hasUnsavedChanges)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !dirty }
    )

    BackHandler(enabled = hasUnsavedChanges) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        AppDialog(
            title = "Discard changes?",
            onDismiss = {
                showUnsavedDialog = false
                scope.launch { sheetState.show() }
            },
            dismissText = "Keep editing",
            confirmText = "Discard",
            destructive = true,
            onConfirm = {
                showUnsavedDialog = false
                receiptImageUri?.takeIf { it != transaction.receiptImageUri }?.let { deleteReceiptFile(context, it) }
                onDismiss()
            }
        ) {
            Text(
                "You have unsaved changes. Are you sure you want to discard them?",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onAdd = { newCategory ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                savedCustomCategories = savedCustomCategories + newCategory
                prefs.edit().putStringSet("key_custom_categories", savedCustomCategories).apply()
                selectedCategory = newCategory
                showNewCategoryDialog = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (hasUnsavedChanges) showUnsavedDialog = true else onDismiss()
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp) // Uniform spacing, no dividers
            ) {
                SheetHeader(
                    title = "Edit transaction",
                    subtitle = "Update items, receipt, or category",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SheetIconTile(
                                icon = Icons.Default.ContentCopy,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = "Duplicate",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDuplicate(transaction)
                                }
                            )
                            SheetIconTile(
                                icon = Icons.Default.DeleteOutline,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                contentDescription = "Delete",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDelete(transaction.id)
                                }
                            )
                        }
                    }
                )

                AnimatedVisibility(
                    visible = suggestedSettlementName != null && suggestedSettlementAmount != null && onSettleSuggested != null,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    SheetCard(
                        containerColor = MaterialTheme.colorScheme.onSurface, // Inverted premium card
                        borderColor = Color.Transparent,
                        contentPadding = 16.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Settle up?", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.surface)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Does this cover ${suggestedSettlementName}'s debt of ₹${String.format(Locale.US, "%.2f", (suggestedSettlementAmount ?: 0L) / 100.0)}?",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    lineHeight = 18.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSettleSuggested?.invoke()
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(AppRadius.pill),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Text("Settle", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = transaction.linkedDebtId != null,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    SheetCard(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        borderColor = Color.Transparent,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text("Linked repayment", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            "This transaction is settling someone's share of a split bill. You can unlink it to reopen that debt. Its type can't be changed while linked.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onUndoRepayment(transaction.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(AppRadius.small),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Undo repayment", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }

                SheetSegmentedControl(
                    options = listOf("Expense", "Income"),
                    selectedIndex = if (transactionType == TransactionType.DEBIT) 0 else 1,
                    onSelect = {
                        if (transaction.linkedDebtId == null) {
                            transactionType = if (it == 0) TransactionType.DEBIT else TransactionType.CREDIT
                        }
                    }
                )

                SheetAmountCard(
                    amountStr = amountStr,
                    onAmountChange = { amountStr = it },
                    calculatedAmount = calculatedAmount?.let { it / 100.0 },
                    isDebit = transactionType == TransactionType.DEBIT,
                    trailing = {
                        SheetDatePill(millis = transactionDateMillis, onDateSelected = { transactionDateMillis = it })
                    }
                )

                // ── Payee Group (Tightly coupled) ──
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetTextField(
                        value = payee,
                        onValueChange = { payee = it },
                        label = "Payee / store name",
                        leadingIcon = Icons.Default.Storefront,
                        singleLine = true
                    )

                    AnimatedVisibility(
                        visible = isPayeeRenamed || isCategoryChanged,
                        enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        SheetCard(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                applyToAllPast = !applyToAllPast
                            },
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            contentPadding = 8.dp
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = applyToAllPast,
                                    onCheckedChange = null,
                                    modifier = Modifier.padding(8.dp),
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                )
                                val labelText = if (isPayeeRenamed) {
                                    "Rename all \"${transaction.payee}\" & apply \"$selectedCategory\""
                                } else {
                                    "Apply \"$selectedCategory\" to all past \"${transaction.payee}\" payments"
                                }
                                Text(
                                    labelText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                            }
                        }
                    }
                }

                if (linkedAccounts.size > 1) {
                    SheetSection(title = "Paid from", trailing = editedBankName ?: "Unspecified") {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SheetChip(
                                text = "Unspecified",
                                isSelected = editedBankName == null,
                                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = {
                                    editedBankName = null
                                    editedAccountNumber = null
                                }
                            )
                            linkedAccounts.forEach { account ->
                                val isSelected = editedBankName == account.bankName && editedAccountNumber == account.accountNumber
                                val label = if (account.accountNumber != null) "${account.bankName} (..${account.accountNumber})" else account.bankName
                                SheetChip(
                                    text = label,
                                    isSelected = isSelected,
                                    onClick = {
                                        editedBankName = account.bankName
                                        editedAccountNumber = account.accountNumber
                                    }
                                )
                            }
                        }
                    }
                }

                // ── Category Group (Tightly coupled) ──
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetCategoryPicker(
                        categories = allCategories,
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it },
                        onAddCustom = { showNewCategoryDialog = true }
                    )

                    AnimatedVisibility(
                        visible = transactionType == TransactionType.DEBIT && (fixedCostLabel != null || fixedCostSuggestion != null),
                        enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        if (fixedCostLabel != null) {
                            SheetCard(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                onClick = onRemoveFromFixedCosts
                            ) {
                                SheetInfoRow(
                                    icon = Icons.Default.CheckCircle,
                                    title = "Counted toward $fixedCostLabel",
                                    subtitle = "Tap to undo",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (fixedCostSuggestion != null) {
                            SheetFixedCostSuggestion(suggestion = fixedCostSuggestion, onClick = onAddToFixedCosts)
                        }
                    }
                }

                SheetDetailsSection(
                    note = itemsSummary,
                    onNoteChange = { itemsSummary = it },
                    receiptUri = receiptImageUri,
                    onReceiptChange = { new ->
                        val old = receiptImageUri
                        if (old != null && old != new && old != transaction.receiptImageUri) deleteReceiptFile(context, old)
                        receiptImageUri = new
                    },
                    receiptFilePrefix = "receipt_${transaction.id}"
                )

                val canSplit = onSplit != null && transaction.type == TransactionType.DEBIT && transaction.linkedDebtId == null
                val canLink = onLinkToBill != null && transaction.type == TransactionType.CREDIT && transaction.linkedDebtId == null

                AnimatedVisibility(
                    visible = canSplit || canLink,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (canSplit) onSplit?.invoke() else onLinkToBill?.invoke()
                            },
                            enabled = !hasUnsavedChanges,
                            shape = RoundedCornerShape(AppRadius.small),
                            // FIX: Replaced transparent background with a soft, tactile premium fill
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            ),
                            // FIX: Removed the rigid border to let the shape and background color do the work
                            border = null,
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(
                                if (canSplit) Icons.AutoMirrored.Filled.CallSplit else Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    canSplit && totalOwedByOthers > 0L -> "Edit split"
                                    canSplit -> "Split this bill"
                                    else -> "Link to a bill"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        AnimatedVisibility(
                            visible = hasUnsavedChanges,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                "Save your changes first to use this.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            val coversDebts = (calculatedAmount ?: 0L) + 0.005 >= totalOwedByOthers &&
                    (totalOwedByOthers <= 0L || transactionType == TransactionType.DEBIT)

            SheetFooter {
                if (!coversDebts) {
                    Text(
                        "This bill is split — the amount can't be less than what others owe (${CurrencyFormat.withSymbol(totalOwedByOthers, 2)}), and it must stay an expense. Edit the split first.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                SheetPrimaryButton(
                    text = "Save changes",
                    enabled = (calculatedAmount ?: 0L) > 0L && coversDebts && hasUnsavedChanges && !isSaving,
                    onClick = {
                        isSaving = true
                        val finalAmount = calculatedAmount ?: transaction.amount
                        val trimmedPayee = payee.trim().ifBlank { if (transactionType == TransactionType.DEBIT) "Cash Expense" else "Income" }

                        if (shouldApplyToAllPast) {
                            onUpdateAllPast(transaction.payee, trimmedPayee, selectedCategory)
                        }

                        val updatedTxn = transaction.copy(
                            payee = trimmedPayee,
                            amount = finalAmount,
                            category = selectedCategory,
                            type = transactionType,
                            timestamp = transactionDateMillis,
                            note = note.trim().ifBlank { null },
                            itemsSummary = itemsSummary.trim().ifBlank { null },
                            receiptImageUri = receiptImageUri,
                            bankName = editedBankName,
                            accountNumber = editedAccountNumber
                        )
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave(updatedTxn)
                        onDismiss()
                    }
                )
            }
        }
    }
}
