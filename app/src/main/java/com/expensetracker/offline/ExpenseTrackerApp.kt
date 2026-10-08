package com.expensetracker.offline

import android.app.Application
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.notification.DailyDigestWorker
import com.expensetracker.offline.engine.notification.DailySummaryReceiver

class ExpenseTrackerApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        DailySummaryReceiver.cancelLegacyAlarm(this)
        DailyDigestWorker.schedule(this)
        AutoBackupWorker.schedule(this) // <-- Add this
    }
}
