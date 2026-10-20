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
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.util.NecessityManager
import java.util.Locale

class DailySummaryReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "daily_summary_channel"
        private const val BILL_CHANNEL_ID = "bill_reminders_channel"
        private const val NOTIF_ID = 2002
        private const val BILL_NOTIF_BASE_ID = 2100

        fun cancelLegacyAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, DailySummaryReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it); it.cancel() }
        }

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
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setContentTitle("🌙 Today's Spending Digest")
                        .setContentText("Tap to view your daily summary.")
                        .build()
                )
                .build()

            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                manager.notify(NOTIF_ID, notification)
            }
        }

        /**
         * Checks configured Fixed Costs & Bills and sends a local system notification 2 days before
         * the due date (and on the due date) if no matching transaction was detected in the current cycle.
         */
        fun checkAndNotifyUpcomingBills(context: Context, txns: List<TransactionEntity>) {
            val settingsRepo = SettingsRepository(context)
            if (!settingsRepo.billRemindersEnabled) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

            val prefs = settingsRepo.prefs
            val startDay = settingsRepo.startDayOfMonth
            val cycleKey = NecessityManager.currentCycleKey(startDay)
            val alerts = NecessityManager.getDueBillAlerts(prefs, txns, startDay)
            if (alerts.isEmpty()) return

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    BILL_CHANNEL_ID,
                    "Upcoming Bill Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts 2 days before fixed costs and subscriptions are due"
                }
                manager.createNotificationChannel(channel)
            }

            val openBillsIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_ROUTE", "necessities")
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 2100, openBillsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alerts.forEachIndexed { idx, alert ->
                val stage = if (alert.daysUntilDue <= 0) "DUE" else "SOON"
                val dedupeKey = "key_bill_reminded_${alert.item.id}_${cycleKey}_$stage"
                if (prefs.getBoolean(dedupeKey, false)) return@forEachIndexed

                val rupees = String.format(Locale.getDefault(), "%,.0f", alert.remainingAmountPaise / 100.0)
                val timingLabel = when {
                    alert.daysUntilDue < 0 -> "was due yesterday"
                    alert.daysUntilDue == 0 -> "is due today"
                    alert.daysUntilDue == 1 -> "is due tomorrow"
                    else -> "is due in ${alert.daysUntilDue} days"
                }
                val title = when {
                    alert.daysUntilDue <= 0 -> "🔔 Bill Due: ${alert.item.name}"
                    else -> "⏳ Upcoming Bill: ${alert.item.name}"
                }
                val body = "${alert.item.name} (₹$rupees) $timingLabel and hasn't been detected yet this cycle. Tap to review or mark as paid."

                val notif = NotificationCompat.Builder(context, BILL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat.Builder(context, BILL_CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_popup_reminder)
                            .setContentTitle("🔔 Upcoming Bill Reminder")
                            .setContentText("A scheduled fixed cost is coming due. Tap to view.")
                            .build()
                    )
                    .build()

                manager.notify(BILL_NOTIF_BASE_ID + (alert.item.id.hashCode() and 0x7FFF) + idx, notif)
                prefs.edit().putBoolean(dedupeKey, true).apply()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        cancelLegacyAlarm(context)
    }
}
