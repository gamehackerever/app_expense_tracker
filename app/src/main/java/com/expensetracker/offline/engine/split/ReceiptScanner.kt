package com.expensetracker.offline.engine.split

import android.content.Context
import android.net.Uri
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

object ReceiptScanner {
    data class ScannedItem(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val pricePaise: Long,
        val qty: Int = 1,
        var assignedTo: MutableSet<String> = mutableSetOf()
    )

    data class ScannedReceipt(
        val items: List<ScannedItem>,
        val subtotalPaise: Long? = null,
        val taxPaise: Long? = null,
        val grandTotalPaise: Long? = null
    )

    suspend fun extractReceipt(context: Context, uri: Uri): ScannedReceipt =
        ReceiptOcrEngine.extractReceipt(context, uri)

    suspend fun extractItems(context: Context, uri: Uri): List<ScannedItem> =
        extractReceipt(context, uri).items

    fun formatItemsSummary(items: List<ScannedItem>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(", ") { item ->
            val qtyPart = if (item.qty > 1) " ×${item.qty}" else ""
            val priceStr = if (item.pricePaise % 100L == 0L) {
                "₹${item.pricePaise / 100L}"
            } else {
                String.format(Locale.US, "₹%.2f", item.pricePaise / 100.0)
            }
            val whoPart = if (item.assignedTo.isNotEmpty()) {
                " [${item.assignedTo.joinToString("/")}]"
            } else ""
            "${item.name}$qtyPart ($priceStr)$whoPart"
        }
    }

    private val DECIMAL_NUMBER_REGEX = Regex("""(\d{1,3}(?:,\d{3})*|\d+)[\.,](\d{2})\b""")
    private val TRAILING_COLUMNS_REGEX = Regex(
        """^(.*?)\s+(?:(\d{1,3})\s+)?(?:(\d{1,5}(?:[\.,]\d{2})?)\s+)?(?:Rs\.?|₹|INR)?\s*(\d{1,3}(?:,\d{3})*[\.,]\d{2}|\d+[\.,]\d{2})\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val PURE_NUMERIC_ROW_REGEX = Regex(
        """^\s*(?:(\d{1,3})\s+)?(?:(\d{1,5}(?:[\.,]\d{2})?)\s+)?(?:Rs\.?|₹|INR)?\s*(\d{1,3}(?:,\d{3})*[\.,]\d{2}|\d+[\.,]\d{2})\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val CONTINUATION_LINE_REGEX = Regex("""^\s*\([A-Za-z0-9\s\-/]{2,20}\)\s*$""")

    private val NON_ITEM_KEYWORD_REGEX = Regex(
        """(?i)\b(sub\s*total|subtotal|grand\s*total|total\s*qty|total\s*amount|net\s*amount|net\s*total|bill\s*total|amount\s*payable|cgst|sgst|igst|utgst|gst|vat|tax|service\s*charge|packaging|delivery\s*charge|round\s*off|rounding|discount|cash\s*tendered|change\s*due|balance\s*due|paid\s*via|card\s*payment|upi\s*payment|thank\s*you|visit\s*again|vist\s*again|cashier|assign\s*to|bill\s*no|invoice|dine\s*in|take\s*away|table\s*\d*|gstin|fssai|mob\s*:|phone|date\s*:|time\s*:|restaurant|hotel|broast)\b"""
    )
    private val HEADER_LINE_REGEX = Regex("""(?i)^\s*(item|items|particulars|description|qty\.?|price|rate|amount|total|sub|name\s*:|\[note\].*)\s*$""")
    private val TABLE_HEADER_TRIGGER = Regex("""(?i)\b(item|particulars|description)\b.*?\b(qty|price|rate|amount)\b""")
    private val TABLE_FOOTER_TRIGGER = Regex("""(?i)\b(total\s*qty|sub\s*total|subtotal|cgst|sgst|grand\s*total)\b""")
    private val HAS_LETTERS_REGEX = Regex("""[A-Za-z]{2,}""")



    internal fun parseRawTextLines(rawLines: List<String>): ScannedReceipt {
        val items = mutableListOf<ScannedItem>()
        var subtotalPaise: Long? = null
        var taxPaise = 0L
        var grandTotalPaise: Long? = null

        val orphanNames = mutableListOf<String>()
        val orphanPrices = mutableListOf<Pair<Int, Long>>()
        var pastItemTable = false
        var pendingNamePrefix: String? = null

        for (rawLine in rawLines) {
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.isBlank()) continue

            val lower = line.lowercase()
            if (TABLE_HEADER_TRIGGER.containsMatchIn(lower) || lower == "item" || lower.startsWith("item qty")) {
                pendingNamePrefix = null
                continue
            }
            if (TABLE_FOOTER_TRIGGER.containsMatchIn(lower)) {
                pastItemTable = true
            }

            if (CONTINUATION_LINE_REGEX.matches(line)) {
                when {
                    pendingNamePrefix != null -> pendingNamePrefix = "$pendingNamePrefix $line"
                    items.isNotEmpty() && orphanNames.isEmpty() -> {
                        val lastIdx = items.lastIndex
                        val lastItem = items[lastIdx]
                        if (!lastItem.name.contains("(")) {
                            items[lastIdx] = lastItem.copy(name = "${lastItem.name} $line")
                        }
                    }
                    orphanNames.isNotEmpty() -> {
                        val lastIdx = orphanNames.lastIndex
                        if (!orphanNames[lastIdx].contains("(")) {
                            orphanNames[lastIdx] = "${orphanNames[lastIdx]} $line"
                        }
                    }
                }
                continue
            }

            if (line.startsWith("[Note]", ignoreCase = true) || HEADER_LINE_REGEX.matches(line)) {
                pendingNamePrefix = null
                continue
            }

            val lastPricePaise = extractLastDecimalPaise(line)
            if (lastPricePaise != null && lastPricePaise > 0L) {
                when {
                    lower.contains("grand total") || lower.startsWith("total ") || lower.matches(Regex("""^total\s*[:₹\d].*""")) -> {
                        grandTotalPaise = lastPricePaise
                        pendingNamePrefix = null
                        continue
                    }
                    lower.contains("sub total") || lower.contains("subtotal") || lower.matches(Regex("""^sub\s+\d.*""")) -> {
                        subtotalPaise = lastPricePaise
                        pendingNamePrefix = null
                        continue
                    }
                    lower.contains("cgst") || lower.contains("sgst") || lower.contains("igst") || lower.contains("vat") || lower.contains("service charge") -> {
                        taxPaise += lastPricePaise
                        pendingNamePrefix = null
                        continue
                    }
                }
            }

            if (NON_ITEM_KEYWORD_REGEX.containsMatchIn(line)) {
                pendingNamePrefix = null
                continue
            }

            val rowMatch = TRAILING_COLUMNS_REGEX.matchEntire(line)
            if (rowMatch != null) {
                val rawNamePart = rowMatch.groupValues[1].trim()
                val qtyStr = rowMatch.groupValues[2].trim()
                val unitPriceStr = rowMatch.groupValues[3].trim()
                val amountStr = rowMatch.groupValues[4].trim()
                val amountPaise = parsePriceToPaise(amountStr)

                if (HAS_LETTERS_REGEX.containsMatchIn(rawNamePart)) {
                    val cleanedName = cleanItemName(rawNamePart)
                    pendingNamePrefix = null
                    if (cleanedName != null && amountPaise != null && amountPaise > 0L) {
                        val qty = qtyStr.toIntOrNull()?.takeIf { it in 1..99 }
                            ?: inferQuantity(unitPriceStr, amountPaise)
                        items.add(ScannedItem(name = cleanedName, pricePaise = amountPaise, qty = qty))
                        continue
                    }
                } else if (!pastItemTable && amountPaise != null && amountPaise > 0L) {
                    val tokens = rawNamePart.split(" ").filter { it.isNotBlank() }
                    val qty = tokens.firstOrNull()?.toIntOrNull()?.takeIf { it in 1..20 }
                        ?: qtyStr.toIntOrNull()?.takeIf { it in 1..20 }
                        ?: inferQuantity(unitPriceStr.ifBlank { tokens.lastOrNull().orEmpty() }, amountPaise)
                    if (pendingNamePrefix != null) {
                        val cleaned = cleanItemName(pendingNamePrefix)
                        pendingNamePrefix = null
                        if (cleaned != null) {
                            items.add(ScannedItem(name = cleaned, pricePaise = amountPaise, qty = qty))
                            continue
                        }
                    }
                    orphanPrices.add(qty to amountPaise)
                    continue
                }
            }

            val numMatch = PURE_NUMERIC_ROW_REGEX.matchEntire(line)
            if (numMatch != null && !pastItemTable) {
                val qtyStr = numMatch.groupValues[1].trim()
                val unitPriceStr = numMatch.groupValues[2].trim()
                val amountStr = numMatch.groupValues[3].trim()
                val amountPaise = parsePriceToPaise(amountStr)
                if (amountPaise != null && amountPaise > 0L) {
                    val qty = qtyStr.toIntOrNull()?.takeIf { it in 1..99 }
                        ?: inferQuantity(unitPriceStr, amountPaise)
                    if (pendingNamePrefix != null) {
                        val cleaned = cleanItemName(pendingNamePrefix)
                        pendingNamePrefix = null
                        if (cleaned != null) {
                            items.add(ScannedItem(name = cleaned, pricePaise = amountPaise, qty = qty))
                            continue
                        }
                    }
                    orphanPrices.add(qty to amountPaise)
                }
                continue
            }

            if (!pastItemTable && DECIMAL_NUMBER_REGEX.find(line) == null) {
                val candidateName = cleanItemName(line)
                if (candidateName != null) {
                    if (pendingNamePrefix != null) {
                        orphanNames.add(pendingNamePrefix)
                    }
                    pendingNamePrefix = candidateName
                }
            } else {
                pendingNamePrefix = null
            }
        }

        if (pendingNamePrefix != null) {
            orphanNames.add(pendingNamePrefix)
        }

        if (orphanNames.isNotEmpty() && orphanPrices.isNotEmpty()) {
            val pairCount = minOf(orphanNames.size, orphanPrices.size)
            for (i in 0 until pairCount) {
                val (qty, amtPaise) = orphanPrices[i]
                items.add(ScannedItem(name = orphanNames[i], pricePaise = amtPaise, qty = qty))
            }
        }

        return ScannedReceipt(
            items = items,
            subtotalPaise = subtotalPaise,
            taxPaise = taxPaise.takeIf { it > 0L },
            grandTotalPaise = grandTotalPaise ?: subtotalPaise?.let { it + taxPaise }
        )
    }

    private fun inferQuantity(unitPriceStr: String, totalPaise: Long): Int {
        if (unitPriceStr.isBlank()) return 1
        val unitRupees = unitPriceStr.replace(",", "").toDoubleOrNull() ?: return 1
        if (unitRupees <= 0.0) return 1
        val unitPaise = (unitRupees * 100.0).roundToLong()
        if (unitPaise <= 0L || totalPaise < unitPaise) return 1
        val ratio = (totalPaise.toDouble() / unitPaise.toDouble()).roundToLong().toInt()
        return if (ratio in 1..50 && abs(totalPaise - ratio * unitPaise) <= 100L) ratio else 1
    }

    private fun extractLastDecimalPaise(line: String): Long? {
        val lastMatch = DECIMAL_NUMBER_REGEX.findAll(line).lastOrNull() ?: return null
        return parsePriceToPaise(lastMatch.value)
    }

    private fun parsePriceToPaise(raw: String): Long? {
        val trimmed = raw.trim()
        val normalized = if (trimmed.matches(Regex(""".*,\d{2}$"""))) {
            trimmed.replace(".", "").replace(",", ".")
        } else {
            trimmed.replace(",", "")
        }
        return normalized.toDoubleOrNull()?.times(100.0)?.roundToLong()?.takeIf { it > 0L }
    }

    private fun cleanItemName(raw: String): String? {
        var s = raw.trim()
            .replace(Regex("""\s+\d{1,3}\s+\d{1,5}(?:[\.,]\d{2})?\s*$"""), "")
            .replace(Regex("""\s+\d{1,4}\s*$""")) { m ->
                val num = m.value.trim().toIntOrNull()
                if (num != null && num >= 1) "" else m.value
            }
            .trim(' ', '-', '.', ':', ',', '*', '#')

        s = s.replace(Regex("""^\d{1,2}[\.\)]\s+"""), "").trim()

        if (s.length < 2) return null
        if (!HAS_LETTERS_REGEX.containsMatchIn(s)) return null
        if (NON_ITEM_KEYWORD_REGEX.containsMatchIn(s)) return null
        if (HEADER_LINE_REGEX.matches(s)) return null
        return s
    }

    /**
     * Distributes the bill's overhead (Tax/Tip/Service Charge) proportionally based on assigned items,
     * using largest-remainder integer allocation when all items are assigned so participant shares
     * always sum to the exact [totalBillPaise] with zero rounding drift.
     */
    fun calculateProRataShares(
        items: List<ScannedItem>,
        totalBillPaise: Long,
        participants: List<String>
    ): Map<String, Long> {
        val assignedItems = items.filter { it.assignedTo.isNotEmpty() }
        if (assignedItems.isEmpty() || participants.isEmpty()) {
            return participants.associateWith { 0L }
        }

        val allItemsSubtotal = items.sumOf { it.pricePaise }.coerceAtLeast(1L)
        val assignedSubtotal = assignedItems.sumOf { it.pricePaise }
        val effectiveBillTotal = maxOf(totalBillPaise, allItemsSubtotal)

        val rawSubtotals = LinkedHashMap<String, Double>().apply {
            participants.forEach { put(it, 0.0) }
        }

        assignedItems.forEach { item ->
            val validAssignees = item.assignedTo.filter { rawSubtotals.containsKey(it) }.ifEmpty { item.assignedTo }
            val splitPrice = item.pricePaise.toDouble() / validAssignees.size.coerceAtLeast(1)
            validAssignees.forEach { person ->
                rawSubtotals[person] = (rawSubtotals[person] ?: 0.0) + splitPrice
            }
        }

        // If all items are assigned (or assignedSubtotal == allItemsSubtotal), allocate effectiveBillTotal
        // with exact largest-remainder rounding so the sum of shares == effectiveBillTotal to the exact paisa.
        val allAssigned = assignedItems.size == items.size && assignedSubtotal > 0L
        if (allAssigned) {
            val targetTotal = if (totalBillPaise > 0L) totalBillPaise else assignedSubtotal
            val exactFloats = rawSubtotals.mapValues { (_, sub) ->
                (sub / assignedSubtotal.toDouble()) * targetTotal.toDouble()
            }
            val floored = exactFloats.mapValues { (_, v) -> v.toLong() }.toMutableMap()
            var remainder = targetTotal - floored.values.sum()
            val byRemainderDesc = exactFloats.keys.sortedByDescending { k ->
                (exactFloats[k] ?: 0.0) - (floored[k] ?: 0L).toDouble()
            }
            for (k in byRemainderDesc) {
                if (remainder <= 0L) break
                if ((exactFloats[k] ?: 0.0) > 0.0) {
                    floored[k] = (floored[k] ?: 0L) + 1L
                    remainder--
                }
            }
            return floored
        }

        // Partial assignment: scale each assigned item by the bill's tax/overhead multiplier
        val taxMultiplier = if (totalBillPaise > allItemsSubtotal) {
            effectiveBillTotal.toDouble() / allItemsSubtotal.toDouble()
        } else {
            1.0
        }
        return rawSubtotals.mapValues { (_, sub) ->
            if (sub <= 0.0) 0L else (sub * taxMultiplier).roundToLong()
        }
    }
}