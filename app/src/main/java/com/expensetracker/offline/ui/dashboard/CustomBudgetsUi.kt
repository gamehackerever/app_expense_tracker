package com.expensetracker.offline.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.repository.CustomBudget
import com.expensetracker.offline.ui.components.CurrencyFormat
import com.expensetracker.offline.ui.components.EmptyState
import com.expensetracker.offline.ui.components.ResistantModalBottomSheet
import com.expensetracker.offline.ui.components.SheetChip
import com.expensetracker.offline.ui.components.SheetHeader
import com.expensetracker.offline.ui.components.SheetPrimaryButton
import com.expensetracker.offline.ui.components.SheetTextField
import com.expensetracker.offline.ui.components.appSwitchColors
import com.expensetracker.offline.ui.components.quietClickable
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.util.tap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────────── Maths ─────────────────────────────

/** Net spent in a custom budget: your share of tagged spends, minus tagged credits (refunds, reimbursements). */
internal fun budgetSpentPaise(budgetId: String, transactions: List<TransactionWithDebts>): Long {
    var total = 0L
    for (item in transactions) {
        val t = item.transaction
        if (t.budgetId != budgetId) continue
        total += if (t.type == TransactionType.DEBIT) mySpendShare(item) else -t.amount
    }
    return total.coerceAtLeast(0L)
}

private fun rupees0(paise: Long): String = CurrencyFormat.withSymbol(paise, 0)

@Composable
private fun budgetColor(spent: Long, target: Long) = when {
    target > 0L && spent > target -> AppColors.negative
    target > 0L && spent >= target * 85 / 100 -> AppColors.warning
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun BudgetProgressBar(spent: Long, target: Long) {
    val progress by animateFloatAsState(
        targetValue = if (target > 0L) (spent.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(450),
        label = "budgetProgress"
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fill = budgetColor(spent, target)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(6.dp)
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(fill)
        )
    }
}

// ───────────────────────────── Picker (Add / Edit sheets) ─────────────────────────────

/** "Budget" chips shown in the Add and Edit transaction sheets. Renders nothing until a budget exists. */
@Composable
fun BudgetPickerRow(
    budgets: List<CustomBudget>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (budgets.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Budget", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetChip(text = "Monthly", isSelected = selectedId == null, onClick = { haptic.tap(); onSelect(null) })
            budgets.forEach { b ->
                SheetChip(text = b.name, isSelected = selectedId == b.id, onClick = { haptic.tap(); onSelect(b.id) })
            }
        }
    }
}

// ───────────────────────────── Dashboard strip ─────────────────────────────

/** Compact card with the user's custom budgets. Only shown once at least one exists. */
@Composable
internal fun CustomBudgetsStrip(
    budgets: List<CustomBudget>,
    transactions: List<TransactionWithDebts>,
    onOpen: () -> Unit
) {
    if (budgets.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.large))
            .quietClickable(pressedAlpha = 0.9f) { haptic.tap(); onOpen() },
        shape = RoundedCornerShape(AppRadius.large),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Custom budgets", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
                Text("Manage", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            }
            budgets.take(3).forEach { b ->
                val spent = remember(b.id, transactions) { budgetSpentPaise(b.id, transactions) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(b.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text("${rupees0(spent)} of ${rupees0(b.targetPaise)}", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    }
                    BudgetProgressBar(spent, b.targetPaise)
                }
            }
            if (budgets.size > 3) {
                Text("+${budgets.size - 3} more", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────────── Sheet ─────────────────────────────

private sealed interface BudgetScreen {
    object Overview : BudgetScreen
    data class Detail(val id: String) : BudgetScreen
    data class Form(val id: String?) : BudgetScreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomBudgetsSheet(
    budgets: List<CustomBudget>,
    transactions: List<TransactionWithDebts>,
    startInForm: Boolean,
    onSave: (id: String?, name: String, targetPaise: Long, excludeFromMonthly: Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRemoveTransaction: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var screen by remember { mutableStateOf<BudgetScreen>(if (startInForm) BudgetScreen.Form(null) else BudgetScreen.Overview) }

    ResistantModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = scheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp, top = 8.dp)
                .animateContentSize(tween(200))
        ) {
            when (val current = screen) {
                is BudgetScreen.Overview -> BudgetListContent(
                    budgets = budgets,
                    transactions = transactions,
                    onOpen = { haptic.tap(); screen = BudgetScreen.Detail(it) },
                    onNew = { haptic.tap(); screen = BudgetScreen.Form(null) }
                )
                is BudgetScreen.Detail -> {
                    val budget = budgets.firstOrNull { it.id == current.id }
                    if (budget == null) BudgetListContent(
                        budgets = budgets,
                        transactions = transactions,
                        onOpen = { haptic.tap(); screen = BudgetScreen.Detail(it) },
                        onNew = { haptic.tap(); screen = BudgetScreen.Form(null) }
                    ) else BudgetDetailContent(
                        budget = budget,
                        transactions = transactions,
                        onBack = { haptic.tap(); screen = BudgetScreen.Overview },
                        onEdit = { haptic.tap(); screen = BudgetScreen.Form(budget.id) },
                        onDelete = { onDelete(budget.id); screen = BudgetScreen.Overview },
                        onRemoveTransaction = onRemoveTransaction
                    )
                }
                is BudgetScreen.Form -> {
                    val editing = budgets.firstOrNull { it.id == current.id }
                    BudgetFormContent(
                        existing = editing,
                        onCancel = { haptic.tap(); screen = if (editing != null) BudgetScreen.Detail(editing.id) else BudgetScreen.Overview },
                        onSave = { name, target, exclude ->
                            onSave(editing?.id, name, target, exclude)
                            screen = BudgetScreen.Overview
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetListContent(
    budgets: List<CustomBudget>,
    transactions: List<TransactionWithDebts>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    SheetHeader(
        title = "Custom budgets",
        subtitle = "Set money aside for something specific, apart from your monthly limit."
    )
    Spacer(Modifier.height(16.dp))
    if (budgets.isEmpty()) {
        EmptyState(
            title = "No custom budgets yet",
            subtitle = "Planning a new phone, a laptop or a trip? Create a budget and add the spending to it.",
            emoji = "🎯"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(budgets, key = { it.id }) { b ->
                val spent = remember(b.id, transactions) { budgetSpentPaise(b.id, transactions) }
                val left = b.targetPaise - spent
                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = scheme.surface,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.medium))
                        .quietClickable(pressedAlpha = 0.9f) { onOpen(b.id) }
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Text(rupees0(spent), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        }
                        BudgetProgressBar(spent, b.targetPaise)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("of ${rupees0(b.targetPaise)}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Text(
                                if (left >= 0L) "${rupees0(left)} left" else "${rupees0(-left)} over",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (left >= 0L) scheme.onSurfaceVariant else AppColors.negative
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    SheetPrimaryButton(text = "New budget", enabled = true, onClick = onNew)
}

@Composable
private fun BudgetDetailContent(
    budget: CustomBudget,
    transactions: List<TransactionWithDebts>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemoveTransaction: (Long) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var confirmDelete by rememberSaveable(budget.id) { mutableStateOf(false) }
    val tagged = remember(budget.id, transactions) {
        transactions.filter { it.transaction.budgetId == budget.id }.sortedByDescending { it.transaction.timestamp }
    }
    val spent = remember(budget.id, transactions) { budgetSpentPaise(budget.id, transactions) }
    val left = budget.targetPaise - spent
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    Text(
        "‹ All budgets",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable { onBack() }.padding(vertical = 6.dp, horizontal = 4.dp)
    )
    Spacer(Modifier.height(4.dp))
    SheetHeader(title = budget.name, subtitle = if (budget.excludeFromMonthly) "Kept out of your monthly spend" else "Also counts in your monthly spend")
    Spacer(Modifier.height(16.dp))
    Text(rupees0(spent), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
    Text(
        if (left >= 0L) "${rupees0(left)} left of ${rupees0(budget.targetPaise)}" else "${rupees0(-left)} over ${rupees0(budget.targetPaise)}",
        fontSize = 13.sp,
        color = if (left >= 0L) scheme.onSurfaceVariant else AppColors.negative
    )
    Spacer(Modifier.height(12.dp))
    BudgetProgressBar(spent, budget.targetPaise)
    Spacer(Modifier.height(16.dp))

    if (tagged.isEmpty()) {
        Text(
            "Nothing in this budget yet. Add an expense and choose this budget, or edit any transaction and pick it under Budget.",
            fontSize = 13.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            items(tagged, key = { it.transaction.id }) { item ->
                val t = item.transaction
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.payee, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                        Text(dateFormat.format(Date(t.timestamp)), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                    Text(
                        (if (t.type == TransactionType.CREDIT) "+" else "") + rupees0(if (t.type == TransactionType.DEBIT) mySpendShare(item) else t.amount),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (t.type == TransactionType.CREDIT) AppColors.positive else scheme.onSurface
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Remove",
                        fontSize = 12.sp, color = scheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable { haptic.tap(); onRemoveTransaction(t.id) }.padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(AppRadius.pill),
            color = if (confirmDelete) AppColors.negative.copy(alpha = 0.14f) else scheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable {
                haptic.tap()
                if (confirmDelete) onDelete() else confirmDelete = true
            }
        ) {
            Text(
                if (confirmDelete) "Tap again to delete" else "Delete",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (confirmDelete) AppColors.negative else scheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(AppRadius.pill),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(AppRadius.pill)).quietClickable { onEdit() }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 12.dp)) {
                Text("Edit", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.surface)
            }
        }
    }
    if (confirmDelete) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Deleting only removes the budget. Your transactions stay.",
            fontSize = 12.sp, color = scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BudgetFormContent(
    existing: CustomBudget?,
    onCancel: () -> Unit,
    onSave: (name: String, targetPaise: Long, excludeFromMonthly: Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var amountText by rememberSaveable {
        mutableStateOf(existing?.let { (it.targetPaise / 100L).toString() }.orEmpty())
    }
    var exclude by rememberSaveable { mutableStateOf(existing?.excludeFromMonthly ?: true) }
    val targetPaise = (amountText.replace(",", "").toDoubleOrNull() ?: 0.0).let { kotlin.math.round(it * 100.0).toLong() }
    val valid = name.trim().isNotEmpty() && targetPaise > 0L

    SheetHeader(title = if (existing == null) "New budget" else "Edit budget")
    Spacer(Modifier.height(16.dp))
    SheetTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        label = "Name",
        placeholder = "e.g. New laptop, Goa trip",
        singleLine = true
    )
    Spacer(Modifier.height(12.dp))
    SheetTextField(
        value = amountText,
        onValueChange = { v -> amountText = v.filter { it.isDigit() || it == '.' }.take(12) },
        label = "Budget (₹)",
        placeholder = "e.g. 80000",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
    Spacer(Modifier.height(14.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Keep out of monthly spend", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(
                "Recommended for one-off purchases, so they don't wreck this month's budget.",
                fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = exclude, onCheckedChange = { haptic.tap(); exclude = it }, colors = appSwitchColors())
    }
    Spacer(Modifier.height(20.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(AppRadius.pill),
            color = scheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier
                .height(56.dp)
                .clip(RoundedCornerShape(AppRadius.pill))
                .quietClickable { onCancel() }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Cancel", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            SheetPrimaryButton(text = if (existing == null) "Create budget" else "Save", enabled = valid, onClick = {
                haptic.tap()
                onSave(name, targetPaise, exclude)
            })
        }
    }
}
