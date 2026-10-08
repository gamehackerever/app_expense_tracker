package com.expensetracker.offline.engine.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.expensetracker.offline.MainActivity
import com.expensetracker.offline.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class DailySummaryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailySummaryReceiver"
        private const val CHANNEL_ID = "daily_summary_channel"
        private const val NOTIF_ID = 2002

        fun cancelLegacyAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, DailySummaryReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it); it.cancel() }
        }

        fun showNotification(context: Context, spent: Double, count: Int) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Daily Spending Recap",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Daily 9:30 PM summary of today's expenses"
                }
                manager.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // FIXED: Verify capture health so we don't praise the user if it's just broken (PO-H)
            val isCaptureHealthy = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

            val message = if (count > 0) {
                "You spent ₹${String.format(Locale.getDefault(), "%,.2f", spent)} across $count transactions today."
            } else if (!isCaptureHealthy) {
                "Zero expenses recorded today, but Auto-Capture is OFF. Tap to enable it."
            } else {
                "Zero expenses recorded today! Great job staying on budget."
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("🌙 Today's Spending Digest")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                // FIXED: Prevent screen privacy leaks (PO-F)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setContentTitle("🌙 Today's Spending Digest")
                        .setContentText("Tap to view your daily summary.")
                        .build()
                )
                .build()

            manager.notify(NOTIF_ID, notification)
        }

        fun scheduleDailySummary(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailySummaryReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
            }

            try {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule daily summary alarm", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("key_daily_digest_enabled", true)) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                cal.add(Calendar.DATE, 1)
                val endOfDay = cal.timeInMillis - 1

                // FIXED: Use the unified spend queries instead of generic sums (PO-H)
                val todaySpent = db.transactionDao().getMySpendBetween(startOfDay, endOfDay)
                val count = db.transactionDao().getMySpendCountBetween(startOfDay, endOfDay)

                showNotification(context, todaySpent, count)
            } catch (e: Exception) {
                Log.e(TAG, "Error generating daily summary", e)
            } finally {
                pendingResult?.finish()
                scope.cancel()
            }
        }
    }
}