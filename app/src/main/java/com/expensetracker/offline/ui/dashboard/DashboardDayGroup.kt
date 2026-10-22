package com.expensetracker.offline.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.unit.dp
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.components.CurrencyFormat
import com.expensetracker.offline.ui.components.DashboardTransactionCard
import com.expensetracker.offline.ui.components.TransactionGroup
import com.expensetracker.offline.ui.components.TransactionRowDivider
import com.expensetracker.offline.util.confirm

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.dashboardDayGroup(
    label: String,
    rows: List<TransactionWithDebts>,
    daySpentPaise: Long,
    pendingDeleteIds: Map<Long, Long>,
    splitLinkInfo: SplitLinkInfo,
    billLabelMap: Map<Long, String>,
    selectedTxnIds: Set<Long>,
    isSelectionMode: Boolean,
    linkedAccountsSize: Int,
    haptic: HapticFeedback,
    onUndoTransactionDelete: (Long) -> Unit,
    onSelectionChange: (Long) -> Unit,
    onEditTransaction: (Long) -> Unit,
    onEnableSelectionMode: (Long) -> Unit,
    onNavigateToOriginalBill: (TransactionEntity) -> Unit
) {
    // Render the day header and its rounded transaction card together inside one cohesive item.
    // This eliminates the Compose LazyColumn stickyHeader + spacedBy bug where the sticky header
    // sliced the top transaction row in half while scrolling and added a 24dp gap above the card.
    item(key = "day_$label", contentType = "day_group") {
        Column(modifier = Modifier.fillMaxWidth().animateItem()) {
            DayHeader(label, if (daySpentPaise > 0) "Spent ${CurrencyFormat.smart(daySpentPaise)}" else null)
            Spacer(modifier = Modifier.height(6.dp))
            TransactionGroup {
                rows.forEachIndexed { index, bill ->
                    key(bill.transaction.id) {
                        if (index > 0) TransactionRowDivider()

                        val txn = bill.transaction
                        val deleteStartTime = pendingDeleteIds[txn.id]

                        if (deleteStartTime != null) {
                            TransactionUndoBar(
                                label = "\"${txn.payee}\" deleted",
                                startTimeMs = deleteStartTime,
                                modifier = Modifier,
                                onUndo = { onUndoTransactionDelete(txn.id) }
                            )
                        } else {
                            val (isSettled, settledBy, outstandingAmount) = remember(bill, splitLinkInfo) {
                                when (txn.type) {
                                    TransactionType.DEBIT -> {
                                        val owed = outstandingOwed(bill, splitLinkInfo)
                                        val settled = bill.debts.isNotEmpty() && owed <= 0L
                                        Triple(settled, if (settled) settledByLabel(bill) else null, owed)
                                    }
                                    TransactionType.CREDIT -> Triple(isRepaymentCredit(txn), null, null)
                                }
                            }

                            val billLabel = billLabelMap[txn.id]
                            val isSelected = selectedTxnIds.contains(txn.id)

                            DashboardTransactionCard(
                                grouped = true,
                                transaction = txn,
                                debts = bill.debts,
                                isSettled = isSettled,
                                settledBy = settledBy,
                                outstandingAmount = outstandingAmount,
                                selectionMode = isSelectionMode,
                                showBankBadge = linkedAccountsSize > 1,
                                isSelected = isSelected,
                                billLabel = billLabel,
                                onCardClick = {
                                    if (isSelectionMode) {
                                        haptic.tap()
                                        onSelectionChange(txn.id)
                                    } else {
                                        haptic.tap()
                                        onEditTransaction(txn.id)
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        haptic.confirm()
                                        onEnableSelectionMode(txn.id)
                                    }
                                },
                                onNavigateToOriginalBill = {
                                    haptic.tap()
                                    onNavigateToOriginalBill(txn)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
