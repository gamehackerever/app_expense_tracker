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
import kotlin.math.abs

object MissingTransactionNotifier {

    private const val TAG = "MissingTxnNotifier"
    private const val CHANNEL_ID = "discrepancy_channel"
    private const val NOTIFICATION_ID = 1005

    fun notify(context: Context, discrepancyAmount: Long, newBalance: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure backward compatibility for devices below Android 8.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Balance Discrepancies",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when your bank balance doesn't match recorded expenses"
            }
            manager.createNotificationChannel(channel)
        }

        // Flags required to properly wake/resume the app to the foreground
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Prevent ugly negative formatting (e.g. "₹-45.00")
        val absoluteGap = abs(discrepancyAmount)
        val formattedDiff = String.format(Locale.getDefault(), "%,.2f", (absoluteGap / 100.0))
        val formattedBal = String.format(Locale.getDefault(), "%,.2f", (newBalance / 100.0))

        // Dynamically guess if they missed logging an expense or an income
        val gapType = if (discrepancyAmount > 0) "expense" else "income"

        Log.d(TAG, "Notifying missing transaction: ₹$formattedDiff gap detected.")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Use alpha-only system vector to prevent solid white square rendering bug
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Missing transaction detected")
            .setContentText("Found a ₹$formattedDiff gap in your balance.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Your bank balance is ₹$formattedBal, but there is a ₹$formattedDiff gap from your last recorded balance. You may have an unrecorded $gapType. Tap to log the missing transaction.")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
