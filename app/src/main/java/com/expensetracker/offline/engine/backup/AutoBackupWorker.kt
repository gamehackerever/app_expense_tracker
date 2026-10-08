package com.expensetracker.offline.engine.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
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
        val prefs = applicationContext.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("key_auto_backup_enabled", false)
        val folderUri = prefs.getString("key_backup_folder_uri", "") ?: ""

        if (!isEnabled || folderUri.isBlank()) {
            return Result.success()
        }

        // Decrypted from the Android Keystore; null if it was never set or the key no longer exists.
        val passphrase = SecurePassphraseStore.load(applicationContext)
        if (passphrase.isNullOrBlank()) {
            notifyFailure("Backup passphrase isn't available. Open Settings and run Backup Now to set it again.")
            return Result.success()
        }

        val hasPermission = applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == folderUri
        }

        if (!hasPermission) {
            // FIXED: Surface the permission failure to the user instead of dying silently (PO-E)
            notifyFailure("Storage permission lost. Please re-select your backup folder in Settings.")
            prefs.edit { putBoolean("key_auto_backup_enabled", false) }
            return Result.failure()
        }

        // FIXED: Stop unbounded retries (PO-E)
        if (runAttemptCount > 3) {
            notifyFailure("Auto-backup failed multiple times. Please run a manual backup.")
            return Result.failure()
        }

        val result = BackupManager.createBackup(applicationContext, folderUri, passphrase)

        return if (result is BackupResult.Success) {
            val now = System.currentTimeMillis()
            prefs.edit { putLong("key_last_backup_timestamp", now) }

            // FIXED: Prune old backups to prevent filling up storage (PO-E)
            pruneOldBackups(applicationContext, folderUri, maxKeep = 5)

            Result.success()
        } else {
            Result.retry()
        }
    }

    private suspend fun pruneOldBackups(context: Context, folderUri: String, maxKeep: Int) = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext
            val backups = dir.listFiles()
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

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("key_auto_backup_enabled", false)
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

            // FIXED: Add idle/charging constraints so large stream operations don't kill the battery (PO-E)
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
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