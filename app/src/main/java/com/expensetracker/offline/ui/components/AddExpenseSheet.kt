package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.ml.SmartExpenseEngine
import com.expensetracker.offline.data.repository.CustomBudget
import com.expensetracker.offline.ui.dashboard.BudgetPickerRow
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    transactionsHistory: List<TransactionEntity> = emptyList(),
    onAddToFixedCosts: (NecessityManager.BillSuggestion) -> Unit = {},
    availableCategories: List<String> = emptyList(),
    autoLaunchReceiptPicker: Boolean = false,
    onReceiptPickerLaunched: () -> Unit = {},
    onDismiss: () -> Unit,
    linkedAccounts: List<AccountInfo>,
    initialSplitPeople: List<String> = listOf("You"),
    onSave: (
        amount: Long, payee: String, category: String, type: TransactionType,
        timestamp: Long, itemsSummary: String?, receiptUri: String?,
        bankName: String?, accountNumber: String?, excludeFromSpend: Boolean
    ) -> Unit,
    onSaveSplit: ((List<SplitParticipant>) -> Unit)? = null,
    customBudgets: List<CustomBudget> = emptyList(),
    /** Reports the chosen custom budget (null = plain monthly spending) so the caller can tag the new transaction. */
    onBudgetSelected: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var selectedBudgetId by remember { mutableStateOf<String?>(null) }

    var savedCustomCategories by remember {
        mutableStateOf(prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet())
    }

    val defaultCategories = CategoryClassifier.PREDEFINED_CATEGORIES
    val allCategories = remember(availableCategories, savedCustomCategories) {
        (availableCategories + savedCustomCategories + defaultCategories).filter { it.isNotBlank() }.distinct()
    }

    var payee by rememberSaveable { mutableStateOf("") }
    var amountStr by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("Uncategorized") }
    var lastNonTransferCategory by rememberSaveable { mutableStateOf("Uncategorized") }
    var userManuallyPickedCategory by rememberSaveable { mutableStateOf(false) }

    var transactionType by rememberSaveable { mutableStateOf(TransactionType.DEBIT) }
    var isTransferMode by rememberSaveable { mutableStateOf(false) }
    var excludeFromSpend by rememberSaveable { mutableStateOf(false) }
    var lastNonTransferExclude by rememberSaveable { mutableStateOf(false) }

    var itemsSummary by rememberSaveable { mutableStateOf("") }
    var receiptImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var transactionDateMillis by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }

    var selectedBankName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAccountNumber by rememberSaveable { mutableStateOf<String?>(null) }

    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var itemizedSplitShares by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val hasItemizedFriendsAssigned = remember(itemizedSplitShares) {
        itemizedSplitShares.any { (person, paise) -> !person.equals("You", ignoreCase = true) && paise > 0L }
    }

    val calculatedAmount = remember(amountStr) { evaluateBasicMath(amountStr) }

    // Smart contextual payee & amount predictions conditioned on amount, hour-of-day, day-of-week & recency
    // Computed off the main thread so typing in Payee/Amount never stutters on large histories.
    var smartPredictions by remember { mutableStateOf<List<SmartExpenseEngine.SmartPayeePrediction>>(emptyList()) }
    LaunchedEffect(payee, calculatedAmount, transactionDateMillis, transactionType, isTransferMode, transactionsHistory) {
        if (isTransferMode || transactionsHistory.isEmpty()) {
            smartPredictions = emptyList()
        } else {
            if (payee.isNotEmpty() || amountStr.isNotEmpty()) delay(50)
            val cleanPayee = payee.trim()
            val hasAmt = amountStr.isNotBlank()
            smartPredictions = withContext(Dispatchers.Default) {
                SmartExpenseEngine.predictContextualPayees(
                    transactions = transactionsHistory,
                    query = cleanPayee,
                    nowMillis = transactionDateMillis,
                    isDebit = transactionType == TransactionType.DEBIT,
                    limit = 5,
                    amountPaise = calculatedAmount
                ).filterNot { pred ->
                    pred.payee.equals(cleanPayee, ignoreCase = true) && hasAmt
                }
            }
        }
    }

    // Hybrid Jaro-Winkler + Subword Naive Bayes + Gaussian Log-Amount & Temporal auto-categorization
    LaunchedEffect(payee, calculatedAmount, transactionDateMillis, isTransferMode, userManuallyPickedCategory) {
        if (isTransferMode || userManuallyPickedCategory) return@LaunchedEffect
        val clean = payee.trim()
        if (clean.length < 2 && (calculatedAmount == null || calculatedAmount <= 0L)) return@LaunchedEffect
        delay(100)
        val inferred = withContext(Dispatchers.Default) {
            SmartExpenseEngine.predictCategoryContextual(
                payee = clean,
                amountPaise = calculatedAmount,
                timestampMillis = transactionDateMillis,
                history = transactionsHistory
            ).takeUnless {
                it.equals("Uncategorized", ignoreCase = true) || it.equals("Transfer", ignoreCase = true)
            }
        }
        if (inferred != null) {
            selectedCategory = inferred
            lastNonTransferCategory = inferred
        }
    }
    val hasUnsavedChanges by remember {
        derivedStateOf {
            amountStr.isNotBlank() || payee.isNotBlank() || itemsSummary.isNotBlank() ||
                    receiptImageUri != null || transactionType != TransactionType.DEBIT || isTransferMode ||
                    excludeFromSpend || selectedBankName != null || hasItemizedFriendsAssigned
        }
    }
    val dirty by rememberUpdatedState(hasUnsavedChanges)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirty) {
                showDiscardDialog = true
                false
            } else {
                true
            }
        }
    )

    LaunchedEffect(showDiscardDialog, sheetState.currentValue) {
        if (!showDiscardDialog && sheetState.currentValue == SheetValue.Hidden && !isSaving) {
            runCatching { sheetState.show() }
        }
    }

    BackHandler(enabled = hasUnsavedChanges) { showDiscardDialog = true }

    if (showDiscardDialog) {
        AppDialog(
            title = "Discard transaction?",
            onDismiss = {
                haptic.tap()
                showDiscardDialog = false
                scope.launch { runCatching { sheetState.show() } }
            },
            dismissText = "Keep editing",
            confirmText = "Discard",
            destructive = true,
            onConfirm = {
                haptic.confirm()
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
    LaunchedEffect(payee, calculatedAmount, transactionType, isTransferMode, selectedCategory, transactionsHistory) {
        if (transactionType != TransactionType.DEBIT || isTransferMode || payee.isBlank()) {
            fixedCostSuggestion = null
        } else {
            delay(250)
            val dummyTxn = TransactionEntity(
                amount = calculatedAmount ?: 0L, payee = payee.trim(), timestamp = System.currentTimeMillis(),
                type = TransactionType.DEBIT, source = TransactionSource.MANUAL, referenceId = null,
                category = selectedCategory, rawContent = "Manual Entry"
            )
            fixedCostSuggestion = withContext(Dispatchers.Default) {
                NecessityManager.suggestionFor(dummyTxn, transactionsHistory.take(500))
            }
        }
    }

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onAdd = { newCategory ->
                haptic.tap()
                savedCustomCategories = savedCustomCategories + newCategory
                prefs.edit().putStringSet("key_custom_categories", savedCustomCategories).apply()
                selectedCategory = newCategory
                userManuallyPickedCategory = true
                if (!newCategory.equals("Transfer", ignoreCase = true)) {
                    lastNonTransferCategory = newCategory
                }
                showNewCategoryDialog = false
            }
        )
    }

    ResistantModalBottomSheet(
        onDismissRequest = { if (hasUnsavedChanges) showDiscardDialog = true else onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        BackHandler(enabled = hasUnsavedChanges) { showDiscardDialog = true }

        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 28.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                SheetHeader(title = "Add transaction", subtitle = "Record an expense, income, or self-transfer")

                SheetSegmentedControl(
                    options = listOf("Expense", "Income", "Transfer"),
                    selectedIndex = when {
                        isTransferMode -> 2
                        transactionType == TransactionType.DEBIT -> 0
                        else -> 1
                    },
                    onSelect = { idx ->
                        when (idx) {
                            0 -> {
                                if (isTransferMode) {
                                    selectedCategory = lastNonTransferCategory
                                    excludeFromSpend = lastNonTransferExclude
                                }
                                isTransferMode = false
                                transactionType = TransactionType.DEBIT
                            }
                            1 -> {
                                if (isTransferMode) {
                                    selectedCategory = lastNonTransferCategory
                                    excludeFromSpend = false
                                }
                                isTransferMode = false
                                transactionType = TransactionType.CREDIT
                            }
                            2 -> {
                                if (!isTransferMode) {
                                    if (!selectedCategory.equals("Transfer", ignoreCase = true)) {
                                        lastNonTransferCategory = selectedCategory
                                    }
                                    lastNonTransferExclude = excludeFromSpend
                                }
                                isTransferMode = true
                                transactionType = TransactionType.DEBIT
                                selectedCategory = "Transfer"
                                excludeFromSpend = true
                            }
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

                SheetTextField(
                    value = payee,
                    onValueChange = { payee = it.take(80) },
                    label = if (isTransferMode) "Destination account / note" else "Payee / store name",
                    placeholder = if (isTransferMode) "e.g., Savings A/c, Credit Card Bill" else "e.g., Supermarket, Restaurant",
                    leadingIcon = Icons.Default.Storefront,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                AnimatedVisibility(
                    visible = !isTransferMode && smartPredictions.isNotEmpty(),
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    Row(
                        modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        smartPredictions.forEach { pred ->
                            val typicalRupees = pred.typicalAmountPaise / 100L
                            val chipLabel = if (typicalRupees > 0L) {
                                "${pred.payee} · ₹$typicalRupees"
                            } else {
                                pred.payee
                            }
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .clickable {
                                        haptic.tap()
                                        payee = pred.payee
                                        if (amountStr.isBlank() && pred.typicalAmountPaise > 0L) {
                                            amountStr = if (pred.typicalAmountPaise % 100L == 0L) {
                                                (pred.typicalAmountPaise / 100L).toString()
                                            } else {
                                                String.format(Locale.US, "%.2f", pred.typicalAmountPaise / 100.0)
                                            }
                                        }
                                        if (!userManuallyPickedCategory &&
                                            pred.category.isNotBlank() &&
                                            !pred.category.equals("Uncategorized", ignoreCase = true)
                                        ) {
                                            selectedCategory = pred.category
                                            lastNonTransferCategory = pred.category
                                        }
                                        if (selectedBankName == null && pred.bankName != null &&
                                            linkedAccounts.any { it.bankName == pred.bankName && it.accountNumber == pred.accountNumber }
                                        ) {
                                            selectedBankName = pred.bankName
                                            selectedAccountNumber = pred.accountNumber
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = chipLabel,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (pred.reasonLabel.isNotBlank() && !pred.reasonLabel.equals(pred.category, ignoreCase = true)) {
                                        Text(
                                            text = "· ${pred.reasonLabel}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

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

                AnimatedVisibility(
                    visible = isTransferMode,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                ) {
                    SheetCard(contentPadding = 14.dp) {
                        SheetInfoRow(
                            icon = Icons.Default.SwapHoriz,
                            title = "Self-transfer / CC bill payment",
                            subtitle = "Automatically excluded from monthly spending totals",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !isTransferMode,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetCategoryPicker(
                            categories = allCategories,
                            selected = selectedCategory,
                            onSelect = {
                                selectedCategory = it
                                userManuallyPickedCategory = true
                                if (it.equals("Transfer", ignoreCase = true)) {
                                    isTransferMode = true
                                    excludeFromSpend = true
                                } else {
                                    isTransferMode = false
                                    lastNonTransferCategory = it
                                }
                            },
                            onAddCustom = { showNewCategoryDialog = true }
                        )

                        if (transactionType == TransactionType.DEBIT) {
                            BudgetPickerRow(
                                budgets = customBudgets,
                                selectedId = selectedBudgetId,
                                onSelect = { id ->
                                    selectedBudgetId = id
                                    onBudgetSelected(id)
                                    if (customBudgets.firstOrNull { it.id == id }?.excludeFromMonthly == true) {
                                        excludeFromSpend = true
                                        lastNonTransferExclude = true
                                    }
                                }
                            )
                            SheetCard(
                                onClick = {
                                    haptic.tap()
                                    val next = !excludeFromSpend
                                    excludeFromSpend = next
                                    lastNonTransferExclude = next
                                },
                                contentPadding = 12.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Exclude from monthly spend",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "Pass-through or reimbursable expenses",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = excludeFromSpend,
                                        onCheckedChange = {
                                            haptic.tap()
                                            excludeFromSpend = it
                                            lastNonTransferExclude = it
                                        },
                                        colors = appSwitchColors()
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = transactionType == TransactionType.DEBIT && !isTransferMode && !excludeFromSpend && fixedCostSuggestion != null,
                            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            val suggestion = fixedCostSuggestion
                            if (suggestion != null) {
                                SheetFixedCostSuggestion(
                                    suggestion = suggestion,
                                    onClick = { onAddToFixedCosts(suggestion) }
                                )
                            }
                        }
                    }
                }

                SheetDetailsSection(
                    note = itemsSummary,
                    onNoteChange = { itemsSummary = it },
                    receiptUri = receiptImageUri,
                    autoLaunchReceiptPicker = autoLaunchReceiptPicker,
                    onReceiptPickerLaunched = onReceiptPickerLaunched,
                    onReceiptChange = { new ->
                        if (receiptImageUri != new) deleteReceiptFile(context, receiptImageUri)
                        receiptImageUri = new
                    },
                    receiptFilePrefix = "receipt_new",
                    currentBillAmountPaise = calculatedAmount,
                    initialPeople = initialSplitPeople,
                    onReceiptScanned = { _, scannedTotalPaise ->
                        if (scannedTotalPaise != null && scannedTotalPaise > 0L) {
                            amountStr = String.format(Locale.US, "%.2f", scannedTotalPaise / 100.0)
                        }
                    },
                    onItemizedSplitComputed = { participants, _ ->
                        itemizedSplitShares = participants.associate { p ->
                            val paise = (p.exactAmountStr.toDoubleOrNull() ?: 0.0) * 100.0
                            p.name to round(paise).toLong()
                        }
                    }
                )
            }

            SheetFooter {
                SheetPrimaryButton(
                    text = when {
                        isTransferMode -> "Save transfer"
                        hasItemizedFriendsAssigned -> "Save transaction & split"
                        else -> "Save transaction"
                    },
                    enabled = (calculatedAmount ?: 0L) > 0L && !isSaving,
                    onClick = {
                        val finalAmount = calculatedAmount ?: 0L
                        val finalPayee = payee.trim().ifBlank {
                            when {
                                isTransferMode -> "Self Transfer"
                                transactionType == TransactionType.DEBIT -> "Cash Expense"
                                else -> "Income"
                            }
                        }
                        val finalCategory = if (isTransferMode) "Transfer" else selectedCategory
                        val finalExclude = excludeFromSpend || isTransferMode || finalCategory.equals("Transfer", ignoreCase = true)
                        if (finalAmount > 0 && !isSaving) {
                            isSaving = true
                            haptic.confirm()
                            if (hasItemizedFriendsAssigned && onSaveSplit != null) {
                                val splitParticipants = itemizedSplitShares.map { (person, paise) ->
                                    val isMe = person.equals("You", ignoreCase = true)
                                    SplitParticipant(
                                        id = if (isMe) "0" else person.hashCode().toString(),
                                        name = if (isMe) "You" else person,
                                        isMe = isMe,
                                        exactAmountStr = String.format(Locale.US, "%.2f", paise / 100.0)
                                    )
                                }
                                onSaveSplit(splitParticipants)
                            }
                            onSave(
                                finalAmount, finalPayee, finalCategory, transactionType,
                                transactionDateMillis, itemsSummary.ifBlank { null }, receiptImageUri,
                                selectedBankName, selectedAccountNumber, finalExclude
                            )
                            onDismiss()
                        }
                    }
                )
            }
        }
    }
}

private const val MAX_EXPRESSION_LENGTH = 64
private const val MAX_NESTING_DEPTH = 16
private const val MAX_AMOUNT = 1_000_000_000_000L

fun evaluateBasicMath(input: String): Long? {
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
