package com.expensetracker.offline.engine.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.expensetracker.offline.MainActivity
import java.util.Locale

class DailySummaryReceiver : BroadcastReceiver() {

    companion object {
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

        // FIXED: Accepts spentPaise (Long) and divides by 100.0 so daily spend isn't reported 100x too high
        fun showNotification(context: Context, spentPaise: Long, count: Int) {
            showNotificationRupees(context, spentPaise / 100.0, count)
        }

        private fun showNotificationRupees(context: Context, spentRupees: Double, count: Int) {
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
                "You spent ₹${String.format(Locale.getDefault(), "%,.2f", spentRupees)} across $count transactions today."
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
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Legacy AlarmManager broadcasts are retired in favor of DailyDigestWorker;
        // if an older alarm fires once before cancellation, cancel it immediately.
        cancelLegacyAlarm(context)
    }
}