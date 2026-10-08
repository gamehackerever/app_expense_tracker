package com.expensetracker.offline.engine.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.expensetracker.offline.MainActivity
import java.util.Locale

object LowBalanceNotifier {

    private const val TAG = "LowBalanceNotifier"
    private const val CHANNEL_ID = "low_balance_channel"
    private const val NOTIFICATION_ID = 1001

    /**
     * @param accountKey identifies the account (BalanceCacheKeys.key(bank, account)) so each account
     *   has its own alert throttle. The caller must only pass balances that are the account's
     *   NEWEST known balance; this function no longer writes the balance cache.
     */
    fun checkAndNotify(context: Context, balance: Long?, accountKey: String = "global") {
        if (balance == null) return

        val prefs = context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
        val throttleKey = "key_last_alerted_balance_$accountKey"

        val isEnabled = prefs.getBoolean("key_low_balance_alert_enabled", true)
        val thresholdRupees = try {
            prefs.getFloat("key_low_balance_threshold", 1000f).toDouble()
        } catch (_: ClassCastException) {
            try {
                prefs.getLong("key_low_balance_threshold", 1000L).toDouble()
            } catch (_: ClassCastException) {
                1000.0
            }
        }
        val thresholdPaise = (thresholdRupees * 100).toLong()

        if (!isEnabled) return

        // If balance is healthy, clear any previous throttle state and exit
        if (balance >= thresholdPaise) {
            prefs.edit().remove(throttleKey).apply()
            return
        }

        // --- SMART THROTTLE ---
        // Prevent spamming the user if they receive multiple SMS messages for the same balance,
        // or if they receive a small refund that leaves them still under the threshold.
        // We ONLY alert again if they spend MORE money, driving the balance strictly lower.
        val lastAlertedBalance = (try { prefs.getLong(throttleKey, Long.MAX_VALUE) } catch (_: ClassCastException) { Long.MAX_VALUE })
        if (balance >= lastAlertedBalance) {
            return
        }

        // Update the throttle state with the newly dropped balance
        prefs.edit().putLong(throttleKey, balance).apply()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure backward compatibility for devices below Android 8.0 (Oreo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Low Balance Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when account balance drops below safety threshold"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val formattedBal = String.format(Locale.getDefault(), "%,.2f", balance / 100.0)
        val formattedThreshold = String.format(Locale.getDefault(), "%,.0f", thresholdRupees)

        // Intent to launch the app when the user taps the notification
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Use an alpha-only vector icon to prevent the "white square" rendering bug
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Low Balance Warning")
            .setContentText("Your balance is ₹$formattedBal (below ₹$formattedThreshold).")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Attention: Your bank account balance is ₹$formattedBal, which has dropped below your safety threshold of ₹$formattedThreshold.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Low balance warning")
                    .setContentText("Open the app to see details.")
                    .build()
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
