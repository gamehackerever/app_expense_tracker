package com.expensetracker.offline.util

import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource

object TransactionModeHelper {

    /**
     * Determines payment mode (UPI, Card, ATM, NEFT, IMPS, NetBanking, Cash)
     * from rawContent, note, payee, and source without needing any network.
     */
    fun detectMode(transaction: TransactionEntity): String {
        val raw = transaction.rawContent.lowercase()
        val note = transaction.note.orEmpty().lowercase()
        val payee = transaction.payee.lowercase()

        return when {
            // UPI patterns: UPI/DR, UPI/CR, VPA, @bank, GPay, PhonePe, Paytm UPI
            raw.contains("upi") || note.contains("upi") || payee.contains("upi") ||
            raw.contains("@") || note.contains("@") ||
            raw.contains("gpay") || raw.contains("google pay") ||
            raw.contains("phonepe") || raw.contains("paytm") -> "UPI"

            // Card / POS
            raw.contains("pos ") || raw.contains("pos/") || raw.contains("ecom") ||
            raw.contains("card") || raw.contains("debit card") || raw.contains("credit card") ||
            raw.contains("visa") || raw.contains("mastercard") || raw.contains("rupay") -> "Card"

            // ATM Cash Withdrawal
            raw.contains("atm ") || raw.contains("atm/") || raw.contains("atm wdl") ||
            raw.contains("atm cash") || raw.contains("cash wdl") || payee.contains("atm") -> "ATM"

            // Wire / NetBanking
            raw.contains("neft") || note.contains("neft") -> "NEFT"
            raw.contains("imps") || note.contains("imps") -> "IMPS"
            raw.contains("rtgs") || note.contains("rtgs") -> "RTGS"
            raw.contains("netbanking") || raw.contains("net banking") || raw.contains("cheque") ||
            raw.contains("chq") || raw.contains("bil/") || raw.contains("bil/onl") ||
            raw.contains("bill payment") -> "NetBanking"

            // Manual cash entry
            transaction.source == TransactionSource.MANUAL && !ImportedSource.isImported(transaction.rawContent) -> "Cash"

            else -> "Bank Transfer"
        }
    }
}
