package com.expensetracker.offline.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ReviewStatus { PENDING, RESOLVED, DISCARDED }

@Entity(tableName = "review_items")
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawContent: String,
    val sender: String,
    val timestamp: Long,
    val source: TransactionSource,
    val extractedPartialAmount: Long? = null,
    val extractedPartialPayee: String? = null,

    // NEW FIELDS: Prevents re-parsing on the review screen (PO-A)
    val type: TransactionType? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val balance: Long? = null,
    val referenceId: String? = null,

    val status: ReviewStatus = ReviewStatus.PENDING
)
