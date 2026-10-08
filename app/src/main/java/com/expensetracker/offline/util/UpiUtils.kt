package com.expensetracker.offline.util

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

object UpiUtils {
    fun buildUpiUri(payeeVpa: String, payeeName: String, amountPaise: Long, note: String): Uri {
        val amountFormat = String.format(Locale.US, "%.2f", amountPaise / 100.0)
        return Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", payeeVpa)
            .appendQueryParameter("pn", payeeName)
            .appendQueryParameter("am", amountFormat)
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", note.take(50)) // Max 50 chars allowed by most UPI apps
            .build()
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }
}