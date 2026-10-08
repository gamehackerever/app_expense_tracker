package com.expensetracker.offline.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.*
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
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.MainActivity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.util.BillingCycleHelper
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

data class WidgetTxn(val payee: String, val amount: Long, val isDebit: Boolean, val timestamp: Long)

class ExpenseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    private val bgZinc950 = Color(0xFF09090B)
    private val bgZinc900 = Color(0xFF18181B)
    private val bgZinc800 = Color(0xFF27272A)
    private val textZinc50 = Color(0xFFFAFAFA)
    private val textZinc400 = Color(0xFFA1A1AA)
    private val emerald500 = Color(0xFF10B981)
    private val emeraldText = Color(0xFF022C22)
    private val creditGreen = Color(0xFF34D399)

    companion object {
        // FIXED: Replaced GlobalScope with a safe SupervisorJob (PO-H)
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun updateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val widget = ExpenseWidget()
            widgetScope.launch {
                manager.getGlanceIds(ExpenseWidget::class.java).forEach { glanceId ->
                    widget.update(context, glanceId)
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        var spentToday = 0.0
        var spentMonth = 0.0
        val recentTxns = mutableListOf<WidgetTxn>()
        var hideAmounts = false

        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as ExpenseTrackerApp
                val dao = app.database.transactionDao()
                val sharedPrefs = context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)

                // FIXED: Check lock state to enforce screen privacy (PO-F)
                val isLocked = sharedPrefs.getBoolean("key_app_lock_enabled", false)
                hideAmounts = sharedPrefs.getBoolean("key_hide_widget_amounts", isLocked)

                // FIXED: Use unified BillingCycle bounds instead of raw calendar months (PO-H)
                val startDay = sharedPrefs.getInt("key_start_day_of_month", 1)
                val (cycleStart, cycleEnd) = BillingCycleHelper.getCycleRange(startDay)

                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val todayEnd = todayStart + 86400000L - 1L

                // FIXED: Use the unified spend queries instead of raw SQL and limits (PO-H)
                spentToday = dao.getMySpendBetween(todayStart, todayEnd)
                spentMonth = dao.getMySpendBetween(cycleStart, cycleEnd)

                // Fetch recent transactions for the list view
                val recents = dao.getRecentTransactions(5)
                recents.forEach { txn ->
                    recentTxns.add(WidgetTxn(
                        payee = txn.payee,
                        amount = txn.amount,
                        isDebit = txn.type == TransactionType.DEBIT,
                        timestamp = txn.timestamp
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("ExpenseWidget", "Failed to load widget data", e)
            }
        }

        provideContent {
            val prefs = currentState<Preferences>()
            val isMonthlyView = prefs[booleanPreferencesKey("widget_is_monthly")] ?: false
            val size = LocalSize.current
            val isCompactHeight = size.height < 200.dp
            val formatter = NumberFormat.getNumberInstance(Locale("en", "IN"))

            // Apply Privacy Lock
            val displayAmount = if (hideAmounts) "***" else formatter.format(if (isMonthlyView) spentMonth else spentToday)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_ADD_EXPENSE", true)
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(bgZinc950))
                    .cornerRadius(24.dp)
                    .padding(16.dp)
                    .clickable(actionStartActivity(openAppIntent))
            ) {
                Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.Start) {

                    // --- HEADER ROW ---
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Overview",
                            style = TextStyle(color = ColorProvider(textZinc400), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight()
                        )

                        Row(
                            modifier = GlanceModifier
                                .background(ColorProvider(bgZinc900))
                                .cornerRadius(50.dp)
                                .padding(4.dp)
                                .clickable(actionRunCallback<ToggleViewModeAction>()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = GlanceModifier.background(ColorProvider(if (!isMonthlyView) bgZinc800 else Color.Transparent)).cornerRadius(50.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Day", style = TextStyle(color = ColorProvider(if (!isMonthlyView) textZinc50 else textZinc400), fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                            }
                            Box(
                                modifier = GlanceModifier.background(ColorProvider(if (isMonthlyView) bgZinc800 else Color.Transparent)).cornerRadius(50.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Cycle", style = TextStyle(color = ColorProvider(if (isMonthlyView) textZinc50 else textZinc400), fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = GlanceModifier.height(16.dp))

                    // --- BALANCE AREA ---
                    Text(
                        text = if (hideAmounts) "***" else "₹$displayAmount",
                        style = TextStyle(color = ColorProvider(textZinc50), fontSize = 36.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )

                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = if (hideAmounts) "Tap to unlock" else "Total spent ${if (isMonthlyView) "this cycle" else "today"}",
                        style = TextStyle(color = ColorProvider(textZinc400), fontSize = 13.sp),
                        maxLines = 1
                    )

                    // --- EXPANDING LIST AREA ---
                    if (!isCompactHeight) {
                        Spacer(modifier = GlanceModifier.height(20.dp))
                        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight().background(ColorProvider(bgZinc900)).cornerRadius(16.dp).padding(12.dp)) {
                            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                                if (recentTxns.isEmpty() || hideAmounts) {
                                    item { Text(if (hideAmounts) "Unlock to view history" else "No recent activity", style = TextStyle(color = ColorProvider(textZinc400), fontSize = 13.sp)) }
                                } else {
                                    items(recentTxns) { txn ->
                                        val timeStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(txn.timestamp))
                                        Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = GlanceModifier.defaultWeight()) {
                                                Text(txn.payee, maxLines = 1, style = TextStyle(color = ColorProvider(textZinc50), fontSize = 14.sp, fontWeight = FontWeight.Medium))
                                                Text(timeStr, maxLines = 1, style = TextStyle(color = ColorProvider(textZinc400), fontSize = 12.sp))
                                            }
                                            Text(
                                                text = "${if (txn.isDebit) "" else "+"}₹${formatter.format(txn.amount)}",
                                                style = TextStyle(color = ColorProvider(if (txn.isDebit) textZinc50 else creditGreen), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = GlanceModifier.height(16.dp))
                    } else {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    }

                    // --- BOTTOM BUTTON ---
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().background(ColorProvider(emerald500)).cornerRadius(50.dp).padding(vertical = 12.dp).clickable(actionStartActivity(launchIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+ Log Expense", style = TextStyle(color = ColorProvider(emeraldText), fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
                    }
                }
            }
        }
    }
}

class ToggleViewModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[booleanPreferencesKey("widget_is_monthly")] ?: false
            prefs[booleanPreferencesKey("widget_is_monthly")] = !current
        }
        ExpenseWidget().update(context, glanceId)
    }
}
