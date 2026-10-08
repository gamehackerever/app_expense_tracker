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
        val (cycleStart, cycleEnd) = getCycleDates(startDay, referenceDate)
        val zone = ZoneId.systemDefault()

        val startMillis = cycleStart.atStartOfDay(zone).toInstant().toEpochMilli()

        // Safe end-of-day calculation: Start of the next day minus 1 millisecond.
        // This avoids nanosecond precision loss bugs when converting LocalTime.MAX to millis.
        val endMillis = cycleEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L

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