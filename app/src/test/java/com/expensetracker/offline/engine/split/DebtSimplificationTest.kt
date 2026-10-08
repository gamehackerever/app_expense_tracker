package com.expensetracker.offline.engine.split

import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtSimplificationTest {

    private fun bill(id: Long, paidBy: String?, vararg debts: SplitDebtEntity) = TransactionWithDebts(
        transaction = TransactionEntity(
            id = id, amount = 100_000L, payee = "Dinner", timestamp = 1L, type = TransactionType.DEBIT,
            source = TransactionSource.MANUAL, referenceId = null, rawContent = "", paidBy = paidBy
        ),
        debts = debts.toList()
    )

    private fun debt(id: Long, txnId: Long, name: String, paise: Long, settledAt: Long? = null) =
        SplitDebtEntity(id = id, transactionId = txnId, debtorName = name, amountOwed = paise, settledAt = settledAt)

    @Test
    fun partialRepayment_isSubtracted() {
        val bills = listOf(bill(1, "You", debt(10, 1, "Ravi", 50_000L)))
        // Ravi already paid back Rs 300, so only Rs 200 is outstanding.
        val result = DebtSimplification.simplifyTripDebts(bills, outstandingByDebtId = mapOf(10L to 20_000L))
        assertEquals(listOf(DebtSimplification.Transfer("Ravi", "You", 20_000L)), result)
    }

    @Test
    fun withoutOutstandingMap_fullAmountIsUsed() {
        val bills = listOf(bill(1, "You", debt(10, 1, "Ravi", 50_000L)))
        val result = DebtSimplification.simplifyTripDebts(bills)
        assertEquals(listOf(DebtSimplification.Transfer("Ravi", "You", 50_000L)), result)
    }

    @Test
    fun sameNameWithDifferentCase_isOnePerson() {
        val bills = listOf(
            bill(1, "You", debt(10, 1, "Ravi", 30_000L)),
            bill(2, "You", debt(11, 2, "ravi ", 20_000L))
        )
        val result = DebtSimplification.simplifyTripDebts(bills)
        assertEquals(listOf(DebtSimplification.Transfer("Ravi", "You", 50_000L)), result)
    }

    @Test
    fun settledDebtsAndZeroOutstandingAreIgnored() {
        val bills = listOf(
            bill(1, "You", debt(10, 1, "Ravi", 30_000L, settledAt = 5L)),
            bill(2, "You", debt(11, 2, "Anu", 20_000L))
        )
        val result = DebtSimplification.simplifyTripDebts(bills, outstandingByDebtId = mapOf(11L to 0L))
        assertTrue(result.isEmpty())
    }

    @Test
    fun chainOfDebts_collapsesToOneTransfer() {
        // A owes B 100, B owes C 100  ->  A just pays C 100.
        val bills = listOf(
            bill(1, "B", debt(10, 1, "A", 10_000L)),
            bill(2, "C", debt(11, 2, "B", 10_000L))
        )
        val result = DebtSimplification.simplifyTripDebts(bills)
        assertEquals(listOf(DebtSimplification.Transfer("A", "C", 10_000L)), result)
    }

    @Test
    fun payerOwingThemselves_isIgnored() {
        val bills = listOf(bill(1, "Ravi", debt(10, 1, "ravi", 10_000L)))
        assertTrue(DebtSimplification.simplifyTripDebts(bills).isEmpty())
    }
}
