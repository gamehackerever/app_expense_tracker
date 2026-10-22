package com.expensetracker.offline.engine.recurring

import com.expensetracker.offline.data.local.dao.TransactionDao.DebitPoint
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How often a payment repeats.
 *
 * Detects 28-day telecom recharges, monthly subscriptions/bills, bi-weekly, bi-monthly,
 * quarterly, and yearly commitments while filtering out high-frequency casual daily habits.
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
    BIWEEKLY("Every 2 weeks", 14, 2, 3, 26.0 / 12.0, true),
    MONTHLY("Monthly", 30, 5, 2, 1.0, false),
    RECHARGE_28D("Every 28 days", 28, 3, 2, 30.4375 / 28.0, false),
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
    private const val SAME_CHARGE_DEDUPE_MS = 12L * 3_600_000L // SMS + notification for the same charge
    private const val SAME_DAY_COLLAPSE_MS = 20L * 3_600_000L
    private const val MIN_MATCH_RATIO = 0.72                   // share of gaps that must fit the cadence
    private const val MAX_VARIABLE_CV = 0.55                   // amount spread allowed for non-fixed series
    private const val ONE_RUPEE_PAISE = 100.0                  // Rs 1 tolerance in paise

    /** Fetch this much history from the DB; yearly payments need > 12 months. */
    const val LOOKBACK_MS = 460L * DAY_MS

    // Known subscription / telecom / utility brands that can be recognized even in the first week of use
    private val KNOWN_SUBSCRIPTION_REGEX = Regex(
        "(?i)\\b(spotify|netflix|youtube|prime video|amazon prime|hotstar|jiocinema|sony liv|zee5|apple|icloud|" +
                "google play|google one|google photos|chatgpt|openai|claude|canva|notion|github|microsoft|" +
                "vi\\s*recharge|vodafone|jio\\s*recharge|jio\\s*prepaid|jiofiber|airtel|bsnl|act\\s*fibernet|" +
                "kseb|ksebl|bescom|tneb|msedcl|broadband|wifi|gym|cult\\s*fit|lic|sip|mutual\\s*fund|zerodha|groww)\\b"
    )
    private val RECHARGE_28D_REGEX = Regex(
        "(?i)\\b(vi\\s*recharge|vodafone|jio\\s*prepaid|jio\\s*recharge|airtel\\s*prepaid|bsnl\\s*prepaid|prepaid|recharge)\\b"
    )

    private val COMMITMENT_CATEGORIES = setOf(
        "subscriptions",
        "subscription",
        "recharge & telecom",
        "bills & utilities",
        "rent & housing",
        "emi & loans",
        "investments",
        "education",
        "household & services"
    )

    private val CASUAL_EVERYDAY_CATEGORIES = setOf(
        "food & dining",
        "groceries",
        "fuel & fastag",
        "travel",
        "transport",
        "shopping",
        "gifts & festivals"
    )

    fun detect(debits: List<DebitPoint>, now: Long = System.currentTimeMillis()): List<RecurringPayment> {
        val ignoredGenericPayees = setOf(
            "unknown", "unspecified", "upi payment", "upi received", "bank transfer",
            "bank credit", "card spend", "atm withdrawal", "self transfer",
            "silent deduction", "cash", "manual entry", "split settlement", "transfer"
        )
        val byPayee = debits
            .filter {
                it.amount > 0L &&
                    !it.category.equals("Transfer", ignoreCase = true) &&
                    it.category != "Split Settlement"
            }
            .groupBy { normalizeKey(it.payee) }
            .filterKeys { it.isNotBlank() && it !in ignoredGenericPayees }

        val results = mutableListOf<RecurringPayment>()

        for ((_, rawGroup) in byPayee) {
            val group = dedupeSameCharge(rawGroup.sortedBy { it.timestamp })
            if (group.isEmpty()) continue

            val latest = group.last()
            val isKnownBrand = KNOWN_SUBSCRIPTION_REGEX.containsMatchIn(latest.payee)
            val isCommitmentCategory = latest.category.trim().lowercase(Locale.ROOT) in COMMITMENT_CATEGORIES
            val isCasualCategory = !isKnownBrand &&
                latest.category.trim().lowercase(Locale.ROOT) in CASUAL_EVERYDAY_CATEGORIES

            // Guard against high-frequency everyday merchants (e.g. daily canteen, bus, chai, grocery shop)
            // being misclassified by cherry-picking 2 same-amount transactions a month apart.
            if (group.size >= 4 && !isKnownBrand && !isCommitmentCategory) {
                val allGaps = group.zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() / DAY_MS }
                val overallMedianGap = median(allGaps)
                if (overallMedianGap < 5.0) {
                    continue
                }
            }

            if (group.size >= 2) {
                // 1. Fixed-amount series: same payee AND (nearly) same amount.
                // Require the cluster to represent the dominant behavior of this payee (not a random 2-item subset of 10 visits).
                val bestFixedHit = clusterByAmount(group)
                    .filter { cluster ->
                        cluster.size >= 2 &&
                            (isKnownBrand || isCommitmentCategory || cluster.size * 2 >= group.size)
                    }
                    .mapNotNull { cluster ->
                        evaluate(
                            points = cluster,
                            totalPayeeVisits = group.size,
                            fixed = true,
                            isKnownBrand = isKnownBrand,
                            isCommitmentCategory = isCommitmentCategory,
                            isCasualCategory = isCasualCategory,
                            now = now
                        )
                    }
                    .maxWithOrNull(
                        compareBy<RecurringPayment> { it.isActive }
                            .thenBy { it.occurrences }
                            .thenBy { it.confidence }
                            .thenBy { it.lastSeen }
                    )
                if (bestFixedHit != null) {
                    results += bestFixedHit
                    continue
                }

                // 2. Variable-amount series: utility-style bills.
                val variableHit = evaluate(
                    points = group,
                    totalPayeeVisits = group.size,
                    fixed = false,
                    isKnownBrand = isKnownBrand,
                    isCommitmentCategory = isCommitmentCategory,
                    isCasualCategory = isCasualCategory,
                    now = now
                )
                if (variableHit != null) {
                    results += variableHit
                    continue
                }
            }

            // 3. First-week intelligence: single-occurrence known subscription / telecom recharge
            if (isKnownBrand) {
                val isRecharge = RECHARGE_28D_REGEX.containsMatchIn(latest.payee) ||
                    latest.category.equals("Recharge & Telecom", ignoreCase = true)
                val cadence = if (isRecharge) Cadence.RECHARGE_28D else Cadence.MONTHLY
                val nextExpected = computeNextExpected(latest.timestamp, cadence)
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
                        firstSeen = group.first().timestamp,
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

    private fun evaluate(
        points: List<DebitPoint>,
        totalPayeeVisits: Int,
        fixed: Boolean,
        isKnownBrand: Boolean,
        isCommitmentCategory: Boolean,
        isCasualCategory: Boolean,
        now: Long
    ): RecurringPayment? {
        val series = dedupeSameDay(points.sortedBy { it.timestamp })
        if (series.size < 2) return null

        // Casual everyday categories (Food, Groceries, Shopping, Fuel, Travel) require >= 3 periodic occurrences
        // unless the payee is a known subscription brand.
        if (series.size == 2 && isCasualCategory && !isKnownBrand) {
            return null
        }

        // For any 2-occurrence series that is NOT a known brand or commitment category,
        // require fixed amount >= ₹99 and no extra unrelated visits in between.
        if (series.size == 2 && !isKnownBrand && !isCommitmentCategory) {
            if (!fixed || series.last().amount < 99_00L || totalPayeeVisits > 2) {
                return null
            }
        }

        val amounts = series.map { it.amount.toDouble() }
        val cv = coefficientOfVariation(amounts)
        if (!fixed && cv > MAX_VARIABLE_CV) return null
        if (series.size == 2 && !fixed && cv > 0.22) return null

        val gaps = series.zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() / DAY_MS }
        val medianGap = median(gaps)

        val last = series.last()
        val isTelecomOrRecharge = RECHARGE_28D_REGEX.containsMatchIn(last.payee) ||
            last.category.equals("Recharge & Telecom", ignoreCase = true)

        val candidates = Cadence.entries.filter { c ->
            if (series.size < c.minOccurrences) return@filter false
            if (!fixed && c.requiresFixedAmount) return@filter false
            if (c == Cadence.RECHARGE_28D && !isTelecomOrRecharge) {
                // Only classify non-telecom merchants as 28-day cycle if >= 3 occurrences cluster tightly around 27.0..28.5d
                series.size >= 3 && medianGap in 26.8..28.5
            } else {
                true
            }
        }

        val bestMatch = candidates
            .map { c ->
                val ratio = matchRatio(gaps, c)
                // Tie-breaker: closer distance to nominal cadence days + preference for MONTHLY over RECHARGE_28D on non-telecom
                val distancePenalty = (abs(medianGap - c.days) / c.days.toDouble()) * 0.04
                val telecomBonus = if (c == Cadence.RECHARGE_28D && isTelecomOrRecharge && medianGap in 25.0..29.2) 0.03 else 0.0
                val monthlyBonus = if (c == Cadence.MONTHLY && !isTelecomOrRecharge) 0.02 else 0.0
                Triple(c, ratio, ratio - distancePenalty + telecomBonus + monthlyBonus)
            }
            .filter { it.second >= MIN_MATCH_RATIO }
            .maxByOrNull { it.third }
            ?: return null

        val cadence = bestMatch.first
        val ratio = bestMatch.second

        val fittingGaps = gaps.filter { abs(it - cadence.days) <= cadence.toleranceDays }
        val stepDays = if (fittingGaps.isNotEmpty()) median(fittingGaps) else cadence.days.toDouble()

        val nextExpected = computeNextExpected(last.timestamp, cadence)
        val graceDays = max(cadence.toleranceDays.toDouble(), stepDays / 2.0)
        val isActive = now <= nextExpected + (graceDays * DAY_MS).toLong()

        val typicalAmount = if (fixed || series.size <= 2) last.amount else median(amounts).toLong()
        val confidence = (ratio *
                (if (fixed) 1.0 else 0.85) *
                min(1.0, series.size / (cadence.minOccurrences + 1.5))).coerceIn(0.35, 0.99)

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
    private fun computeNextExpected(lastTimestamp: Long, cadence: Cadence): Long {
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

    /** Collapse duplicate SMS + Notification records for the exact same charge within 12 hours. */
    private fun dedupeSameCharge(sorted: List<DebitPoint>): List<DebitPoint> {
        val kept = mutableListOf<DebitPoint>()
        for (p in sorted) {
            val prev = kept.lastOrNull()
            if (prev != null &&
                p.timestamp - prev.timestamp < SAME_CHARGE_DEDUPE_MS &&
                abs(p.amount - prev.amount) <= 100L
            ) {
                continue
            }
            kept.add(p)
        }
        return kept
    }

    /** Within a candidate recurring series, collapse same-day retries/split parts within 20 hours. */
    private fun dedupeSameDay(sorted: List<DebitPoint>): List<DebitPoint> {
        val kept = mutableListOf<DebitPoint>()
        for (p in sorted) {
            if (kept.isEmpty() || p.timestamp - kept.last().timestamp >= SAME_DAY_COLLAPSE_MS) {
                kept.add(p)
            }
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
