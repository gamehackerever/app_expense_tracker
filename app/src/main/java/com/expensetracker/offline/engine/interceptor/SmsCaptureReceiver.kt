package com.expensetracker.offline.engine.interceptor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.util.SenderFilter

/**
 * Does almost nothing on purpose: classify the sender, then hand the raw message to WorkManager
 * (persisted, retried). Parsing/dedupe/DB all happen in [CaptureWorker], parsed ONCE with the sender,
 * exactly like the history scanner, so live capture and rescans agree.
 */
class SmsCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract messages from intent", e)
            return
        }
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress.orEmpty()
        val fullBody = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        val timestamp = messages[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        if (fullBody.isBlank()) return
        // Personal chats (phone-number senders) and promotional (-P) headers never become transactions.
        if (!SenderFilter.isAllowedSmsSender(sender)) return

        // Same sender+body+timestamp (carrier duplicate) collapses into one work item.
        val key = "${sender}_${timestamp}_${fullBody.hashCode()}"
        CaptureWorker.enqueue(
            context = context,
            uniqueKey = key,
            sender = sender,
            body = fullBody,
            title = null,
            timestamp = timestamp,
            source = TransactionSource.SMS
        )
    }

    private companion object {
        const val TAG = "SmsCaptureReceiver"
    }
}
