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
}
