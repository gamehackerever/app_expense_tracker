@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.offline.ui.necessities

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.ui.dashboard.computeSafeToSpend
import com.expensetracker.offline.ui.insights.MoneyHero
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageNecessitiesScreen(
    items: List<NecessityItem>,
    transactions: List<TransactionWithDebts>,
    onSave: (List<NecessityItem>) -> Unit,
    onNavigateBack: () -> Unit,
    prefill: NecessityItem? = null,
    onPrefillConsumed: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var localItems by remember { mutableStateOf(items) }
    val scope = rememberCoroutineScope()
    var pendingDeleteIds by remember { mutableStateOf(mapOf<String, Long>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    var showFormSheet by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<NecessityItem?>(null) }
    var prefillItem by remember { mutableStateOf<NecessityItem?>(null) }
    var itemToLink by remember { mutableStateOf<NecessityItem?>(null) }

    val listState = rememberLazyListState()
    val isFabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var isFabScrolledAway by remember { mutableStateOf(false) }
    val fabScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f) isFabScrolledAway = true
                else if (available.y > 8f) isFabScrolledAway = false
                return Offset.Zero
            }
        }
    }
    val isFabVisible by remember { derivedStateOf { !isFabScrolledAway || isFabExpanded || !listState.canScrollForward } }
    var itemPendingDelete by remember { mutableStateOf<NecessityItem?>(null) }

    val total = remember(localItems, pendingDeleteIds) {
        localItems
            .filter { it.id !in pendingDeleteIds }
            .sumOf {
                if (it.frequency == NecessityFrequency.MONTHLY || it.isProrated) NecessityManager.getMonthlyReserveAmount(it) else 0L
            }
    }

    val allTxns = remember(transactions) { transactions.map { it.transaction } }

    val context = LocalContext.current
    val startDayOfMonth = remember {
        context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
            .getInt("key_start_day_of_month", 1)
    }
    val cycleKey = NecessityManager.currentCycleKey(startDayOfMonth)
    val cycleStartMillis = remember(startDayOfMonth) { BillingCycleHelper.getCycleRange(startDayOfMonth).first }
    val now = remember { System.currentTimeMillis() }

    val statuses = remember(localItems, transactions, startDayOfMonth) {
        computeSafeToSpend(null, transactions, localItems, startDayOfMonth).itemStatuses.associateBy { it.item.id }
    }

    // Every bill gets a due date, monthly ones included.
    val dueById = remember(localItems, allTxns, statuses, cycleKey, now) {
        localItems.associate { item ->
            val paid = item.paidCycleKey == cycleKey || statuses[item.id]?.isFullyPaid == true
            item.id to effectiveDue(item, allTxns, now, isPaid = paid)
        }
    }

    // Unpaid first (soonest due on top), paid last.
    val sortedItems = remember(localItems, dueById, statuses, cycleKey) {
        localItems.sortedWith(
            compareBy<NecessityItem> { it.paidCycleKey == cycleKey || statuses[it.id]?.isFullyPaid == true }
                .thenBy { dueById[it.id] ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
    }
    val paidCount = remember(sortedItems, statuses, cycleKey) {
        sortedItems.count { it.paidCycleKey == cycleKey || statuses[it.id]?.isFullyPaid == true }
    }

    val categories = remember(allTxns) {
        (listOf(
            "Bills & Utilities", "Subscription", "Entertainment", "Health", "Groceries",
            "Food & Dining", "Shopping", "Travel"
        ) + allTxns.map { it.category })
            .filter { it.isNotBlank() && !it.equals("Uncategorized", true) }.distinct()
    }

    val setPaid: (String, Boolean) -> Unit = { id, paid ->
        localItems = localItems.map {
            if (it.id == id) it.copy(paidCycleKey = if (paid) cycleKey else null) else it
        }
    }

    val togglePaid: (NecessityItem, Boolean) -> Unit = { item, manuallyPaid ->
        setPaid(item.id, !manuallyPaid)
        if (!manuallyPaid) {
            // Rows re-sort when paid, so confirm what happened and offer a way back.
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = "${item.name} marked as paid",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) setPaid(item.id, false)
            }
        }
    }

    LaunchedEffect(prefill) {
        if (prefill != null) {
            itemToEdit = null
            prefillItem = prefill
            showFormSheet = true
            onPrefillConsumed()
        }
    }

    LaunchedEffect(localItems) {
        if (localItems != items) onSave(localItems)
    }

    // A delete still inside its 5s undo window must be committed if the user leaves the screen;
    // otherwise the cancelled timer silently resurrects the item.
    val latestOnSave by rememberUpdatedState(onSave)
    val latestItems by rememberUpdatedState(localItems)
    val latestPendingDeletes by rememberUpdatedState(pendingDeleteIds)
    DisposableEffect(Unit) {
        onDispose {
            if (latestPendingDeletes.isNotEmpty()) {
                latestOnSave(latestItems.filter { it.id !in latestPendingDeletes })
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Fixed Costs & Bills",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.3).sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.tap()
                            onNavigateBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        },
        floatingActionButton = {
            if (localItems.isNotEmpty()) {
                AnimatedVisibility(
                    visible = isFabVisible,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f)
                ) {
                    ExtendedFloatingActionButton(
                    onClick = {
                        haptic.tap()
                        itemToEdit = null
                        prefillItem = null
                        showFormSheet = true
                    },
                    expanded = isFabExpanded,
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(AppRadius.pill),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    icon = {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                        text = {
                            Text("Add Fixed Cost", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(fabScrollConnection)
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(AppRadius.large),
                    color = MaterialTheme.colorScheme.surface,
                    border = cardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NecessityIcon(Icons.Default.Savings)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Reserved every month",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        MoneyHero(amount = total, fractionDigits = 0, size = 40.sp, sessionKey = "necessities_hero_total")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Kept out of Safe to Spend, so rent and subscription money never gets spent by accident.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        if (sortedItems.isNotEmpty() && total > 0L) {
                            val paidFraction = (paidCount.toFloat() / sortedItems.size.toFloat()).coerceIn(0f, 1f)
                            val animatedPaidFraction by com.expensetracker.offline.ui.insights.rememberAnimatedValue(
                                target = paidFraction,
                                key = paidFraction,
                                sessionKey = "necessities_paid_bar"
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            ) {
                                val clampedPaid = animatedPaidFraction.coerceIn(0f, 1f)
                                if (clampedPaid > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(clampedPaid)
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurface)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "$paidCount of ${sortedItems.size} paid this cycle",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (paidCount == sortedItems.size) "All settled" else "${sortedItems.size - paidCount} remaining",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (paidCount == sortedItems.size) AppColors.positive else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            if (localItems.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.large),
                        color = MaterialTheme.colorScheme.surface,
                        border = cardBorder(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            NecessityIcon(Icons.Default.Savings, size = 48.dp, iconSize = 24.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Nothing reserved yet",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Add rent, subscriptions and bills. We'll keep that money out of Safe to Spend.",
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    haptic.tap()
                                    itemToEdit = null
                                    prefillItem = null
                                    showFormSheet = true
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Text("Add your first fixed cost", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Your Bills",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${sortedItems.size} ${if (sortedItems.size == 1) "bill" else "bills"} tracked",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(sortedItems, key = { it.id }) { item ->
                    val deleteStartTime = pendingDeleteIds[item.id]

                    if (deleteStartTime != null) {
                        NecessityUndoBar(
                            label = "\"${item.name}\" deleted",
                            startTimeMs = deleteStartTime,
                            modifier = Modifier.animateItem(),
                            onUndo = { pendingDeleteIds = pendingDeleteIds - item.id }
                        )
                        return@items
                    }

                    val status = statuses[item.id]
                    val manuallyPaid = item.paidCycleKey == cycleKey
                    val isPaid = manuallyPaid || status?.isFullyPaid == true

                    NecessityCard(
                        item = item,
                        isPaid = isPaid,
                        manuallyPaid = manuallyPaid,
                        amountPaid = status?.amountPaid ?: 0L,
                        effectiveAmount = status?.effectiveAmount ?: 0L,
                        due = dueById[item.id],
                        now = now,
                        onClick = { itemToEdit = item; showFormSheet = true },
                        onTogglePaid = { togglePaid(item, manuallyPaid) },
                        onLink = { itemToLink = item },
                        onDelete = {
                            haptic.tap()
                            itemPendingDelete = item
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    val pendingDeleteBill = itemPendingDelete
    if (pendingDeleteBill != null) {
        com.expensetracker.offline.ui.components.AppDialog(
            title = "Remove fixed cost?",
            onDismiss = { itemPendingDelete = null },
            confirmText = "Remove",
            destructive = true,
            onConfirm = {
                val toRemove = pendingDeleteBill
                itemPendingDelete = null
                val startTime = System.currentTimeMillis()
                pendingDeleteIds = pendingDeleteIds + (toRemove.id to startTime)
                scope.launch {
                    delay(5000)
                    if (pendingDeleteIds[toRemove.id] == startTime) {
                        localItems = localItems.filter { it.id != toRemove.id }
                        pendingDeleteIds = pendingDeleteIds - toRemove.id
                    }
                }
            }
        ) {
            Text(
                "Remove \"${pendingDeleteBill.name}\" from Fixed Costs? Your past transactions won't be affected.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    if (showFormSheet) {
        NecessityFormSheet(
            initialItem = itemToEdit,
            prefill = prefillItem,
            categories = categories,
            transactions = allTxns,
            onDismiss = { showFormSheet = false; prefillItem = null },
            onSave = { newItem ->
                val editId = itemToEdit?.id
                localItems = if (editId != null) {
                    // Keep live aliases / paid state; the form only started from a snapshot.
                    localItems.map {
                        if (it.id == editId) newItem.copy(aliases = it.aliases, paidCycleKey = it.paidCycleKey) else it
                    }
                } else {
                    localItems + newItem
                }
                showFormSheet = false
                prefillItem = null
                haptic.confirm()
            }
        )
    }

    val target = itemToLink
    if (target != null) {
        val current = localItems.find { it.id == target.id } ?: target
        LinkTransactionSheet(
            item = current,
            transactions = allTxns,
            monthStart = cycleStartMillis,
            onAliasesChange = { newAliases ->
                localItems = localItems.map {
                    if (it.id == target.id) it.copy(aliases = newAliases) else it
                }
            },
            onDismiss = { itemToLink = null }
        )
    }
}

