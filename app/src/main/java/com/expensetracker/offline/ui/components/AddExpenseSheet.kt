package com.expensetracker.offline.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.NecessityManager
import com.expensetracker.offline.util.tick
import com.expensetracker.offline.util.click
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    transactionsHistory: List<TransactionEntity> = emptyList(),
    onAddToFixedCosts: (NecessityManager.BillSuggestion) -> Unit = {},
    availableCategories: List<String> = emptyList(),
    onDismiss: () -> Unit,
    linkedAccounts: List<AccountInfo>,
    onSave: (
        amount: Long, payee: String, category: String, type: TransactionType,
        timestamp: Long, itemsSummary: String?, receiptUri: String?,
        bankName: String?, accountNumber: String?
    ) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var savedCustomCategories by remember {
        mutableStateOf(prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet())
    }

    val defaultCategories = listOf("Food & Dining", "Groceries", "Shopping", "Travel", "Bills & Utilities", "Entertainment", "Health", "Investments", "Uncategorized")
    val allCategories = remember(availableCategories, savedCustomCategories) {
        (availableCategories + savedCustomCategories + defaultCategories).filter { it.isNotBlank() }.distinct()
    }

    var payee by rememberSaveable { mutableStateOf("") }
    var amountStr by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("Uncategorized") }

    var transactionType by rememberSaveable { mutableStateOf(TransactionType.DEBIT) }

    var itemsSummary by rememberSaveable { mutableStateOf("") }
    var receiptImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var transactionDateMillis by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }

    var selectedBankName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAccountNumber by rememberSaveable { mutableStateOf<String?>(null) }

    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val calculatedAmount = remember(amountStr) { evaluateBasicMath(amountStr) }
    val hasUnsavedChanges by remember {
        derivedStateOf {
            amountStr.isNotBlank() || payee.isNotBlank() || itemsSummary.isNotBlank() ||
                    receiptImageUri != null || transactionType != TransactionType.DEBIT || selectedBankName != null
        }
    }
    val dirty by rememberUpdatedState(hasUnsavedChanges)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !dirty }
    )

    BackHandler(enabled = hasUnsavedChanges) { showDiscardDialog = true }

    // A blocked swipe-down snaps back without calling onDismissRequest, so surface the dialog here.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue }.collect { target ->
            if (target == SheetValue.Hidden && dirty) showDiscardDialog = true
        }
    }

    if (showDiscardDialog) {
        AppDialog(
            title = "Discard transaction?",
            onDismiss = {
                showDiscardDialog = false
                scope.launch { sheetState.show() }
            },
            dismissText = "Keep editing",
            confirmText = "Discard",
            destructive = true,
            onConfirm = {
                showDiscardDialog = false
                deleteReceiptFile(context, receiptImageUri)
                onDismiss()
            }
        ) {
            Text(
                "You have unsaved details. Are you sure you want to discard them?",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    var fixedCostSuggestion by remember { mutableStateOf<NecessityManager.BillSuggestion?>(null) }
    LaunchedEffect(payee, calculatedAmount, transactionType, selectedCategory, transactionsHistory) {
        if (transactionType != TransactionType.DEBIT || payee.isBlank()) {
            fixedCostSuggestion = null
        } else {
            // Push calculation to Default dispatcher
            val dummyTxn = TransactionEntity(
                amount = calculatedAmount ?: 0L, payee = payee.trim(), timestamp = System.currentTimeMillis(),
                type = TransactionType.DEBIT, source = TransactionSource.MANUAL, referenceId = null,
                category = selectedCategory, rawContent = "Manual Entry"
            )
            fixedCostSuggestion = NecessityManager.suggestionFor(dummyTxn, transactionsHistory)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (hasUnsavedChanges) showDiscardDialog = true else onDismiss() },
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
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp) // Uniform spacing, no dividers
            ) {
                SheetHeader(title = "Add transaction", subtitle = "Record an expense or income")

                SheetSegmentedControl(
                    options = listOf("Expense", "Income"),
                    selectedIndex = if (transactionType == TransactionType.DEBIT) 0 else 1,
                    onSelect = { transactionType = if (it == 0) TransactionType.DEBIT else TransactionType.CREDIT }
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

                SheetTextField(
                    value = payee,
                    onValueChange = { payee = it.take(80) },
                    label = "Payee / store name",
                    placeholder = "e.g., Supermarket, Rent, Client",
                    leadingIcon = Icons.Default.Storefront,
                    singleLine = true
                )

                PaymentMethodSelector(
                    linkedAccounts = linkedAccounts,
                    selectedBankName = selectedBankName,
                    selectedAccountNumber = selectedAccountNumber,
                    onBankSelected = { bank, acc ->
                        selectedBankName = bank
                        selectedAccountNumber = acc
                    },
                    nullLabel = "Cash"
                )

                // Category Group (Tightly coupled with fixed cost suggestions)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetCategoryPicker(
                        categories = allCategories,
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it },
                        onAddCustom = { showNewCategoryDialog = true }
                    )

                    AnimatedVisibility(
                        visible = transactionType == TransactionType.DEBIT && fixedCostSuggestion != null,
                        enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        fixedCostSuggestion?.let { suggestion ->
                            SheetFixedCostSuggestion(
                                suggestion = suggestion,
                                onClick = { onAddToFixedCosts(suggestion) }
                            )
                        }
                    }
                }

                SheetDetailsSection(
                    note = itemsSummary,
                    onNoteChange = { itemsSummary = it },
                    receiptUri = receiptImageUri,
                    onReceiptChange = { new ->
                        if (receiptImageUri != new) deleteReceiptFile(context, receiptImageUri)
                        receiptImageUri = new
                    },
                    receiptFilePrefix = "receipt_new"
                )
            }

            SheetFooter {
                SheetPrimaryButton(
                    text = "Save transaction",
                    enabled = (calculatedAmount ?: 0L) > 0L && !isSaving,
                    onClick = {
                        val finalAmount = calculatedAmount ?: 0L
                        val finalPayee = payee.trim().ifBlank { if (transactionType == TransactionType.DEBIT) "Cash Expense" else "Income" }
                        if (finalAmount > 0 && !isSaving) {
                            isSaving = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSave(
                                finalAmount, finalPayee, selectedCategory, transactionType,
                                transactionDateMillis, itemsSummary.ifBlank { null }, receiptImageUri,
                                selectedBankName, selectedAccountNumber
                            )
                            onDismiss()
                        }
                    }
                )
            }
        }
    }
}

// Ensure the math parser remains available locally so compilation doesn't fail
private const val MAX_EXPRESSION_LENGTH = 64
private const val MAX_NESTING_DEPTH = 16
private const val MAX_AMOUNT = 1_000_000_000_000L

fun evaluateBasicMath(input: String): Long? {
    // FIXED: Sanitize commas before evaluating math to allow pasting "1,200.50" (PO-I)
    val str = input
        .filterNot { it.isWhitespace() }
        .replace(",", "")
        .replace('×', '*')
        .replace('÷', '/')
    if (str.isEmpty() || str.length > MAX_EXPRESSION_LENGTH) return null

    return try {
        val rupees = ExpressionParser(str).parse()
        if (abs(rupees) > MAX_AMOUNT) null
        else round(rupees * 100.0).toLong()
    } catch (e: Exception) {
        null
    }
}

private class ExpressionParser(private val s: String) {
    private var pos = 0
    private var depth = 0

    fun parse(): Double {
        val value = expression()
        if (pos != s.length) fail()
        return value
    }

    private fun fail(): Nothing = throw IllegalArgumentException("Invalid expression at $pos")

    private fun eat(c: Char): Boolean {
        if (pos < s.length && s[pos] == c) { pos++; return true }
        return false
    }

    private fun expression(): Double {
        var x = term()
        while (true) {
            x = when {
                eat('+') -> x + term()
                eat('-') -> x - term()
                else -> return x
            }
        }
    }

    private fun term(): Double {
        var x = factor()
        while (true) {
            x = when {
                eat('*') -> x * factor()
                eat('/') -> x / factor()
                else -> return x
            }
        }
    }

    private fun factor(): Double {
        if (eat('+')) return factor()
        if (eat('-')) return -factor()

        if (eat('(')) {
            if (++depth > MAX_NESTING_DEPTH) fail()
            val x = expression()
            if (!eat(')')) fail()
            depth--
            return x
        }

        val start = pos
        var dots = 0
        while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.')) {
            if (s[pos] == '.') dots++
            pos++
        }
        if (pos == start || dots > 1) fail()
        return s.substring(start, pos).toDouble()
    }
}
