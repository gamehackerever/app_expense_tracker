package com.expensetracker.offline.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransactionType { DEBIT, CREDIT }
enum class TransactionSource { SMS, NOTIFICATION, MERGED, MANUAL }

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["amount", "timestamp"]),
        Index(value = ["referenceId"]),
        Index(value = ["linkedDebtId"]),
        Index(value = ["bankName", "accountNumber"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val payee: String,
    val timestamp: Long,
    val type: TransactionType,
    val source: TransactionSource,
    val referenceId: String?,
    val rawContent: String,
    val category: String = "Uncategorized",
    val note: String? = null,
    val balance: Long? = null,
    val itemsSummary: String? = null,
    val receiptImageUri: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val isNecessity: Boolean = false,
    val excludeFromSpend: Boolean = false,
    val linkedDebtId: Long? = null,
    val paidBy: String? = "You",
    val accountType: String = "BANK"
)
