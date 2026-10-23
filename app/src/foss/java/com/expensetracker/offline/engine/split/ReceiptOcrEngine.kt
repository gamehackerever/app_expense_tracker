package com.expensetracker.offline.engine.split

import android.content.Context
import android.net.Uri

object ReceiptOcrEngine {
    val isSupported: Boolean = false

    suspend fun extractReceipt(context: Context, uri: Uri): ReceiptScanner.ScannedReceipt {
        return ReceiptScanner.ScannedReceipt(emptyList())
    }
}
