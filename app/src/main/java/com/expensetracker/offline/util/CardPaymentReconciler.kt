package com.expensetracker.offline.util

import com.expensetracker.offline.data.local.dao.TransactionDao
import com.expensetracker.offline.data.local.entity.TransactionEntity
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

    /** Below this, an exact-amount match alone is too weak to call two entries one bill payment (₹500). */
    private const val MIN_AMOUNT_ONLY_PAISE = 50_000L

    private val CARD_BILL_HINT = Regex("""(?i)\b(?:cred(?:\.?club)?|billdesk|bbps|credit\s*card|cc\s*bill|card\s*(?:bill|payment))\b""")

    /** Supplies the user's own card names (see SettingsRepository.cardPayeeHints). Set once in Application. */
    @Volatile var hintProvider: (() -> List<String>)? = null
    @Volatile private var cachedHints: List<String> = emptyList()
    @Volatile private var cachedAt = 0L

    private fun userHints(): List<String> {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 15_000L) {
            cachedHints = runCatching { hintProvider?.invoke() }.getOrNull().orEmpty()
            cachedAt = now
        }
        return cachedHints
    }

    /**
     * A payee that reads like a card bill: CRED / BillDesk / BBPS / "credit card" anywhere in the text,
     * or one of the user's own card names in the PAYEE (never the raw SMS, which names the bank on every message).
     */
    fun looksLikeCardBill(payee: String?, rawContent: String?): Boolean {
        if (CARD_BILL_HINT.containsMatchIn("${payee.orEmpty()} ${rawContent.orEmpty()}")) return true
        val p = payee.orEmpty().lowercase()
        return p.isNotBlank() && userHints().any { p.contains(it) }
    }

    /**
     * The bank debit that funded a card payment: a card-bill-looking one first, otherwise the single
     * un-excluded debit with exactly the same amount (payee names like "Repay" give no hint).
     */
    private suspend fun pickBankLeg(dao: TransactionDao, amount: Long, timestamp: Long): TransactionEntity? {
        val candidates = dao.findPaymentCounterpartCandidates(
            amount, TransactionType.DEBIT, "BANK", timestamp - WINDOW_MS, timestamp + WINDOW_MS, timestamp
        )
        candidates.firstOrNull { looksLikeCardBill(it.payee, it.rawContent) }?.let { return it }
        if (amount < MIN_AMOUNT_ONLY_PAISE) return null
        return candidates.filter { !it.excludeFromSpend }.singleOrNull()
    }

    /** New card payment credit arrived: exclude the bank debit that paid it. @return true if one was found. */
    suspend fun markBankLeg(dao: TransactionDao, amount: Long, timestamp: Long): Boolean {
        val leg = pickBankLeg(dao, amount, timestamp) ?: return false
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

    /** Same as above for a debit with no card-bill hint: only a card "payment received" credit counts, and only for real amounts. */
    suspend fun hasCardPaymentCredit(dao: TransactionDao, amount: Long, timestamp: Long): Boolean =
        amount >= MIN_AMOUNT_ONLY_PAISE && dao.findCardPaymentCreditsByAmount(
            amount, timestamp - WINDOW_MS, timestamp + WINDOW_MS, timestamp
        ).isNotEmpty()

    /** Re-pairs everything (used after history scans and on startup heal). @return legs newly excluded. */
    suspend fun reconcileAll(dao: TransactionDao, sinceMs: Long = 0L): Int {
        var fixed = 0
        for (credit in dao.getCardPaymentCredits(sinceMs)) {
            val leg = pickBankLeg(dao, credit.amount, credit.timestamp)
            if (leg != null && !leg.excludeFromSpend && markBankLeg(dao, credit.amount, credit.timestamp)) fixed++
        }
        return fixed
    }
}

private object CategoryHelper {
    fun isGeneric(payee: String): Boolean =
        payee.isBlank() || payee.equals("UPI Payment", true) || payee.equals("Bank Transfer", true)
}
