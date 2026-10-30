package com.expensetracker.offline

import com.expensetracker.offline.data.local.dao.TransactionDao.DebitPoint
import com.expensetracker.offline.engine.recurring.RecurringPaymentDetector
import org.junit.Assert.*
import org.junit.Test

class RecurringPaymentDetectorTest {
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L
    private fun dp(id: Long, payee: String, amount: Long, daysAgo: Int) =
        DebitPoint(id, payee, amount, now - daysAgo * day, "Subscriptions")

    @Test fun sameBrandUnderTwoSpellingsIsOneSubscription() {
        val result = RecurringPaymentDetector.detect(
            listOf(
                dp(1, "Spotify India Pvt Ltd", 6900, 74), dp(2, "Spotify India Pvt Ltd", 6900, 43),
                dp(3, "Spotify", 6800, 40), dp(4, "Spotify", 6800, 10)
            ), now
        )
        assertEquals(1, result.size)
    }

    @Test fun groupKeyIgnoresCompanySuffixes() {
        assertEquals(
            RecurringPaymentDetector.groupKey("Spotify India Pvt Ltd"),
            RecurringPaymentDetector.groupKey("SPOTIFY")
        )
    }
}
