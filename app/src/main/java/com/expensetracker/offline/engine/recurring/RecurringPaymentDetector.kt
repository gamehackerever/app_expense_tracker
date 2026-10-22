package com.expensetracker.offline.engine.recurring

import com.expensetracker.offline.data.local.dao.TransactionDao.DebitPoint
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How often a payment repeats.
 *
 * Upgraded to detect 28-day telecom recharges, bi-weekly cycles, 2-charge monthly subscriptions,
 * and first-week known subscription/recharge merchants.
 */
enum class Cadence(
    val label: String,
    val days: Int,
    val toleranceDays: Int,
    val minOccurrences: Int,
    val perMonth: Double,
    val requiresFixedAmount: Boolean
) {
    WEEKLY("Weekly", 7, 2, 3, 52.0 / 12.0, true),
    BIWEEKLY("Every 2 weeks", 14, 3, 2, 26.0 / 12.0, true),
    RECHARGE_28D("Every 28 days", 28, 3, 2, 30.4375 / 28.0, false),
    MONTHLY("Monthly", 30, 5, 2, 1.0, false),
    BIMONTHLY("Every 2 months", 61, 7, 2, 0.5, false),
    QUARTERLY("Quarterly", 91, 10, 2, 1.0 / 3.0, false),
    YEARLY("Yearly", 365, 20, 2, 1.0 / 12.0, true)
}

data class RecurringPayment(
    val payee: String,
    val category: String,
    val amount: Long,            // typical (latest for fixed, median for variable) charge in paise
    val isFixedAmount: Boolean,  // false = varies, e.g. a utility bill
    val cadence: Cadence,
    val occurrences: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val nextExpected: Long,
    val isActive: Boolean,       // false = overdue by a lot, probably cancelled
    val monthlyCost: Long,
    val confidence: Double,      // 0..1, grows with history and regularity
    val transactionIds: List<Long>
)

object RecurringPaymentDetector {

    private const val DAY_MS = 86_400_000L
    private const val DEDUPE_WINDOW_MS = 36L * 3_600_000L   // SMS + notification for the same charge
    private const val MIN_MATCH_RATIO = 0.70                // share of gaps that must fit the cadence
    private const val MAX_VARIABLE_CV = 0.60                // amount spread allowed for non-fixed series
    private const val ONE_RUPEE_PAISE = 100.0               // Rs 1 tolerance in paise

    /** Fetch this much history from the DB; yearly payments need > 12 months. */
    const val LOOKBACK_MS = 460L * DAY_MS

    // Known subscription / telecom / utility brands that can be recognized even in the first week of use
    private val KNOWN_SUBSCRIPTION_REGEX = Regex(
        "(?i)\\b(spotify|netflix|youtube|prime video|hotstar|jiocinema|sony liv|apple|icloud|" +
                "google play|google one|google photos|chatgpt|openai|claude|canva|notion|github|" +
                "vi\\s*recharge|vodafone|jio\\s*recharge|airtel|bsnl|kseb|bescom|broadband|wifi|gym|cult\\s*fit)\\b"
    )
    private val RECHARGE_28D_REGEX = Regex("(?i)\\b(vi\\s*recharge|vodafone|jio|airtel|bsnl|prepaid|recharge)\\b")

    fun detect(debits: List<DebitPoint>, now: Long = System.currentTimeMillis()): List<RecurringPayment> {
        val ignoredGenericPayees = setOf(
            "unknown", "unspecified", "upi payment", "upi received", "bank transfer",
            "bank credit", "card spend", "atm withdrawal", "self transfer",
            "silent deduction", "cash", "manual entry", "split settlement"
        )
        val byPayee = debits
            .filter { it.amount > 0L }
            .groupBy { normalizeKey(it.payee) }
            .filterKeys { it.isNotBlank() && it !in ignoredGenericPayees }

        val results = mutableListOf<RecurringPayment>()

        for ((_, group) in byPayee) {
            if (group.size >= 2) {
                // 1. Fixed-amount series: same payee AND (nearly) same amount.
                // Pick the strongest active series per payee so duplicate payee keys never crash LazyColumn.
                val bestFixedHit = clusterByAmount(group)
                    .mapNotNull { evaluate(it, fixed = true, now = now) }
                    .maxWithOrNull(
                        compareBy<RecurringPayment> { it.isActive }
                            .thenBy { it.occurrences }
                            .thenBy { it.lastSeen }
                    )
                if (bestFixedHit != null) {
                    results += bestFixedHit
                    continue
                }

                // 2. Variable-amount series: utility-style bills.
                val variableHit = evaluate(group, fixed = false, now = now)
                if (variableHit != null) {
                    results += variableHit
                    continue
                }
            }

            // 3. First-week intelligence: single-occurrence known subscription / telecom recharge
            val latest = group.maxByOrNull { it.timestamp } ?: continue
            if (KNOWN_SUBSCRIPTION_REGEX.containsMatchIn(latest.payee)) {
                val isRecharge = RECHARGE_28D_REGEX.containsMatchIn(latest.payee)
                val cadence = if (isRecharge) Cadence.RECHARGE_28D else Cadence.MONTHLY
                val nextExpected = computeNextExpected(latest.timestamp, cadence, cadence.days.toDouble())
                val graceDays = max(cadence.toleranceDays.toDouble(), cadence.days / 2.0)
                val isActive = now <= nextExpected + (graceDays * DAY_MS).toLong()
                if (isActive) {
                    results += RecurringPayment(
                        payee = latest.payee,
                        category = latest.category,
                        amount = latest.amount,
                        isFixedAmount = true,
                        cadence = cadence,
                        occurrences = group.size,
                        firstSeen = group.minOf { it.timestamp },
                        lastSeen = latest.timestamp,
                        nextExpected = nextExpected,
                        isActive = true,
                        monthlyCost = (latest.amount * cadence.perMonth).toLong(),
                        confidence = 0.65,
                        transactionIds = group.map { it.id }
                    )
                }
            }
        }

        return results
            .distinctBy { normalizeKey(it.payee) }
            .sortedWith(
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

        val amounts = series.map { it.amount.toDouble() }
        val cv = coefficientOfVariation(amounts)
        if (!fixed && cv > MAX_VARIABLE_CV) return null
        // For 2-occurrence non-fixed series, require tighter amount consistency (CV <= 0.25)
        if (series.size == 2 && !fixed && cv > 0.25) return null

        val gaps = series.zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() / DAY_MS }

        val (cadence, ratio) = Cadence.entries
            .filter { series.size >= it.minOccurrences && (fixed || !it.requiresFixedAmount) }
            .map { it to matchRatio(gaps, it) }
            .filter { it.second >= MIN_MATCH_RATIO }
            .maxByOrNull { it.second }
            ?: return null

        val fittingGaps = gaps.filter { abs(it - cadence.days) <= cadence.toleranceDays }
        val stepDays = if (fittingGaps.isNotEmpty()) median(fittingGaps) else cadence.days.toDouble()

        val last = series.last()
        val nextExpected = computeNextExpected(last.timestamp, cadence, stepDays)
        val graceDays = max(cadence.toleranceDays.toDouble(), stepDays / 2.0)
        val isActive = now <= nextExpected + (graceDays * DAY_MS).toLong()

        // For fixed-amount subscriptions/recharges (or 2-point series), use the latest charge amount
        // so e.g. [₹399, ₹398] displays the current ₹398 plan price instead of averaging to ₹398.50 (₹399).
        val typicalAmount = if (fixed || series.size <= 2) last.amount else median(amounts).toLong()
        val confidence = ratio *
                (if (fixed) 1.0 else 0.85) *
                min(1.0, series.size / (cadence.minOccurrences + 1.5))

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
     * Uses real calendar month/year addition for calendar cadences (so a monthly bill paid on 17 Oct
     * is expected on 17 Nov rather than 16 Nov due to 31 days in October), while using exact day steps
     * for N-day cadences like 28-day prepaid telecom recharges.
     */
    private fun computeNextExpected(lastTimestamp: Long, cadence: Cadence, stepDays: Double): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = lastTimestamp }
        return when (cadence) {
            Cadence.MONTHLY -> {
                cal.add(Calendar.MONTH, 1)
                cal.timeInMillis
            }
            Cadence.BIMONTHLY -> {
                cal.add(Calendar.MONTH, 2)
                cal.timeInMillis
            }
            Cadence.QUARTERLY -> {
                cal.add(Calendar.MONTH, 3)
                cal.timeInMillis
            }
            Cadence.YEARLY -> {
                cal.add(Calendar.YEAR, 1)
                cal.timeInMillis
            }
            Cadence.RECHARGE_28D -> lastTimestamp + 28L * DAY_MS
            Cadence.BIWEEKLY -> lastTimestamp + 14L * DAY_MS
            Cadence.WEEKLY -> lastTimestamp + 7L * DAY_MS
        }
    }

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

    private fun clusterByAmount(points: List<DebitPoint>): List<List<DebitPoint>> {
        val clusters = mutableListOf<MutableList<DebitPoint>>()
        for (p in points.sortedBy { it.amount }) {
            val current = clusters.lastOrNull()
            if (current != null) {
                val anchor = current.first().amount.toDouble()
                if (p.amount - anchor <= max(ONE_RUPEE_PAISE, anchor * 0.03)) {
                    current.add(p)
                    continue
                }
            }
            clusters.add(mutableListOf(p))
        }
        return clusters
    }

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