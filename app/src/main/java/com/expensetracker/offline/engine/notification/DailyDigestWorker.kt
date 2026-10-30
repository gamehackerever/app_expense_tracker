package com.expensetracker.offline.engine.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.repository.SettingsRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyDigestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.transactionDao()

        if (settingsRepo.billRemindersEnabled) {
            val recentTxns = dao.getRecentTransactions(500)
            DailySummaryReceiver.checkAndNotifyUpcomingBills(applicationContext, recentTxns)
        }

        val pendingReview = runCatching { db.reviewItemDao().getPendingReviewCountSync() }.getOrDefault(0)
        val reviewForNotification = if (settingsRepo.reviewRemindersEnabled) pendingReview else 0

        if (!settingsRepo.dailyDigestEnabled) {
            // No spending digest: still nudge once in the evening, but only if something is actually waiting.
            DailySummaryReceiver.showReviewReminder(applicationContext, reviewForNotification)
            return Result.success()
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val endOfDay = cal.apply { add(Calendar.DATE, 1) }.timeInMillis - 1

        val spentPaise = dao.getMySpendBetween(startOfDay, endOfDay)
        val count = dao.getMySpendCountBetween(startOfDay, endOfDay)

        DailySummaryReceiver.showNotification(applicationContext, spentPaise, count, reviewForNotification)

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
