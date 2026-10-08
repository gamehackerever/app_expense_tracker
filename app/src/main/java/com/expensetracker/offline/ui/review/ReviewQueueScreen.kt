package com.expensetracker.offline.ui.review

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.ui.components.AppDialog
import com.expensetracker.offline.ui.components.PaymentMethodSelector
import com.expensetracker.offline.ui.components.SheetChip
import com.expensetracker.offline.ui.components.SheetSegmentedControl
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.ExpenseRed
import com.expensetracker.offline.util.tick
import com.expensetracker.offline.util.click
import com.expensetracker.offline.util.confirm
import com.expensetracker.offline.util.delete
import com.expensetracker.offline.ui.theme.IncomeGreen
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.absoluteValue

private enum class CardStage { SWIPING, COLLAPSING_TO_UNDO, UNDO, COLLAPSING_FINAL }

private const val UNDO_WINDOW_MS = 4000
private const val COLLAPSE_TO_UNDO_MS = 480
private const val RESTORE_MS = 420
private const val COLLAPSE_FINAL_MS = 380

private val AMOUNT_LOCALE = Locale.US

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReviewQueueScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val items by viewModel.pendingReviewItems.collectAsState()
    val linkedAccounts by viewModel.linkedAccounts.collectAsState() // NEW: Grab linked accounts
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }

    // The "tips" banner only needs to be seen until the user dismisses it once
    var tipDismissed by remember { mutableStateOf(prefs.getBoolean("key_review_tip_dismissed", false)) }

    // Bulk actions ask first
    var confirmBulkApprove by remember { mutableStateOf(false) }
    var confirmBulkDiscard by remember { mutableStateOf(false) }

    // Selection State
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    // Inbox Zero Animation State
    val iconScale = remember { Animatable(0.5f) }
    var hasCelebrated by remember { mutableStateOf(false) }

    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty() && !hasCelebrated) {
            hasCelebrated = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            iconScale.animateTo(
                targetValue = 1.2f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
            iconScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
            )
        } else if (items.isNotEmpty()) {
            hasCelebrated = false
            iconScale.snapTo(0.5f)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (isSelectionMode) {
                            Text("${selectedIds.size} Selected", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        } else {
                            Text("Needs Review", fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSelectionMode) {
                                isSelectionMode = false
                                selectedIds = emptySet()
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                confirmBulkApprove = true
                            }) { Icon(Icons.Default.CheckCircle, tint = MaterialTheme.colorScheme.onSurface, contentDescription = "Approve selected") }

                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                confirmBulkDiscard = true
                            }) { Icon(Icons.Default.DeleteOutline, tint = ExpenseRed, contentDescription = "Discard selected") }
                        } else if (items.isNotEmpty()) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isSelectionMode = true
                                selectedIds = items.map { it.id }.toSet()
                            }) { Icon(Icons.Default.SelectAll, contentDescription = "Select All") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(AppSpacing.screenPadding),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp).scale(iconScale.value)
                        )
                        Text("All caught up!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                        Text("Nothing needs your attention. Messages we're unsure about will show up here.", fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            var unknownEduDismissed by remember { mutableStateOf(prefs.getBoolean("key_unknown_edu_dismissed", false)) }
            val hasUnknownDeduction = remember(items) { items.any { it.extractedPartialPayee.isNullOrBlank() } }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = AppSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (!isSelectionMode) {
                    item { ReviewExplainer(items.size) }

                    if (hasUnknownDeduction && !unknownEduDismissed) {
                        item {
                            SilentDeductionBanner(onDismiss = {
                                unknownEduDismissed = true
                                prefs.edit().putBoolean("key_unknown_edu_dismissed", true).apply()
                            })
                        }
                    }

                    if (!tipDismissed) {
                        item {
                            SwipeHintBanner(onDismiss = {
                                tipDismissed = true
                                prefs.edit().putBoolean("key_review_tip_dismissed", true).apply()
                            })
                        }
                    }
                }

                items(items, key = { it.id }) { item ->
                    ReviewCard(
                        modifier = Modifier.animateItem(),
                        item = item,
                        linkedAccounts = linkedAccounts, // NEW: Passed in
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedIds.contains(item.id),
                        onToggleSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedIds = if (selectedIds.contains(item.id)) selectedIds - item.id else selectedIds + item.id
                            if (selectedIds.isEmpty()) isSelectionMode = false
                        },
                        onLongPress = {
                            if (!isSelectionMode) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isSelectionMode = true
                                selectedIds = setOf(item.id)
                            }
                        },
                        // UPDATED: Now receives the bank details from the callback
                        onResolve = { amt, payee, type, cat, bankName, accNum ->
                            // Update the item copy before resolving it so the ViewModel gets the manual bank changes
                            viewModel.resolveReview(item, amt, payee, type, cat, bankName, accNum)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDiscard = {
                            viewModel.discardReview(item.id)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }
            }
        }
    }

    if (confirmBulkApprove) {
        val count = selectedIds.size
        AppDialog(
            title = "Approve $count ${if (count == 1) "transaction" else "transactions"}?",
            onDismiss = { confirmBulkApprove = false },
            confirmText = "Approve",
            onConfirm = {
                val selectedItems = items.filter { selectedIds.contains(it.id) }
                viewModel.bulkResolveReviewItems(selectedItems) { approved, skipped ->
                    if (skipped > 0) {
                        Toast.makeText(context, "$approved approved. $skipped still need an amount. Tap them to fix.", Toast.LENGTH_LONG).show()
                    }
                }
                confirmBulkApprove = false
                isSelectionMode = false
                selectedIds = emptySet()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        ) {
            Text("They'll be added using the amount and merchant we could read from each message. Merchants we don't recognise stay Uncategorized, and you can change them later. To pick categories now, approve them one at a time instead.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }

    if (confirmBulkDiscard) {
        val count = selectedIds.size
        AppDialog(
            title = "Discard $count ${if (count == 1) "item" else "items"}?",
            onDismiss = { confirmBulkDiscard = false },
            confirmText = "Discard",
            destructive = true,
            onConfirm = {
                viewModel.bulkDiscardReviewItems(selectedIds.toList())
                confirmBulkDiscard = false
                isSelectionMode = false
                selectedIds = emptySet()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        ) {
            Text("They won't be added to your expenses. This can't be undone.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewExplainer(count: Int) {
    Column {
        Text(
            "$count ${if (count == 1) "message" else "messages"} to review",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "These looked like transactions, but we couldn't read every detail. " +
                    "Check each one, then approve it, or discard it if it isn't real.",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SwipeHintBanner(onDismiss: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Quick tips", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("Swipe right to approve • Swipe left to discard • Long-press to select several", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss tips", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReviewCard(
    modifier: Modifier = Modifier,
    item: ReviewItemEntity,
    linkedAccounts: List<AccountInfo> = emptyList(),
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onResolve: (amount: Long, payee: String, type: TransactionType, category: String, bankName: String?, accountNumber: String?) -> Unit,
    onDiscard: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    var amountText by rememberSaveable { mutableStateOf(item.extractedPartialAmount?.let { String.format(AMOUNT_LOCALE, "%.2f", it / 100.0) } ?: "") }

    var payeeText by rememberSaveable {
        mutableStateOf(
            item.extractedPartialPayee?.takeIf { it.isNotBlank() }
                ?: if (item.rawContent.startsWith("Silent Deduction", ignoreCase = true)) "Silent Deduction" else ""
        )
    }

    var transactionType by rememberSaveable { mutableStateOf(TransactionType.DEBIT) }
    var categoryText by rememberSaveable { mutableStateOf("Uncategorized") }
    var userSetType by rememberSaveable { mutableStateOf(false) }
    var userSetCategory by rememberSaveable { mutableStateOf(false) }

    var selectedBankName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAccountNum by rememberSaveable { mutableStateOf<String?>(null) }
    var hasSelectedBankExplicitly by rememberSaveable { mutableStateOf(false) }

    // Collapse state based on whether an amount is missing
    var expanded by rememberSaveable(item.id) { mutableStateOf(item.extractedPartialAmount == null) }

    fun resolvedBankInfo(): Pair<String?, String?> {
        if (hasSelectedBankExplicitly) return selectedBankName to selectedAccountNum
        if (selectedBankName != null) return selectedBankName to selectedAccountNum
        val fallback = FinancialParser.parse(item.rawContent)
        return fallback.bankName to fallback.accountNumber
    }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(item.rawContent) {
        withContext(Dispatchers.Default) {
            val parsed = FinancialParser.parse(item.rawContent)
            val catToClassify = item.extractedPartialPayee?.takeIf { it.isNotBlank() } ?: parsed.payee ?: "Unknown"
            val cat = CategoryClassifier.classifySync(catToClassify, item.rawContent)

            withContext(Dispatchers.Main) {
                if (amountText.isBlank() && parsed.amount != null) {
                    amountText = String.format(AMOUNT_LOCALE, "%.2f", parsed.amount / 100.0)
                }
                if ((payeeText.isBlank() || payeeText == "Silent Deduction") && !parsed.payee.isNullOrBlank()) {
                    payeeText = parsed.payee
                }
                if (selectedBankName == null && !parsed.bankName.isNullOrBlank()) {
                    selectedBankName = parsed.bankName
                    selectedAccountNum = parsed.accountNumber
                }
                if (!userSetType && parsed.type != null) transactionType = parsed.type
                if (!userSetCategory) categoryText = cat
            }
        }
    }

    val standardCategories = listOf("Uncategorized", "Food & Dining", "Groceries", "Shopping",
        "Travel", "Bills & Utilities", "Entertainment", "Health")

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var fullHeightPx by remember { mutableStateOf(0f) }
    val undoBarHeightPx = with(density) { 56.dp.toPx() }

    val animatedHeightPx = remember { Animatable(0f) }
    val undoProgress = remember { Animatable(1f) }

    val neutralColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val undoBarColor = remember { Animatable(neutralColor, Color.VectorConverter(neutralColor.colorSpace)) }

    var stage by remember { mutableStateOf(CardStage.SWIPING) }
    var pendingLabel by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var collapseSeedColor by remember { mutableStateOf(neutralColor) }
    var swipeEpoch by remember { mutableStateOf(0) }
    var actionFired by remember { mutableStateOf(false) }

    val cardBgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface,
        label = "cardBgColor"
    )
    val cardBorderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        label = "cardBorderColor"
    )

    fun firePendingActionOnce() {
        if (!actionFired) {
            actionFired = true
            pendingAction?.invoke()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (stage != CardStage.SWIPING) firePendingActionOnce()
        }
    }

    val commitScope = rememberCoroutineScope()
    fun commit(label: String, seedColor: Color, action: () -> Unit) {
        if (stage != CardStage.SWIPING) return
        pendingLabel = label
        pendingAction = action
        collapseSeedColor = seedColor
        commitScope.launch {
            undoBarColor.snapTo(seedColor)
            stage = CardStage.COLLAPSING_TO_UNDO
        }
    }

    LaunchedEffect(fullHeightPx) {
        if (fullHeightPx > 0f && animatedHeightPx.value == 0f) animatedHeightPx.snapTo(fullHeightPx)
    }

    // Keep the pinned height in sync when the card's content grows/shrinks (expand/collapse).
    LaunchedEffect(fullHeightPx, animatedHeightPx.isRunning) {
        if (stage == CardStage.SWIPING && !animatedHeightPx.isRunning &&
            fullHeightPx > 0f && animatedHeightPx.value != fullHeightPx
        ) {
            animatedHeightPx.snapTo(fullHeightPx)
        }
    }

    LaunchedEffect(stage) {
        when (stage) {
            CardStage.SWIPING -> {
                if (fullHeightPx > 0f && animatedHeightPx.value != fullHeightPx) {
                    animatedHeightPx.animateTo(targetValue = fullHeightPx, animationSpec = tween(RESTORE_MS, easing = FastOutSlowInEasing))
                }
            }
            CardStage.COLLAPSING_TO_UNDO -> {
                coroutineScope {
                    launch { animatedHeightPx.animateTo(targetValue = undoBarHeightPx, animationSpec = tween(COLLAPSE_TO_UNDO_MS, easing = FastOutSlowInEasing)) }
                    launch { undoBarColor.animateTo(targetValue = neutralColor, animationSpec = tween(COLLAPSE_TO_UNDO_MS, easing = FastOutSlowInEasing)) }
                }
                stage = CardStage.UNDO
            }
            CardStage.UNDO -> {
                undoProgress.snapTo(1f)
                undoProgress.animateTo(targetValue = 0f, animationSpec = tween(UNDO_WINDOW_MS, easing = LinearEasing))
                stage = CardStage.COLLAPSING_FINAL
            }
            CardStage.COLLAPSING_FINAL -> {
                animatedHeightPx.animateTo(targetValue = 0f, animationSpec = tween(COLLAPSE_FINAL_MS, easing = FastOutSlowInEasing))
                firePendingActionOnce()
            }
        }
    }

    val dismissState = key(swipeEpoch) {
        rememberSwipeToDismissBoxState(
            positionalThreshold = { totalDistance -> totalDistance * 0.4f },
            confirmValueChange = { dismissValue ->
                if (isSelectionMode) return@rememberSwipeToDismissBoxState false

                when (dismissValue) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        val parsedAmt = (amountText.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
                        if (parsedAmt > 0L) {
                            val payee = payeeText.trim().ifBlank { "Unknown" }
                            val category = categoryText.trim().ifBlank { "Uncategorized" }
                            val (finalBank, finalAcct) = resolvedBankInfo()
                            commit("Approved · ₹${String.format(AMOUNT_LOCALE, "%.2f", parsedAmt / 100.0)} to $payee", IncomeGreen) {
                                onResolve(parsedAmt, payee, transactionType, category, finalBank, finalAcct)
                            }
                            true
                        } else false
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        commit("Discarded", ExpenseRed) { onDiscard() }
                        true
                    }
                    else -> false
                }
            }
        )
    }

    var hasCrossedThreshold by remember { mutableStateOf(false) }
    val showUndo = stage == CardStage.UNDO || stage == CardStage.COLLAPSING_FINAL
    val animatedHeightDp = with(density) { animatedHeightPx.value.toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeightDp)
            .clipToBounds()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onGloballyPositioned { coordinates ->
                    if (!showUndo && coordinates.size.height > 0) fullHeightPx = coordinates.size.height.toFloat()
                }
        ) {
            if (showUndo) {
                UndoBar(
                    label = pendingLabel,
                    progress = undoProgress.value,
                    backgroundColor = undoBarColor.value,
                    onUndo = {
                        swipeEpoch++
                        hasCrossedThreshold = false
                        stage = CardStage.SWIPING
                    }
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val thresholdPx = remember(maxWidth) { (with(density) { maxWidth.toPx() } * 0.4f).coerceAtLeast(1f) }
                    val rawOffset by remember { derivedStateOf { runCatching { dismissState.requireOffset() }.getOrDefault(0f) } }

                    val pullFraction by remember {
                        derivedStateOf {
                            val linear = rawOffset.absoluteValue / thresholdPx
                            if (linear <= 1f) linear else 1f + (1f - 1f / (1f + (linear - 1f))) * 0.35f
                        }
                    }

                    LaunchedEffect(pullFraction >= 1f) {
                        if (pullFraction >= 1f && !hasCrossedThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasCrossedThreshold = true
                        } else if (pullFraction < 1f) {
                            hasCrossedThreshold = false
                        }
                    }

                    SwipeToDismissBox(
                        state = dismissState,
                        modifier = Modifier.clip(RoundedCornerShape(AppRadius.medium)),
                        backgroundContent = {
                            val isRightSwipe = rawOffset > 0f
                            val bgColor = if (stage == CardStage.SWIPING) {
                                val alphaWash = pullFraction.coerceIn(0f, 1f) * 0.15f
                                MaterialTheme.colorScheme.onSurface.copy(alpha = alphaWash)
                            } else {
                                undoBarColor.value
                            }
                            val icon = if (isRightSwipe) Icons.Default.CheckCircle else Icons.Default.DeleteOutline
                            val iconTint = if (isRightSwipe) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                            val alignment = if (isRightSwipe) Alignment.CenterStart else Alignment.CenterEnd

                            val iconScale by animateFloatAsState(
                                targetValue = 0.65f + pullFraction.coerceIn(0f, 1.35f) * 0.6f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh),
                                label = "iconScale"
                            )

                            Box(
                                modifier = Modifier.fillMaxSize().background(bgColor).padding(horizontal = 32.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp).scale(iconScale))
                            }
                        },
                        content = {
                            val cardScale by animateFloatAsState(
                                targetValue = 1f - pullFraction.coerceIn(0f, 1f) * 0.035f,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                label = "cardScale"
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = cardScale; scaleY = cardScale },
                                shape = RoundedCornerShape(AppRadius.medium),
                                color = cardBgColor,
                                border = BorderStroke(1.dp, cardBorderColor)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {

                                    // --- 1. COMPACT SUMMARY ROW ---
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .quietCombinedClickable(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    if (isSelectionMode) onToggleSelect() else expanded = !expanded
                                                },
                                                onLongClick = onLongPress
                                            )
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelectionMode) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = "Select",
                                                tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.padding(end = 12.dp).size(22.dp)
                                            )
                                        }

                                        Column(Modifier.weight(1f)) {
                                            Text(payeeText.ifBlank { "Unknown merchant" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            // Hiding redundant preview snippet when card is expanded
                                            if (!expanded) {
                                                Text(item.rawContent.take(60), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        val needsAmount = ((amountText.toDoubleOrNull()?.times(100))?.toLong() ?: 0L) <= 0L
                                        Text(
                                            if (needsAmount) "Add amount" else "₹$amountText",
                                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                            color = if (needsAmount) AppColors.warning else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (!isSelectionMode) {
                                            val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                                            Icon(
                                                Icons.Default.ExpandMore,
                                                contentDescription = if (expanded) "Collapse" else "Expand",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 4.dp).size(20.dp).rotate(chevronRotation)
                                            )
                                        }
                                    }

                                    // --- 2. EXPANDED FULL FORM ---
                                    AnimatedVisibility(visible = expanded) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.RateReview, contentDescription = null, tint = AppColors.warning, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Raw Message", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.warning)
                                                }
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                    shape = RoundedCornerShape(AppRadius.small),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = item.rawContent,
                                                        fontSize = 13.sp,
                                                        lineHeight = 18.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(12.dp)
                                                    )
                                                }
                                            }

                                            SheetSegmentedControl(
                                                options = listOf("Expense (Debit)", "Income (Credit)"),
                                                selectedIndex = if (transactionType == TransactionType.DEBIT) 0 else 1,
                                                onSelect = { index ->
                                                    transactionType = if (index == 0) TransactionType.DEBIT else TransactionType.CREDIT
                                                    userSetType = true
                                                }
                                            )

                                            ReviewTextField(
                                                value = amountText,
                                                onValueChange = { input ->
                                                    if (input.isEmpty() || input.matches(Regex("""^\d{0,9}(\.\d{0,2})?$"""))) amountText = input
                                                },
                                                label = "Amount (₹)",
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Decimal,
                                                    imeAction = ImeAction.Done
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onDone = { focusManager.clearFocus() }
                                                )
                                            )

                                            ReviewTextField(
                                                value = payeeText,
                                                onValueChange = { payeeText = it },
                                                label = "Payee / Merchant",
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Text,
                                                    imeAction = ImeAction.Done
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onDone = { focusManager.clearFocus() }
                                                )
                                            )

                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text("Category", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                                                @OptIn(ExperimentalLayoutApi::class)
                                                FlowRow(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    standardCategories.forEach { cat ->
                                                        val isCatSelected = categoryText.equals(cat, ignoreCase = true)
                                                        SheetChip(
                                                            text = cat,
                                                            isSelected = isCatSelected,
                                                            accent = getCategoryColor(cat),
                                                            icon = if (isCatSelected) Icons.Default.Check else getCategoryIcon(cat),
                                                            onClick = {
                                                                categoryText = cat
                                                                userSetCategory = true
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            if (linkedAccounts.size > 1) {
                                                PaymentMethodSelector(
                                                    linkedAccounts = linkedAccounts,
                                                    selectedBankName = selectedBankName,
                                                    selectedAccountNumber = selectedAccountNum,
                                                    onBankSelected = { bank, acc ->
                                                        selectedBankName = bank
                                                        selectedAccountNum = acc
                                                        hasSelectedBankExplicitly = true
                                                    },
                                                    nullLabel = "Unspecified"
                                                )
                                            }

                                            if (!isSelectionMode) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    Surface(
                                                        shape = RoundedCornerShape(AppRadius.small),
                                                        // Light red tint for semantics
                                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(AppRadius.small)).quietClickable {
                                                            commit("Discarded", ExpenseRed) { onDiscard() }
                                                        }
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text("Discard", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }

                                                    val isEnabled = ((amountText.toDoubleOrNull()?.times(100))?.toLong() ?: 0L) > 0L
                                                    Surface(
                                                        shape = RoundedCornerShape(AppRadius.small),
                                                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                                                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(AppRadius.small)).quietClickable(enabled = isEnabled) {
                                                            val parsedAmt = (amountText.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
                                                            if (parsedAmt > 0L) {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                val payee = payeeText.trim().ifBlank { "Unknown" }
                                                                val category = categoryText.trim().ifBlank { "Uncategorized" }
                                                                val (finalBank, finalAcct) = resolvedBankInfo()
                                                                commit("Approved · ₹${String.format(AMOUNT_LOCALE, "%.2f", parsedAmt / 100.0)} to $payee", IncomeGreen) {
                                                                    onResolve(parsedAmt, payee, transactionType, category, finalBank, finalAcct)
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text("Approve", color = if (isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UndoBar(
    label: String,
    progress: Float,
    backgroundColor: Color,
    onUndo: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(AppRadius.medium),
        color = backgroundColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUndo()
                    }.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                    Text("UNDO", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Box(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction = progress.coerceIn(0f, 1f)).height(2.dp).background(MaterialTheme.colorScheme.onSurface)
            )
        }
    }
}

@Composable
private fun ReviewTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppRadius.small)
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.onSurface else Color.Transparent,
        label = "fieldBorder"
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.onSurface.copy(alpha = 0.04f))
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, fontSize = 16.sp, color = colors.onSurfaceVariant.copy(alpha = 0.55f))
                        }
                        inner()
                    }
                }
            }
        }
    )
}

/** Click handler with no ripple; presses fade the element slightly instead. */
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

/** Tap + long-press with no ripple; presses fade the element slightly instead. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.quietCombinedClickable(
    pressedAlpha: Float = 0.8f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { this.alpha = alpha }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

@Composable
fun SilentDeductionBanner(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("What is a Silent Deduction?", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.surface)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "We noticed your bank balance dropped, but you never received an SMS alert to explain it. We caught the discrepancy and placed it here so your totals stay perfectly accurate.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(AppRadius.small),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Text("Got it", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
