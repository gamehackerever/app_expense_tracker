package com.expensetracker.offline.engine.split

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max

object ReceiptOcrEngine {
    val isSupported: Boolean = true

    suspend fun extractReceipt(context: Context, uri: Uri): ReceiptScanner.ScannedReceipt = suspendCoroutine { cont ->
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            cont.resume(ReceiptScanner.ScannedReceipt(emptyList()))
            return@suspendCoroutine
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                cont.resume(parseVisionText(visionText))
            }
            .addOnFailureListener { cont.resume(ReceiptScanner.ScannedReceipt(emptyList())) }
            .addOnCompleteListener { recognizer.close() }
    }

    private data class SpatialWord(
        val text: String,
        val box: Rect,
        val corners: Array<Point>?
    ) {
        val centerX: Float get() = (box.left + box.right) / 2f
        val centerY: Float get() = (box.top + box.bottom) / 2f
        val height: Float get() = (box.bottom - box.top).coerceAtLeast(1).toFloat()
    }

    private data class DeskewedWord(
        val word: SpatialWord,
        val deskewedY: Float
    )

    private val HAS_LETTERS_REGEX = Regex("""[A-Za-z]{2,}""")
    private val TABLE_FOOTER_TRIGGER = Regex("""(?i)\b(total\s*qty|sub\s*total|subtotal|cgst|sgst|grand\s*total)\b""")

    private fun parseVisionText(visionText: Text): ReceiptScanner.ScannedReceipt {
        val words = mutableListOf<SpatialWord>()
        val candidateSlopes = mutableListOf<Float>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineCorners = line.cornerPoints
                if (lineCorners != null && lineCorners.size >= 2) {
                    val dx = (lineCorners[1].x - lineCorners[0].x).toFloat()
                    val dy = (lineCorners[1].y - lineCorners[0].y).toFloat()
                    if (dx >= 45f) {
                        val s = dy / dx
                        if (abs(s) <= 0.40f) candidateSlopes.add(s)
                    }
                }
                val lineElems = line.elements
                if (lineElems.size >= 2 && HAS_LETTERS_REGEX.containsMatchIn(line.text)) {
                    val firstBox = lineElems.first().boundingBox
                    val lastBox = lineElems.last().boundingBox
                    if (firstBox != null && lastBox != null) {
                        val dx = ((lastBox.left + lastBox.right) - (firstBox.left + firstBox.right)) / 2f
                        val dy = ((lastBox.top + lastBox.bottom) - (firstBox.top + firstBox.bottom)) / 2f
                        if (dx in 40f..320f) {
                            val s = dy / dx
                            if (abs(s) <= 0.40f) candidateSlopes.add(s)
                        }
                    }
                }

                for (elem in lineElems) {
                    val box = elem.boundingBox
                    val t = elem.text.trim()
                    if (t.isNotEmpty() && box != null) {
                        val ec = elem.cornerPoints
                        if (ec != null && ec.size >= 2) {
                            val dx = (ec[1].x - ec[0].x).toFloat()
                            val dy = (ec[1].y - ec[0].y).toFloat()
                            if (dx >= 35f) {
                                val s = dy / dx
                                if (abs(s) <= 0.40f) candidateSlopes.add(s)
                            }
                        }
                        words.add(SpatialWord(t, box, ec))
                    }
                }
            }
        }

        if (words.isEmpty()) {
            return ReceiptScanner.parseRawTextLines(visionText.text.lines())
        }

        val sortedSlopes = candidateSlopes.sorted()
        val medianSlope = if (sortedSlopes.isNotEmpty()) sortedSlopes[sortedSlopes.size / 2] else 0f

        val heights = words.map { it.height }.sorted()
        val medianHeight = if (heights.isNotEmpty()) heights[heights.size / 2] else 22f
        val yTolerance = max(medianHeight * 0.55f, 9f)

        val deskewedWords = words.map { w ->
            DeskewedWord(w, w.centerY - medianSlope * w.centerX)
        }.sortedBy { it.deskewedY }

        val rows = mutableListOf<MutableList<DeskewedWord>>()
        for (dw in deskewedWords) {
            val matchRow = rows.lastOrNull { row ->
                val avgY = row.map { it.deskewedY }.average().toFloat()
                abs(dw.deskewedY - avgY) <= yTolerance &&
                        row.none { existing -> horizontalOverlapRatio(existing.word.box, dw.word.box) > 0.50f }
            }
            if (matchRow != null) {
                matchRow.add(dw)
            } else {
                rows.add(mutableListOf(dw))
            }
        }

        val reconstructedLines = rows.map { row ->
            row.sortedBy { it.word.box.left }.joinToString(" ") { it.word.text }
        }

        val primaryResult = ReceiptScanner.parseRawTextLines(reconstructedLines)

        val columnPairedResult = pairLeftNamesWithRightAmounts(deskewedWords, yTolerance, primaryResult)
        return if (columnPairedResult.items.size > primaryResult.items.size) {
            columnPairedResult
        } else {
            primaryResult
        }
    }

    private fun pairLeftNamesWithRightAmounts(
        deskewedWords: List<DeskewedWord>,
        yTolerance: Float,
        baseReceipt: ReceiptScanner.ScannedReceipt
    ): ReceiptScanner.ScannedReceipt {
        if (deskewedWords.isEmpty()) return baseReceipt
        val minX = deskewedWords.minOf { it.word.box.left }.toFloat()
        val maxX = deskewedWords.maxOf { it.word.box.right }.toFloat()
        val width = (maxX - minX).coerceAtLeast(1f)
        val splitX = minX + width * 0.54f

        val leftWords = deskewedWords.filter { it.word.centerX <= splitX }
        val rightWords = deskewedWords.filter { it.word.centerX > splitX }

        fun clusterColumnLines(colWords: List<DeskewedWord>): List<Pair<Float, String>> {
            val colRows = mutableListOf<MutableList<DeskewedWord>>()
            for (dw in colWords.sortedBy { it.deskewedY }) {
                val match = colRows.lastOrNull { r ->
                    val avgY = r.map { it.deskewedY }.average().toFloat()
                    abs(dw.deskewedY - avgY) <= yTolerance
                }
                if (match != null) match.add(dw) else colRows.add(mutableListOf(dw))
            }
            return colRows.map { r ->
                val y = r.map { it.deskewedY }.average().toFloat()
                val text = r.sortedBy { it.word.box.left }.joinToString(" ") { it.word.text }.trim()
                y to text
            }
        }

        val leftLines = clusterColumnLines(leftWords)
        val rightLines = clusterColumnLines(rightWords)

        val headerY = leftLines.firstOrNull { (_, text) ->
            text.equals("Item", ignoreCase = true) || text.startsWith("Item ", ignoreCase = true)
        }?.first
        val footerY = leftLines.firstOrNull { (y, text) ->
            (headerY == null || y > headerY) && TABLE_FOOTER_TRIGGER.containsMatchIn(text.lowercase())
        }?.first

        if (headerY == null && footerY == null) return baseReceipt

        val startY = headerY ?: Float.MIN_VALUE
        val endY = footerY ?: Float.MAX_VALUE

        val extractedNames = mutableListOf<String>()
        for ((y, text) in leftLines) {
            if (y <= startY + yTolerance * 0.4f || y >= endY - yTolerance * 0.4f) continue
            if (text.startsWith("[Note]", ignoreCase = true)) continue
            val cleaned = text.replace(Regex("""^\d+\s*"""), "").trim()
            if (cleaned.length >= 2 && HAS_LETTERS_REGEX.containsMatchIn(cleaned)) {
                extractedNames.add(cleaned)
            }
        }

        val extractedAmountsPaise = mutableListOf<Long>()
        val decimalRegex = Regex("""(\d{1,3}(?:,\d{3})*|\d+)[\.,](\d{2})\b""")
        for ((y, text) in rightLines) {
            if (y <= startY + yTolerance * 0.4f || y >= endY - yTolerance * 0.4f) continue
            val m = decimalRegex.find(text)
            if (m != null) {
                val rawVal = m.value.replace(",", "").replace(".", "")
                rawVal.toLongOrNull()?.let { extractedAmountsPaise.add(it) }
            }
        }

        if (extractedNames.isEmpty() || extractedAmountsPaise.isEmpty()) return baseReceipt

        val pairedItems = mutableListOf<ReceiptScanner.ScannedItem>()
        val count = minOf(extractedNames.size, extractedAmountsPaise.size)
        for (i in 0 until count) {
            pairedItems.add(
                ReceiptScanner.ScannedItem(
                    name = extractedNames[i],
                    pricePaise = extractedAmountsPaise[i]
                )
            )
        }

        return ReceiptScanner.ScannedReceipt(items = pairedItems)
    }

    private fun horizontalOverlapRatio(a: Rect, b: Rect): Float {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapRight = minOf(a.right, b.right)
        val overlapWidth = (overlapRight - overlapLeft).coerceAtLeast(0)
        val minWidth = minOf(a.width(), b.width()).coerceAtLeast(1)
        return overlapWidth.toFloat() / minWidth.toFloat()
    }
}
