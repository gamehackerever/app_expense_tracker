package com.expensetracker.offline.util

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class BillingCycleHelperTest {

    @Test fun cycleStartingOn27thBelongsToTheMonthWithMostDays() {
        // 27 Sep - 26 Oct has 26 days in Oct
        assertEquals(YearMonth.of(2026, 10), BillingCycleHelper.getCycleDominantMonth(27, 0, LocalDate.of(2026, 10, 25)))
        // 27 Oct - 26 Nov has only 5 days in Oct, so it is November
        assertEquals(YearMonth.of(2026, 11), BillingCycleHelper.getCycleDominantMonth(27, 0, LocalDate.of(2026, 10, 27)))
    }

    @Test fun nextAndFollowingCyclesRollOverTheYear() {
        val ref = LocalDate.of(2026, 12, 30)
        assertEquals(YearMonth.of(2027, 1), BillingCycleHelper.getCycleDominantMonth(27, 0, ref))
        assertEquals(YearMonth.of(2027, 2), BillingCycleHelper.getCycleDominantMonth(27, 1, ref))
        assertEquals(YearMonth.of(2027, 3), BillingCycleHelper.getCycleDominantMonth(27, 2, ref))
    }

    @Test fun firstOfMonthCycleIsTheCalendarMonth() {
        assertEquals(YearMonth.of(2026, 11), BillingCycleHelper.getCycleDominantMonth(1, 1, LocalDate.of(2026, 10, 25)))
    }

    @Test fun offsetsPreviousCycles() {
        assertEquals(YearMonth.of(2026, 9), BillingCycleHelper.getCycleDominantMonth(27, -1, LocalDate.of(2026, 10, 25)))
    }

    @Test fun yearRangeForCurrentAndOffsets() {
        val ref = LocalDate.of(2026, 11, 19)
        val zone = java.time.ZoneId.systemDefault()

        // Current Year 2026
        val (start2026, end2026) = BillingCycleHelper.getYearRangeForOffset(0, ref)
        val expectedStart2026 = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedEnd2026 = LocalDate.of(2026, 12, 31).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        assertEquals(expectedStart2026, start2026)
        assertEquals(expectedEnd2026, end2026)

        // Previous Year 2025
        val (start2025, end2025) = BillingCycleHelper.getYearRangeForOffset(-1, ref)
        val expectedStart2025 = LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedEnd2025 = LocalDate.of(2025, 12, 31).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        assertEquals(expectedStart2025, start2025)
        assertEquals(expectedEnd2025, end2025)
    }
}
