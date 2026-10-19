package com.expensetracker.offline.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object BillingCycleHelper {

    // Pre-compile formatters to save CPU cycles (DateTimeFormatter is completely thread-safe)
    private val START_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault())
    private val CYCLE_LABEL_FORMATTER = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())

    /**
     * Core logic to determine the LocalDate boundaries of the active billing cycle.
     * Start day is clamped to 1..28 to safely avoid February leap year edge cases.
     */
    private fun getCycleDates(startDay: Int, referenceDate: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
        val clampedDay = startDay.coerceIn(1, 28)

        // Because clampedDay is <= 28, withDayOfMonth() is 100% safe without length checks.
        val cycleStart = if (referenceDate.dayOfMonth >= clampedDay) {
            referenceDate.withDayOfMonth(clampedDay)
        } else {
            referenceDate.minusMonths(1).withDayOfMonth(clampedDay)
        }

        // The cycle ends exactly one day before the next cycle begins.
        val cycleEnd = cycleStart.plusMonths(1).minusDays(1)

        return Pair(cycleStart, cycleEnd)
    }

    /**
     * Returns Pair(startMillis, endMillis) for the active billing cycle.
     * endMillis strictly bounds to 23:59:59.999 of the cycle end date.
     */
    fun getCycleRange(startDay: Int, referenceDate: LocalDate = LocalDate.now()): Pair<Long, Long> {
        return getCycleRangeForOffset(startDay, cycleOffset = 0, referenceDate = referenceDate)
    }

    /**
     * Returns Pair(startMillis, endMillis) for a billing cycle shifted by [cycleOffset] months
     * (0 = current cycle, -1 = previous cycle, etc.).
     * Shifting the start LocalDate first and then deriving cycleEnd avoids Calendar.MONTH truncation
     * bugs when moving between 30-day and 31-day months (e.g. Apr 30 -> Mar 31, not Mar 30).
     */
    fun getCycleRangeForOffset(
        startDay: Int,
        cycleOffset: Int,
        referenceDate: LocalDate = LocalDate.now()
    ): Pair<Long, Long> {
        val (baseStart, _) = getCycleDates(startDay, referenceDate)
        val shiftedStart = if (cycleOffset == 0) baseStart else baseStart.plusMonths(cycleOffset.toLong())
        val shiftedEnd = shiftedStart.plusMonths(1).minusDays(1)
        val zone = ZoneId.systemDefault()

        val startMillis = shiftedStart.atStartOfDay(zone).toInstant().toEpochMilli()
        // Safe end-of-day calculation: Start of the next day minus 1 millisecond.
        val endMillis = shiftedEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L

        return Pair(startMillis, endMillis)
    }

    fun getCycleStartDateFormatted(startDay: Int, referenceDate: LocalDate = LocalDate.now()): String {
        val (cycleStart, _) = getCycleDates(startDay, referenceDate)
        return cycleStart.format(START_DATE_FORMATTER)
    }

    fun getCycleLabel(startDay: Int, referenceDate: LocalDate = LocalDate.now()): String {
        val (cycleStart, cycleEnd) = getCycleDates(startDay, referenceDate)
        val s = cycleStart.format(CYCLE_LABEL_FORMATTER)
        val e = cycleEnd.format(CYCLE_LABEL_FORMATTER)
        return "$s – $e"
    }
}