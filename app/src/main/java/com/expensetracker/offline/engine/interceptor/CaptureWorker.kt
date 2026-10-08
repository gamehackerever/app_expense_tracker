package com.expensetracker.offline.engine.interceptor

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.engine.parser.FinancialParser
import java.util.concurrent.TimeUnit

/**
 * Processes ONE captured SMS / notification outside the BroadcastReceiver (goAsync only gives
 * ~10 s) and outside the listener service lifetime. WorkManager persists the request, so a
 * process death or a slow DB open no longer loses the message; failures are retried.
 */
class CaptureWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val title = inputData.getString(KEY_TITLE)
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        val source = runCatching { TransactionSource.valueOf(inputData.getString(KEY_SOURCE) ?: "SMS") }
            .getOrDefault(TransactionSource.SMS)

        if (body.isBlank()) return Result.success()
        val app = applicationContext as? ExpenseTrackerApp ?: return Result.failure()

        return try {
            // One header-aware parse. For SMS the sender header plays the role of the "title".
            val parsed = FinancialParser.parse(
                rawText = body,
                title = title ?: sender,
                source = source
            )
            if (!parsed.isFinancial) return Result.success() // rejected; nothing to persist

            val result = CaptureDispatcher.dispatch(app, parsed, sender, timestamp, source)
            if (result == null && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Capture work failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "CaptureWorker"
        private const val MAX_ATTEMPTS = 5
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_TITLE = "title"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SOURCE = "source"

        /** [uniqueKey] makes identical enqueues (carrier duplicates, notification reposts) collapse. */
        fun enqueue(
            context: Context,
            uniqueKey: String,
            sender: String,
            body: String,
            title: String?,
            timestamp: Long,
            source: TransactionSource
        ) {
            val data = Data.Builder()
                .putString(KEY_SENDER, sender)
                .putString(KEY_BODY, body.take(4000))   // WorkManager input limit is 10 KB
                .putString(KEY_TITLE, title)
                .putLong(KEY_TIMESTAMP, timestamp)
                .putString(KEY_SOURCE, source.name)
                .build()
            val request = OneTimeWorkRequestBuilder<CaptureWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("capture_${source.name}_$uniqueKey", ExistingWorkPolicy.KEEP, request)
        }
    }
}
