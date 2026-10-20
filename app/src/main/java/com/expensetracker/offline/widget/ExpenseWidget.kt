package com.expensetracker.offline.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.MainActivity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class WidgetTxn(
    val payee: String,
    val category: String,
    val amount: Long,
    val isDebit: Boolean,
    val timestamp: Long
)

class ExpenseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    // Monochromatic Zinc Palette matching the main app's Dark theme
    private val bgZinc950 = Color(0xFF09090B)
    private val bgZinc900 = Color(0xFF18181B)
    private val bgZinc800 = Color(0xFF27272A)
    private val textZinc50 = Color(0xFFFAFAFA)
    private val textZinc400 = Color(0xFFA1A1AA)
    private val accentWhite = Color(0xFFFAFAFA)
    private val accentDarkText = Color(0xFF09090B)
    private val emeraldGreen = Color(0xFF10B981)
    private val warningAmber = Color(0xFFF59E0B)
    private val dangerRed = Color(0xFFEF4444)

    companion object {
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun updateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val widget = ExpenseWidget()
            widgetScope.launch {
                runCatching {
                    manager.getGlanceIds(ExpenseWidget::class.java).forEach { glanceId ->
                        widget.update(context, glanceId)
                    }
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        var spentTodayPaise = 0L
        var spentMonthPaise = 0L
        var monthlyBudgetPaise = 0L
        var pendingReviewCount = 0
        var dueBillsCount = 0
        val recentTxns = mutableListOf<WidgetTxn>()
        var hideAmounts = false

        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as ExpenseTrackerApp
                val dao = app.database.transactionDao()
                val reviewDao = app.database.reviewItemDao()
                val settingsRepo = SettingsRepository(context)

                hideAmounts = settingsRepo.hideWidgetAmounts || settingsRepo.appLockEnabled
                val startDay = settingsRepo.startDayOfMonth
                val (cycleStart, cycleEnd) = BillingCycleHelper.getCycleRange(startDay)

                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val todayEnd = todayStart + 86_400_000L - 1L

                spentTodayPaise = dao.getMySpendBetween(todayStart, todayEnd)
                spentMonthPaise = dao.getMySpendBetween(cycleStart, cycleEnd)
                monthlyBudgetPaise = if (settingsRepo.isBudgetExplicitlySet) settingsRepo.monthlyBudget * 100L else 0L
                pendingReviewCount = reviewDao.getPendingReviewCountSync()

                val recentForBills = dao.getRecentTransactions(150)
                dueBillsCount = NecessityManager.getDueBillAlerts(settingsRepo.prefs, recentForBills, startDay).size

                recentForBills.take(4).forEach { txn ->
                    recentTxns.add(
                        WidgetTxn(
                            payee = txn.payee,
                            category = txn.category,
                            amount = txn.amount,
                            isDebit = txn.type == TransactionType.DEBIT,
                            timestamp = txn.timestamp
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ExpenseWidget", "Failed to load widget data", e)
            }
        }

        provideContent {
            val prefs = currentState<Preferences>()
            val isMonthlyView = prefs[booleanPreferencesKey("widget_is_monthly")] ?: true
            val size = LocalSize.current
            val isTall = size.height >= 210.dp

            val formatter = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
                maximumFractionDigits = 0
                minimumFractionDigits = 0
            }

            val activeSpendPaise = if (isMonthlyView) spentMonthPaise else spentTodayPaise
            val displayAmount = if (hideAmounts) "•••••" else formatter.format(activeSpendPaise / 100.0)

            val budgetPct = if (monthlyBudgetPaise > 0L) {
                ((spentMonthPaise.toDouble() / monthlyBudgetPaise.toDouble()) * 100.0).roundToInt()
            } else null
            val remainingBudgetPaise = (monthlyBudgetPaise - spentMonthPaise)

            val openDashboardIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val addExpenseIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_ADD_EXPENSE", true)
            }
            val scanReceiptIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_ADD_EXPENSE", true)
                putExtra("OPEN_RECEIPT_SCAN", true)
            }
            val thirdActionIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_ROUTE", if (pendingReviewCount > 0) "review" else "insights")
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(bgZinc950))
                    .cornerRadius(24.dp)
                    .padding(16.dp)
                    .clickable(actionStartActivity(openDashboardIntent))
            ) {
                Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.Start) {

                    // ── 1. HEADER BAR: Label + Budget/Bill Badge + Day/Cycle Toggle ──
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = if (isMonthlyView) "Cycle Spend" else "Today's Spend",
                                style = TextStyle(color = ColorProvider(textZinc400), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1
                            )
                        }

                        Row(
                            modifier = GlanceModifier
                                .background(ColorProvider(bgZinc900))
                                .cornerRadius(50.dp)
                                .padding(3.dp)
                                .clickable(actionRunCallback<ToggleViewModeAction>()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .background(ColorProvider(if (!isMonthlyView) bgZinc800 else Color.Transparent))
                                    .cornerRadius(50.dp)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Today",
                                    style = TextStyle(
                                        color = ColorProvider(if (!isMonthlyView) textZinc50 else textZinc400),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1
                                )
                            }
                            Box(
                                modifier = GlanceModifier
                                    .background(ColorProvider(if (isMonthlyView) bgZinc800 else Color.Transparent))
                                    .cornerRadius(50.dp)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Cycle",
                                    style = TextStyle(
                                        color = ColorProvider(if (isMonthlyView) textZinc50 else textZinc400),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = GlanceModifier.height(8.dp))

                    // ── 2. HERO FIGURE & BUDGET RUNWAY SUBTITLE ──
                    Text(
                        text = "₹$displayAmount",
                        style = TextStyle(color = ColorProvider(textZinc50), fontSize = 32.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )

                    Spacer(modifier = GlanceModifier.height(2.dp))

                    val statusLine = when {
                        hideAmounts -> "App Locked · Tap to unlock"
                        dueBillsCount > 0 -> "$dueBillsCount fixed ${if (dueBillsCount == 1) "bill" else "bills"} due soon"
                        isMonthlyView && budgetPct != null -> {
                            if (remainingBudgetPaise >= 0L) {
                                "₹${formatter.format(remainingBudgetPaise / 100.0)} left · $budgetPct% of budget used"
                            } else {
                                "₹${formatter.format((-remainingBudgetPaise) / 100.0)} over monthly budget ($budgetPct%)"
                            }
                        }
                        !isMonthlyView && spentMonthPaise > 0L -> "₹${formatter.format(spentMonthPaise / 100.0)} spent this cycle"
                        else -> "100% offline · Auto-capture active"
                    }
                    val statusColor = when {
                        hideAmounts -> textZinc400
                        isMonthlyView && monthlyBudgetPaise > 0L && remainingBudgetPaise < 0L -> dangerRed
                        dueBillsCount > 0 || (budgetPct != null && budgetPct >= 85) -> warningAmber
                        else -> textZinc400
                    }

                    Text(
                        text = statusLine,
                        style = TextStyle(color = ColorProvider(statusColor), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )

                    // ── 3. RECENT TRANSACTIONS LIST (Tall Widget Mode) ──
                    if (isTall) {
                        Spacer(modifier = GlanceModifier.height(12.dp))
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .defaultWeight()
                                .background(ColorProvider(bgZinc900))
                                .cornerRadius(16.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (hideAmounts || recentTxns.isEmpty()) {
                                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (hideAmounts) "Unlock app to view recent activity" else "No recent transactions yet",
                                        style = TextStyle(color = ColorProvider(textZinc400), fontSize = 12.sp)
                                    )
                                }
                            } else {
                                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                                    items(recentTxns) { txn ->
                                        val timeStr = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(txn.timestamp))
                                        Row(
                                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = GlanceModifier.defaultWeight()) {
                                                Text(
                                                    txn.payee,
                                                    maxLines = 1,
                                                    style = TextStyle(color = ColorProvider(textZinc50), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                )
                                                Text(
                                                    "${txn.category} · $timeStr",
                                                    maxLines = 1,
                                                    style = TextStyle(color = ColorProvider(textZinc400), fontSize = 11.sp)
                                                )
                                            }
                                            Text(
                                                text = "${if (txn.isDebit) "" else "+"}₹${formatter.format(txn.amount / 100.0)}",
                                                style = TextStyle(
                                                    color = ColorProvider(if (txn.isDebit) textZinc50 else emeraldGreen),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = GlanceModifier.height(12.dp))
                    } else {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    }

                    // ── 4. QUICK ACTION BAR: + Add | Scan Bill | Review (N) / Insights ──
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(accentWhite))
                                .cornerRadius(50.dp)
                                .padding(vertical = 10.dp)
                                .clickable(actionStartActivity(addExpenseIntent)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+ Add",
                                style = TextStyle(color = ColorProvider(accentDarkText), fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(8.dp))

                        Box(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(bgZinc800))
                                .cornerRadius(50.dp)
                                .padding(vertical = 10.dp)
                                .clickable(actionStartActivity(scanReceiptIntent)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Scan Bill",
                                style = TextStyle(color = ColorProvider(textZinc50), fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(8.dp))

                        val hasPending = pendingReviewCount > 0
                        Box(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(if (hasPending) warningAmber else bgZinc900))
                                .cornerRadius(50.dp)
                                .padding(vertical = 10.dp)
                                .clickable(actionStartActivity(thirdActionIntent)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (hasPending) "Review ($pendingReviewCount)" else "Insights",
                                style = TextStyle(
                                    color = ColorProvider(if (hasPending) accentDarkText else textZinc400),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

class ToggleViewModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[booleanPreferencesKey("widget_is_monthly")] ?: true
            prefs[booleanPreferencesKey("widget_is_monthly")] = !current
        }
        ExpenseWidget().update(context, glanceId)
    }
}
