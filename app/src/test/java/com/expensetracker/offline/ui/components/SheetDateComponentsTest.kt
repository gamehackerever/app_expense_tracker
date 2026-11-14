package com.expensetracker.offline.ui.components

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetDateComponentsTest {
    @Test
    fun choosingDatePreservesTheSelectedLocalTime() {
        val pickedDateUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 16, 0, 0, 0)
        }.timeInMillis
        val existingLocalTime = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 10, 18, 42, 0)
        }.timeInMillis

        val selected = Calendar.getInstance().apply {
            timeInMillis = sheetDateWithExistingTime(pickedDateUtc, existingLocalTime)
        }

        assertEquals(2026, selected.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, selected.get(Calendar.MONTH))
        assertEquals(16, selected.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, selected.get(Calendar.HOUR_OF_DAY))
        assertEquals(42, selected.get(Calendar.MINUTE))
        assertEquals(0, selected.get(Calendar.SECOND))
    }
}
