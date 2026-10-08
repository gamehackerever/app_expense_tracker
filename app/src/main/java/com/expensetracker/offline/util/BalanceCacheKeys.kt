package com.expensetracker.offline.util

object BalanceCacheKeys {
    fun key(bankName: String?, accountNumber: String?): String =
        "key_latest_bank_balance_${bankName ?: "unspecified"}_${accountNumber ?: "na"}"
}
