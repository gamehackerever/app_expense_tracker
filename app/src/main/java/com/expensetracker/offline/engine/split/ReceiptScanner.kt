package com.expensetracker.offline.engine.split

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ReceiptScanner {
    data class ScannedItem(val id: String, val name: String, val pricePaise: Long, var assignedTo: MutableSet<String> = mutableSetOf())

    suspend fun extractItems(context: Context, uri: Uri): List<ScannedItem> = suspendCoroutine { cont ->
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                cont.resume(parseRegex(visionText.text))
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    private fun parseRegex(rawText: String): List<ScannedItem> {
        // Looks for "Chicken Shawarma 120.00" or "Pasta Rs 350"
        val regex = Regex("""^(.+?)(?:Rs\.?|₹|inr)?\s*(\d+[\.,]\d{2})\s*$""", RegexOption.MULTILINE)
        val results = mutableListOf<ScannedItem>()

        regex.findAll(rawText).forEach { match ->
            val name = match.groupValues[1].trim().takeIf { it.length > 2 && !it.contains("total", true) && !it.contains("tax", true) }
            val priceStr = match.groupValues[2].replace(",", "")
            val pricePaise = (priceStr.toDoubleOrNull()?.times(100))?.toLong()

            if (name != null && pricePaise != null && pricePaise > 0L) {
                results.add(ScannedItem(java.util.UUID.randomUUID().toString(), name, pricePaise))
            }
        }
        return results
    }

    /** Distributes the bill's overhead (Tax/Tip/Delivery) proportionally based on assigned items. */
    fun calculateProRataShares(items: List<ScannedItem>, totalBillPaise: Long, participants: List<String>): Map<String, Long> {
        val itemSubtotal = items.sumOf { it.pricePaise }
        val overhead = (totalBillPaise - itemSubtotal).coerceAtLeast(0L)

        val individualTotals = mutableMapOf<String, Double>().apply { participants.forEach { put(it, 0.0) } }

        // 1. Split base item prices
        items.forEach { item ->
            if (item.assignedTo.isNotEmpty()) {
                val splitPrice = item.pricePaise.toDouble() / item.assignedTo.size
                item.assignedTo.forEach { person ->
                    individualTotals[person] = (individualTotals[person] ?: 0.0) + splitPrice
                }
            }
        }

        // 2. Distribute overhead proportionally
        return individualTotals.mapValues { (_, subtotal) ->
            val shareRatio = if (itemSubtotal > 0L) subtotal / itemSubtotal.toDouble() else 1.0 / participants.size
            (subtotal + (overhead * shareRatio)).toLong()
        }
    }
}