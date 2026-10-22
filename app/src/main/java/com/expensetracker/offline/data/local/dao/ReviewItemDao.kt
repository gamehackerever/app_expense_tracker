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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewItem(item: ReviewItemEntity): Long

    @Update
    suspend fun updateReviewItem(item: ReviewItemEntity)

    @Query("SELECT * FROM review_items WHERE id = :id LIMIT 1")
    suspend fun getReviewItemById(id: Long): ReviewItemEntity?

    @Query("SELECT * FROM review_items WHERE status = 'PENDING' ORDER BY timestamp DESC")
    fun getPendingReviewItemsFlow(): Flow<List<ReviewItemEntity>>

    @Query("""
        SELECT SUM(CASE 
            WHEN type = 'DEBIT' THEN -extractedPartialAmount 
            WHEN type = 'CREDIT' THEN extractedPartialAmount 
            ELSE 0 
        END) 
        FROM review_items 
        WHERE status = 'PENDING' AND extractedPartialAmount IS NOT NULL
    """)
    suspend fun getPendingDebitsSum(): Long?

    @Query("SELECT COUNT(*) FROM review_items WHERE status = 'PENDING'")
    fun getPendingReviewCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_items WHERE status = 'PENDING'")
    suspend fun getPendingReviewCountSync(): Int

    @Query("SELECT COUNT(*) FROM review_items WHERE status = 'PENDING' AND extractedPartialAmount IS NULL")
    fun getUnparsedReviewCountFlow(): Flow<Int>

    @Query("UPDATE review_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ReviewStatus)

    @Query("DELETE FROM review_items WHERE id IN (:ids)")
    suspend fun deleteReviewItemsByIds(ids: List<Long>)

    @Query("""
        SELECT COUNT(*) FROM review_items
        WHERE extractedPartialAmount = :amount
          AND timestamp BETWEEN :startTime AND :endTime
          AND (:type IS NULL OR type = :type)
          AND status = 'PENDING'
          AND (referenceId IS NULL OR :referenceId IS NULL OR referenceId = :referenceId)
          AND (bankName IS NULL OR :bankName IS NULL OR bankName = :bankName)
          AND (accountNumber IS NULL OR :accountNumber IS NULL OR accountNumber = :accountNumber)
    """)
    suspend fun countMatchingReviewItems(
        amount: Long, type: TransactionType?, startTime: Long, endTime: Long,
        referenceId: String?, bankName: String?, accountNumber: String?
    ): Int

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

    @Query("SELECT * FROM review_items WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingReviewItemsSync(): List<ReviewItemEntity>

    @Query("""
        UPDATE review_items
        SET bankName = TRIM(SUBSTR(bankName, 1, LENGTH(bankName) - 3))
        WHERE bankName LIKE '% CC'
          AND (
              bankName LIKE 'South Indian Bank%'
              OR LOWER(rawContent) LIKE '%a/c%'
              OR LOWER(rawContent) LIKE '%acct%'
              OR LOWER(rawContent) LIKE '%upi%'
              OR LOWER(rawContent) LIKE '%debit%'
              OR LOWER(rawContent) LIKE '%avl bal%'
              OR LOWER(rawContent) LIKE '%avail bal%'
              OR LOWER(rawContent) LIKE '%balance%'
          )
    """)
    suspend fun healFalseCreditCardReviewItems(): Int
}