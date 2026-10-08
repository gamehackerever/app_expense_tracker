package com.expensetracker.offline.engine.split

import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import java.util.PriorityQueue

object DebtSimplification {
    data class Transfer(val from: String, val to: String, val amountPaise: Long)

    fun simplifyTripDebts(transactions: List<TransactionWithDebts>): List<Transfer> {
        val netBalances = mutableMapOf<String, Long>()

        // 1. Calculate net balance for every individual across all transactions
        transactions.forEach { txnWithDebts ->
            val payer = txnWithDebts.transaction.paidBy ?: "You"

            txnWithDebts.debts.filter { it.settledAt == null }.forEach { debt ->
                val debtor = debt.debtorName
                val amount = debt.amountOwed.toLong()

                netBalances[payer] = (netBalances[payer] ?: 0L) + amount
                netBalances[debtor] = (netBalances[debtor] ?: 0L) - amount
            }
        }

        // 2. Separate into Debtors (-) and Creditors (+)
        val debtors = PriorityQueue<Pair<String, Long>>(compareBy { it.second })
        val creditors = PriorityQueue<Pair<String, Long>>(compareByDescending { it.second })

        netBalances.forEach { (person, balance) ->
            if (balance < 0) debtors.add(person to balance)
            else if (balance > 0) creditors.add(person to balance)
        }

        // 3. Greedily match largest debtor to largest creditor
        val simplifiedTransfers = mutableListOf<Transfer>()
        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val debtor = debtors.poll()!!
            val creditor = creditors.poll()!!

            val amountToSettle = minOf(-debtor.second, creditor.second)
            simplifiedTransfers.add(Transfer(debtor.first, creditor.first, amountToSettle))

            val newDebtorBal = debtor.second + amountToSettle
            val newCreditorBal = creditor.second - amountToSettle

            if (newDebtorBal < 0) debtors.add(debtor.first to newDebtorBal)
            if (newCreditorBal > 0) creditors.add(creditor.first to newCreditorBal)
        }

        return simplifiedTransfers
    }
}