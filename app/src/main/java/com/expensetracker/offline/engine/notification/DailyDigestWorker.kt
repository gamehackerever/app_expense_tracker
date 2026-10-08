package com.expensetracker.offline.engine.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.expensetracker.offline.data.local.AppDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyDigestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("key_daily_digest_enabled", true)) return Result.success()

        val db = AppDatabase.getInstance(applicationContext)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        // FIXED: Subtract 1ms to ensure strict 23:59:59.999 boundary
        val endOfDay = cal.apply { add(Calendar.DATE, 1) }.timeInMillis - 1

        // FIXED: Use the unified spend queries instead of generic un-clamped sums (PO-H)
        val spent = db.transactionDao().getMySpendBetween(startOfDay, endOfDay)
        val count = db.transactionDao().getMySpendCountBetween(startOfDay, endOfDay)

        // showNotification handles the Auto-Capture health check internally
        DailySummaryReceiver.showNotification(applicationContext, spent, count)

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_digest_worker"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)

            val request = PeriodicWorkRequestBuilder<DailyDigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}