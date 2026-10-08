package com.expensetracker.offline.engine.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.expensetracker.offline.util.BackupManager
import com.expensetracker.offline.util.BackupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val folderUri = prefs.getString("key_backup_folder_uri", "") ?: ""

        if (!isEnabled || folderUri.isBlank()) {
            return Result.success()
        }

        // Decrypted from the Android Keystore; null if it was never set or the key no longer exists.
        val passphrase = SecurePassphraseStore.load(applicationContext)
        if (passphrase.isNullOrBlank()) {
            recordStatus(ok = false, reason = "Passphrase not available")
            notifyFailure("Backup passphrase isn't available. Open Settings and run Backup Now to set it again.")
            return Result.success()
        }

        val hasPermission = applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == folderUri
        }

        if (!hasPermission) {
            // FIXED: Surface the permission failure to the user instead of dying silently (PO-E)
            recordStatus(ok = false, reason = "Storage permission lost")
            notifyFailure("Storage permission lost. Please re-select your backup folder in Settings.")
            prefs.edit { putBoolean(KEY_ENABLED, false) }
            return Result.failure()
        }

        // FIXED: Stop unbounded retries (PO-E)
        if (runAttemptCount > 3) {
            val lastReason = prefs.getString(KEY_LAST_RUN_REASON, null)
            recordStatus(
                ok = false,
                reason = if (lastReason.isNullOrBlank()) "Gave up after repeated failures"
                else "Gave up after repeated failures ($lastReason)"
            )
            notifyFailure("Auto-backup failed multiple times. Please run a manual backup.")
            return Result.failure()
        }

        return when (val result = BackupManager.createBackup(applicationContext, folderUri, passphrase)) {
            is BackupResult.Success -> {
                prefs.edit { putLong("key_last_backup_timestamp", System.currentTimeMillis()) }
                recordStatus(ok = true)

                // FIXED: Prune old backups to prevent filling up storage (PO-E)
                pruneOldBackups(applicationContext, folderUri, maxKeep = 5)

                Result.success()
            }
            is BackupResult.Error -> {
                recordStatus(ok = false, reason = result.message)
                Result.retry()
            }
        }
    }

    /** Remembers the outcome of the latest run so Settings can show it. */
    private fun recordStatus(ok: Boolean, reason: String? = null) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LAST_RUN_OK, ok)
            putLong(KEY_LAST_RUN_TIME, System.currentTimeMillis())
            if (reason.isNullOrBlank()) remove(KEY_LAST_RUN_REASON) else putString(KEY_LAST_RUN_REASON, reason)
        }
    }

    private suspend fun pruneOldBackups(context: Context, folderUri: String, maxKeep: Int) = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext
            val files = dir.listFiles()
            // Leftovers from a backup that failed halfway (older than an hour, so a running one is safe).
            val staleCutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            files.filter {
                it.name?.startsWith("ExpenseBackup_") == true && it.name?.endsWith(".enc.tmp") == true &&
                        it.lastModified() < staleCutoff
            }.forEach { it.delete() }

            val backups = files
                .filter { it.name?.startsWith("ExpenseBackup_") == true && it.name?.endsWith(".enc") == true }
                .sortedByDescending { it.lastModified() }

            if (backups.size > maxKeep) {
                backups.drop(maxKeep).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e("AutoBackup", "Failed to prune backups", e)
        }
    }

    private fun notifyFailure(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "backup_alerts_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Backup Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Expense Tracker Backup Stopped")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        manager.notify(3003, notification)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "auto_backup_periodic_work"
        private const val PREFS = "expense_tracker_prefs"
        private const val KEY_ENABLED = "key_auto_backup_enabled"

        // Outcome of the latest auto-backup run (excluded from backups, see BackupManager).
        const val KEY_LAST_RUN_OK = "key_last_auto_backup_ok"
        const val KEY_LAST_RUN_TIME = "key_last_auto_backup_run_time"
        const val KEY_LAST_RUN_REASON = "key_last_auto_backup_reason"

        /** One line for Settings, e.g. "Last auto-backup: OK, 2 hours ago". Null before the first run. */
        fun lastRunSummary(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_LAST_RUN_OK)) return null
            val whenText = DateUtils.getRelativeTimeSpanString(
                prefs.getLong(KEY_LAST_RUN_TIME, 0L),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            return if (prefs.getBoolean(KEY_LAST_RUN_OK, false)) {
                "Last auto-backup: OK, $whenText"
            } else {
                val reason = prefs.getString(KEY_LAST_RUN_REASON, null)?.takeIf { it.isNotBlank() } ?: "unknown reason"
                "Last auto-backup: failed ($reason), $whenText"
            }
        }

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean(KEY_ENABLED, false)
            val folderUri = prefs.getString("key_backup_folder_uri", "") ?: ""
            val frequency = prefs.getString("key_backup_frequency", "Weekly") ?: "Weekly"
            val workManager = WorkManager.getInstance(context)

            if (!isEnabled || folderUri.isBlank()) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }

            val intervalDays = when (frequency) {
                "Daily" -> 1L
                "Weekly" -> 7L
                "Monthly" -> 30L
                else -> 7L
            }

            // Only skip the run when the battery is low. The database is small, so waiting for
            // idle + charging just meant backups silently never ran.
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}