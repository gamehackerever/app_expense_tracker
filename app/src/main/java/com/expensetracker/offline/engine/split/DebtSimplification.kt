package com.expensetracker.offline.engine.split

import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import java.util.PriorityQueue

object DebtSimplification {
    data class Transfer(val from: String, val to: String, val amountPaise: Long)

    /**
     * [outstandingByDebtId] is what is still unpaid per debt (amount owed minus linked repayments).
     * `amountOwed` itself never shrinks when someone repays, so a debt missing from the map falls back to
     * the full amount. Names are matched ignoring case and spaces: "Ravi" and "ravi " are one person.
     */
    fun simplifyTripDebts(
        transactions: List<TransactionWithDebts>,
        outstandingByDebtId: Map<Long, Long> = emptyMap()
    ): List<Transfer> {
        val netBalances = mutableMapOf<String, Long>()
        val displayNames = mutableMapOf<String, String>()

        fun keyOf(name: String): String {
            val trimmed = name.trim()
            val key = trimmed.lowercase()
            displayNames.putIfAbsent(key, trimmed)
            return key
        }

        // 1. Calculate net balance for every individual across all transactions
        transactions.forEach { txnWithDebts ->
            val payerKey = keyOf(txnWithDebts.transaction.paidBy ?: "You")

            txnWithDebts.debts.filter { it.settledAt == null }.forEach { debt ->
                val debtorKey = keyOf(debt.debtorName)
                val amount = outstandingByDebtId[debt.id] ?: debt.amountOwed
                if (amount <= 0L || debtorKey == payerKey) return@forEach

                netBalances[payerKey] = (netBalances[payerKey] ?: 0L) + amount
                netBalances[debtorKey] = (netBalances[debtorKey] ?: 0L) - amount
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
            simplifiedTransfers.add(
                Transfer(displayNames[debtor.first] ?: debtor.first, displayNames[creditor.first] ?: creditor.first, amountToSettle)
            )

            val newDebtorBal = debtor.second + amountToSettle
            val newCreditorBal = creditor.second - amountToSettle

            if (newDebtorBal < 0) debtors.add(debtor.first to newDebtorBal)
            if (newCreditorBal > 0) creditors.add(creditor.first to newCreditorBal)
        }

        return simplifiedTransfers
    }
}