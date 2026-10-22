package com.expensetracker.offline.engine.interceptor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.ml.SmsPatternLearner
import com.expensetracker.offline.engine.notification.DailyDigestWorker
import com.expensetracker.offline.engine.scanner.SmsHistoryScanner
import com.expensetracker.offline.widget.ExpenseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ensures true "Install and Forget" reliability across device reboots and app updates.
 */
class SystemBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemBootReceiver"
        private const val CATCHUP_WINDOW_MS = 12L * 60L * 60L * 1000L // 12 hours
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in BOOT_ACTIONS) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SmsPatternLearner.initialize(appContext)
                val settings = SettingsRepository(appContext)

                if (settings.disclosureAccepted) {
                    if (!settings.isManualModePreferred) {
                        NotificationCaptureService.forceRebind(appContext)
                    }

                    DailyDigestWorker.schedule(appContext)
                    AutoBackupWorker.schedule(appContext)

                    val hasReadSms = ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_SMS
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!settings.isManualModePreferred && hasReadSms) {
                        val app = appContext as? ExpenseTrackerApp
                        if (app != null) {
                            val since = System.currentTimeMillis() - CATCHUP_WINDOW_MS
                            SmsHistoryScanner(appContext, app.database).scanSmsSince(since)
                        }
                    }

                    runCatching { ExpenseWidget.updateAll(appContext) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering background services on $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
