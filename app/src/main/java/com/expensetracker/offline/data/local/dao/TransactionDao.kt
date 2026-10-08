package com.expensetracker.offline.data.local.dao

import androidx.room.*
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import kotlinx.coroutines.flow.Flow

data class PayeeInsight(val payee: String, val totalSpent: Double, val transactionCount: Int, val lastSpentTimestamp: Long)
data class CategoryInsight(val category: String, val totalSpent: Double, val transactionCount: Int)
data class AccountInfo(val bankName: String, val accountNumber: String?)
data class AccountBalanceInsight(val bankName: String, val accountNumber: String?, val latestBalance: Long?, val lastUpdated: Long)

@Dao
interface TransactionDao {

    @Query("SELECT DISTINCT bankName, accountNumber FROM transactions WHERE bankName IS NOT NULL")
    fun getLinkedAccountsFlow(): Flow<List<AccountInfo>>

    // FIXED: Filter by both bankName AND accountNumber to isolate spend correctly (PO-B)
    @Query("""
    SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN -amount ELSE amount END), 0.0) 
    FROM transactions 
    WHERE timestamp > :lastBalanceTime AND timestamp <= :currentTime 
    AND (bankName = :bankName OR (:bankName IS NULL AND bankName IS NULL))
    AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL))
""")
    suspend fun getIntermediateNetSpend(
        lastBalanceTime: Long,
        currentTime: Long,
        bankName: String?,
        accountNumber: String?
    ): Double

    @Query("""
    SELECT balance FROM transactions 
    WHERE bankName = :bankName 
      AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL)) 
      AND balance IS NOT NULL 
    ORDER BY timestamp DESC LIMIT 1
""")
    fun getLatestBalanceForAccountFlow(bankName: String, accountNumber: String?): Flow<Long?>
    @Query("SELECT * FROM transactions WHERE bankName = :bankName AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL)) ORDER BY timestamp DESC")
    fun getTransactionsByAccountFlow(bankName: String, accountNumber: String?): Flow<List<TransactionEntity>>

    @Query("UPDATE transactions SET bankName = :bankName, accountNumber = :accountNumber WHERE id = :transactionId AND bankName IS NULL")
    suspend fun updateBankDetails(transactionId: Long, bankName: String, accountNumber: String?)

    @Query("""
    SELECT bankName, accountNumber,
           (SELECT balance FROM transactions t2 
            WHERE t2.bankName = t.bankName AND (t2.accountNumber = t.accountNumber OR (t2.accountNumber IS NULL AND t.accountNumber IS NULL)) AND t2.balance IS NOT NULL 
            ORDER BY t2.timestamp DESC LIMIT 1) AS latestBalance,
           MAX(timestamp) AS lastUpdated
    FROM transactions t WHERE bankName IS NOT NULL GROUP BY bankName, accountNumber ORDER BY lastUpdated DESC
    """)
    fun getLatestBalancesForAllAccountsFlow(): Flow<List<AccountBalanceInsight>>

    // @Upsert instead of REPLACE: REPLACE deletes the existing row first, and split_debts has
    // ON DELETE CASCADE -> re-inserting an existing id would silently wipe that bill's debts.
    @Upsert
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteTransactionsByIds(ids: List<Long>)

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    // Fetches transactions WITH their attached debts for the Dashboard
    @Transaction
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactionsWithDebtsFlow(): Flow<List<TransactionWithDebts>>

    // ── Repayment links (CREDIT transaction -> specific person's debt) ──
    @Query("UPDATE transactions SET linkedDebtId = :debtId WHERE id = :creditId")
    suspend fun linkRepayment(creditId: Long, debtId: Long)

    @Query("UPDATE transactions SET linkedDebtId = NULL WHERE id = :creditId")
    suspend fun unlinkRepayment(creditId: Long)

    @Query("UPDATE transactions SET linkedDebtId = NULL WHERE linkedDebtId IN (:debtIds)")
    suspend fun unlinkRepaymentsForDebts(debtIds: List<Long>)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Query("SELECT balance FROM transactions WHERE balance IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    fun getLatestBalanceFlow(): Flow<Long?>

    @Query("""
        SELECT * FROM transactions 
        WHERE amount = :amount 
          AND type = :type 
          AND timestamp BETWEEN :startTime AND :endTime 
          AND (bankName = :bankName OR :bankName IS NULL)
          AND (accountNumber = :accountNumber OR :accountNumber IS NULL)
        ORDER BY ABS(timestamp - :targetTime) 
        LIMIT 1
    """)
    suspend fun findMatchingTransaction(
        amount: Double,
        type: TransactionType,
        startTime: Long,
        endTime: Long,
        targetTime: Long,
        bankName: String?,
        accountNumber: String?
    ): TransactionEntity?

    @Query("UPDATE transactions SET payee = :newPayee, category = :newCategory WHERE payee = :oldPayee")
    suspend fun updatePayeeAndCategoryBulk(oldPayee: String, newPayee: String, newCategory: String)

    // Monthly spend = your share only (bill minus what others owe)
    @Query("SELECT SUM(MAX(0, amount - COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = transactions.id), 0))) FROM transactions WHERE type = 'DEBIT' AND timestamp >= :startOfMonth AND timestamp < :endOfMonth")
    fun getMonthlyDebitSumFlow(startOfMonth: Long, endOfMonth: Long): Flow<Double?>

    // Sum of every unsettled debt minus its own repayments
    @Query("""
        SELECT SUM(
            MAX(0.0, sd.amountOwed - COALESCE((SELECT SUM(amount) FROM transactions t2 WHERE t2.linkedDebtId = sd.id), 0))
        ) 
        FROM split_debts sd WHERE sd.settledAt IS NULL
    """)
    fun getTotalMoneyOwedToYouFlow(): Flow<Double?>

    @Query("SELECT SUM(MAX(0, amount - COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = transactions.id), 0))) FROM transactions WHERE type = 'DEBIT' AND timestamp >= :startOfDay AND timestamp < :endOfDay")
    suspend fun getDailyDebitSum(startOfDay: Long, endOfDay: Long): Double?

    @Query("SELECT COUNT(*) FROM transactions WHERE type = 'DEBIT' AND category != 'Split Settlement' AND timestamp >= :startOfDay AND timestamp < :endOfDay")
    suspend fun getDailyDebitCount(startOfDay: Long, endOfDay: Long): Int

    @Query("UPDATE transactions SET category = :newCategory WHERE category = :oldCategory")
    suspend fun reassignCategory(oldCategory: String, newCategory: String)

    @Query("UPDATE transactions SET payee = :newPayee WHERE payee = :oldPayee")
    suspend fun renameAllPayees(oldPayee: String, newPayee: String)

    @Query("""
        SELECT payee, 
               SUM(MAX(0, amount - COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = transactions.id), 0))) AS totalSpent, 
               COUNT(*) AS transactionCount, MAX(timestamp) AS lastSpentTimestamp
        FROM transactions WHERE type = 'DEBIT' GROUP BY payee ORDER BY totalSpent DESC
    """)
    fun getPayeeInsightsFlow(): Flow<List<PayeeInsight>>

    data class DebitPoint(val id: Long, val payee: String, val amount: Double, val timestamp: Long, val category: String)

    @Query("SELECT id, payee, amount, timestamp, category FROM transactions WHERE type = 'DEBIT' AND timestamp >= :since")
    fun getDebitsSinceFlow(since: Long): Flow<List<DebitPoint>>

    @Query("""
        SELECT category, 
               SUM(MAX(0, amount - COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = transactions.id), 0))) AS totalSpent, 
               COUNT(*) AS transactionCount
        FROM transactions WHERE type = 'DEBIT' GROUP BY category ORDER BY totalSpent DESC
    """)
    fun getCategoryInsightsFlow(): Flow<List<CategoryInsight>>

    @Query("UPDATE transactions SET payee = :newPayee WHERE payee = :oldPayee")
    suspend fun renamePayeeForAll(oldPayee: String, newPayee: String): Int

    // Search also matches the names of people attached to the bill.
    // ESCAPE '\' so a user typing % or _ is searched literally (escape in the caller).
    @Transaction
    @Query("""
        SELECT t.* FROM transactions t
        WHERE (t.payee LIKE '%' || :search || '%' ESCAPE '\'
           OR t.category LIKE '%' || :search || '%' ESCAPE '\'
           OR COALESCE(t.itemsSummary, '') LIKE '%' || :search || '%' ESCAPE '\'
           OR COALESCE((SELECT GROUP_CONCAT(debtorName) FROM split_debts WHERE transactionId = t.id), '') LIKE '%' || :search || '%' ESCAPE '\')
        AND (
            :filterCategory = 'All' 
            OR (:filterCategory = 'Split Only' AND EXISTS (SELECT 1 FROM split_debts WHERE transactionId = t.id)) 
            OR t.category = :filterCategory
        )
        AND t.timestamp >= :startTime
        ORDER BY t.timestamp DESC
        LIMIT :limit
    """)

    fun searchAndFilterTransactionsFlow(search: String, filterCategory: String, startTime: Long, limit: Int): Flow<List<TransactionWithDebts>>
    // ONE SHARED DEFINITION OF SPEND (PO-H)
    @Query("""
        SELECT COALESCE(SUM(
            MAX(0.0, amount - COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = t.id), 0.0))
        ), 0.0)
        FROM transactions t
        WHERE type = 'DEBIT' 
          AND category != 'Split Settlement' 
          AND excludeFromSpend = 0
          AND timestamp >= :startMillis AND timestamp <= :endMillis
    """)
    suspend fun getMySpendBetween(startMillis: Long, endMillis: Long): Double

    @Query("""
        SELECT COUNT(*)
        FROM transactions t
        WHERE type = 'DEBIT' 
          AND category != 'Split Settlement'
          AND excludeFromSpend = 0
          AND timestamp >= :startMillis AND timestamp <= :endMillis
    """)
    suspend fun getMySpendCountBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM transactions WHERE category != 'Split Settlement' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<TransactionEntity>

}


