package com.expensetracker.offline.engine.interceptor

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.expensetracker.offline.data.local.entity.TransactionSource

class NotificationCaptureService : NotificationListenerService() {

    private val rebindHandler = Handler(Looper.getMainLooper())
    private var rebindRunnable: Runnable? = null

    companion object {
        private const val TAG = "NotificationInterceptor"
        private const val REBIND_DELAY_MS = 1000L

        // FIXED: Removed WhatsApp to prevent parsing personal chats (PO-A)
        private val UPI_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp",
            // CRED ("com.dreamplug.androidapp") removed: its reminders/cashback promos look like
            // payments. Bank SMS already covers CRED bill payments.
        )

        fun isPermissionGranted(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }

        fun forceRebind(context: Context) {
            if (!isPermissionGranted(context)) {
                Log.d(TAG, "Notification listener permission not granted; skipping forceRebind")
                return
            }
            val componentName = ComponentName(context, NotificationCaptureService::class.java)
            try {
                requestRebind(componentName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to requestRebind, attempting component toggle fallback", e)
                try {
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed component toggle fallback", e2)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        cancelPendingRebind()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
        cancelPendingRebind()

        if (!isPermissionGranted(this)) {
            Log.d(TAG, "Notification listener permission not granted on disconnect; skipping rebind")
            return
        }

        // Delay requestRebind off the unbind callstack to allow Android's ManagedServices
        // to finish unbinding cleanly without "Service not registered" exceptions.
        val runnable = Runnable {
            try {
                if (isPermissionGranted(this)) {
                    Log.d(TAG, "Attempting delayed requestRebind")
                    requestRebind(ComponentName(this, NotificationCaptureService::class.java))
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestRebind failed in onListenerDisconnected", e)
            }
        }
        rebindRunnable = runnable
        rebindHandler.postDelayed(runnable, REBIND_DELAY_MS)
    }

    override fun onDestroy() {
        cancelPendingRebind()
        super.onDestroy()
    }

    private fun cancelPendingRebind() {
        rebindRunnable?.let { rebindHandler.removeCallbacks(it) }
        rebindRunnable = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkgName = sbn.packageName ?: return
        if (!UPI_PACKAGES.contains(pkgName)) return

        val notification = sbn.notification ?: return
        // Ongoing ("app is running") and group-summary notifications are never payments.
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        // bigText usually repeats text; use the longer one instead of concatenating both.
        val body = if (bigText.isNotBlank() && (bigText.length >= text.length)) bigText else text
        val combinedContent = "$title $body".trim()
        if (combinedContent.isBlank()) return

        val notifTime = if (notification.`when` > 0) notification.`when` else sbn.postTime
        val senderName = title.ifBlank { getAppName(pkgName) }

        // Parsing happens in WorkManager (persisted + retried; not on the binder thread and not lost
        // when this service is destroyed). Reposts of the same notification share one unique key.
        CaptureWorker.enqueue(
            context = applicationContext,
            uniqueKey = "${sbn.key}_$notifTime",
            sender = senderName,
            body = combinedContent,
            title = title,
            timestamp = notifTime,
            source = TransactionSource.NOTIFICATION
        )
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }
}
