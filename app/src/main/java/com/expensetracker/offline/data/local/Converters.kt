package com.expensetracker.offline.data.local

import androidx.room.TypeConverter
import com.expensetracker.offline.data.local.entity.ReviewStatus
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType

class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = runCatching {
        TransactionType.valueOf(value)
    }.getOrDefault(TransactionType.DEBIT)

    @TypeConverter
    fun fromTransactionSource(value: TransactionSource): String = value.name

    @TypeConverter
    fun toTransactionSource(value: String): TransactionSource = runCatching {
        TransactionSource.valueOf(value)
    }.getOrDefault(TransactionSource.MANUAL)

    @TypeConverter
    fun fromReviewStatus(value: ReviewStatus): String = value.name

    @TypeConverter
    fun toReviewStatus(value: String): ReviewStatus = runCatching {
        ReviewStatus.valueOf(value)
    }.getOrDefault(ReviewStatus.PENDING)
}