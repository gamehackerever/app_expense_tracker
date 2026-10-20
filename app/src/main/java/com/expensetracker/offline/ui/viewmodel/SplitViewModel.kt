package com.expensetracker.offline.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.ui.components.SplitParticipant
import com.expensetracker.offline.ui.dashboard.SplitLinkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.round

class SplitViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ExpenseTrackerApp).repository

    val unsettledSplitsWithDebts = repository.unsettledSplitsWithDebts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allTransactionsWithDebts: StateFlow<List<TransactionWithDebts>> = repository.allTransactionsWithDebts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val splitLinkInfo: StateFlow<SplitLinkInfo> = allTransactionsWithDebts.map { list ->
        val creditsWithLinks = ArrayList<TransactionEntity>()
        for (i in 0 until list.size) {
            val item = list[i]
            val txn = item.transaction
            if (txn.type == com.expensetracker.offline.data.local.entity.TransactionType.CREDIT && txn.linkedDebtId != null) {
                creditsWithLinks.add(txn)
            }
        }
        val linkedCreditsByDebtId = HashMap<Long, List<TransactionEntity>>()
        val linkedDebtIdByCreditId = HashMap<Long, Long>()
        for (i in 0 until creditsWithLinks.size) {
            val txn = creditsWithLinks[i]
            val debtId = txn.linkedDebtId!!
            val subList = (linkedCreditsByDebtId[debtId] as? MutableList<TransactionEntity>) ?: ArrayList<TransactionEntity>().also { linkedCreditsByDebtId[debtId] = it }
            subList.add(txn)
            linkedDebtIdByCreditId[txn.id] = debtId
        }
        SplitLinkInfo(linkedCreditsByDebtId, linkedDebtIdByCreditId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SplitLinkInfo(emptyMap(), emptyMap()))

    val totalMoneyOwed: StateFlow<Long?> = repository.totalMoneyOwed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val totalMoneyYouOwe: StateFlow<Long?> = repository.totalMoneyYouOwe
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun saveMultiSplit(
        transactionId: Long,
        participants: List<SplitParticipant>,
        paidBy: String = "You"
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPayer = paidBy.trim().ifBlank { "You" }
            val isPaidByMe = normalizedPayer.equals("You", ignoreCase = true)
            val debtsToSave = participants
                .filter { p ->
                    val amt = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                    if (amt <= 0.0) false
                    else if (isPaidByMe) !p.isMe
                    else {
                        val pName = if (p.isMe) "You" else p.name.trim()
                        !pName.equals(normalizedPayer, ignoreCase = true)
                    }
                }
                .map { p ->
                    val rupees = p.exactAmountStr.replace(",", "").toDoubleOrNull() ?: 0.0
                    val amtPaise = round(rupees * 100.0).toLong()
                    val debtorName = if (p.isMe) "You" else p.name.trim()
                    SplitDebtEntity(
                        id = p.debtId ?: 0L,
                        transactionId = transactionId,
                        debtorName = debtorName,
                        originalAmount = amtPaise,
                        amountOwed = amtPaise
                    )
                }
            repository.saveMultiSplit(transactionId, debtsToSave, normalizedPayer)
        }
    }

    fun markDebtSettled(debtId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markDebtAsSettled(debtId, System.currentTimeMillis())
        }
    }

    fun forgiveDebt(debtId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.forgiveDebt(debtId, System.currentTimeMillis())
        }
    }

    fun logCashRepayment(debtId: Long, amount: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logCashRepayment(debtId, amount, System.currentTimeMillis())
        }
    }

    fun linkDebtToRepayment(debtId: Long, creditId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.linkDebtToRepayment(debtId, creditId)
        }
    }

    fun resetSplit(transactionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetSplit(transactionId)
        }
    }

    fun undoRepayment(creditTxn: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.undoRepayment(creditTxn)
        }
    }
}
