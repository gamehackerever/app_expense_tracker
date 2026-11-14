package com.expensetracker.offline.engine.validation

import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionIntegrityMonitorTest {
    private val now = 1_800_000_000_000L
    private val history = listOf(1, 2, 3).map { offset ->
        TransactionEntity(
            amount = 6_900L,
            payee = "Spotify",
            timestamp = now - offset * DAY_MS,
            type = TransactionType.DEBIT,
            source = TransactionSource.SMS,
            referenceId = "REF-$offset",
            rawContent = "Completed debit"
        )
    }

    @Test
    fun flagsAnAmountThatIsFarOutsideMerchantHistory() {
        val reason = TransactionIntegrityMonitor.assess(
            amount = 200_000L,
            payee = "SPOTIFY",
            type = TransactionType.DEBIT,
            source = TransactionSource.NOTIFICATION,
            timestamp = now,
            recentTransactions = history,
            now = now
        )

        assertEquals(true, reason?.contains("much higher") == true)
    }

    @Test
    fun ordinaryChangeAndShortHistoryDoNotCreateNoise() {
        assertNull(TransactionIntegrityMonitor.assess(
            amount = 7_500L,
            payee = "Spotify",
            type = TransactionType.DEBIT,
            source = TransactionSource.SMS,
            timestamp = now,
            recentTransactions = history,
            now = now
        ))
        assertNull(TransactionIntegrityMonitor.assess(
            amount = 200_000L,
            payee = "Spotify",
            type = TransactionType.DEBIT,
            source = TransactionSource.SMS,
            timestamp = now,
            recentTransactions = history.take(2),
            now = now
        ))
    }

    @Test
    fun futureCaptureIsFlaggedButManualEntryIsNot() {
        val reason = TransactionIntegrityMonitor.assess(
            amount = 6_900L,
            payee = "Spotify",
            type = TransactionType.DEBIT,
            source = TransactionSource.NOTIFICATION,
            timestamp = now + 10 * 60_000L,
            recentTransactions = emptyList(),
            now = now
        )
        assertEquals(true, reason?.contains("date is in the future") == true)

        assertNull(TransactionIntegrityMonitor.assess(
            amount = 6_900L,
            payee = "Spotify",
            type = TransactionType.DEBIT,
            source = TransactionSource.MANUAL,
            timestamp = now + 10 * 60_000L,
            recentTransactions = emptyList(),
            now = now
        ))
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
