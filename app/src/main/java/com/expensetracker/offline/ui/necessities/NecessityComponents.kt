@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.offline.ui.necessities

import com.expensetracker.offline.ui.components.fadingHorizontalScroll
import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*


internal const val NO_CATEGORY = "No category"

// ─────────────────────────────────────────────────────────────
// BILL CARD
// ─────────────────────────────────────────────────────────────
@Composable
internal fun NecessityCard(
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
                val iconKey = item.name + " " + (cat ?: "")
                val iconAccent = getCategoryColor(iconKey)
                NecessityIcon(
                    icon = getCategoryIcon(iconKey),
                    tint = iconAccent,
                    containerColor = iconAccent.copy(alpha = 0.12f)
                )
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
                Spacer(Modifier.width(10.dp))
                Text(
                    amountLabel(item),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
            }

            if (vault != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurface.copy(alpha = 0.08f))
                ) {
                    val f = vault.fraction.coerceIn(0f, 1f)
                    if (f > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(scheme.onSurface)
                        )
                    }
                }
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        val paidLabel = when {
                            manuallyPaid -> "Paid · Undo"
                            effectiveAmount == 0L && amountPaid == 0L -> "Up to date"
                            else -> "Paid · Auto"
                        }
                        Text(
                            paidLabel,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Mark as paid",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (item.aliases.isEmpty()) "Link payee" else "${item.aliases.size} linked",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(AppRadius.small),
                    color = scheme.onSurface.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(AppRadius.small))
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
        }
    }
}

internal fun subtitleFor(item: NecessityItem): String {
    if (item.frequency == NecessityFrequency.MONTHLY) return "Monthly"
    val strategy = if (item.isProrated) "Sinking fund" else "Lump sum"
    val freq = if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0)
        "Every ${item.validityDays}d"
    else item.frequency.label
    return "$freq · $strategy"
}

internal fun amountLabel(item: NecessityItem): String = when {
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
internal fun FormLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun StrategyOption(
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
internal fun NecessityFormSheet(
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

    val autoTxn by produceState<TransactionEntity?>(initialValue = null, currentDraftItem, transactions) {
        if (currentDraftItem.name.isBlank()) {
            value = null
        } else {
            delay(150)
            value = withContext(Dispatchers.Default) {
                NecessityManager.findLastTransaction(currentDraftItem, transactions)
            }
        }
    }

    var extraCategories by remember { mutableStateOf(emptyList<String>()) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    val pickerCategories = remember(categories, extraCategories, seed?.categoryName) {
        listOf(NO_CATEGORY) +
                (categories + extraCategories + listOfNotNull(seed?.categoryName))
                    .filter { it.isNotBlank() }
                    .distinct()
    }

    val suggestions by produceState(initialValue = emptyList<NecessityManager.BillSuggestion>(), nameInput, transactions) {
        if (seed != null) {
            value = emptyList()
        } else {
            delay(200)
            value = withContext(Dispatchers.Default) {
                NecessityManager.suggestBills(nameInput, transactions.take(1000))
                    .filter { !it.payee.equals(nameInput.trim(), true) }
            }
        }
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
                        Modifier.fadingHorizontalScroll(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { s ->
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = colors.onSurface.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .clickable {
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
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp), tint = colors.onSurface)
                                    Text(
                                        "Found ${s.payee} (${CurrencyFormat.withSymbol(s.amount, 0)}" +
                                                "${if (s.frequency == NecessityFrequency.MONTHLY) "/mo" else ""}) · Tap to autofill",
                                        maxLines = 1,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                }
                            }
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
                            when (it) {
                                NecessityFrequency.MONTHLY -> "Monthly"
                                NecessityFrequency.EVERY_N_DAYS -> "Days"
                                NecessityFrequency.QUARTERLY -> "Quarterly"
                                NecessityFrequency.HALF_YEARLY -> "6-Month"
                                NecessityFrequency.YEARLY -> "Yearly"
                            }
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
                val autoDueDate by produceState<Long?>(initialValue = null, currentDraftItem, transactions) {
                    value = withContext(Dispatchers.Default) {
                        effectiveDue(currentDraftItem.copy(dueTimestamp = null), transactions, System.currentTimeMillis())
                    }
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
                            val currentAutoTxn = autoTxn
                            if (currentAutoTxn != null) {
                                Text(
                                    "Auto-detected from transaction history",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    "Last paid on ${dateFmt.format(currentAutoTxn.timestamp)} (${currentAutoTxn.payee})",
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
                                amount = parsedAmount ?: return@SheetPrimaryButton,
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
internal enum class MatchState { NONE, LINKED, AUTO }

internal fun matchState(item: NecessityItem, payee: String): MatchState = when {
    payee.contains(item.name, ignoreCase = true) -> MatchState.AUTO
    item.aliases.any { payee.contains(it, ignoreCase = true) } -> MatchState.LINKED
    else -> MatchState.NONE
}

internal fun cleanAlias(payee: String): String {
    val trimmed = payee.trim()
    val stripped = trimmed.replace(Regex("[\\s\\d]+$"), "")
    return if (stripped.length >= 3) stripped else trimmed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkTransactionSheet(
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
                    Modifier.fadingHorizontalScroll().padding(horizontal = 24.dp),
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
                    items(thisMonth, key = { "m_${it.id}" }) { t ->
                        LinkTxnRow(t, matchState(item, t.payee), dateFmt) { onRowClick(t) }
                    }
                }
                if (earlier.isNotEmpty()) {
                    item(key = "hdr_earlier") { LinkSectionHeader("Earlier") }
                    items(earlier, key = { "e_${it.id}" }) { t ->
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
internal fun LinkSectionHeader(title: String) {
    Text(
        title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
internal fun LinkTxnRow(
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
internal fun NecessityUndoBar(
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
internal fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

/** One due date for every bill: NecessityManager's own, else an estimate for monthly bills. */
internal fun effectiveDue(
    item: NecessityItem,
    txns: List<TransactionEntity>,
    now: Long,
    isPaid: Boolean = false
): Long? {
    val baseDue = NecessityManager.computeEffectiveDueDate(item, txns)
        ?: if (item.frequency == NecessityFrequency.MONTHLY) {
            estimateMonthlyDue(item, NecessityManager.findLastTransaction(item, txns)?.timestamp, now)
        } else null
    if (baseDue == null) return null
    if (!isPaid) return baseDue

    val today = dayStart(now)
    if (dayStart(baseDue) >= today) return baseDue

    return if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
        val cycleMillis = item.validityDays!! * 86_400_000L
        var d = baseDue
        var guard = 0
        while (dayStart(d) < today && guard++ < 600) d += cycleMillis
        d
    } else {
        val months = if (item.frequency.months > 0) item.frequency.months else 1
        val cal = Calendar.getInstance().apply { timeInMillis = baseDue }
        var guard = 0
        while (dayStart(cal.timeInMillis) < today && guard++ < 600) cal.add(Calendar.MONTH, months)
        cal.timeInMillis
    }
}

/**
 * NecessityManager gives monthly bills no due date. Estimate one: the manual due date if set,
 * else last payment + 1 month, rolled forward to the next occurrence on/after today.
 */
internal fun estimateMonthlyDue(item: NecessityItem, lastPaid: Long?, now: Long): Long? {
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

internal fun dayStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Calendar-day difference (negative = overdue), so "due today" doesn't flip to overdue after midnight-stamped dates. */
internal fun daysUntil(due: Long, now: Long): Int =
    Math.round((dayStart(due) - dayStart(now)) / 86_400_000.0).toInt()

internal fun relativeDue(days: Int): String = when {
    days < 0 -> "${-days}d overdue"
    days == 0 -> "today"
    days == 1 -> "tomorrow"
    else -> "in $days days"
}

/** "15 Apr" within the current year, "15 Apr 2027" otherwise. */
internal fun formatDue(due: Long, now: Long): String {
    val sameYear = Calendar.getInstance().apply { timeInMillis = due }.get(Calendar.YEAR) ==
            Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
    return SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale.getDefault()).format(due)
}

internal fun lumpSumSuffix(months: Int): String = when (months) {
    12 -> "/yr"
    3 -> "/qtr"
    else -> "/$months mo"
}

@Composable
internal fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.75f,
    pressedScale: Float = 0.98f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "quietScale"
    )
    return this
        .graphicsLayer {
            this.alpha = alpha
            this.scaleX = scale
            this.scaleY = scale
        }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

/** Shared icon chip: 12dp radius, 4% tint, centred glyph. */
@Composable
internal fun NecessityIcon(
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