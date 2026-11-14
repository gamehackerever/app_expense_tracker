package com.expensetracker.offline.engine.validation

import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import java.util.Locale

/**
 * Conservative checks for automatically captured transactions. Findings are routed to user review;
 * the monitor never rejects or edits a real transaction on its own.
 */
object TransactionIntegrityMonitor {
    private const val FUTURE_TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L
    private const val AMOUNT_HISTORY_WINDOW_MS = 400L * 24 * 60 * 60 * 1000
    private const val MIN_HISTORY_COUNT = 3
    private const val MIN_AMOUNT_DIFFERENCE_PAISE = 100_000L

    fun assess(
        amount: Long,
        payee: String?,
        type: TransactionType,
        source: TransactionSource,
        timestamp: Long,
        recentTransactions: List<TransactionEntity>,
        now: Long = System.currentTimeMillis()
    ): String? {
        if (source == TransactionSource.MANUAL) return null
        if (amount <= 0L) return "The captured amount is missing or invalid. Verify the original alert."
        if (timestamp > now + FUTURE_TIMESTAMP_TOLERANCE_MS) {
            return "The captured date is in the future. Check the date and time in the original alert."
        }

        val normalizedPayee = normalizePayee(payee.orEmpty())
        if (normalizedPayee.isBlank()) return null

        val comparableAmounts = recentTransactions.asSequence()
            .filter { it.type == type && it.amount > 0L && !it.excludeFromSpend }
            .filter { it.timestamp in (timestamp - AMOUNT_HISTORY_WINDOW_MS)..timestamp }
            .filter { normalizePayee(it.payee) == normalizedPayee }
            .map { it.amount }
            .sorted()
            .toList()

        if (comparableAmounts.size < MIN_HISTORY_COUNT) return null

        // A lower median avoids being pulled upward by one earlier anomalous payment.
        val usualAmount = comparableAmounts[(comparableAmounts.size - 1) / 2]
        if (usualAmount <= 0L || amount == usualAmount) return null

        val difference = kotlin.math.abs(amount - usualAmount)
        if (difference < MIN_AMOUNT_DIFFERENCE_PAISE) return null

        val ratio = maxOf(amount, usualAmount).toDouble() / minOf(amount, usualAmount).toDouble()
        if (ratio < 4.0) return null

        val direction = if (amount > usualAmount) "higher" else "lower"
        val displayPayee = payee?.trim()?.takeIf { it.isNotBlank() } ?: "this merchant"
        return "This amount is much $direction than recent payments to $displayPayee. Verify the original alert."
    }

    private fun normalizePayee(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
