@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.insights

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.ui.components.ResistantModalBottomSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import com.expensetracker.offline.ui.components.quietClickable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Person
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.dao.CategoryInsight
import com.expensetracker.offline.data.local.dao.PayeeInsight
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.components.DashboardTransactionCard
import com.expensetracker.offline.ui.components.TransactionGroup
import com.expensetracker.offline.ui.components.TransactionRowDivider
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.theme.hasCategoryIcon
import com.expensetracker.offline.util.tick
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsSheet(
    categoryName: String,
    allTransactions: List<TransactionWithDebts>,
    onDismiss: () -> Unit,
    budgetPaise: Long? = null,
    onManageBudgetClick: (() -> Unit)? = null,
    onTransactionClick: ((TransactionEntity) -> Unit)? = null,
    categoryOptions: List<String> = emptyList(),
    onCategorizePayee: ((payee: String, category: String) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val categoryTxns = remember(allTransactions, categoryName) {
        allTransactions.filter { it.transaction.category.equals(categoryName, ignoreCase = true) && isSpendDebit(it.transaction) }
            .sortedByDescending { it.transaction.timestamp }
    }

    val totalSpent = remember(categoryTxns) { categoryTxns.sumOf { myShareOf(it) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasBudget = budgetPaise != null && budgetPaise > 0L

    ResistantModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        categoryName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${categoryTxns.size} ${if (categoryTxns.size == 1) "transaction" else "transactions"} • ₹${String.format(Locale.getDefault(), "%,.2f", totalSpent / 100.0)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onManageBudgetClick != null && !categoryName.equals("Uncategorized", ignoreCase = true)) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable {
                                haptic.tap()
                                onManageBudgetClick()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                if (hasBudget) Icons.Default.Edit else Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (hasBudget) "Cap: ${formatRupees(budgetPaise!!)}" else "Set cap",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                if (categoryName.equals("Uncategorized", ignoreCase = true) && onCategorizePayee != null && categoryOptions.isNotEmpty()) {
                    item(key = "sort_by_payee") {
                        SortByPayeePanel(
                            transactions = categoryTxns,
                            categoryOptions = categoryOptions,
                            onCategorize = { payee, category ->
                                haptic.tap()
                                onCategorizePayee(payee, category)
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                item {
                    TransactionGroup {
                        categoryTxns.forEachIndexed { index, item ->
                            if (index > 0) TransactionRowDivider()
                            key(item.transaction.id) {
                                DashboardTransactionCard(
                                    transaction = item.transaction,
                                    debts = item.debts,
                                    isSettled = false,
                                    settledBy = null,
                                    showActions = false,
                                    grouped = true,
                                    showCategory = false,
                                    showDate = true,
                                    onCardClick = {
                                        if (onTransactionClick != null) {
                                            haptic.tap()
                                            onTransactionClick(item.transaction)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantTransactionsSheet(
    merchantName: String,
    allTransactions: List<TransactionWithDebts>,
    onDismiss: () -> Unit,
    onTransactionClick: ((TransactionEntity) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val merchantTxns = remember(allTransactions, merchantName) {
        allTransactions.filter { it.transaction.payee.equals(merchantName, ignoreCase = true) && isSpendDebit(it.transaction) }
            .sortedByDescending { it.transaction.timestamp }
    }

    val totalSpent = remember(merchantTxns) { merchantTxns.sumOf { myShareOf(it) } }
    val avgPerVisit = remember(merchantTxns, totalSpent) {
        if (merchantTxns.isNotEmpty()) totalSpent.toDouble() / merchantTxns.size else 0.0
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ResistantModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        merchantName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${merchantTxns.size} ${if (merchantTxns.size == 1) "visit" else "visits"} • ₹${String.format(Locale.getDefault(), "%,.2f", totalSpent / 100.0)} • avg ${formatRupees(avgPerVisit)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item {
                    TransactionGroup {
                        merchantTxns.forEachIndexed { index, item ->
                            if (index > 0) TransactionRowDivider()
                            key(item.transaction.id) {
                                DashboardTransactionCard(
                                    transaction = item.transaction,
                                    debts = item.debts,
                                    isSettled = false,
                                    settledBy = null,
                                    showActions = false,
                                    grouped = true,
                                    showCategory = true,
                                    showDate = true,
                                    onCardClick = {
                                        if (onTransactionClick != null) {
                                            haptic.tap()
                                            onTransactionClick(item.transaction)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * One row per payee that still has uncategorised spends, biggest first. Tapping a row opens its category choices
 * right underneath; picking one files all of that payee's payments at once and teaches the app, so the next payment
 * to them is categorised automatically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortByPayeePanel(
    transactions: List<TransactionWithDebts>,
    categoryOptions: List<String>,
    onCategorize: (payee: String, category: String) -> Unit
) {
    data class PayeeGroup(val payee: String, val count: Int, val total: Long, val isPerson: Boolean)

    val scheme = MaterialTheme.colorScheme
    val groups = remember(transactions) {
        transactions.groupBy { it.transaction.payee.trim() }
            .filterKeys { it.isNotEmpty() }
            .map { (payee, items) ->
                PayeeGroup(
                    payee = payee,
                    count = items.size,
                    total = items.sumOf { myShareOf(it) },
                    isPerson = com.expensetracker.offline.engine.categorizer.PayeeHeuristics.looksLikePerson(payee)
                )
            }
            .sortedByDescending { it.total }
            .take(8)
    }
    if (groups.isEmpty()) return
    var expanded by remember { mutableStateOf<String?>(null) }
    // If the open payee was just filed (and so left the list), close the panel state with it.
    if (expanded != null && groups.none { it.payee == expanded }) expanded = null

    Surface(
        shape = RoundedCornerShape(AppRadius.medium),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Sort by payee", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = scheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                "Tap a payee and pick a category. All their payments are filed, now and in future.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.3f))
                val isOpen = expanded == group.payee
                Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(220))) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.small))
                            .quietClickable(pressedAlpha = 0.85f, pressedScale = 0.995f) {
                                expanded = if (isOpen) null else group.payee
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (group.isPerson) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Person",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                group.payee,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = scheme.onSurface
                            )
                            Text(
                                "${group.count} ${if (group.count == 1) "payment" else "payments"} · ${formatRupees(group.total)}",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isOpen) "Close" else "Choose category",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isOpen) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categoryOptions.forEach { option ->
                                val tint = getCategoryColor(option)
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = tint.copy(alpha = 0.12f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .quietClickable { onCategorize(group.payee, option) }
                                ) {
                                    Text(
                                        option,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
