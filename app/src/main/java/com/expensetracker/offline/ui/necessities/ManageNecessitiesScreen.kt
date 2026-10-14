@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.offline.ui.necessities

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.components.CurrencyFormat
import com.expensetracker.offline.ui.components.NewCategoryDialog
import com.expensetracker.offline.ui.components.SheetAmountCard
import com.expensetracker.offline.ui.components.SheetCard
import com.expensetracker.offline.ui.components.SheetCategoryPicker
import com.expensetracker.offline.ui.components.SheetFooter
import com.expensetracker.offline.ui.components.SheetHeader
import com.expensetracker.offline.ui.components.SheetPrimaryButton
import com.expensetracker.offline.ui.components.SheetSegmentedControl
import com.expensetracker.offline.ui.components.SheetTextField
import com.expensetracker.offline.ui.components.evaluateBasicMath
import com.expensetracker.offline.ui.dashboard.computeSafeToSpend
import com.expensetracker.offline.ui.insights.MoneyHero
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
    // Same billing-cycle boundary the rest of the app uses (not the calendar month).
    val cycleStartMillis = remember(startDayOfMonth) { BillingCycleHelper.getCycleRange(startDayOfMonth).first }
    val cycleKey = NecessityManager.currentCycleKey(startDayOfMonth)
    val now = remember { System.currentTimeMillis() }

    val statuses = remember(localItems, transactions, startDayOfMonth) {
        computeSafeToSpend(null, transactions, localItems, startDayOfMonth).itemStatuses.associateBy { it.item.id }
    }

    // Every bill gets a due date, monthly ones included.
    val dueById = remember(localItems, allTxns, now) {
        localItems.associate { it.id to effectiveDue(it, allTxns, now) }
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
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            if (localItems.isNotEmpty())
                ExtendedFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
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
                        MoneyHero(amount = total, fractionDigits = 0, size = 40.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Kept out of Safe to Spend, so rent and subscription money never gets spent by accident.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
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
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                            "$paidCount of ${sortedItems.size} paid this month",
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
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val startTime = System.currentTimeMillis()
                            pendingDeleteIds = pendingDeleteIds + (item.id to startTime)
                            scope.launch {
                                delay(5000)
                                if (pendingDeleteIds[item.id] == startTime) {
                                    localItems = localItems.filter { it.id != item.id }
                                    pendingDeleteIds = pendingDeleteIds - item.id
                                }
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
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
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        )
    }

    itemToLink?.let { target ->
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

