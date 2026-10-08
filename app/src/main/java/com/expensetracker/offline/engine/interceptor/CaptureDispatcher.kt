package com.expensetracker.offline.engine.interceptor

import android.util.Log
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.TransactionRepository
import com.expensetracker.offline.data.repository.TransactionRepository.IngestResult
import com.expensetracker.offline.engine.notification.LowBalanceNotifier
import com.expensetracker.offline.engine.notification.MissingTransactionNotifier
import com.expensetracker.offline.engine.parser.ParsedTransaction
import com.expensetracker.offline.util.BalanceCache
import com.expensetracker.offline.util.BalanceCacheKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

object CaptureDispatcher {
    private const val TAG = "CaptureDispatcher"

    /** Shared by live capture AND the history scanner so find-then-insert cannot race. */
    val ingestMutex = Mutex()

    /** Ghost alerts ignore gaps below Rs 10 (amounts are PAISE). */
    private const val GHOST_THRESHOLD_PAISE = 1_000L

    /**
     * @return the ingest result, or null if an unexpected error happened (already logged).
     */
    suspend fun dispatch(
        app: ExpenseTrackerApp,
        parsed: ParsedTransaction,
        sender: String,
        timestamp: Long,
        source: TransactionSource
    ): IngestResult? = withContext(Dispatchers.IO) {
        ingestMutex.withLock {
            try {
                val repository = TransactionRepository(app.database, app.applicationContext)

                // 1. SAVE (single DB transaction)
                val result = repository.processIncomingEvent(parsed, sender, timestamp, source)
                if (result is IngestResult.Failed) {
                    Log.d(TAG, "Not ingested (${source.name}): ${result.reason}")
                    return@withLock result
                }

                // 2. BALANCE: ghost check + baseline + low-balance alert, all keyed on the result.
                val balance = parsed.balance
                if (balance != null) {
                    handleBalance(app, parsed, balance, timestamp, sender, source, result)
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch captured transaction from $source", e)
                null
            }
        }
    }

    private suspend fun handleBalance(
        app: ExpenseTrackerApp,
        parsed: ParsedTransaction,
        balance: Long,
        timestamp: Long,
        sender: String,
        source: TransactionSource,
        result: IngestResult
    ) {
        val prefs = BalanceCache.prefs(app.applicationContext)
        val bank = parsed.bankName
        val account = parsed.accountNumber

        val storedTs = BalanceCache.timestampOf(prefs, bank, account)
        // A stale message (older than the stored baseline) must never touch baseline or alerts.
        if (timestamp <= storedTs) return

        val baseline = BalanceCache.baselineOf(prefs, bank, account)
        val ingested = result is IngestResult.Inserted || result is IngestResult.Merged ||
                result is IngestResult.QueuedForReview
        val amount = parsed.amount

        // ── GHOST CHECK ── only with a known baseline, and only when this message was actually kept.
        if (ingested && baseline != null && storedTs > 0L && amount != null) {
            val netSince = app.database.transactionDao()
                .getIntermediateNetSpend(storedTs, timestamp, bank, account).toLong()
            // Pending review items newer than the baseline for THIS account (the baseline already
            // contains everything older). Includes this message if it went to review.
            val pendingNet = app.database.reviewItemDao()
                .getPendingNetBetween(bank, account, storedTs, timestamp) ?: 0L

            val expected = baseline + netSince + pendingNet
            val discrepancy = expected - balance

            if (abs(discrepancy) > GHOST_THRESHOLD_PAISE) {
                MissingTransactionNotifier.notify(app.applicationContext, discrepancy, balance)

                val ghostIsDebit = discrepancy > 0
                val accountStr = account?.let { "A/c $it" } ?: ""
                val bankStr = bank?.let { "- $it" } ?: ""
                val gap = String.format(Locale.US, "%.2f", abs(discrepancy) / 100.0)
                val bal = String.format(Locale.US, "%.2f", balance / 100.0)
                val verb = if (ghostIsDebit) "debited" else "credited"

                app.database.reviewItemDao().insertReviewItem(
                    ReviewItemEntity(
                        rawContent = "Silent Deduction: $verb Rs.$gap $accountStr Bal:Rs.$bal $bankStr".trim(),
                        sender = sender,
                        source = source,
                        extractedPartialAmount = abs(discrepancy),
                        extractedPartialPayee = "Silent Deduction",
                        timestamp = timestamp - 1000,
                        type = if (ghostIsDebit) TransactionType.DEBIT else TransactionType.CREDIT,
                        bankName = bank,
                        accountNumber = account
                    )
                )
            }
        }

        // ── BASELINE ── (first message for an account initialises it; later ones advance it)
        if (BalanceCache.updateIfNewer(prefs, bank, account, balance, timestamp) && ingested) {
            // ── LOW BALANCE ── only for the account's newest balance, throttled per account.
            LowBalanceNotifier.checkAndNotify(
                app.applicationContext, balance, BalanceCacheKeys.key(bank, account)
            )
        }
    }
}
