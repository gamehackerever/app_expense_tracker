package com.expensetracker.offline.util

import com.expensetracker.offline.data.local.dao.TransactionDao
import com.expensetracker.offline.data.local.entity.TransactionType

/**
 * Pairs a credit-card "payment received" (CREDIT on a CREDIT_CARD account) with the bank-side
 * DEBIT that funded it, so the bank leg stops counting as spend. Pairing is by amount + time +
 * "looks like a card-bill payee" (CRED, BillDesk, BBPS, "credit card"), so a CRED payment with NO
 * matching card credit (e.g. rent paid through CRED) is left alone as normal spending.
 */
object CardPaymentReconciler {
    /** Card payments can post to the card a day or more after the bank debit. */
    const val WINDOW_MS = 3L * 24 * 3600_000L

    private val CARD_BILL_HINT = Regex("""(?i)\b(?:cred(?:\.?club)?|billdesk|bbps|credit\s*card|cc\s*bill)\b""")

    fun looksLikeCardBill(payee: String?, rawContent: String?): Boolean =
        CARD_BILL_HINT.containsMatchIn("${payee.orEmpty()} ${rawContent.orEmpty()}")

    /** New card payment credit arrived: exclude the bank debit that paid it. @return true if one was found. */
    suspend fun markBankLeg(dao: TransactionDao, amount: Long, timestamp: Long): Boolean {
        val leg = dao.findPaymentCounterpartCandidates(
            amount, TransactionType.DEBIT, "BANK", timestamp - WINDOW_MS, timestamp + WINDOW_MS, timestamp
        ).firstOrNull { looksLikeCardBill(it.payee, it.rawContent) } ?: return false
        if (!leg.excludeFromSpend || !leg.category.equals("Transfer", ignoreCase = true)) {
            dao.updateTransaction(
                leg.copy(
                    excludeFromSpend = true,
                    category = "Transfer",
                    payee = if (CategoryHelper.isGeneric(leg.payee)) "Credit Card Bill" else leg.payee,
                    note = leg.note ?: "Credit card bill payment"
                )
            )
        }
        return true
    }

    /** New bank debit that looks like a card-bill payment: is there a card credit that matches it? */
    suspend fun hasCardCredit(dao: TransactionDao, amount: Long, timestamp: Long): Boolean =
        dao.findPaymentCounterpartCandidates(
            amount, TransactionType.CREDIT, "CREDIT_CARD", timestamp - WINDOW_MS, timestamp + WINDOW_MS, timestamp
        ).isNotEmpty()

    /** Re-pairs everything (used after history scans and on startup heal). @return legs newly excluded. */
    suspend fun reconcileAll(dao: TransactionDao, sinceMs: Long = 0L): Int {
        var fixed = 0
        for (credit in dao.getCardPaymentCredits(sinceMs)) {
            val before = dao.findPaymentCounterpartCandidates(
                credit.amount, TransactionType.DEBIT, "BANK",
                credit.timestamp - WINDOW_MS, credit.timestamp + WINDOW_MS, credit.timestamp
            ).any { !it.excludeFromSpend && looksLikeCardBill(it.payee, it.rawContent) }
            if (before && markBankLeg(dao, credit.amount, credit.timestamp)) fixed++
        }
        return fixed
    }
}

private object CategoryHelper {
    fun isGeneric(payee: String): Boolean =
        payee.isBlank() || payee.equals("UPI Payment", true) || payee.equals("Bank Transfer", true)
}
