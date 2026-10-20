package com.expensetracker.offline.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.util.BalanceCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ExpenseTrackerApp).repository

    val pendingReviewItems: StateFlow<List<ReviewItemEntity>> = repository.pendingReviewItems
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingReviewCount: StateFlow<Int> = repository.pendingReviewCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun resolveReview(
        reviewItem: ReviewItemEntity,
        amount: Long,
        payee: String,
        type: TransactionType,
        category: String,
        bankName: String? = null,
        accountNumber: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val needsParse = reviewItem.bankName == null && reviewItem.balance == null && reviewItem.referenceId == null
            val parsed = if (needsParse) FinancialParser.parse(reviewItem.rawContent, reviewItem.sender, reviewItem.source) else null
            val bank = bankName ?: reviewItem.bankName ?: parsed?.bankName
            val account = accountNumber ?: reviewItem.accountNumber ?: parsed?.accountNumber
            val balance = reviewItem.balance ?: parsed?.balance

            val newTransaction = TransactionEntity(
                amount = amount,
                payee = payee,
                type = type,
                category = category,
                timestamp = reviewItem.timestamp,
                rawContent = reviewItem.rawContent,
                bankName = bank,
                accountNumber = account,
                balance = balance,
                referenceId = reviewItem.referenceId ?: parsed?.referenceId,
                note = parsed?.note,
                source = reviewItem.source,
                itemsSummary = null,
                receiptImageUri = null,
                excludeFromSpend = parsed?.excludeFromSpend ?: category.equals("Transfer", ignoreCase = true)
            )
            repository.approveReviewItem(newTransaction, reviewItem.id)
            repository.learnCategoryRule(payee, category)

            balance?.let {
                val context = getApplication<Application>().applicationContext
                BalanceCache.updateIfNewer(BalanceCache.prefs(context), bank, account, it, reviewItem.timestamp)
            }
        }
    }

    fun discardReview(itemId: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.discardReviewItem(itemId) }
    }

    fun bulkResolveReviewItems(
        items: List<ReviewItemEntity>,
        onDone: (approved: Int, skipped: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var approved = 0
            var skipped = 0
            val approvals = mutableListOf<Pair<TransactionEntity, Long>>()
            val context = getApplication<Application>().applicationContext
            val prefs = BalanceCache.prefs(context)

            items.sortedBy { it.timestamp }.forEach { item ->
                val needsParse = item.bankName == null && item.balance == null && item.referenceId == null && item.type == null
                val parsed = if (needsParse) FinancialParser.parse(item.rawContent, item.sender) else null
                val amt = item.extractedPartialAmount ?: parsed?.amount ?: 0L
                val payee = item.extractedPartialPayee ?: parsed?.payee ?: "Unknown"
                val type = item.type ?: parsed?.type

                if (amt > 0 && type != null) {
                    val category = repository.classifyCategory(payee, item.rawContent)
                    val bank = item.bankName ?: parsed?.bankName
                    val account = item.accountNumber ?: parsed?.accountNumber
                    val balance = item.balance ?: parsed?.balance

                    val newTransaction = TransactionEntity(
                        amount = amt,
                        payee = payee,
                        type = type,
                        category = category,
                        timestamp = item.timestamp,
                        rawContent = item.rawContent,
                        bankName = bank,
                        accountNumber = account,
                        balance = balance,
                        referenceId = item.referenceId ?: parsed?.referenceId,
                        note = parsed?.note,
                        source = item.source,
                        itemsSummary = null,
                        receiptImageUri = null,
                        excludeFromSpend = parsed?.excludeFromSpend ?: false
                    )

                    approvals.add(newTransaction to item.id)

                    balance?.let {
                        BalanceCache.updateIfNewer(prefs, bank, account, it, item.timestamp)
                    }
                    approved++
                } else {
                    skipped++
                }
            }

            repository.bulkApproveReviewItems(approvals)

            withContext(Dispatchers.Main) { onDone(approved, skipped) }
        }
    }

    fun bulkDiscardReviewItems(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { repository.discardReviewItem(it) }
        }
    }
}
