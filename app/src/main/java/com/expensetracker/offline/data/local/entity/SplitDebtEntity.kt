package com.expensetracker.offline.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DebtStatus { OPEN, SETTLED, FORGIVEN }

@Entity(
    tableName = "split_debts",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            // Do not cascade delete. If the bill goes, the debt goes,
            // but we need to warn users if there are repayments first.
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["transactionId"]),
        // Prevent the exact same person from being added to the same bill twice
        Index(value = ["transactionId", "debtorName"], unique = true)
    ]
)
data class SplitDebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val debtorName: String,

    // FIXED: Migrated from Double to Long to match MIGRATION_13_14 and prevent Room crash
    val amountOwed: Long,
    val originalAmount: Long = amountOwed,

    // Timestamp for tracking
    val createdAt: Long = System.currentTimeMillis(),
    val settledAt: Long? = null,

    // Explicit status instead of just checking settledAt
    val status: DebtStatus = DebtStatus.OPEN
)