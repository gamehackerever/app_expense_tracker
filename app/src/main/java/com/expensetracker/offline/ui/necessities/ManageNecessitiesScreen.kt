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

private const val NO_CATEGORY = "No category"

// ─────────────────────────────────────────────────────────────
// BILL CARD
// ─────────────────────────────────────────────────────────────
@Composable
private fun NecessityCard(
    item: NecessityItem,
    isPaid: Boolean,
    manuallyPaid: Boolean,
    amountPaid: Long,
    effectiveAmount: Long,
    due: Long?,
    now: Long,
    onClick: () -> Unit,
    onTogglePaid: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(AppRadius.medium)
    val cat = item.categoryName
    val vault = NecessityManager.sinkingFundProgress(item)
    val autoPaid = isPaid && !manuallyPaid
    val days = due?.let { daysUntil(it, now) }
    val urgent = !isPaid && days != null && days <= 3
    val relativeColor = when {
        isPaid || days == null -> scheme.onSurfaceVariant
        days < 0 -> AppColors.negative
        days <= 3 -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }

    Surface(
        shape = shape,
        color = scheme.surface,
        border = cardBorder(),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .quietClickable(pressedAlpha = 0.85f, onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NecessityIcon(if (cat != null) getCategoryIcon(cat) else Icons.Default.Savings)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitleFor(item),
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    amountLabel(item),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.onSurface.copy(alpha = 0.04f),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .quietClickable(onClick = onDelete)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete ${item.name}",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (vault != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { vault.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = scheme.onSurface,
                    trackColor = scheme.onSurface.copy(alpha = 0.08f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${CurrencyFormat.withSymbol(vault.saved, 0)} / ${CurrencyFormat.withSymbol(vault.target, 0)} set aside",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            // Due date: shown for every bill, whatever its billing cycle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Event,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildAnnotatedString {
                        if (due == null) {
                            append("No due date yet")
                        } else {
                            append(if (isPaid) "Next due " else "Due ")
                            withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.SemiBold)) {
                                append(formatDue(due, now))
                            }
                            if (days != null) {
                                append(" · ")
                                withStyle(
                                    SpanStyle(
                                        color = relativeColor,
                                        fontWeight = if (urgent) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                ) { append(relativeDue(days)) }
                            }
                        }
                    },
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isPaid && amountPaid > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${CurrencyFormat.withSymbol(amountPaid, 0)} of ${CurrencyFormat.withSymbol(effectiveAmount, 0)} paid this month",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPaid) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePaid()
                        },
                        enabled = !autoPaid,
                        shape = RoundedCornerShape(AppRadius.small),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.positive.copy(alpha = 0.10f),
                            contentColor = AppColors.positive,
                            disabledContainerColor = AppColors.positive.copy(alpha = 0.10f),
                            disabledContentColor = AppColors.positive
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (autoPaid) "Paid · detected" else "Paid · Undo",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePaid()
                        },
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.onSurface.copy(alpha = 0.08f),
                            contentColor = scheme.onSurface
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mark as paid", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onLink()
                    },
                    shape = RoundedCornerShape(AppRadius.small),
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (item.aliases.isEmpty()) "Link payee" else "${item.aliases.size} linked",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
        }
    }
}

private fun subtitleFor(item: NecessityItem): String {
    if (item.frequency == NecessityFrequency.MONTHLY) return "Monthly"
    val strategy = if (item.isProrated) "Sinking fund" else "Lump sum"
    val freq = if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0)
        "Every ${item.validityDays} days"
    else item.frequency.label
    return "$freq · $strategy"
}

private fun amountLabel(item: NecessityItem): String = when {
    item.frequency == NecessityFrequency.EVERY_N_DAYS && !item.isProrated ->
        CurrencyFormat.withSymbol(item.amount, 0) + " / ${item.validityDays ?: 28}d"
    item.frequency != NecessityFrequency.MONTHLY && !item.isProrated ->
        CurrencyFormat.withSymbol(item.amount, 0) + lumpSumSuffix(item.frequency.months)
    else -> CurrencyFormat.withSymbol(NecessityManager.getMonthlyReserveAmount(item), 0) + "/mo"
}

// ─────────────────────────────────────────────────────────────
// FORM SHEET
// ─────────────────────────────────────────────────────────────
@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StrategyOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AppRadius.small)
    Surface(
        shape = shape,
        color = if (selected) colors.onSurface.copy(alpha = 0.06f) else colors.surface,
        border = BorderStroke(
            1.dp,
            if (selected) colors.onSurface.copy(alpha = 0.45f) else colors.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier.clip(shape).quietClickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.onSurface)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(description, fontSize = 11.sp, color = colors.onSurfaceVariant, lineHeight = 15.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NecessityFormSheet(
    initialItem: NecessityItem?,
    prefill: NecessityItem? = null,
    categories: List<String>,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onSave: (NecessityItem) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    val seed = initialItem ?: prefill

    var nameInput by remember { mutableStateOf(seed?.name ?: "") }
    var amountInput by remember {
        mutableStateOf(
            seed?.let {
                if (it.amount > 0) String.format(Locale.US, "%.2f", it.amount / 100.0).removeSuffix(".00") else ""
            } ?: ""
        )
    }
    var frequency by remember { mutableStateOf(seed?.frequency ?: NecessityFrequency.MONTHLY) }
    var dueTimestamp by remember { mutableStateOf(seed?.dueTimestamp) }
    var isProrated by remember { mutableStateOf(seed?.isProrated ?: false) }
    var categoryName by remember { mutableStateOf(seed?.categoryName) }
    var validityDaysInput by remember { mutableStateOf(seed?.validityDays?.toString() ?: "28") }

    val parsedValidityDays = validityDaysInput.toIntOrNull()?.takeIf { it > 0 }
    // Only "every N days" items carry a validity; otherwise the default "28" would leak into
    // other frequencies and make every edit look like a change.
    val effectiveValidityDays = if (frequency == NecessityFrequency.EVERY_N_DAYS) parsedValidityDays else null
    val parsedAmount = remember(amountInput) { evaluateBasicMath(amountInput) }

    val currentDraftItem = remember(nameInput, parsedAmount, frequency, dueTimestamp, isProrated, categoryName, effectiveValidityDays) {
        (initialItem ?: prefill ?: NecessityItem(UUID.randomUUID().toString(), "", 0L)).copy(
            name = nameInput.trim(),
            amount = parsedAmount ?: 0L,
            frequency = frequency,
            dueTimestamp = dueTimestamp,
            isProrated = isProrated,
            categoryName = categoryName,
            validityDays = effectiveValidityDays
        )
    }

    val autoTxn = remember(currentDraftItem, transactions) {
        NecessityManager.findLastTransaction(currentDraftItem, transactions)
    }

    var extraCategories by remember { mutableStateOf(emptyList<String>()) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    val pickerCategories = remember(categories, extraCategories, seed?.categoryName) {
        listOf(NO_CATEGORY) +
                (categories + extraCategories + listOfNotNull(seed?.categoryName))
                    .filter { it.isNotBlank() }
                    .distinct()
    }

    val suggestions = remember(nameInput, transactions) {
        if (seed != null) emptyList()
        else NecessityManager.suggestBills(nameInput, transactions)
            .filter { !it.payee.equals(nameInput.trim(), true) }
    }

    val isValid = nameInput.isNotBlank() && parsedAmount != null && parsedAmount > 0L &&
            (frequency != NecessityFrequency.EVERY_N_DAYS || parsedValidityDays != null)
    val hasChanges = initialItem == null ||
            nameInput.trim() != initialItem.name ||
            parsedAmount != initialItem.amount ||
            frequency != initialItem.frequency ||
            dueTimestamp != initialItem.dueTimestamp ||
            isProrated != initialItem.isProrated ||
            categoryName != initialItem.categoryName ||
            effectiveValidityDays != initialItem.validityDays

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onAdd = { newCategory ->
                extraCategories = extraCategories + newCategory
                categoryName = newCategory
                showNewCategoryDialog = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = colors.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SheetHeader(
                    title = if (initialItem != null) "Edit fixed cost" else "Add fixed cost",
                    subtitle = "Kept out of Safe to Spend"
                )

                SheetAmountCard(
                    amountStr = amountInput,
                    onAmountChange = { amountInput = it },
                    calculatedAmount = parsedAmount?.let { it / 100.0 },
                    isDebit = true
                )

                SheetTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(60) },
                    label = "Name",
                    placeholder = "e.g., Netflix",
                    leadingIcon = Icons.Default.Storefront,
                    singleLine = true
                )

                if (suggestions.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { s ->
                            AssistChip(
                                onClick = {
                                    nameInput = s.payee
                                    amountInput = String.format(Locale.US, "%.2f", s.amount / 100.0).removeSuffix(".00")
                                    frequency = s.frequency
                                    s.category?.let { categoryName = it }
                                    s.validityDays?.let { validityDaysInput = it.toString() }
                                    if (s.frequency != NecessityFrequency.MONTHLY) {
                                        dueTimestamp = Calendar.getInstance().apply {
                                            timeInMillis = s.lastTimestamp
                                            add(Calendar.MONTH, s.frequency.months)
                                        }.timeInMillis
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                label = {
                                    Text(
                                        "Found ${s.payee} (${CurrencyFormat.withSymbol(s.amount, 0)}" +
                                                "${if (s.frequency == NecessityFrequency.MONTHLY) "/mo" else ""}) · Tap to autofill",
                                        maxLines = 1
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                SheetCategoryPicker(
                    categories = pickerCategories,
                    selected = categoryName ?: NO_CATEGORY,
                    onSelect = { categoryName = if (it == NO_CATEGORY) null else it },
                    onAddCustom = { showNewCategoryDialog = true }
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormLabel("Billing cycle")
                    val cycles = NecessityFrequency.entries
                    SheetSegmentedControl(
                        options = cycles.map {
                            if (it == NecessityFrequency.EVERY_N_DAYS) "Days"
                            else it.label.lowercase().replaceFirstChar { c -> c.uppercase() }
                        },
                        selectedIndex = cycles.indexOf(frequency),
                        onSelect = { frequency = cycles[it] }
                    )
                }

                AnimatedVisibility(visible = frequency == NecessityFrequency.EVERY_N_DAYS) {
                    SheetTextField(
                        value = validityDaysInput,
                        onValueChange = { validityDaysInput = it.filter { c -> c.isDigit() }.take(4) },
                        label = "Cycle validity (in days)",
                        placeholder = "e.g., 28 for VI recharge, 84 for Creatine",
                        leadingIcon = Icons.Default.Event,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // Due date: shown for every billing type, monthly included.
                val autoDueDate = remember(currentDraftItem, transactions) {
                    effectiveDue(currentDraftItem.copy(dueTimestamp = null), transactions, System.currentTimeMillis())
                }
                val shownDue = dueTimestamp ?: autoDueDate

                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = colors.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NecessityIcon(Icons.Default.AutoAwesome)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (autoTxn != null) {
                                Text(
                                    "Auto-detected from transaction history",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    "Last paid on ${dateFmt.format(autoTxn.timestamp)} (${autoTxn.payee})",
                                    fontSize = 13.sp,
                                    color = colors.onSurface
                                )
                            } else {
                                Text(
                                    "Auto-calculated due date",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    "No past transaction found. Pick a date below to set one.",
                                    fontSize = 13.sp,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                SheetCard(
                    onClick = {
                        val cal = Calendar.getInstance()
                        shownDue?.let { cal.timeInMillis = it }
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                cal.set(y, m, d)
                                dueTimestamp = cal.timeInMillis
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    contentPadding = 14.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NecessityIcon(Icons.Default.Event)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (dueTimestamp != null) "Next due date (Manual override)" else "Next due date (Auto-detected)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = shownDue?.let { dateFmt.format(it) } ?: "Not set · tap to choose",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                        }
                        if (dueTimestamp != null) {
                            TextButton(onClick = { dueTimestamp = null }) {
                                Text("Reset auto", fontSize = 12.sp)
                            }
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = frequency != NecessityFrequency.MONTHLY) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormLabel("Saving strategy")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val monthlyReserve = NecessityManager.getMonthlyReserveAmount(currentDraftItem)
                            val formattedSave = CurrencyFormat.withSymbol(monthlyReserve, 0)
                            StrategyOption(
                                title = "Sinking fund",
                                description = "Sets aside $formattedSave every month",
                                selected = isProrated,
                                onClick = {
                                    isProrated = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            StrategyOption(
                                title = "Lump sum",
                                description = "Reserves total amount only when due",
                                selected = !isProrated,
                                onClick = {
                                    isProrated = false
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            SheetFooter {
                SheetPrimaryButton(
                    text = if (initialItem != null) "Update fixed cost" else "Save fixed cost",
                    enabled = isValid && hasChanges,
                    onClick = {
                        focusManager.clearFocus()
                        onSave(
                            (initialItem ?: prefill ?: NecessityItem(UUID.randomUUID().toString(), "", 0L)).copy(
                                name = nameInput.trim(),
                                amount = parsedAmount!!,
                                frequency = frequency,
                                dueTimestamp = dueTimestamp,
                                isProrated = isProrated,
                                categoryName = categoryName,
                                validityDays = effectiveValidityDays
                            )
                        )
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// LINK TRANSACTIONS SHEET
// ─────────────────────────────────────────────────────────────
private enum class MatchState { NONE, LINKED, AUTO }

private fun matchState(item: NecessityItem, payee: String): MatchState = when {
    payee.contains(item.name, ignoreCase = true) -> MatchState.AUTO
    item.aliases.any { payee.contains(it, ignoreCase = true) } -> MatchState.LINKED
    else -> MatchState.NONE
}

private fun cleanAlias(payee: String): String {
    val trimmed = payee.trim()
    val stripped = trimmed.replace(Regex("[\\s\\d]+$"), "")
    return if (stripped.length >= 3) stripped else trimmed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkTransactionSheet(
    item: NecessityItem,
    transactions: List<TransactionEntity>,
    monthStart: Long,
    onAliasesChange: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    val windowDays = if (item.frequency == NecessityFrequency.MONTHLY) 90L else 400L
    val cutoff = remember(windowDays) { System.currentTimeMillis() - windowDays * 86_400_000L }

    val recent = remember(transactions, cutoff) {
        transactions.filter { it.type == TransactionType.DEBIT && it.timestamp >= cutoff }
            .sortedByDescending { it.timestamp }
    }

    val visible = remember(recent, query) {
        val q = query.trim()
        if (q.isEmpty()) recent else recent.filter { it.payee.contains(q, ignoreCase = true) }
    }
    val thisMonth = remember(visible, monthStart) { visible.filter { it.timestamp >= monthStart } }
    val earlier = remember(visible, monthStart) { visible.filter { it.timestamp < monthStart } }

    val matched = remember(recent, item.aliases, item.name) {
        recent.filter { matchState(item, it.payee) != MatchState.NONE }
    }
    val matchedThisMonth = remember(matched, monthStart) {
        matched.filter { it.timestamp >= monthStart }.sumOf { it.amount }
    }
    val aliasHits = remember(recent, item.aliases) {
        item.aliases.associateWith { alias -> recent.count { it.payee.contains(alias, ignoreCase = true) } }
    }

    val onRowClick: (TransactionEntity) -> Unit = { t ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (matchState(item, t.payee)) {
            MatchState.AUTO -> Unit
            // Unlinking removes every alias this payee matches, so the row really stops counting.
            MatchState.LINKED -> onAliasesChange(item.aliases.filterNot { alias ->
                t.payee.contains(alias, ignoreCase = true)
            })
            MatchState.NONE -> onAliasesChange((item.aliases + cleanAlias(t.payee)).distinctBy { it.lowercase() })
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = colors.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {

            Box(Modifier.padding(horizontal = 24.dp)) {
                SheetHeader(
                    title = "Link transactions",
                    subtitle = "Tell the app which bank payees mean ${item.name}"
                )
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(AppRadius.medium),
                color = colors.surface,
                border = cardBorder(),
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Matched this month", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Text(
                            CurrencyFormat.withSymbol(matchedThisMonth, 0),
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${matched.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        Text("in last $windowDays days", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                if (item.aliases.isEmpty()) "Linked payees" else "Linked payees · ${item.aliases.size}",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (item.aliases.isEmpty()) {
                Text(
                    "Nothing linked yet. Pick a transaction below and every similar payee will count toward this bill.",
                    fontSize = 12.sp, lineHeight = 16.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item.aliases.forEach { alias ->
                        val hits = aliasHits[alias] ?: 0
                        InputChip(
                            selected = false,
                            onClick = { onAliasesChange(item.aliases.filterNot { it.equals(alias, ignoreCase = true) }) },
                            label = { Text("$alias · $hits", fontSize = 12.sp, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove $alias", modifier = Modifier.size(14.dp)) },
                            colors = InputChipDefaults.inputChipColors(
                                containerColor = colors.onSurface.copy(alpha = 0.04f),
                                labelColor = colors.onSurface,
                                trailingIconColor = colors.onSurfaceVariant
                            ),
                            border = InputChipDefaults.inputChipBorder(
                                enabled = true, selected = false,
                                borderColor = colors.outlineVariant.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(Modifier.padding(horizontal = 24.dp)) {
                SheetTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search payments",
                    placeholder = "Search recent payments",
                    leadingIcon = Icons.Default.Search,
                    singleLine = true
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (visible.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.LinkOff, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (query.isBlank()) "No payments in the last $windowDays days" else "No payees match \"${query.trim()}\"",
                                fontSize = 14.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                if (thisMonth.isNotEmpty()) {
                    item(key = "hdr_this_month") { LinkSectionHeader("This month") }
                    items(thisMonth, key = { it.id }) { t ->
                        LinkTxnRow(t, matchState(item, t.payee), dateFmt) { onRowClick(t) }
                    }
                }
                if (earlier.isNotEmpty()) {
                    item(key = "hdr_earlier") { LinkSectionHeader("Earlier") }
                    items(earlier, key = { it.id }) { t ->
                        LinkTxnRow(t, matchState(item, t.payee), dateFmt) { onRowClick(t) }
                    }
                }
            }

            SheetFooter {
                SheetPrimaryButton(text = "Done", enabled = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun LinkSectionHeader(title: String) {
    Text(
        title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun LinkTxnRow(
    txn: TransactionEntity,
    state: MatchState,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val linked = state == MatchState.LINKED
    val catColor = getCategoryColor(txn.category)

    Surface(
        shape = RoundedCornerShape(AppRadius.medium),
        color = if (linked) scheme.onSurface.copy(alpha = 0.04f) else scheme.surface,
        border = BorderStroke(
            1.dp,
            if (linked) scheme.onSurface else scheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .quietClickable(enabled = state != MatchState.AUTO, onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NecessityIcon(getCategoryIcon(txn.category), tint = catColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    txn.payee, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${dateFmt.format(txn.timestamp)} · ${txn.category}", fontSize = 12.sp,
                    color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CurrencyFormat.withSymbol(txn.amount, 0), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = scheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                when (state) {
                    MatchState.LINKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = scheme.onSurface, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Linked", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    }
                    MatchState.AUTO -> Text("Name match", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                    MatchState.NONE -> Text("Tap to link", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// UNDO BAR
// ─────────────────────────────────────────────────────────────
@Composable
private fun NecessityUndoBar(
    label: String,
    startTimeMs: Long,
    modifier: Modifier = Modifier,
    durationMs: Int = 5000,
    onUndo: () -> Unit
) {
    val initialElapsed = System.currentTimeMillis() - startTimeMs
    val remainingMs = maxOf(0L, durationMs - initialElapsed)
    val initialProgress = (remainingMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val progress = remember(startTimeMs) { Animatable(initialProgress) }

    LaunchedEffect(startTimeMs) {
        if (remainingMs > 0) {
            progress.animateTo(0f, tween(remainingMs.toInt(), easing = LinearEasing))
        }
    }

    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = cardBorder()
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    NecessityIcon(
                        Icons.Default.DeleteOutline,
                        tint = AppColors.negative,
                        containerColor = AppColors.negative.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .quietClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onUndo()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                    Text("UNDO", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer {
                        scaleX = progress.value.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────────────────────
@Composable
private fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

/** One due date for every bill: NecessityManager's own, else an estimate for monthly bills. */
private fun effectiveDue(item: NecessityItem, txns: List<TransactionEntity>, now: Long): Long? =
    NecessityManager.computeEffectiveDueDate(item, txns)
        ?: if (item.frequency == NecessityFrequency.MONTHLY)
            estimateMonthlyDue(item, NecessityManager.findLastTransaction(item, txns)?.timestamp, now)
        else null

/**
 * NecessityManager gives monthly bills no due date. Estimate one: the manual due date if set,
 * else last payment + 1 month, rolled forward to the next occurrence on/after today.
 */
private fun estimateMonthlyDue(item: NecessityItem, lastPaid: Long?, now: Long): Long? {
    val cal = Calendar.getInstance()
    val manual = item.dueTimestamp
    when {
        manual != null -> cal.timeInMillis = manual
        lastPaid != null -> { cal.timeInMillis = lastPaid; cal.add(Calendar.MONTH, 1) }
        else -> return null
    }
    val today = dayStart(now)
    var guard = 0
    while (cal.timeInMillis < today && guard++ < 600) cal.add(Calendar.MONTH, 1)
    return cal.timeInMillis
}

private fun dayStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Calendar-day difference (negative = overdue), so "due today" doesn't flip to overdue after midnight-stamped dates. */
private fun daysUntil(due: Long, now: Long): Int =
    Math.round((dayStart(due) - dayStart(now)) / 86_400_000.0).toInt()

private fun relativeDue(days: Int): String = when {
    days < 0 -> "${-days}d overdue"
    days == 0 -> "today"
    days == 1 -> "tomorrow"
    else -> "in $days days"
}

/** "15 Apr" within the current year, "15 Apr 2027" otherwise. */
private fun formatDue(due: Long, now: Long): String {
    val sameYear = Calendar.getInstance().apply { timeInMillis = due }.get(Calendar.YEAR) ==
            Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
    return SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale.getDefault()).format(due)
}

private fun lumpSumSuffix(months: Int): String = when (months) {
    12 -> "/yr"
    3 -> "/qtr"
    else -> "/$months mo"
}

@Composable
private fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.6f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { this.alpha = alpha }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

/** Shared icon chip: 12dp radius, 4% tint, centred glyph. */
@Composable
private fun NecessityIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}