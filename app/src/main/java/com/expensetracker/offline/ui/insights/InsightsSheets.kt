@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.insights

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
    onTransactionClick: ((TransactionEntity) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val categoryTxns = remember(allTransactions, categoryName) {
        allTransactions.filter { it.transaction.category.equals(categoryName, ignoreCase = true) && isSpendDebit(it.transaction) }
            .sortedByDescending { it.transaction.timestamp }
    }

    val totalSpent = remember(categoryTxns) { categoryTxns.sumOf { myShareOf(it) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasBudget = budgetPaise != null && budgetPaise > 0L

    ModalBottomSheet(
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

            LazyColumn {
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

    ModalBottomSheet(
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

            LazyColumn {
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
