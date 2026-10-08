package com.expensetracker.offline.engine.recurring

import com.expensetracker.offline.data.local.dao.TransactionDao.DebitPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How often a payment repeats.
 *
 * @param perMonth how many charges per month this cadence works out to (for "monthly cost")
 * @param requiresFixedAmount short cadences are only trusted when the amount is stable,
 *        otherwise things like a weekly Uber habit would look like a subscription
 */
enum class Cadence(
    val label: String,
    val days: Int,
    val toleranceDays: Int,
    val minOccurrences: Int,
    val perMonth: Double,
    val requiresFixedAmount: Boolean
) {
    WEEKLY("Weekly", 7, 2, 4, 52.0 / 12.0, true),
    MONTHLY("Monthly", 30, 4, 3, 1.0, false),
    BIMONTHLY("Every 2 months", 61, 7, 3, 0.5, false),
    QUARTERLY("Quarterly", 91, 10, 3, 1.0 / 3.0, false),
    YEARLY("Yearly", 365, 20, 2, 1.0 / 12.0, true)
}

data class RecurringPayment(
    val payee: String,
    val category: String,
    val amount: Long,            // typical (median) charge
    val isFixedAmount: Boolean,    // false = varies, e.g. a utility bill
    val cadence: Cadence,
    val occurrences: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val nextExpected: Long,
    val isActive: Boolean,         // false = overdue by a lot, probably cancelled
    val monthlyCost: Long,
    val confidence: Double,        // 0..1, grows with history and regularity
    val transactionIds: List<Long>
)

object RecurringPaymentDetector {

    private const val DAY_MS = 86_400_000L
    private const val DEDUPE_WINDOW_MS = 36L * 3_600_000L   // SMS + notification for the same charge
    private const val MIN_MATCH_RATIO = 0.75                // share of gaps that must fit the cadence
    private const val MAX_VARIABLE_CV = 0.6                 // amount spread allowed for non-fixed series

    /** Fetch this much history from the DB; yearly payments need > 12 months. */
    const val LOOKBACK_MS = 460L * DAY_MS

    fun detect(debits: List<DebitPoint>, now: Long = System.currentTimeMillis()): List<RecurringPayment> {
        val byPayee = debits
            .filter { it.amount > 0.0 }
            .groupBy { normalizeKey(it.payee) }
            .filterKeys { it.isNotBlank() && it != "unknown" }

        val results = mutableListOf<RecurringPayment>()

        for ((_, group) in byPayee) {
            if (group.size < 2) continue

            // 1. Fixed-amount series: same payee AND (nearly) same amount.
            //    Handles one payee with several subscriptions (e.g. two different Google plans).
            val fixedHits = clusterByAmount(group).mapNotNull { evaluate(it, fixed = true, now = now) }
            if (fixedHits.isNotEmpty()) {
                results += fixedHits
                continue
            }

            // 2. Variable-amount series: utility-style bills. Only monthly or slower.
            evaluate(group, fixed = false, now = now)?.let { results += it }
        }

        return results.sortedWith(
            compareByDescending<RecurringPayment> { it.isActive }.thenByDescending { it.monthlyCost }
        )
    }

    /** Sum of what the active recurring payments cost per month. */
    fun monthlyTotal(payments: List<RecurringPayment>): Long =
        payments.filter { it.isActive }.sumOf { it.monthlyCost }

    // ------------------------------------------------------------------------------------

    private fun evaluate(points: List<DebitPoint>, fixed: Boolean, now: Long): RecurringPayment? {
        val series = dedupe(points.sortedBy { it.timestamp })
        if (series.size < 2) return null

        val amounts = series.map { it.amount }
        if (!fixed && coefficientOfVariation(amounts) > MAX_VARIABLE_CV) return null

        val gaps = series.zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() / DAY_MS }

        val (cadence, ratio) = Cadence.values()
            .filter { series.size >= it.minOccurrences && (fixed || !it.requiresFixedAmount) }
            .map { it to matchRatio(gaps, it) }
            .filter { it.second >= MIN_MATCH_RATIO }
            .maxByOrNull { it.second }
            ?: return null

        // Step between payments: median of the gaps that fit the cadence (handles 28-day cycles etc.)
        val fittingGaps = gaps.filter { abs(it - cadence.days) <= cadence.toleranceDays }
        val stepDays = if (fittingGaps.isNotEmpty()) median(fittingGaps) else cadence.days.toDouble()

        val last = series.last()
        val nextExpected = last.timestamp + (stepDays * DAY_MS).toLong()
        val graceDays = max(cadence.toleranceDays.toDouble(), stepDays / 2.0)
        val isActive = now <= nextExpected + (graceDays * DAY_MS).toLong()

        val typicalAmount = median(amounts).toLong()
        val confidence = ratio *
                (if (fixed) 1.0 else 0.85) *
                min(1.0, series.size / (cadence.minOccurrences + 2.0))

        return RecurringPayment(
            payee = last.payee,
            category = last.category,
            amount = typicalAmount,
            isFixedAmount = fixed,
            cadence = cadence,
            occurrences = series.size,
            firstSeen = series.first().timestamp,
            lastSeen = last.timestamp,
            nextExpected = nextExpected,
            isActive = isActive,
            monthlyCost = (typicalAmount * cadence.perMonth).toLong(),
            confidence = confidence,
            transactionIds = series.map { it.id }
        )
    }

    /**
     * Fraction of gaps that look like this cadence. A gap of ~2x the period counts half,
     * so one missed or unparsed payment doesn't break a real subscription.
     */
    private fun matchRatio(gaps: List<Double>, c: Cadence): Double {
        var score = 0.0
        for (g in gaps) {
            score += when {
                abs(g - c.days) <= c.toleranceDays -> 1.0
                abs(g - 2 * c.days) <= c.toleranceDays -> 0.5
                else -> 0.0
            }
        }
        return score / gaps.size
    }

    /** Groups payments whose amounts are within max(Rs 1, 3%) of the cluster's smallest amount. */
    private fun clusterByAmount(points: List<DebitPoint>): List<List<DebitPoint>> {
        val clusters = mutableListOf<MutableList<DebitPoint>>()
        for (p in points.sortedBy { it.amount }) {
            val current = clusters.lastOrNull()
            if (current != null) {
                val anchor = current.first().amount
                if (p.amount - anchor <= max(1.0, anchor * 0.03)) {
                    current.add(p)
                    continue
                }
            }
            clusters.add(mutableListOf(p))
        }
        return clusters
    }

    /** Drops near-simultaneous duplicates (same charge seen via SMS and notification). */
    private fun dedupe(sorted: List<DebitPoint>): List<DebitPoint> {
        val kept = mutableListOf<DebitPoint>()
        for (p in sorted) {
            if (kept.isEmpty() || p.timestamp - kept.last().timestamp >= DEDUPE_WINDOW_MS) kept.add(p)
        }
        return kept
    }

    private fun normalizeKey(payee: String): String =
        payee.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    private fun coefficientOfVariation(values: List<Double>): Double {
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }
}