@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.offline.ui.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.expensetracker.offline.ui.insights.MoneyHero
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import com.expensetracker.offline.ui.components.*
import androidx.core.content.edit
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import com.expensetracker.offline.util.NecessityManager.KEY_NECESSITIES_MIGRATED
import com.expensetracker.offline.util.NecessityManager.saveNecessities
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

import com.expensetracker.offline.util.tick
import com.expensetracker.offline.util.click
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete


enum class TimeRange(val tabLabel: String, val spendLabel: String) {
    TODAY("Today", "today"),
    THIS_WEEK("Week", "this week"),
    THIS_MONTH("Month", "this month"),
    THIS_YEAR("Year", "this year"),
    ALL_TIME("All", "all time")
}

internal fun HapticFeedback.tap() = tick()

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

internal data class SplitLinkInfo(
    val linkedCreditsByDebtId: Map<Long, List<TransactionEntity>>,
    val linkedDebtIdByCreditId: Map<Long, Long>
)

internal const val SETTLEMENT_EPSILON = 0.005

internal fun repaidFor(debt: SplitDebtEntity, linkInfo: SplitLinkInfo): Long =
    linkInfo.linkedCreditsByDebtId[debt.id]?.sumOf { it.amount } ?: 0L

internal fun outstandingFor(debt: SplitDebtEntity, linkInfo: SplitLinkInfo): Long {
    if (debt.settledAt != null) return 0L
    val outstanding = (debt.amountOwed.toLong() - repaidFor(debt, linkInfo)).coerceAtLeast(0L)
    return outstanding
}

internal fun outstandingOwed(item: TransactionWithDebts, linkInfo: SplitLinkInfo): Long =
    item.debts.sumOf { outstandingFor(it, linkInfo) }

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

    val nextCycleStartCal = Calendar.getInstance().apply { timeInMillis = cycleStart; add(Calendar.MONTH, 1) }
    val nextCycleEndCal = Calendar.getInstance().apply { timeInMillis = cycleEnd; add(Calendar.MONTH, 1) }

    var reservedTotal = 0L
    val statuses = mutableListOf<NecessityStatus>()
    val upcomingNextMonth = mutableListOf<UpcomingBill>()

    val validTxns = transactions.filter { it.transaction.type == TransactionType.DEBIT && it.transaction.timestamp in cycleStart..cycleEnd }
    val rawTxns = transactions.map { it.transaction }
    var totalPaid = 0L

    val currentCycleKey = NecessityManager.currentCycleKey(startDayOfMonth)

    for (item in necessities) {
        val isDueNext = isBillDueInCycle(item, nextCycleStartCal.timeInMillis, nextCycleEndCal.timeInMillis, rawTxns)
        if (!item.isProrated && isDueNext && item.frequency != NecessityFrequency.MONTHLY) {
            val dueMillis = NecessityManager.computeEffectiveDueDate(item, rawTxns) ?: 0L
            upcomingNextMonth.add(UpcomingBill(item.name, item.amount, dueMillis))
        }

        val effectiveAmount = when {
            item.frequency == NecessityFrequency.MONTHLY -> item.amount
            item.isProrated -> NecessityManager.getMonthlyReserveAmount(item)
            isBillDueInCycle(item, cycleStart, cycleEnd, rawTxns) -> item.amount
            else -> 0L
        }

        if (effectiveAmount > 0) {
            reservedTotal += effectiveAmount

            val amountPaidForThisItem = validTxns
                .filter { NecessityManager.matches(item, it.transaction.payee, it.transaction.category) }
                .sumOf { (it.transaction.amount - it.debts.sumOf { d -> d.amountOwed.toLong() }).coerceAtLeast(0L) }

            val manuallyPaid = item.paidCycleKey == currentCycleKey
            val cappedPaid = if (manuallyPaid) effectiveAmount else minOf(amountPaidForThisItem, effectiveAmount)

            totalPaid += cappedPaid
            statuses.add(NecessityStatus(item, effectiveAmount, cappedPaid, cappedPaid >= effectiveAmount))
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

/**
 * True when this app is enabled under Settings > Notification access (NotificationListenerService).
 * That is the permission live capture depends on, and it is what the "Notification access" setup
 * step opens via ACTION_NOTIFICATION_LISTENER_SETTINGS.
 */
internal fun isNotificationPermissionGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

