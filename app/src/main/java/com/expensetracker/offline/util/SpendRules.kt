package com.expensetracker.offline.util

import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType

/**
 * The single definition of which transactions count as real spending (and real income).
 *
 * Every total in the app - dashboard hero, day groups, categories, budgets, insights, accounts, necessities -
 * must use these helpers so the same transaction is never counted on one screen and ignored on another.
 * The SQL twins (TransactionDao.getMySpendBetween / getDebitsSinceFlow / ...) apply the same rule:
 * DEBIT, not flagged excludeFromSpend, category not "Transfer" and not "Split Settlement" (case-insensitive).
 */
fun TransactionEntity.isOwnAccountTransfer(): Boolean {
    val cat = category.trim()
    return cat.equals("Self Transfer", ignoreCase = true) ||
           cat.equals("Own Account Transfer", ignoreCase = true) ||
           (note?.startsWith("Self-transfer", ignoreCase = true) == true)
}

fun TransactionEntity.isTransferCategory(): Boolean = isOwnAccountTransfer()

fun TransactionEntity.isSplitSettlementCategory(): Boolean = category.trim().equals("Split Settlement", ignoreCase = true)

/** True for a debit that is genuine spending: not excluded, not an own-account transfer, not a split settlement. */
fun TransactionEntity.countsAsSpend(): Boolean =
    type == TransactionType.DEBIT &&
        !excludeFromSpend &&
        !isOwnAccountTransfer() &&
        !isSplitSettlementCategory()
