package com.expensetracker.offline.engine.interceptor

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.util.SenderFilter

class NotificationCaptureService : NotificationListenerService() {

    private val rebindHandler = Handler(Looper.getMainLooper())
    private var rebindRunnable: Runnable? = null

    companion object {
        private const val TAG = "NotificationInterceptor"
        private const val REBIND_DELAY_MS = 1000L

        // Expanded to cover 40+ Indian UPI, mobile banking, credit card, wallet, and neobank apps
        private val UPI_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay (GPay)
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM
            "in.amazon.mShop.android.shopping",       // Amazon Pay
            "money.super.app",                        // Super.money (Flipkart UPI)
            "com.dreamplug.androidapp",               // CRED
            "in.gokiwi.kiwitpap",                     // Kiwi (RuPay CC on UPI)
            "com.tatadigital.tcp",                    // Tata Neu
            "com.fampay.in",                          // FamPay / FamApp
            "com.bharatpe.app",                       // BharatPe
            "com.mobikwik_new",                       // MobiKwik
            "indwin.c3.shareapp",                     // Slice
            "com.slice",                              // Slice (new)
            "com.fplabs.onecard",                     // OneCard
            "com.naviapp",                            // Navi UPI
            "com.samsung.android.spay",               // Samsung Wallet / Pay
            "com.samsung.android.spaymini",           // Samsung Pay Mini
            "com.epifi.paisa",                        // Fi Money
            "money.jupiter",                          // Jupiter
            "com.freecharge.android",                 // Freecharge
            "com.myairtelapp",                        // Airtel Thanks
            "com.jio.myjio",                          // MyJio
            "com.jio.finance",                        // JioFinance
            "com.lazypay.app",                        // LazyPay
            "com.getsimpl.android",                   // Simpl
            // Major Indian Banking Apps
            "com.sbi.lotusintouch",                   // YONO SBI
            "com.sbi.SBIFreedomPlus",                 // YONO Lite SBI
            "com.sbicard.pay",                        // SBI Card
            "com.snapwork.hdfc",                      // HDFC Bank MobileBanking
            "com.hdfcbank.payzapp",                   // HDFC PayZapp
            "com.csam.icici.bank.imobile",            // ICICI iMobile Pay
            "com.axis.mobile",                        // Axis Mobile
            "com.msf.kbank.mobile",                   // Kotak811 / Kotak Bank
            "com.kotak.bank.mobile",                  // Kotak Mobile Banking
            "com.idfcfirstbank.optimus",              // IDFC FIRST Bank
            "com.indusind.indusmobile",               // IndusMobile
            "com.yesbank",                            // IRIS by YES BANK
            "com.fedmobile",                          // Federal Bank FedMobile
            "com.bankofbaroda.mconnect",              // BoB World
            "com.canarabank.mobility",                // Canara ai1
            "com.canarabank.epassbook",               // Canara e-Passbook
            "com.canarabank.canaramobile",            // Canara Mobile Banking
            "com.paytm.business",                     // Paytm for Business
            "com.Version1",                           // PNB ONE
            "com.infrasoft.uboi",                     // Union Bank Vyom
            "com.IndianBank.IndOASIS",                // Indian Bank IndOASIS
            "com.SIBMobile",                          // SIB Mirror+
            "com.rblbank.mobank",                     // RBL MoBank
            "com.aubank.aupay"                        // AU 0101
        )

        // RCS / SMS Messaging Apps — only processed when the notification sender header passes SenderFilter
        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",      // Google Messages (RCS + SMS)
            "com.samsung.android.messaging",          // Samsung Messages
            "com.microsoft.android.smsorganizer"      // Microsoft SMS Organizer
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
        val isUpiOrBankApp = UPI_PACKAGES.contains(pkgName)
        val isMessagingApp = MESSAGING_PACKAGES.contains(pkgName)
        val isWhatsApp = pkgName == "com.whatsapp"
        if (!isUpiOrBankApp && !isMessagingApp && !isWhatsApp) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty().trim()

        // For RCS/SMS messaging apps, strictly require a verified DLT/Bank sender header in the title
        // so personal chat notifications are never captured.
        if (isMessagingApp) {
            if (title.isBlank() || !SenderFilter.isAllowedSmsSender(title)) return
        }

        // Extract any messages from MessagingStyle (frequently used by Google Pay payment threads)
        val messagingTexts = mutableListOf<String>()
        try {
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (messagingStyle != null) {
                messagingStyle.messages.forEach { msg ->
                    val msgText = msg.text?.toString()?.trim().orEmpty()
                    if (msgText.isNotBlank()) {
                        messagingTexts.add(msgText)
                    }
                }
            }
        } catch (_: Exception) {}

        // Also check EXTRA_TEXT_LINES (used by InboxStyle notifications)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val primaryBody = if (bigText.isNotBlank() && (bigText.length >= text.length)) bigText else text
        val bodyBuilder = StringBuilder(primaryBody)

        if (messagingTexts.isNotEmpty()) {
            val joinedMessages = messagingTexts.joinToString(" ")
            if (!bodyBuilder.contains(joinedMessages)) {
                if (bodyBuilder.isNotEmpty()) bodyBuilder.append(" ")
                bodyBuilder.append(joinedMessages)
            }
        }

        if (textLines.isNotEmpty()) {
            val joinedLines = textLines.joinToString(" ")
            if (!bodyBuilder.contains(joinedLines)) {
                if (bodyBuilder.isNotEmpty()) bodyBuilder.append(" ")
                bodyBuilder.append(joinedLines)
            }
        }

        if (subText.isNotBlank() && !bodyBuilder.contains(subText, ignoreCase = true) && !title.contains(subText, ignoreCase = true)) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append(" ")
            bodyBuilder.append(subText)
        }

        if (summaryText.isNotBlank() && !bodyBuilder.contains(summaryText, ignoreCase = true) && !title.contains(summaryText, ignoreCase = true)) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append(" ")
            bodyBuilder.append(summaryText)
        }

        val body = bodyBuilder.toString().trim()
        val combinedContent = if (body.isNotBlank()) "$title $body".trim() else title
        if (combinedContent.isBlank()) return

        // For WhatsApp, only capture notifications that contain financial payment cues
        if (isWhatsApp) {
            val lower = combinedContent.lowercase(java.util.Locale.ROOT)
            val isFinancial = (lower.contains("paid") || lower.contains("sent") || lower.contains("received") || lower.contains("debited") || lower.contains("credited")) &&
                    (lower.contains("₹") || lower.contains("inr") || lower.contains("rs.") || lower.contains("rs "))
            if (!isFinancial) return
        }

        val notifTime = if (notification.`when` > 0) notification.`when` else if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val senderName = title.ifBlank { getAppName(pkgName) }

        // Key on both notification key AND content hash so notification updates (e.g. GPay status completion,
        // payee or bank details added after initial post) trigger capture instead of being silently dropped.
        val contentHash = combinedContent.hashCode()
        CaptureWorker.enqueue(
            context = applicationContext,
            uniqueKey = "${sbn.key}_$contentHash",
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
