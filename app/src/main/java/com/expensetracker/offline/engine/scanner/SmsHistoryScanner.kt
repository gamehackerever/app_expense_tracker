package com.expensetracker.offline.engine.scanner

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.room.withTransaction
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.TransactionRepository
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.parser.FinancialParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.expensetracker.offline.engine.interceptor.CaptureDispatcher
import com.expensetracker.offline.util.SenderFilter
import com.expensetracker.offline.widget.ExpenseWidget
import kotlinx.coroutines.sync.withLock

data class ScanResult(
    val scannedCount: Int,
    val directImportedCount: Int,
    val reviewCount: Int,
    val importedTransactionIds: List<Long>,
    val importedReviewIds: List<Long>
)

// FIXED: Use a sealed response class to correctly bubble up Permission errors to the UI (P1)
sealed class ScanResponse {
    data class Success(val result: ScanResult) : ScanResponse()
    object PermissionDenied : ScanResponse()
    data class Failed(val message: String) : ScanResponse()
}

class SmsHistoryScanner(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "SmsHistoryScanner"
        private const val DUPLICATE_MATCH_WINDOW_MS = 300_000L
        private val NUMERIC_PAYEE_REGEX = Regex("""^\d{8,12}.*""")
        private const val BATCH_SIZE = 100 // process in chunks so the DB write lock is released often
        private const val REVIEW_THRESHOLD_PAISE = TransactionRepository.REVIEW_THRESHOLD_PAISE
    }

    private val transactionDao = database.transactionDao()
    private val reviewItemDao = database.reviewItemDao()
    private val categoryRuleDao = database.categoryRuleDao()

    suspend fun scanSmsSince(sinceTimestampMillis: Long): ScanResponse = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        val skipScanReview = prefs.getBoolean("key_skip_scan_review", false)

        val fallbackPayee = prefs.getString("key_scan_fallback_payee", null)?.ifBlank { null }
            ?: prefs.getString("key_fallback_payee", "Bank Transfer")?.ifBlank { "Bank Transfer" }
            ?: "Bank Transfer"

        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ?"
        val selectionArgs = arrayOf(sinceTimestampMillis.toString())
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        var scannedCount = 0
        val newTransactionIds = mutableListOf<Long>()
        val newReviewIds = mutableListOf<Long>()

        val batchSignatures = mutableSetOf<String>()
        val batchTxns = mutableListOf<TransactionEntity>()
        val batchReviews = mutableListOf<ReviewItemEntity>()

        // Helper to flush batched entities to the database in one quick transaction
        suspend fun flushBatch() {
            if (batchTxns.isEmpty() && batchReviews.isEmpty()) return
            CaptureDispatcher.ingestMutex.withLock { database.withTransaction {
                batchTxns.forEach { t ->
                    val id = transactionDao.insertTransaction(t)
                    if (id > 0) newTransactionIds.add(id)
                }
                batchReviews.forEach { r ->
                    val id = reviewItemDao.insertReviewItem(r)
                    if (id > 0) newReviewIds.add(id)
                }
            } }
            batchTxns.clear()
            batchReviews.clear()
        }

        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    scannedCount++

                    val address = cursor.getString(addressCol).orEmpty()
                    val body = cursor.getString(bodyCol).orEmpty()
                    val date = cursor.getLong(dateCol)

                    if (body.isBlank()) continue
                    // Personal chats (phone-number senders) and promotional (-P) headers are not bank alerts.
                    if (!SenderFilter.isAllowedSmsSender(address)) continue

                    val parsed = FinancialParser.parse(rawText = body, title = address)
                    if (!parsed.isFinancial) continue
                    val amount = parsed.amount
                    val parsedType = parsed.type

                    // Same policy as live capture: an unreadable financial message is kept for review,
                    // never silently skipped and never guessed (a missing type is NOT assumed DEBIT).
                    if (amount == null || amount <= 0L || parsedType == null) {
                        run {
                            val exists = reviewItemDao.countExactReviewItems(body, date) > 0 ||
                                    batchReviews.any { it.rawContent == body && it.timestamp == date }
                            if (!exists) {
                                batchReviews.add(
                                    ReviewItemEntity(
                                        rawContent = body, sender = address, timestamp = date, source = TransactionSource.SMS,
                                        extractedPartialAmount = amount?.takeIf { it > 0L }, extractedPartialPayee = parsed.payee,
                                        type = parsedType, bankName = parsed.bankName, accountNumber = parsed.accountNumber,
                                        balance = parsed.balance, referenceId = parsed.referenceId
                                    )
                                )
                            }
                        }
                        if (batchTxns.size + batchReviews.size >= BATCH_SIZE) flushBatch()
                        continue
                    }

                    val signature = "${address}_${amount}_${date}"
                    if (!batchSignatures.add(signature)) continue

                    // Uncommitted duplicates in the current batch. Two payments of the same amount are
                    // only duplicates if they could be the same transaction: refs must not differ.
                    val inBatchDuplicate = batchTxns.any { t ->
                        t.amount == amount && t.type == parsedType &&
                                abs(t.timestamp - date) <= DUPLICATE_MATCH_WINDOW_MS &&
                                !(t.referenceId != null && parsed.referenceId != null && t.referenceId != parsed.referenceId) &&
                                (t.referenceId != null && t.referenceId == parsed.referenceId || t.rawContent == body)
                    }
                    if (inBatchDuplicate) continue

                    val existing: TransactionEntity? = transactionDao.findMatchingTransaction(
                        amount = amount.toDouble(), type = parsedType,
                        startTime = date - DUPLICATE_MATCH_WINDOW_MS, endTime = date + DUPLICATE_MATCH_WINDOW_MS,
                        targetTime = date, bankName = parsed.bankName, accountNumber = parsed.accountNumber
                    )

                    val existingIsSame = existing != null &&
                            !(existing.referenceId != null && parsed.referenceId != null && existing.referenceId != parsed.referenceId) &&
                            (existing.referenceId != null && existing.referenceId == parsed.referenceId ||
                                    existing.rawContent.contains(body) ||
                                    existing.source != TransactionSource.SMS) // other channel/manual row for the same payment

                    if (existing != null && existingIsSame) {
                        // Back-fill what the existing (e.g. notification-only) row is missing.
                        val bank = parsed.bankName
                        if (existing.bankName == null && bank != null) {
                            transactionDao.updateBankDetails(existing.id, bank, parsed.accountNumber)
                        }
                        if ((existing.balance == null && parsed.balance != null) ||
                            (existing.referenceId == null && parsed.referenceId != null)) {
                            transactionDao.updateTransaction(
                                existing.copy(
                                    balance = existing.balance ?: parsed.balance,
                                    referenceId = existing.referenceId ?: parsed.referenceId
                                )
                            )
                        }
                        continue
                    }

                    val payee = parsed.payee
                    val isHighValue = parsedType == TransactionType.DEBIT && amount > REVIEW_THRESHOLD_PAISE
                    val isMissingPayee = payee.isNullOrBlank() || payee.equals("Unknown", ignoreCase = true) || payee.matches(NUMERIC_PAYEE_REGEX)

                    if (!skipScanReview && (isHighValue || isMissingPayee)) {
                        val alreadyInReview = reviewItemDao.countMatchingReviewItems(
                            amount, parsedType, date - DUPLICATE_MATCH_WINDOW_MS, date + DUPLICATE_MATCH_WINDOW_MS,
                            parsed.referenceId, parsed.bankName, parsed.accountNumber
                        ) > 0
                        val inBatchReviewDuplicate = batchReviews.any { r ->
                            r.extractedPartialAmount == amount && r.type == parsedType &&
                                abs(r.timestamp - date) <= DUPLICATE_MATCH_WINDOW_MS &&
                                (r.referenceId == null || parsed.referenceId == null || r.referenceId == parsed.referenceId) &&
                                (r.bankName == null || parsed.bankName == null || r.bankName == parsed.bankName) &&
                                (r.accountNumber == null || parsed.accountNumber == null || r.accountNumber == parsed.accountNumber)
                        }

                        if (!alreadyInReview && !inBatchReviewDuplicate) {
                            batchReviews.add(
                                ReviewItemEntity(
                                    rawContent = body, sender = address, timestamp = date, source = TransactionSource.SMS,
                                    extractedPartialAmount = amount, extractedPartialPayee = payee,
                                    type = parsedType, bankName = parsed.bankName, accountNumber = parsed.accountNumber,
                                    balance = parsed.balance, referenceId = parsed.referenceId
                                )
                            )
                        }
                    } else {
                        val resolvedPayee = if (isMissingPayee || payee == null) fallbackPayee else payee
                        val aliasedPayee = prefs.getString("merchant_alias_${resolvedPayee.trim().lowercase()}", null) ?: resolvedPayee
                        val category = CategoryClassifier.classify(aliasedPayee, body, categoryRuleDao)

                        batchTxns.add(
                            TransactionEntity(
                                amount = amount,
                                payee = aliasedPayee, timestamp = date, type = parsedType,
                                source = TransactionSource.SMS, referenceId = parsed.referenceId,
                                rawContent = body, category = category, note = parsed.note,
                                balance = parsed.balance,
                                bankName = parsed.bankName, accountNumber = parsed.accountNumber,
                                excludeFromSpend = parsed.excludeFromSpend
                            )
                        )
                    }

                    // Flush batch periodically
                    if (batchTxns.size + batchReviews.size >= BATCH_SIZE) {
                        flushBatch()
                    }
                }

                // Flush remainder
                flushBatch()
            }

            if (newTransactionIds.isNotEmpty()) runCatching { ExpenseWidget.updateAll(context) }
            ScanResponse.Success(
                ScanResult(scannedCount, newTransactionIds.size, newReviewIds.size, newTransactionIds, newReviewIds)
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission denied or revoked during scan", e)
            ScanResponse.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during bulk SMS scan", e)
            ScanResponse.Failed(e.localizedMessage ?: "Unknown Error")
        }
    }
}
