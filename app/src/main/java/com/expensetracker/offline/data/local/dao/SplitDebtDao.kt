package com.expensetracker.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.expensetracker.offline.data.local.entity.DebtStatus
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

data class TransactionWithDebts(
    @Embedded val transaction: TransactionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "transactionId"
    )
    val debts: List<SplitDebtEntity>
)

@Dao
interface SplitDebtDao {
    @Upsert
    suspend fun upsertDebts(debts: List<SplitDebtEntity>)

    @Query("DELETE FROM split_debts WHERE transactionId = :transactionId")
    suspend fun deleteDebtsForTransaction(transactionId: Long)

    @Query("DELETE FROM split_debts WHERE id IN (:ids)")
    suspend fun deleteDebtsByIds(ids: List<Long>)

    @Transaction
    @Query("""
        SELECT DISTINCT t.* FROM transactions t
        INNER JOIN split_debts sd ON t.id = sd.transactionId
        WHERE sd.status = 'OPEN'
        ORDER BY t.timestamp DESC
    """)
    fun getUnsettledTransactionsWithDebtsFlow(): Flow<List<TransactionWithDebts>>

    @Query("SELECT * FROM split_debts WHERE transactionId = :transactionId")
    suspend fun getDebtsForTransaction(transactionId: Long): List<SplitDebtEntity>

    @Query("SELECT * FROM split_debts WHERE id = :debtId LIMIT 1")
    suspend fun getDebtById(debtId: Long): SplitDebtEntity?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE linkedDebtId = :debtId")
    suspend fun getRepaidAmountForDebt(debtId: Long): Long

    // FIXED: Instead of ad-hoc updates, we just push the whole reconciled entity back
    @Upsert
    suspend fun updateDebt(debt: SplitDebtEntity)

    @Query("UPDATE split_debts SET status = 'SETTLED', settledAt = :timestamp WHERE id = :debtId")
    suspend fun markDebtSettled(debtId: Long, timestamp: Long)

    @Query("UPDATE split_debts SET status = 'OPEN', settledAt = NULL WHERE id = :debtId")
    suspend fun markDebtUnsettled(debtId: Long)

    // FIXED: Forgive now just changes the status; it does NOT destroy originalAmount (PO-D)
    @Query("UPDATE split_debts SET status = 'FORGIVEN', settledAt = :timestamp WHERE id = :debtId")
    suspend fun forgiveDebt(debtId: Long, timestamp: Long)
}