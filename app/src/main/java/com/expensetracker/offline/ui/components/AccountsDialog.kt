package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.data.repository.NetWorthMath
import com.expensetracker.offline.data.repository.ManualAccountStore
import com.expensetracker.offline.data.repository.ManualAccount
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import com.expensetracker.offline.data.local.dao.AccountBalanceInsight
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.CreditCardConfig
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.ui.dashboard.SafeToSpendBreakdown
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.BillingCycleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private fun <T> smoothSize() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsDialog(
    accountBalances: List<AccountBalanceInsight>,
    selectedAccount: AccountInfo?,
    contextLabel: String,
    contextBalance: Long?,
    safeToSpendBreakdown: SafeToSpendBreakdown?,
    transactions: List<TransactionEntity> = emptyList(),
    totalCreditCardDebt: Long = 0L,
    onAccountSelected: (AccountInfo?) -> Unit,
    onRefresh: () -> Unit,
    onManageNecessities: () -> Unit,
    onDismiss: () -> Unit,
    onUpdateBalance: ((bankName: String, accountNumber: String?, balancePaise: Long) -> Unit)? = null,
    onSetCreditCard: ((bankName: String, accountNumber: String?, enabled: Boolean) -> Unit)? = null,
    onAddManualAccount: ((bankName: String, accountNumber: String?, balancePaise: Long, isCc: Boolean, limitPaise: Long, stmtDay: Int, dueDay: Int) -> Unit)? = null,
    secure: Boolean = true
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settingsRepo = remember { SettingsRepository(context) }
    var ccConfigVersion by remember { mutableIntStateOf(0) }

    // UI States for the two distinct actions
    var editingAccount by remember { mutableStateOf<AccountBalanceInsight?>(null) }
    var showAddAccountDialog by remember { mutableStateOf(false) }

    // Savings, FDs, investments and loans that never send SMS, plus the net worth they feed.
    val manualStore = remember { ManualAccountStore(settingsRepo.prefs) }
    var manualVersion by remember { mutableIntStateOf(0) }
    val manualAccounts = remember(manualVersion) { manualStore.load() }
    var showManualDialog by remember { mutableStateOf(false) }
    var editingManual by remember { mutableStateOf<ManualAccount?>(null) }

    val listMaxHeight = minOf(maxOf((LocalConfiguration.current.screenHeightDp * 0.36f).dp, 160.dp), 340.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var syncing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var statusVisible by remember { mutableStateOf(false) }
    val newestUpdate by rememberUpdatedState(accountBalances.maxOfOrNull { it.lastUpdated })

    LaunchedEffect(syncing) {
        if (!syncing) return@LaunchedEffect
        val baseline = newestUpdate
        val updated = withTimeoutOrNull(6000) { snapshotFlow { newestUpdate }.first { it != baseline } }
        statusText = if (updated != null) "Balance updated" else "No new balance found in recent SMS"
        statusVisible = true
        syncing = false
    }
    LaunchedEffect(statusVisible) {
        if (statusVisible) {
            delay(2500)
            statusVisible = false
        }
    }

    val spinTransition = rememberInfiniteTransition(label = "syncSpin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "syncAngle"
    )

    ResistantModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        properties = ModalBottomSheetProperties(
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
        ),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = AppColors.positive, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Securely unlocked", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.positive)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (accountBalances.size <= 1) "Balance & Safe to Spend" else "Accounts & Safe to Spend",
                        fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (totalCreditCardDebt > 0L) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Total CC Debt: ₹${String.format(Locale.US, "%,.2f", totalCreditCardDebt / 100.0)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.negative
                        )
                    }
                }
                IconButton(
                    onClick = {
                        haptic.tap()
                        syncing = true
                        onRefresh()
                    },
                    enabled = !syncing
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Sync balance from SMS",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (syncing) spinAngle else 0f }
                    )
                }
                IconButton(onClick = {
                    haptic.tap()
                    onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(
                visible = statusVisible,
                enter = fadeIn(tween(200)) + expandVertically(smoothSize()),
                exit = fadeOut(tween(160)) + shrinkVertically(smoothSize())
            ) {
                Text(
                    statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            val netWorth = remember(accountBalances, manualAccounts, totalCreditCardDebt, ccConfigVersion) {
                NetWorthMath.compute(
                    bankBalancesPaise = accountBalances
                        .filter { settingsRepo.getCreditCardConfig(it.bankName, it.accountNumber) == null }
                        .mapNotNull { it.latestBalance },
                    manual = manualAccounts,
                    cardDebtPaise = totalCreditCardDebt
                )
            }
            if (manualAccounts.isNotEmpty() || totalCreditCardDebt > 0L || accountBalances.size > 1) {
                NetWorthStrip(netWorth)
                Spacer(Modifier.height(14.dp))
            }
            ManualAccountsSection(
                accounts = manualAccounts,
                onAdd = {
                    haptic.tap()
                    editingManual = null
                    showManualDialog = true
                },
                onEdit = {
                    haptic.tap()
                    editingManual = it
                    showManualDialog = true
                }
            )
            Spacer(Modifier.height(18.dp))

            if (accountBalances.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("No accounts detected yet.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onAddManualAccount != null) {
                        OutlinedButton(
                            onClick = {
                                haptic.tap()
                                showAddAccountDialog = true
                            },
                            shape = RoundedCornerShape(AppRadius.small)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Offline Account", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                val single = accountBalances.size == 1
                val activeAccountInsight = remember(accountBalances, selectedAccount, single) {
                    if (single) accountBalances.first()
                    else accountBalances.firstOrNull {
                        it.bankName == selectedAccount?.bankName && it.accountNumber == selectedAccount?.accountNumber
                    }
                }

                BalanceFlipCard(
                    contextLabel = activeAccountInsight?.bankName ?: contextLabel,
                    accountNumber = activeAccountInsight?.accountNumber,
                    balance = activeAccountInsight?.latestBalance ?: contextBalance,
                    breakdown = safeToSpendBreakdown,
                    lastUpdated = activeAccountInsight?.lastUpdated ?: accountBalances.maxOfOrNull { it.lastUpdated },
                    onManageNecessities = onManageNecessities
                )

                key(ccConfigVersion) {
                    if (single) {
                        val account = accountBalances.first()
                        val ccConfig = remember(account, ccConfigVersion) {
                            settingsRepo.getCreditCardConfig(account.bankName, account.accountNumber)
                        }
                        val ccUnbilledPaise = remember(account, ccConfig, transactions) {
                            if (ccConfig == null) 0L
                            else {
                                val (stmtStart, stmtEnd) = BillingCycleHelper.getCreditCardCycleRange(ccConfig.statementDay)
                                transactions.asSequence()
                                    .filter {
                                        it.type == TransactionType.DEBIT &&
                                                !it.excludeFromSpend &&
                                                it.bankName == account.bankName &&
                                                it.accountNumber == account.accountNumber &&
                                                it.timestamp in stmtStart..stmtEnd
                                    }
                                    .sumOf { it.amount }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        if (ccConfig != null) {
                            CreditCardUtilizationCard(
                                bankName = account.bankName,
                                accountNumber = account.accountNumber,
                                ccConfig = ccConfig,
                                ccUnbilledPaise = remember(account, ccConfig, ccUnbilledPaise, transactions) {
                                    ccConfig?.let { usedFromAvailableLimit(settingsRepo, account.bankName, account.accountNumber, it, ccUnbilledPaise) } ?: ccUnbilledPaise
                                },
                                onConfigureCc = {
                                    haptic.tap()
                                    editingAccount = account
                                }
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(AppRadius.medium),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(AppRadius.medium))
                                    .quietClickable {
                                        haptic.tap()
                                        editingAccount = account
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Settings & Adjustments",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "Fix balance manually or setup as credit card",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        // With exactly one account the list below never shows, so the add button must live here too.
                        if (onAddManualAccount != null) {
                            Spacer(Modifier.height(12.dp))
                            AddAccountButton {
                                haptic.tap()
                                showAddAccountDialog = true
                            }
                        }
                    } else {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Switch account",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight)
                        ) {
                            item(key = "all_accounts") {
                                val combinedBal = remember(accountBalances) {
                                    accountBalances.mapNotNull { it.latestBalance }.takeIf { it.isNotEmpty() }?.sum()
                                }
                                AccountListItem(
                                    title = "All accounts",
                                    subtitle = "Combined across ${accountBalances.size} accounts",
                                    icon = Icons.Default.AccountTree,
                                    isSelected = selectedAccount == null,
                                    balance = combinedBal,
                                    onClick = {
                                        haptic.tap()
                                        onAccountSelected(null)
                                    }
                                )
                            }
                            itemsIndexed(accountBalances, key = { idx, it -> "${it.bankName}|${it.accountNumber ?: "none"}|$idx" }) { _, account ->
                                val isSelected = selectedAccount?.bankName == account.bankName && selectedAccount?.accountNumber == account.accountNumber
                                val ccConfig = remember(account, ccConfigVersion) {
                                    settingsRepo.getCreditCardConfig(account.bankName, account.accountNumber)
                                }
                                val ccUnbilledPaise = remember(account, ccConfig, transactions) {
                                    if (ccConfig == null) 0L
                                    else {
                                        val (stmtStart, stmtEnd) = BillingCycleHelper.getCreditCardCycleRange(ccConfig.statementDay)
                                        transactions.asSequence()
                                            .filter {
                                                it.type == TransactionType.DEBIT &&
                                                        !it.excludeFromSpend &&
                                                        it.bankName == account.bankName &&
                                                        it.accountNumber == account.accountNumber &&
                                                        it.timestamp in stmtStart..stmtEnd
                                            }
                                            .sumOf { it.amount }
                                    }
                                }
                                AccountListItem(
                                    title = account.bankName,
                                    subtitle = account.accountNumber?.let { "··$it" },
                                    icon = if (ccConfig != null) Icons.Default.CreditCard else null,
                                    initial = if (ccConfig == null) account.bankName.take(1).uppercase() else null,
                                    isSelected = isSelected,
                                    balance = account.latestBalance,
                                    ccConfig = ccConfig,
                                    ccUnbilledPaise = remember(account, ccConfig, ccUnbilledPaise, transactions) {
                                    ccConfig?.let { usedFromAvailableLimit(settingsRepo, account.bankName, account.accountNumber, it, ccUnbilledPaise) } ?: ccUnbilledPaise
                                },
                                    onConfigureCc = {
                                        haptic.tap()
                                        editingAccount = account
                                    },
                                    onClick = {
                                        haptic.tap()
                                        onAccountSelected(AccountInfo(account.bankName, account.accountNumber))
                                    }
                                )
                            }
                            if (onAddManualAccount != null) {
                                item(key = "add_account_btn") {
                                    Surface(
                                        shape = RoundedCornerShape(AppRadius.medium),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                            .clip(RoundedCornerShape(AppRadius.medium))
                                            .clickable {
                                                haptic.tap()
                                                showAddAccountDialog = true
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Add Offline Account", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualAccountDialog(
            initial = editingManual,
            onDismiss = { showManualDialog = false },
            onSave = { account, addToFixedCosts ->
                manualStore.upsert(account)
                if (addToFixedCosts) linkToFixedCosts(settingsRepo, account)
                manualVersion++
                showManualDialog = false
                statusText = "Saved ${account.name}"
                statusVisible = true
            },
            onDelete = { account ->
                manualStore.delete(account.id)
                removeFixedCostLink(settingsRepo, account)
                manualVersion++
                showManualDialog = false
            }
        )
    }

    // DIALOG 1: Add a completely new offline account / CC
    if (showAddAccountDialog && onAddManualAccount != null) {
        var bankInput by remember { mutableStateOf("") }
        var acctNumInput by remember { mutableStateOf("") }
        var balanceStr by remember { mutableStateOf("") }

        var isCcNew by remember { mutableStateOf(false) }
        var newCcLimitStr by remember { mutableStateOf("") }
        var newCcStmtDay by remember { mutableIntStateOf(15) }
        var newCcDueDay by remember { mutableIntStateOf(5) }

        val parsedBalancePaise = remember(balanceStr) {
            balanceStr.trim().replace(",", "").toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { (it * 100.0).roundToLong() }
        }

        AppDialog(
            title = "Add Offline Account",
            onDismiss = {
                haptic.tap()
                showAddAccountDialog = false
            },
            confirmText = "Add Account",
            confirmEnabled = bankInput.trim().isNotEmpty() && parsedBalancePaise != null,
            onConfirm = {
                val bal = parsedBalancePaise ?: return@AppDialog
                val limit = (newCcLimitStr.toLongOrNull() ?: 0L).let { if (it > 0L) it.coerceAtLeast(1000L) * 100L else 0L }
                haptic.confirm()

                onAddManualAccount(
                    bankInput.trim(),
                    acctNumInput.trim().takeIf { it.isNotEmpty() },
                    bal,
                    isCcNew,
                    limit,
                    newCcStmtDay,
                    newCcDueDay
                )

                statusText = "Account created: ${bankInput.trim()}"
                statusVisible = true
                showAddAccountDialog = false
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Manually track an account or wallet that doesn't receive SMS alerts.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
                OutlinedTextField(
                    value = bankInput,
                    onValueChange = { bankInput = it.take(40) },
                    label = { Text("Account / Wallet Name") },
                    placeholder = { Text("e.g., Cash Wallet") },
                    singleLine = true,
                    shape = RoundedCornerShape(AppRadius.small),
                    colors = sheetFieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = acctNumInput,
                        onValueChange = { acctNumInput = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Last 4 Digits (Opt)") },
                        placeholder = { Text("1234") },
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = sheetFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.9f)
                    )
                    OutlinedTextField(
                        value = balanceStr,
                        onValueChange = { balanceStr = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                        label = { Text(if (isCcNew) "Current Debt (₹)" else "Starting Balance (₹)") },
                        placeholder = { Text("0.00") },
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = sheetFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.1f)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Track as Credit Card", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("Sets credit limit & statement dates", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isCcNew,
                        onCheckedChange = {
                            haptic.tap()
                            isCcNew = it
                        },
                        colors = appSwitchColors()
                    )
                }

                AnimatedVisibility(
                    visible = isCcNew,
                    enter = expandVertically(tween(260), expandFrom = Alignment.Top) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(120))
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = newCcLimitStr,
                            onValueChange = { newCcLimitStr = it.filter { c -> c.isDigit() }.take(9) },
                            label = { Text("Credit Limit (₹)") },
                            singleLine = true,
                            shape = RoundedCornerShape(AppRadius.small),
                            colors = sheetFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = newCcStmtDay.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { newCcStmtDay = it.coerceIn(1, 28) } },
                                label = { Text("Statement Day") },
                                singleLine = true,
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = sheetFieldColors(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newCcDueDay.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { newCcDueDay = it.coerceIn(1, 28) } },
                                label = { Text("Due Day") },
                                singleLine = true,
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = sheetFieldColors(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                }
                }
            }
        }
    }

    // DIALOG 2: Settings & Adjustments for an EXISTING account
    val targetAccount = editingAccount
    if (targetAccount != null && onUpdateBalance != null) {
        val existingConfig = settingsRepo.getCreditCardConfig(targetAccount.bankName, targetAccount.accountNumber)

        var balanceStr by remember(targetAccount) {
            val bal = targetAccount.latestBalance
            mutableStateOf(
                if (bal != null && bal > 0L) String.format(Locale.US, "%.2f", bal / 100.0).removeSuffix(".00") else ""
            )
        }

        var isCc by remember(targetAccount) { mutableStateOf(existingConfig != null) }
        var limitRupeesStr by remember(targetAccount) {
            mutableStateOf(((existingConfig?.creditLimitPaise ?: 0L) / 100L).takeIf { it > 0L }?.toString() ?: "")
        }
        var stmtDay by remember(targetAccount) { mutableIntStateOf(existingConfig?.statementDay ?: 15) }
        var dueDay by remember(targetAccount) { mutableIntStateOf(existingConfig?.dueDay ?: 5) }
        var aliasesStr by remember(targetAccount) { mutableStateOf(existingConfig?.payeeAliases ?: "") }

        AppDialog(
            title = "${targetAccount.bankName} Settings",
            onDismiss = {
                haptic.tap()
                editingAccount = null
            },
            confirmText = "Save Changes",
            onConfirm = {
                haptic.confirm()

                // 1. Update Balance if it changed
                val newBal = balanceStr.trim().replace(",", "").toDoubleOrNull()?.let { (it * 100.0).roundToLong() } ?: 0L
                if (newBal != (targetAccount.latestBalance ?: 0L)) {
                    onUpdateBalance(targetAccount.bankName, targetAccount.accountNumber, newBal)
                }

                // 2. Update CC settings
                if (isCc) {
                    val limitPaise = (limitRupeesStr.toLongOrNull() ?: 0L).let { if (it > 0L) it.coerceAtLeast(1000L) * 100L else 0L }
                    settingsRepo.saveCreditCardConfig(
                        bankName = targetAccount.bankName,
                        accountNumber = targetAccount.accountNumber,
                        config = CreditCardConfig(
                            accountKey = settingsRepo.accountKey(targetAccount.bankName, targetAccount.accountNumber),
                            isCreditCard = true,
                            creditLimitPaise = limitPaise,
                            statementDay = stmtDay,
                            dueDay = dueDay,
                            payeeAliases = aliasesStr.trim()
                        )
                    )
                    onSetCreditCard?.invoke(targetAccount.bankName, targetAccount.accountNumber, true)
                } else if (existingConfig != null) {
                    // Switched OFF: persist the choice (so name-based auto-detection can't turn it
                    // back on) and re-tag this account's transactions as normal bank rows.
                    onSetCreditCard?.invoke(targetAccount.bankName, targetAccount.accountNumber, false)
                }

                ccConfigVersion++
                editingAccount = null
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Adjust Balance Section
                Text("Fix Balance", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = balanceStr,
                    onValueChange = { balanceStr = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("Balance shown by your bank (₹)") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    shape = RoundedCornerShape(AppRadius.small),
                    colors = sheetFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                val enteredBalancePaise = balanceStr.trim().replace(",", "").toBigDecimalOrNull()
                    ?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.longValueExact()
                val recordedBalancePaise = targetAccount.latestBalance
                if (enteredBalancePaise != null && recordedBalancePaise != null) {
                    val difference = enteredBalancePaise - recordedBalancePaise
                    val sign = if (difference > 0L) "+" else if (difference < 0L) "−" else ""
                    val differenceText = String.format(Locale.US, "₹%.2f", kotlin.math.abs(difference) / 100.0)
                    Text(
                        text = if (difference == 0L) "Matches the latest balance recorded in Expense Tracker."
                        else "Difference from the app's recorded balance: $sign$differenceText.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Saving records a balance checkpoint for this account; it does not add income or spending.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // CC Section
                Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Track as Credit Card", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("Monitor limit & utilization", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isCc,
                        onCheckedChange = {
                            haptic.tap()
                            isCc = it
                        },
                        colors = appSwitchColors()
                    )
                }

                AnimatedVisibility(
                    visible = isCc,
                    enter = expandVertically(tween(260), expandFrom = Alignment.Top) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(120))
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = limitRupeesStr,
                            onValueChange = { limitRupeesStr = it.filter { c -> c.isDigit() }.take(9) },
                            label = { Text("Credit Limit (₹)") },
                            singleLine = true,
                            shape = RoundedCornerShape(AppRadius.small),
                            colors = sheetFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = stmtDay.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { stmtDay = it.coerceIn(1, 28) } },
                                label = { Text("Stmt Day") },
                                singleLine = true,
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = sheetFieldColors(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = dueDay.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { dueDay = it.coerceIn(1, 28) } },
                                label = { Text("Due Day") },
                                singleLine = true,
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = sheetFieldColors(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = aliasesStr,
                            onValueChange = { aliasesStr = it.take(80) },
                            label = { Text("Name when paying this card (optional)") },
                            placeholder = { Text("e.g. RePay, HDFC Regalia") },
                            supportingText = { Text("Payments to this name are treated as a card bill, not a new expense.") },
                            singleLine = true,
                            shape = RoundedCornerShape(AppRadius.small),
                            colors = sheetFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                }
                }
            }
        }
    }
}

/** Real amount used on a card: limit minus the latest "Avl Lmt" from SMS; falls back to this cycle's tracked spend. */
private fun usedFromAvailableLimit(
    settingsRepo: SettingsRepository, bankName: String, accountNumber: String?,
    config: CreditCardConfig, unbilledPaise: Long
): Long {
    val limit = config.creditLimitPaise
    if (limit <= 0L) return unbilledPaise
    val available = settingsRepo.getCardAvailableLimit(bankName, accountNumber)?.first ?: return unbilledPaise
    return (limit - available).coerceAtLeast(0L)
}

@Composable
private fun CreditCardUtilizationCard(
    bankName: String,
    accountNumber: String?,
    ccConfig: CreditCardConfig,
    ccUnbilledPaise: Long,
    onConfigureCc: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val limit = ccConfig.creditLimitPaise
    val hasLimit = limit > 0L
    val utilRatio = if (hasLimit) (ccUnbilledPaise.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
    val utilPct = if (hasLimit) ((ccUnbilledPaise.toDouble() / limit.toDouble()) * 100.0).roundToInt() else 0
    val utilColor = when {
        utilPct >= 70 -> AppColors.negative
        utilPct >= 30 -> AppColors.warning
        else -> AppColors.positive
    }
    val daysToDue = remember(ccConfig.dueDay) {
        val today = LocalDate.now()
        val candidate = today.withDayOfMonth(ccConfig.dueDay.coerceIn(1, 28))
        val nextDue = if (candidate.isBefore(today)) candidate.plusMonths(1) else candidate
        ChronoUnit.DAYS.between(today, nextDue).toInt()
    }

    Surface(
        shape = RoundedCornerShape(AppRadius.medium),
        color = scheme.onSurface.copy(alpha = 0.03f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CreditCard, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (accountNumber != null) "$bankName ··$accountNumber Credit Card" else "$bankName Credit Card",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onConfigureCc, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Card settings", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Cycle Unbilled Spend", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (hasLimit) "${CurrencyFormat.withSymbol(ccUnbilledPaise, 0)} / ${CurrencyFormat.withSymbol(limit, 0)}" else "${CurrencyFormat.withSymbol(ccUnbilledPaise, 0)} · limit not set",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Utilization · Due Day ${ccConfig.dueDay}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (hasLimit) "$utilPct% used · Due in ${daysToDue}d" else "Due in ${daysToDue}d",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = utilColor
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { utilRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(AppRadius.pill)),
                color = utilColor,
                trackColor = scheme.onSurface.copy(alpha = 0.08f)
            )
        }
    }
}

@Composable
private fun AccountListItem(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    initial: String? = null,
    isSelected: Boolean,
    balance: Long? = null,
    ccConfig: CreditCardConfig? = null,
    ccUnbilledPaise: Long = 0L,
    onConfigureCc: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> scheme.onSurface.copy(alpha = 0.05f)
            isPressed -> scheme.onSurface.copy(alpha = 0.02f)
            else -> scheme.surfaceVariant.copy(alpha = 0.25f)
        },
        animationSpec = tween(200), label = "accountItemBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) scheme.onSurface.copy(alpha = 0.55f) else scheme.outlineVariant.copy(alpha = 0.30f),
        animationSpec = tween(200), label = "accountItemBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .selectable(
                selected = isSelected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            ),
        shape = RoundedCornerShape(AppRadius.medium),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.animateContentSize(smoothSize()).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp)) {
                    Surface(shape = CircleShape, color = scheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            when {
                                icon != null -> Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                initial != null -> Text(initial, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant, fontSize = 16.sp)
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSelected,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(140)),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(x = 2.dp, y = 2.dp)
                                .size(18.dp)
                                .border(2.dp, scheme.surface, CircleShape)
                                .padding(2.dp)
                                .background(scheme.onSurface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = scheme.surface, modifier = Modifier.size(10.dp))
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (ccConfig != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = scheme.onSurface.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    "Credit Card",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    }
                }

                if (balance != null && ccConfig == null) {
                    Text(
                        text = CurrencyFormat.smart(balance),
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = if (onConfigureCc != null) 4.dp else 0.dp)
                    )
                } else if (ccConfig != null) {
                    Text(
                        text = "Unbilled: ${CurrencyFormat.withSymbol(ccUnbilledPaise, 0)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.negative,
                        modifier = Modifier.padding(end = if (onConfigureCc != null) 8.dp else 0.dp)
                    )
                }

                if (onConfigureCc != null) {
                    IconButton(onClick = onConfigureCc, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Account & Card settings",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (ccConfig != null) {
                val limit = ccConfig.creditLimitPaise
    val hasLimit = limit > 0L
                val utilRatio = if (hasLimit) (ccUnbilledPaise.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
                val utilPct = if (hasLimit) ((ccUnbilledPaise.toDouble() / limit.toDouble()) * 100.0).roundToInt() else 0
                val utilColor = when {
                    utilPct >= 70 -> AppColors.negative
                    utilPct >= 30 -> AppColors.warning
                    else -> AppColors.positive
                }
                val daysToDue = remember(ccConfig.dueDay) {
                    val today = LocalDate.now()
                    val candidate = today.withDayOfMonth(ccConfig.dueDay.coerceIn(1, 28))
                    val nextDue = if (candidate.isBefore(today)) candidate.plusMonths(1) else candidate
                    ChronoUnit.DAYS.between(today, nextDue).toInt()
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Cycle Unbilled Spend", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hasLimit) "${CurrencyFormat.withSymbol(ccUnbilledPaise, 0)} / ${CurrencyFormat.withSymbol(limit, 0)}" else "${CurrencyFormat.withSymbol(ccUnbilledPaise, 0)} · limit not set",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Utilization · Due Day ${ccConfig.dueDay}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hasLimit) "$utilPct% used · Due in ${daysToDue}d" else "Due in ${daysToDue}d",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = utilColor
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { utilRatio },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(AppRadius.pill)),
                    color = utilColor,
                    trackColor = scheme.onSurface.copy(alpha = 0.08f)
                )
            }
        }
    }
}

@Composable
private fun BalanceFlipCard(
    contextLabel: String,
    accountNumber: String?,
    balance: Long?,
    breakdown: SafeToSpendBreakdown?,
    lastUpdated: Long?,
    onManageNecessities: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current

    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }

    var isFlipped by remember { mutableStateOf(prefs.getBoolean("key_balance_card_flipped", false)) }
    var showBills by remember { mutableStateOf(false) }

    val flipInteraction = remember { MutableInteractionSource() }
    val flipPressed by flipInteraction.collectIsPressedAsState()
    val flipColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onSurface.copy(alpha = if (flipPressed) 0.07f else 0.04f),
        animationSpec = tween(150), label = "flipCardBg"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "balanceCardFlip"
    )
    val showFront by remember { derivedStateOf { rotation <= 90f } }
    val isStale = remember(lastUpdated) {
        lastUpdated != null && System.currentTimeMillis() - lastUpdated > STALE_BALANCE_MS
    }
    val syncText = remember(lastUpdated) {
        val base = lastUpdated?.let { "Last synced: ${formatSyncTime(context, it)}" } ?: "Not synced yet"
        if (isStale) "$base · may be outdated" else base
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 16f * density.density
            }
            .clip(RoundedCornerShape(AppRadius.large))
            .clickable(
                interactionSource = flipInteraction,
                indication = null,
                onClickLabel = if (isFlipped) "Show balance" else "Show safe to spend",
                role = Role.Button
            ) {
                haptic.tap()
                isFlipped = !isFlipped
                prefs.edit().putBoolean("key_balance_card_flipped", isFlipped).apply()
            },
        shape = RoundedCornerShape(AppRadius.large),
        color = flipColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 186.dp).animateContentSize(smoothSize()).padding(22.dp)) {
            if (showFront) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Available balance", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(AppRadius.pill), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
                            Text(
                                text = if (accountNumber != null) "$contextLabel · ··$accountNumber" else contextLabel,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Crossfade(targetState = balance, animationSpec = tween(220), label = "balanceSwap") { shown ->
                        if (shown != null) {
                            HeroAmount(text = CurrencyFormat.withSymbol(shown, 2), color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            Text("No data yet", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(syncText, fontSize = 13.sp, color = if (isStale) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(20.dp))
                    FlipHint("Tap card to flip to Safe to Spend")
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Safe to spend", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(AppRadius.pill),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable {
                                haptic.tap()
                                onManageNecessities()
                            }
                        ) {
                            Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Manage bills",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    when {
                        breakdown == null || breakdown.reservedTotal <= 0L -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("No bills reserved", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Tap Manage bills to reserve fixed costs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        !breakdown.hasBalanceData -> {
                            Text("—", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Waiting for sync", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            val heroColor = if (breakdown.isShortOnReserve) AppColors.warning else MaterialTheme.colorScheme.onSurface
                            HeroAmount(text = CurrencyFormat.withSymbol(breakdown.safeToSpend, 0), color = heroColor)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when {
                                    breakdown.isShortOnReserve -> "Not enough to cover ${CurrencyFormat.withSymbol(breakdown.remainingReserve, 0)} of bills"
                                    breakdown.remainingReserve <= 0L -> "All bills paid this month"
                                    else -> "${CurrencyFormat.withSymbol(breakdown.remainingReserve, 0)} reserved for bills"
                                },
                                fontSize = 13.sp,
                                color = if (breakdown.isShortOnReserve) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .quietClickable {
                                        haptic.tap()
                                        showBills = !showBills
                                    }
                                    .heightIn(min = 36.dp)
                            ) {
                                Text(
                                    if (showBills) "Hide bills" else "View bills (${breakdown.itemStatuses.size})",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    if (showBills) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp)
                                )
                            }

                            AnimatedVisibility(
                                visible = showBills,
                                enter = fadeIn(tween(220)) + expandVertically(smoothSize()),
                                exit = fadeOut(tween(140)) + shrinkVertically(smoothSize())
                            ) {
                                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                    remember(breakdown.itemStatuses) { breakdown.itemStatuses.sortedBy { it.isFullyPaid } }.forEachIndexed { index, status ->
                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        val left = (status.effectiveAmount - status.amountPaid).coerceAtLeast(0L)
                                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                status.item.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                color = if (status.isFullyPaid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                                textDecoration = if (status.isFullyPaid) TextDecoration.LineThrough else null,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (status.isFullyPaid) {
                                                Icon(Icons.Default.CheckCircle, null, tint = AppColors.positive, modifier = Modifier.size(16.dp))
                                            } else {
                                                Text(CurrencyFormat.withSymbol(left, 0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    FlipHint("Tap card to flip back to Balance")
                }
            }
        }
    }
}

@Composable
private fun HeroAmount(text: String, color: Color) {
    val symbolColor = MaterialTheme.colorScheme.onSurfaceVariant
    val size = when {
        text.length > 14 -> 34.sp
        text.length > 11 -> 40.sp
        else -> 46.sp
    }
    val firstDigit = text.indexOfFirst { it.isDigit() }.let { if (it < 0) 0 else it }
    val dot = text.lastIndexOf('.').takeIf { it > firstDigit } ?: text.length
    val styled = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = size * 0.55f, fontWeight = FontWeight.SemiBold, color = symbolColor)) {
            append(text.substring(0, firstDigit))
        }
        append(text.substring(firstDigit, dot))
        if (dot < text.length) {
            withStyle(SpanStyle(fontSize = size * 0.55f, fontWeight = FontWeight.SemiBold, color = symbolColor)) {
                append(text.substring(dot))
            }
        }
    }
    Text(
        text = styled,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = color,
        letterSpacing = (-1.5).sp,
        maxLines = 1,
        softWrap = false,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
    )
}

@Composable
private fun FlipHint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.FlipCameraAndroid, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val STALE_BALANCE_MS = 3L * 24 * 60 * 60 * 1000

private fun formatSyncTime(context: Context, millis: Long): String {
    val time = DateFormat.getTimeFormat(context).format(Date(millis))
    if (DateUtils.isToday(millis)) return "Today, $time"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM")
    return "${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))}, $time"
}

private fun fixedCostId(account: ManualAccount) = "manual-${account.id}"

/** Mirrors a SIP / EMI into Fixed Costs so it is reserved for and reminded about like any other bill. */
private fun linkToFixedCosts(settingsRepo: SettingsRepository, account: ManualAccount) {
    if (account.monthlyPaise <= 0L) return
    val prefs = settingsRepo.prefs
    val due = java.util.Calendar.getInstance().apply {
        val day = if (account.dueDay in 1..28) account.dueDay else get(java.util.Calendar.DAY_OF_MONTH).coerceAtMost(28)
        set(java.util.Calendar.DAY_OF_MONTH, day)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis < System.currentTimeMillis()) add(java.util.Calendar.MONTH, 1)
    }.timeInMillis
    val item = com.expensetracker.offline.util.NecessityItem(
        id = fixedCostId(account),
        name = account.name,
        amount = account.monthlyPaise,
        frequency = com.expensetracker.offline.util.NecessityFrequency.MONTHLY,
        dueTimestamp = due
    )
    val current = com.expensetracker.offline.util.NecessityManager.loadNecessities(prefs)
    com.expensetracker.offline.util.NecessityManager.saveNecessities(prefs, current.filter { it.id != item.id } + item)
}

private fun removeFixedCostLink(settingsRepo: SettingsRepository, account: ManualAccount) {
    val prefs = settingsRepo.prefs
    val current = com.expensetracker.offline.util.NecessityManager.loadNecessities(prefs)
    if (current.any { it.id == fixedCostId(account) }) {
        com.expensetracker.offline.util.NecessityManager.saveNecessities(prefs, current.filter { it.id != fixedCostId(account) })
    }
}


/** Outlined "Add Offline Account" row shared by the single-account and empty states. */
@Composable
private fun AddAccountButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AppRadius.medium),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add Offline Account", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
