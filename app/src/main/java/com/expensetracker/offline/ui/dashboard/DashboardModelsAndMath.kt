@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.dashboard

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import java.util.*
import kotlin.math.roundToLong
import com.expensetracker.offline.ui.components.*
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.DebtStatus
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

import com.expensetracker.offline.util.tick


enum class TimeRange(val tabLabel: String, val spendLabel: String) {
    TODAY("Today", "today"),
    THIS_WEEK("Week", "this week"),
    THIS_MONTH("Month", "this month"),
    THIS_YEAR("Year", "this year"),
    ALL_TIME("All", "all time"),
    /** The previous billing cycle. Reached from the "Previous cycle" link, not shown as a tab. */
    LAST_MONTH("Last", "last cycle")
}


internal val LongConverter = TwoWayConverter<Long, AnimationVector1D>(
    convertToVector = { AnimationVector1D(it.toFloat()) },
    convertFromVector = { it.value.toLong() }
)

internal val SPLIT_ID_TAG_REGEX = Regex("""\[splitId:(\d+)\]""")
internal val CREDIT_ID_TAG_REGEX = Regex("""\[creditId:(\d+)\]""")
internal val REPAID_FOR_REGEX = Regex("""(?i)repaid for\s+([^(]+)""")
internal val REFUND_KEYWORD_REGEX = Regex("""\brefund\b""", RegexOption.IGNORE_CASE)

data class DebtInfo(
    val debt: com.expensetracker.offline.data.local.entity.SplitDebtEntity,
    val parentTransaction: com.expensetracker.offline.data.local.entity.TransactionEntity,
    val totalOwed: Long,
    val repaidSoFar: Long,
    val outstanding: Long
)

data class SplitLinkInfo(
    val linkedCreditsByDebtId: Map<Long, List<TransactionEntity>>,
    val linkedDebtIdByCreditId: Map<Long, Long>
)

internal const val SETTLEMENT_EPSILON = 0.005

internal fun repaidFor(debt: SplitDebtEntity, linkInfo: SplitLinkInfo): Long =
    linkInfo.linkedCreditsByDebtId[debt.id]?.sumOf { it.amount } ?: 0L

internal fun outstandingFor(debt: SplitDebtEntity, linkInfo: SplitLinkInfo): Long {
    if (debt.status != DebtStatus.OPEN || debt.settledAt != null) return 0L
    val outstanding = (debt.amountOwed.toLong() - repaidFor(debt, linkInfo)).coerceAtLeast(0L)
    return outstanding
}

internal fun mySpendShare(item: TransactionWithDebts): Long {
    val paidBy = item.transaction.paidBy?.trim()
    return if (paidBy != null && paidBy.isNotEmpty() && !paidBy.equals("You", ignoreCase = true)) {
        item.debts.firstOrNull { it.debtorName.trim().equals("You", ignoreCase = true) }?.amountOwed ?: 0L
    } else {
        val othersSum = item.debts.filter { !it.debtorName.trim().equals("You", ignoreCase = true) }.sumOf { it.amountOwed.toLong() }
        (item.transaction.amount - othersSum).coerceAtLeast(0L)
    }
}

internal fun outstandingOwed(item: TransactionWithDebts, linkInfo: SplitLinkInfo): Long {
    val paidBy = item.transaction.paidBy?.trim()
    return if (paidBy != null && paidBy.isNotEmpty() && !paidBy.equals("You", ignoreCase = true)) {
        item.debts.filter { it.debtorName.trim().equals("You", ignoreCase = true) }.sumOf { outstandingFor(it, linkInfo) }
    } else {
        item.debts.filter { !it.debtorName.trim().equals("You", ignoreCase = true) }.sumOf { outstandingFor(it, linkInfo) }
    }
}

internal fun isRepaymentCredit(credit: TransactionEntity): Boolean = credit.linkedDebtId != null

internal fun settledByLabel(item: TransactionWithDebts): String? {
    val people = item.debts.map { it.debtorName.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    return when {
        people.isEmpty() -> null
        people.size == 1 -> people.first()
        else -> "${people.size} people"
    }
}

data class UpcomingBill(val name: String, val amount: Long, val dueDate: Long)

data class NecessityStatus(
    val item: NecessityItem,
    val effectiveAmount: Long,
    val amountPaid: Long,
    val isFullyPaid: Boolean
)

data class SafeToSpendBreakdown(
    val balance: Long?,
    val reservedTotal: Long,
    val paidTowardNecessities: Long,
    val remainingReserve: Long,
    val safeToSpend: Long,
    val isShortOnReserve: Boolean,
    val hasBalanceData: Boolean,
    val itemStatuses: List<NecessityStatus>,
    val upcomingNextMonth: List<UpcomingBill> = emptyList()
)

internal fun isBillDue(frequency: NecessityFrequency, dueTimestamp: Long?, targetCal: Calendar): Boolean {
    if (dueTimestamp == null || frequency == NecessityFrequency.MONTHLY) return true
    val dueCal = Calendar.getInstance().apply { timeInMillis = dueTimestamp }
    val monthsBetween = (targetCal.get(Calendar.YEAR) - dueCal.get(Calendar.YEAR)) * 12 +
            (targetCal.get(Calendar.MONTH) - dueCal.get(Calendar.MONTH))
    val months = if (frequency.months > 0) frequency.months else 1
    return monthsBetween >= 0 && monthsBetween % months == 0
}

internal fun isBillDueInCycle(
    item: NecessityItem,
    cycleStart: Long,
    cycleEnd: Long,
    rawTxns: List<TransactionEntity> = emptyList()
): Boolean {
    if (item.frequency == NecessityFrequency.MONTHLY) return true
    val dueMillis = NecessityManager.computeEffectiveDueDate(item, rawTxns) ?: return true
    return dueMillis in cycleStart..cycleEnd
}

fun computeSafeToSpend(
    balance: Long?,
    transactions: List<TransactionWithDebts>,
    necessities: List<NecessityItem>,
    startDayOfMonth: Int = 1
): SafeToSpendBreakdown {
    val (cycleStart, cycleEnd) = BillingCycleHelper.getCycleRange(startDayOfMonth)
    val (nextCycleStart, nextCycleEnd) = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, 1)

    var reservedTotal = 0L
    val statuses = mutableListOf<NecessityStatus>()
    val upcomingNextMonth = mutableListOf<UpcomingBill>()

    // transactions are ordered by timestamp DESC; stop scanning once we pass cycleStart
    val isDesc = transactions.size < 2 || transactions[0].transaction.timestamp >= transactions.last().transaction.timestamp
    val validTxns = ArrayList<TransactionWithDebts>()
    for (item in transactions) {
        val ts = item.transaction.timestamp
        if (ts > cycleEnd) continue
        if (isDesc && ts < cycleStart) break
        if (ts in cycleStart..cycleEnd && item.transaction.type == TransactionType.DEBIT) {
            validTxns.add(item)
        }
    }
    // Bound rawTxns lookback to ~400 days so due-date estimation stays O(K) even on 1M-row DBs
    val lookbackCutoff = cycleStart - 400L * 86_400_000L
    val rawTxns = ArrayList<TransactionEntity>()
    for (item in transactions) {
        val txn = item.transaction
        if (isDesc && txn.timestamp < lookbackCutoff) break
        if (txn.timestamp >= lookbackCutoff) rawTxns.add(txn)
    }
    var totalPaid = 0L

    val currentCycleKey = NecessityManager.currentCycleKey(startDayOfMonth)

    for (item in necessities) {
        val isDueNext = isBillDueInCycle(item, nextCycleStart, nextCycleEnd, rawTxns)
        if (!item.isProrated && isDueNext && item.frequency != NecessityFrequency.MONTHLY) {
            val dueMillis = NecessityManager.computeEffectiveDueDate(item, rawTxns) ?: 0L
            upcomingNextMonth.add(UpcomingBill(item.name, item.amount, dueMillis))
        }

        val amountPaidForThisItem = validTxns
            .filter { NecessityManager.matches(item, it.transaction.payee, it.transaction.category) }
            .sumOf { (it.transaction.amount - it.debts.sumOf { d -> d.amountOwed.toLong() }).coerceAtLeast(0L) }
        val manuallyPaid = item.paidCycleKey == currentCycleKey

        val effectiveAmount = when {
            item.frequency == NecessityFrequency.MONTHLY -> item.amount
            item.isProrated -> NecessityManager.getMonthlyReserveAmount(item)
            manuallyPaid || amountPaidForThisItem >= item.amount -> item.amount
            isBillDueInCycle(item, cycleStart, cycleEnd, rawTxns) -> item.amount
            else -> 0L
        }

        if (effectiveAmount > 0L) {
            reservedTotal += effectiveAmount
            val cappedPaid = if (manuallyPaid) effectiveAmount else minOf(amountPaidForThisItem, effectiveAmount)
            totalPaid += cappedPaid
            statuses.add(NecessityStatus(item, effectiveAmount, cappedPaid, cappedPaid >= effectiveAmount))
        } else {
            // Non-prorated lump-sum bill whose next due date is in a future cycle (e.g. next year):
            // include in statuses as up-to-date for this cycle so the UI doesn't show it as unpaid.
            statuses.add(NecessityStatus(item, 0L, amountPaidForThisItem, isFullyPaid = true))
        }
    }

    val remaining = maxOf(0L, reservedTotal - totalPaid)
    val safe = if (balance != null) maxOf(0L, balance - remaining) else 0L
    val isShort = balance != null && reservedTotal > 0L && balance < remaining

    return SafeToSpendBreakdown(
        balance = balance, reservedTotal = reservedTotal, paidTowardNecessities = totalPaid,
        remainingReserve = remaining, safeToSpend = safe, isShortOnReserve = isShort,
        hasBalanceData = balance != null, itemStatuses = statuses,
        upcomingNextMonth = upcomingNextMonth
    )
}

internal const val KEY_LATEST_BANK_BALANCE = "key_latest_bank_balance"

enum class CategoryBudgetHealth(val priority: Int) {
    OVER_BUDGET(0),
    AT_LIMIT(1),
    NEAR_LIMIT(2),
    PACING_HIGH(3),
    HEALTHY(4)
}

data class CategoryBudgetStatus(
    val category: String,
    val limitPaise: Long,
    val spentPaise: Long,
    val progress: Float,
    val isOverBudget: Boolean,
    val isNearLimit: Boolean,
    val isAtLimit: Boolean = limitPaise > 0L && spentPaise == limitPaise,
    val isPacingHigh: Boolean = false,
    val remainingPaise: Long = (limitPaise - spentPaise).coerceAtLeast(0L),
    val overagePaise: Long = (spentPaise - limitPaise).coerceAtLeast(0L),
    val dailyAllowancePaise: Long = 0L,
    val projectedPaise: Long = spentPaise,
    val transactionCount: Int = 0,
    val health: CategoryBudgetHealth = when {
        isOverBudget -> CategoryBudgetHealth.OVER_BUDGET
        limitPaise > 0L && spentPaise == limitPaise -> CategoryBudgetHealth.AT_LIMIT
        isNearLimit -> CategoryBudgetHealth.NEAR_LIMIT
        isPacingHigh -> CategoryBudgetHealth.PACING_HIGH
        else -> CategoryBudgetHealth.HEALTHY
    }
)

data class CategorySpendStats(
    val category: String,
    val currentCycleSpentPaise: Long,
    val currentCycleTxnCount: Int,
    val lastCycleSpentPaise: Long,
    val threeCycleAvgPaise: Long,
    val suggestedLimitPaise: Long
)

private const val CYCLE_DAY_MS = 86_400_000.0

internal fun isRefundCredit(txn: TransactionEntity): Boolean {
    if (txn.type != TransactionType.CREDIT || txn.linkedDebtId != null) return false
    return txn.note?.let { REFUND_KEYWORD_REGEX.containsMatchIn(it) } == true ||
            txn.itemsSummary?.let { REFUND_KEYWORD_REGEX.containsMatchIn(it) } == true
}

internal fun matchesAccountFilter(txn: TransactionEntity, accountFilter: AccountInfo?): Boolean {
    if (accountFilter == null) return true
    if (accountFilter.bankName == "Unspecified") return txn.bankName == null
    return txn.bankName == accountFilter.bankName && txn.accountNumber == accountFilter.accountNumber
}

/**
 * Computes net category spending (my share of debits minus category refunds) and transaction counts
 * for a given [cycleStart]..[cycleEnd] window, keyed by lowercase trimmed category name.
 */
internal fun computeCycleCategoryNetSpend(
    transactions: List<TransactionWithDebts>,
    cycleStart: Long,
    cycleEnd: Long,
    pendingDeleteIds: Set<Long> = emptySet(),
    accountFilter: AccountInfo? = null
): Map<String, Pair<Long, Int>> {
    val grossByKey = mutableMapOf<String, Long>()
    val countByKey = mutableMapOf<String, Int>()
    val refundsByKey = mutableMapOf<String, Long>()

    val isDesc = transactions.size < 2 || transactions[0].transaction.timestamp >= transactions.last().transaction.timestamp
    for (item in transactions) {
        val txn = item.transaction
        if (txn.timestamp > cycleEnd) continue
        if (isDesc && txn.timestamp < cycleStart) break
        if (txn.timestamp < cycleStart) continue
        if (txn.id in pendingDeleteIds) continue
        if (!matchesAccountFilter(txn, accountFilter)) continue
        val key = txn.category.trim().lowercase()
        if (key.isEmpty() || key == "split settlement") continue

        if (txn.type == TransactionType.DEBIT && !txn.excludeFromSpend && !key.equals("transfer", ignoreCase = true)) {
            val myShare = mySpendShare(item)
            grossByKey[key] = (grossByKey[key] ?: 0L) + myShare
            countByKey[key] = (countByKey[key] ?: 0) + 1
        } else if (isRefundCredit(txn)) {
            refundsByKey[key] = (refundsByKey[key] ?: 0L) + txn.amount
        }
    }

    val result = mutableMapOf<String, Pair<Long, Int>>()
    for ((key, gross) in grossByKey) {
        val refund = refundsByKey[key] ?: 0L
        val net = (gross - refund).coerceAtLeast(0L)
        result[key] = net to (countByKey[key] ?: 0)
    }
    return result
}

fun computeCategoryBudgetStatuses(
    transactions: List<TransactionWithDebts>,
    categoryBudgets: Map<String, Long>,
    startDayOfMonth: Int = 1,
    pendingDeleteIds: Set<Long> = emptySet(),
    accountFilter: AccountInfo? = null,
    cycleOffset: Int = 0
): List<CategoryBudgetStatus> {
    if (categoryBudgets.isEmpty()) return emptyList()

    val (cycleStart, cycleEnd) = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, cycleOffset)
    val now = System.currentTimeMillis()
    val isCurrent = cycleOffset == 0
    val effectiveNow = if (isCurrent) minOf(now, cycleEnd) else cycleEnd
    val cycleLengthDays = ceil((cycleEnd - cycleStart) / CYCLE_DAY_MS).toInt().coerceAtLeast(1)
    val elapsedDays = ceil((effectiveNow - cycleStart) / CYCLE_DAY_MS).toInt().coerceIn(1, cycleLengthDays)
    val daysLeft = if (isCurrent) ceil((cycleEnd - now) / CYCLE_DAY_MS).toInt().coerceAtLeast(0) else 0

    val spendMap = computeCycleCategoryNetSpend(
        transactions = transactions,
        cycleStart = cycleStart,
        cycleEnd = cycleEnd,
        pendingDeleteIds = pendingDeleteIds,
        accountFilter = accountFilter
    )

    return categoryBudgets.entries
        .filter { (cat, limit) -> cat.isNotBlank() && !cat.equals("Split Settlement", ignoreCase = true) && limit > 0L }
        .map { (category, limitPaise) ->
            val key = category.trim().lowercase()
            val (spentPaise, txnCount) = spendMap[key] ?: (0L to 0)
            val progress = (spentPaise.toFloat() / limitPaise.toFloat()).coerceAtLeast(0f)

            // Strictly greater than limit = over budget; exact 100% = at limit (not "₹0 over")
            val isOver = spentPaise > limitPaise
            val isAtLimit = spentPaise == limitPaise
            val isNear = !isOver && (progress >= 0.80f || isAtLimit)

            val remainingPaise = (limitPaise - spentPaise).coerceAtLeast(0L)
            val overagePaise = (spentPaise - limitPaise).coerceAtLeast(0L)
            val dailyAllowancePaise = if (isCurrent && daysLeft > 0 && remainingPaise > 0L) {
                remainingPaise / daysLeft
            } else 0L

            val projectedPaise = if (isCurrent && elapsedDays >= 3 && spentPaise > 0L) {
                (spentPaise.toDouble() / elapsedDays.toDouble() * cycleLengthDays.toDouble()).roundToLong()
            } else {
                spentPaise
            }
            val isPacingHigh = isCurrent && !isOver && !isNear && elapsedDays >= 5 && projectedPaise > limitPaise

            val health = when {
                isOver -> CategoryBudgetHealth.OVER_BUDGET
                isAtLimit -> CategoryBudgetHealth.AT_LIMIT
                isNear -> CategoryBudgetHealth.NEAR_LIMIT
                isPacingHigh -> CategoryBudgetHealth.PACING_HIGH
                else -> CategoryBudgetHealth.HEALTHY
            }

            CategoryBudgetStatus(
                category = category.trim(),
                limitPaise = limitPaise,
                spentPaise = spentPaise,
                progress = progress,
                isOverBudget = isOver,
                isNearLimit = isNear,
                isAtLimit = isAtLimit,
                isPacingHigh = isPacingHigh,
                remainingPaise = remainingPaise,
                overagePaise = overagePaise,
                dailyAllowancePaise = dailyAllowancePaise,
                projectedPaise = projectedPaise,
                transactionCount = txnCount,
                health = health
            )
        }
        .sortedWith(
            compareBy<CategoryBudgetStatus> { it.health.priority }
                .thenByDescending { it.progress }
                .thenByDescending { it.spentPaise }
                .thenBy { it.category.lowercase() }
        )
}

/**
 * Computes current cycle, previous cycle, and 3-cycle average spending for every category,
 * along with a rounded smart budget suggestion (to nearest ₹100 / ₹500).
 */
fun computeCategorySpendStats(
    transactions: List<TransactionWithDebts>,
    availableCategories: List<String>,
    startDayOfMonth: Int = 1,
    pendingDeleteIds: Set<Long> = emptySet()
): Map<String, CategorySpendStats> {
    val currentRange = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, 0)
    val prev1Range = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, -1)
    val prev2Range = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, -2)
    val prev3Range = BillingCycleHelper.getCycleRangeForOffset(startDayOfMonth, -3)

    val currentMap = computeCycleCategoryNetSpend(transactions, currentRange.first, currentRange.second, pendingDeleteIds)
    val prev1Map = computeCycleCategoryNetSpend(transactions, prev1Range.first, prev1Range.second, pendingDeleteIds)
    val prev2Map = computeCycleCategoryNetSpend(transactions, prev2Range.first, prev2Range.second, pendingDeleteIds)
    val prev3Map = computeCycleCategoryNetSpend(transactions, prev3Range.first, prev3Range.second, pendingDeleteIds)

    // Only sample recent transactions if availableCategories is already populated, avoiding 1M-row map allocations
    val recentCats = transactions.asSequence().take(500).map { it.transaction.category }.toList()
    val allCats = (availableCategories + recentCats)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("Split Settlement", ignoreCase = true) }
        .distinctBy { it.lowercase() }

    return allCats.associateWith { cat ->
        val key = cat.lowercase()
        val (curSpent, curCount) = currentMap[key] ?: (0L to 0)
        val prev1Spent = prev1Map[key]?.first ?: 0L
        val prev2Spent = prev2Map[key]?.first ?: 0L
        val prev3Spent = prev3Map[key]?.first ?: 0L

        val historicalNonZero = listOf(prev1Spent, prev2Spent, prev3Spent).filter { it > 0L }
        val avgPaise = when {
            historicalNonZero.isNotEmpty() -> historicalNonZero.sum() / historicalNonZero.size
            curSpent > 0L -> curSpent
            else -> 0L
        }

        val referencePaise = when {
            avgPaise > 0L && curSpent > avgPaise -> (avgPaise + curSpent) / 2L
            avgPaise > 0L -> avgPaise
            curSpent > 0L -> curSpent
            else -> 0L
        }

        val suggestedLimitPaise = roundToCleanRupeeCap(referencePaise)

        CategorySpendStats(
            category = cat,
            currentCycleSpentPaise = curSpent,
            currentCycleTxnCount = curCount,
            lastCycleSpentPaise = prev1Spent,
            threeCycleAvgPaise = avgPaise,
            suggestedLimitPaise = suggestedLimitPaise
        )
    }
}

private fun roundToCleanRupeeCap(paise: Long): Long {
    if (paise <= 0L) return 0L
    val rupees = paise / 100L
    val roundedRupees = when {
        rupees < 500L -> ((rupees + 99L) / 100L) * 100L
        rupees < 3_000L -> ((rupees + 249L) / 250L) * 250L
        rupees < 10_000L -> ((rupees + 499L) / 500L) * 500L
        else -> ((rupees + 999L) / 1000L) * 1000L
    }
    return roundedRupees.coerceAtLeast(100L) * 100L
}

/**
 * True when this app is enabled under Settings > Notification access (NotificationListenerService).
 * That is the permission live capture depends on, and it is what the "Notification access" setup
 * step opens via ACTION_NOTIFICATION_LISTENER_SETTINGS.
 */
internal fun isNotificationPermissionGranted(context: Context): Boolean =
    com.expensetracker.offline.engine.interceptor.NotificationCaptureService.isPermissionGranted(context)
