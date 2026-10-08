package com.expensetracker.offline.engine.parser

import com.expensetracker.offline.data.local.entity.TransactionType

data class ParsedTransaction(
    val isFinancial: Boolean,
    val amount: Long? = null,
    val payee: String? = null,
    val type: TransactionType? = null,
    val referenceId: String? = null,
    val balance: Long? = null,
    val note: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val rawText: String,

    // NEW FIELDS (PO-C)
    val currency: String? = "INR",
    val vpa: String? = null,
    val accountType: String? = null,
    val transactionDate: Long? = null,
    val isTransfer: Boolean = false,
    val excludeFromSpend: Boolean = false,
    val confidence: Float = 1.0f,
    val rejectReason: String? = null,
    val parserVersion: Int = 2
) {
    val isFullyParsed: Boolean
        get() = isFinancial && amount != null && amount > 0L && type != null && bankName != null
}
