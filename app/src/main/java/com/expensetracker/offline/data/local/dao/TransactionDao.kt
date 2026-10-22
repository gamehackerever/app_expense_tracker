package com.expensetracker.offline.data.local.dao

import androidx.room.*
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import kotlinx.coroutines.flow.Flow

data class PayeeInsight(val payee: String, val totalSpent: Long, val transactionCount: Int, val lastSpentTimestamp: Long)
data class CategoryInsight(val category: String, val totalSpent: Long, val transactionCount: Int)
data class AccountInfo(val bankName: String, val accountNumber: String?)
data class AccountBalanceInsight(val bankName: String, val accountNumber: String?, val latestBalance: Long?, val lastUpdated: Long)

@Dao
interface TransactionDao {

    @Query("SELECT DISTINCT bankName, accountNumber FROM transactions WHERE bankName IS NOT NULL")
    fun getLinkedAccountsFlow(): Flow<List<AccountInfo>>

    @Query("""
    SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN -amount ELSE amount END), 0) 
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
    ): Long

    @Query("""
    SELECT balance FROM transactions 
    WHERE bankName = :bankName 
      AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL)) 
      AND balance IS NOT NULL 
    ORDER BY timestamp DESC LIMIT 1
""")
    fun getLatestBalanceForAccountFlow(bankName: String, accountNumber: String?): Flow<Long?>

    @Query("""
    SELECT balance FROM transactions 
    WHERE bankName = :bankName 
      AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL)) 
      AND balance IS NOT NULL 
    ORDER BY timestamp DESC LIMIT 1
""")
    suspend fun getLatestBalanceForAccountSync(bankName: String, accountNumber: String?): Long?

    @Query("SELECT * FROM transactions WHERE bankName = :bankName AND (accountNumber = :accountNumber OR (:accountNumber IS NULL AND accountNumber IS NULL)) ORDER BY timestamp DESC")
    fun getTransactionsByAccountFlow(bankName: String, accountNumber: String?): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getTransactionsInRangeSync(startTime: Long, endTime: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET bankName = :bankName, accountNumber = :accountNumber WHERE id = :transactionId AND bankName IS NULL")
    suspend fun updateBankDetails(transactionId: Long, bankName: String, accountNumber: String?)

    @Query("UPDATE transactions SET paidBy = :paidBy WHERE id = :transactionId")
    suspend fun updatePaidBy(transactionId: Long, paidBy: String)

    @Query("""
    SELECT bankName, accountNumber,
           (SELECT balance FROM transactions t2 
            WHERE t2.bankName = t.bankName AND (t2.accountNumber = t.accountNumber OR (t2.accountNumber IS NULL AND t.accountNumber IS NULL)) AND t2.balance IS NOT NULL 
            ORDER BY t2.timestamp DESC LIMIT 1) AS latestBalance,
           MAX(timestamp) AS lastUpdated
    FROM transactions t WHERE bankName IS NOT NULL GROUP BY bankName, accountNumber ORDER BY lastUpdated DESC
    """)
    fun getLatestBalancesForAllAccountsFlow(): Flow<List<AccountBalanceInsight>>

    @Upsert
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Upsert
    suspend fun insertTransactions(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp BETWEEN :fromTs AND :toTs AND amount = :amount AND type = :type AND payee = :payee")
    suspend fun countSameTransaction(fromTs: Long, toTs: Long, amount: Long, type: TransactionType, payee: String): Int

    @Query("SELECT receiptImageUri FROM transactions WHERE id IN (:ids) AND receiptImageUri IS NOT NULL")
    suspend fun getReceiptUrisByIds(ids: List<Long>): List<String>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteTransactionsByIds(ids: List<Long>)

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    @Transaction
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 50000")
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
          AND (bankName = :bankName OR :bankName IS NULL OR bankName IS NULL)
          AND (accountNumber = :accountNumber OR :accountNumber IS NULL OR accountNumber IS NULL)
        ORDER BY ABS(timestamp - :targetTime) 
        LIMIT 1
    """)
    suspend fun findMatchingTransaction(
        amount: Long,
        type: TransactionType,
        startTime: Long,
        endTime: Long,
        targetTime: Long,
        bankName: String?,
        accountNumber: String?
    ): TransactionEntity?

    /**
     * Detects the opposite leg of an inter-account self-transfer (same amount, opposite DEBIT/CREDIT
     * type, within a narrow time window, on a DIFFERENT bank account).
     */
    @Query("""
        SELECT * FROM transactions
        WHERE amount = :amount
          AND type = :oppositeType
          AND timestamp BETWEEN :startTime AND :endTime
          AND bankName IS NOT NULL
          AND :currentBank IS NOT NULL
          AND (bankName != :currentBank OR COALESCE(accountNumber, '') != COALESCE(:currentAccount, ''))
        ORDER BY ABS(timestamp - :targetTime)
        LIMIT 1
    """)
    suspend fun findOppositeTransferLeg(
        amount: Long,
        oppositeType: TransactionType,
        startTime: Long,
        endTime: Long,
        targetTime: Long,
        currentBank: String?,
        currentAccount: String?
    ): TransactionEntity?

    @Query("UPDATE transactions SET payee = :newPayee, category = :newCategory WHERE payee = :oldPayee")
    suspend fun updatePayeeAndCategoryBulk(oldPayee: String, newPayee: String, newCategory: String)

    // Sum of every unsettled debt owed TO YOU (where you paid the bill) minus repayments
    @Query("""
        SELECT SUM(
            MAX(0, sd.amountOwed - COALESCE((SELECT SUM(amount) FROM transactions t2 WHERE t2.linkedDebtId = sd.id), 0))
        ) 
        FROM split_debts sd
        INNER JOIN transactions t ON t.id = sd.transactionId
        WHERE sd.status = 'OPEN'
          AND sd.settledAt IS NULL
          AND (t.paidBy IS NULL OR LOWER(TRIM(t.paidBy)) = 'you')
          AND LOWER(TRIM(sd.debtorName)) != 'you'
    """)
    fun getTotalMoneyOwedToYouFlow(): Flow<Long?>

    // Sum of every unsettled debt YOU OWE to someone else (where another person paid the bill)
    @Query("""
        SELECT SUM(
            MAX(0, sd.amountOwed - COALESCE((SELECT SUM(amount) FROM transactions t2 WHERE t2.linkedDebtId = sd.id), 0))
        ) 
        FROM split_debts sd
        INNER JOIN transactions t ON t.id = sd.transactionId
        WHERE sd.status = 'OPEN'
          AND sd.settledAt IS NULL
          AND t.paidBy IS NOT NULL
          AND LOWER(TRIM(t.paidBy)) != 'you'
          AND LOWER(TRIM(sd.debtorName)) = 'you'
    """)
    fun getTotalMoneyYouOweFlow(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM transactions WHERE type = 'DEBIT' AND category != 'Split Settlement' AND timestamp >= :startOfDay AND timestamp < :endOfDay")
    suspend fun getDailyDebitCount(startOfDay: Long, endOfDay: Long): Int

    @Query("UPDATE transactions SET category = :newCategory WHERE category = :oldCategory")
    suspend fun reassignCategory(oldCategory: String, newCategory: String)

    @Query("UPDATE transactions SET payee = :newPayee WHERE payee = :oldPayee")
    suspend fun renameAllPayees(oldPayee: String, newPayee: String)

    data class DebitPoint(val id: Long, val payee: String, val amount: Long, val timestamp: Long, val category: String)

    @Query("""
        SELECT
            t.id AS id,
            t.payee AS payee,
            CASE
                WHEN t.paidBy IS NOT NULL AND LOWER(TRIM(t.paidBy)) != 'you' AND TRIM(t.paidBy) != ''
                THEN COALESCE((SELECT MAX(sd.amountOwed) FROM split_debts sd WHERE sd.transactionId = t.id AND LOWER(TRIM(sd.debtorName)) = 'you'), 0)
                ELSE MAX(0, t.amount - COALESCE((SELECT SUM(sd.amountOwed) FROM split_debts sd WHERE sd.transactionId = t.id AND LOWER(TRIM(sd.debtorName)) != 'you'), 0))
            END AS amount,
            t.timestamp AS timestamp,
            t.category AS category
        FROM transactions t
        WHERE t.type = 'DEBIT'
          AND t.excludeFromSpend = 0
          AND LOWER(TRIM(t.category)) != 'transfer'
          AND t.category != 'Split Settlement'
          AND t.timestamp >= :since
    """)
    fun getDebitsSinceFlow(since: Long): Flow<List<DebitPoint>>

    @Query("UPDATE transactions SET payee = :newPayee WHERE payee = :oldPayee")
    suspend fun renamePayeeForAll(oldPayee: String, newPayee: String): Int

    @Transaction
    @Query("""
        SELECT t.* FROM transactions t
        WHERE (t.payee LIKE '%' || :search || '%' ESCAPE '\'
   OR t.category LIKE '%' || :search || '%' ESCAPE '\'
   OR COALESCE(t.itemsSummary, '') LIKE '%' || :search || '%' ESCAPE '\'
   OR COALESCE(t.note, '') LIKE '%' || :search || '%' ESCAPE '\'
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

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.paidBy IS NOT NULL AND LOWER(TRIM(t.paidBy)) != 'you' THEN
                    COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = t.id AND LOWER(TRIM(debtorName)) = 'you'), 0)
                ELSE
                    MAX(0, amount - COALESCE((SELECT SUM(amountOwed) FROM split_debts WHERE transactionId = t.id AND LOWER(TRIM(debtorName)) != 'you'), 0))
            END
        ), 0)
        FROM transactions t
        WHERE type = 'DEBIT' 
          AND category != 'Split Settlement' 
          AND category != 'Transfer'
          AND excludeFromSpend = 0
          AND timestamp >= :startMillis AND timestamp <= :endMillis
    """)
    suspend fun getMySpendBetween(startMillis: Long, endMillis: Long): Long

    @Query("""
        SELECT COUNT(*)
        FROM transactions t
        WHERE type = 'DEBIT' 
          AND category != 'Split Settlement'
          AND category != 'Transfer'
          AND excludeFromSpend = 0
          AND timestamp >= :startMillis AND timestamp <= :endMillis
    """)
    suspend fun getMySpendCountBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM transactions WHERE category != 'Split Settlement' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<TransactionEntity>

    @Query("""
        UPDATE transactions
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
              OR accountNumber IN (
                  SELECT DISTINCT accountNumber
                  FROM transactions
                  WHERE bankName NOT LIKE '% CC' AND accountNumber IS NOT NULL
              )
          )
    """)
    suspend fun healFalseCreditCardAccounts(): Int

    @Query("UPDATE transactions SET accountNumber = SUBSTR(accountNumber, -4) WHERE accountNumber IS NOT NULL AND LENGTH(accountNumber) > 4")
    suspend fun normalizeAccountNumbersToLast4(): Int

    @Query("""
        UPDATE transactions
        SET accountNumber = :canonicalAccountNumber
        WHERE bankName = :bankName
          AND (accountNumber IS NULL OR accountNumber != :canonicalAccountNumber)
    """)
    suspend fun unifyBankAccountNumber(bankName: String, canonicalAccountNumber: String): Int
}
