package com.expensetracker.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.ReviewStatus
import com.expensetracker.offline.data.local.entity.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewItemDao {

    // FIXED: Changed to IGNORE to prevent identical review items from duplicating (PO-A)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewItem(item: ReviewItemEntity): Long

    @Update
    suspend fun updateReviewItem(item: ReviewItemEntity)

    @Query("SELECT * FROM review_items WHERE status = 'PENDING' ORDER BY timestamp DESC")
    fun getPendingReviewItemsFlow(): Flow<List<ReviewItemEntity>>

    // FIXED: Now uses the `type` column to sum debits and credits correctly (PO-B)
    @Query("""
        SELECT SUM(CASE 
            WHEN type = 'DEBIT' THEN -extractedPartialAmount 
            WHEN type = 'CREDIT' THEN extractedPartialAmount 
            ELSE 0 
        END) 
        FROM review_items 
        WHERE status = 'PENDING' AND extractedPartialAmount IS NOT NULL
    """)
    suspend fun getPendingDebitsSum(): Double?

    @Query("SELECT COUNT(*) FROM review_items WHERE status = 'PENDING'")
    fun getPendingReviewCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_items WHERE status = 'PENDING' AND extractedPartialAmount IS NULL")
    fun getUnparsedReviewCountFlow(): Flow<Int>

    @Query("UPDATE review_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ReviewStatus)

    @Query("DELETE FROM review_items WHERE id IN (:ids)")
    suspend fun deleteReviewItemsByIds(ids: List<Long>)

    // FIXED: Deduplicate on amount and a 10-minute window rather than exact timestamp/rawText (PO-A)
    @Query("""
        SELECT COUNT(*) FROM review_items 
        WHERE extractedPartialAmount = :amount 
          AND timestamp BETWEEN :startTime AND :endTime 
          AND (:type IS NULL OR type = :type)
          AND status = 'PENDING'
    """)
    suspend fun countMatchingReviewItems(amount: Long, type: TransactionType?, startTime: Long, endTime: Long): Int

    // Pending review items for ONE account between two timestamps (exclusive start, inclusive end),
    // signed: debits negative, credits positive. Used by the ghost-transaction check so it only
    // counts items the stored baseline balance has not already absorbed.
    @Query("""
        SELECT SUM(CASE WHEN type = 'DEBIT' THEN -extractedPartialAmount
                        WHEN type = 'CREDIT' THEN extractedPartialAmount ELSE 0 END)
        FROM review_items
        WHERE status = 'PENDING' AND extractedPartialAmount IS NOT NULL
          AND timestamp > :fromTs AND timestamp <= :toTs
          AND (bankName = :bankName OR (:bankName IS NULL AND bankName IS NULL))
          AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL))
    """)
    suspend fun getPendingNetBetween(bankName: String?, accountNumber: String?, fromTs: Long, toTs: Long): Long?

    @Query("SELECT COUNT(*) FROM review_items WHERE rawContent = :raw AND timestamp = :ts")
    suspend fun countExactReviewItems(raw: String, ts: Long): Int
}