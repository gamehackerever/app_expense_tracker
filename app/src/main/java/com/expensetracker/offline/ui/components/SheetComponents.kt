package com.expensetracker.offline.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.util.ImageUtil
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun HapticFeedback.tap() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SheetSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trailing != null) {
                Text(
                    trailing,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        content()
    }
}

@Composable
fun SheetCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), // Softened borders
    shape: Shape = RoundedCornerShape(AppRadius.medium),
    contentPadding: Dp = 16.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

@Composable
fun SheetIconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), // Premium neutral background
        modifier = modifier
            .size(40.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SheetInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetIconTile(icon = icon, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SheetBadge(text: String, color: Color, icon: ImageVector? = null) {
    Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape = RoundedCornerShape(AppRadius.pill)) {        Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
    }
}

@Composable
fun SheetSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val pill = RoundedCornerShape(AppRadius.pill)
    val colors = MaterialTheme.colorScheme

    // 1. Measure pixel width directly (avoids heavy subcomposition)
    var segmentWidthPx by remember { mutableFloatStateOf(0f) }

    // 2. Animate the index, NOT the raw pixels.
    // This prevents the thumb from accidentally "sliding in" on first mount.
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(220),
        label = "segThumbIndex"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(pill)
            .background(colors.onSurface.copy(alpha = 0.05f))
            .padding(4.dp)
            .onSizeChanged { size ->
                segmentWidthPx = size.width.toFloat() / options.size
            }
    ) {
        // Animated Thumb (Hardware Accelerated)
        Box(
            Modifier
                .fillMaxWidth(1f / options.size)
                .fillMaxHeight()
                .graphicsLayer { translationX = segmentWidthPx * animatedIndex }
                .shadow(1.dp, pill)
                .background(colors.surface, pill)
        )

        // Labels
        // Inside your SheetSegmentedControl Box:
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                // FIX: Use stark neutral (onSurface) instead of the primary color for active text
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) colors.onSurface else colors.onSurface.copy(alpha = 0.7f),
                    label = "segText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .clickable {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        // 1. Drop the font size to give longer labels like "Half-yearly" breathing room
                        fontSize = 13.sp, // Changed from 15.sp
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                        // 2. Force a single line and add an ellipsis fallback for very narrow screens
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun SheetChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    val haptic = LocalHapticFeedback.current
    val pill = RoundedCornerShape(AppRadius.pill)

    // FIX: Active state is a solid neutral dark, inactive is surface
    val bg by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        label = "chipBg"
    )
    val border by animateColorAsState(
        targetValue = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        label = "chipBorder"
    )
    val fg by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipText"
    )

    Surface(
        shape = pill,
        color = bg,
        border = if (isSelected) null else BorderStroke(1.dp, border),
        modifier = modifier.clip(pill).clickable {
            if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = fg
            )
        }
    }
}

@Composable
fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.onSurface, // Swapped to stark neutral
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)

@Composable
fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 3,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppRadius.small)

    // FIX: Soft translucent border when focused, completely transparent when unfocused
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.onSurface.copy(alpha = 0.15f) else Color.Transparent,
        label = "fieldBorder"
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        keyboardOptions = keyboardOptions,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interaction,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    // FIX: Replaced explicit colors.surface background with a soft tinted fill
                    .background(colors.onSurface.copy(alpha = 0.04f))
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.onSurface.copy(alpha = 0.04f)), // Unified neutral background
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(leadingIcon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) colors.onSurface else colors.onSurfaceVariant, // Swapped to onSurface
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

@Composable
fun SheetAmountCard(
    amountStr: String,
    onAmountChange: (String) -> Unit,
    calculatedAmount: Double?,
    isDebit: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val accent = if (isDebit) MaterialTheme.colorScheme.onSurface else AppColors.positive
    // FIXED: Include comma in allowed symbols so the keyboard doesn't reject pastes (PO-I)
    val allowedSymbols = remember { setOf('.', '+', '-', '*', '/', '(', ')', ' ', ',') }

    // Fintech typographic scale: massive, confident, tight tracking
    val bigStyle = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, letterSpacing = (-1).sp)

    SheetCard(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(AppRadius.large),
        contentPadding = 16.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Amount", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trailing != null) trailing()
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("₹", fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = accent, letterSpacing = (-1).sp)
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = amountStr,
                onValueChange = { input ->
                    if (input.all { it.isDigit() || it in allowedSymbols }) onAmountChange(input)
                },
                singleLine = true,
                textStyle = bigStyle,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (amountStr.isEmpty()) {
                            Text(
                                "0.00",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        if (calculatedAmount != null && amountStr.any { it in listOf('+', '-', '*', '/') }) {
            Text(
                text = "= ₹${String.format(Locale.US, "%.2f", calculatedAmount)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 36.dp, top = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SheetCategoryPicker(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onAddCustom: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showPicker by remember { mutableStateOf(false) }
    val selectedIcon = getCategoryIcon(selected)

    SheetCard(
        onClick = {
            haptic.tap()
            showPicker = true
        },
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), // match SheetTextField's 14dp inner inset
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // FIX: Force a pure neutral background, overriding any theme tint
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center
            ) {
                if (selectedIcon != null) {
                    Icon(selectedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Category", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    selected,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Choose category",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = selected.equals(cat, ignoreCase = true)
                        SheetChip(
                            text = cat,
                            isSelected = isSelected,
                            onClick = {
                                onSelect(cat)
                                showPicker = false
                            },
                            accent = getCategoryColor(cat),
                            icon = if (isSelected) Icons.Default.Check else getCategoryIcon(cat)
                        )
                    }
                    SheetChip(
                        text = "New category",
                        isSelected = false,
                        onClick = {
                            showPicker = false
                            onAddCustom()
                        },
                        icon = Icons.Default.Add,
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun NewCategoryDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AppDialog(
        title = "New Category",
        onDismiss = onDismiss,
        confirmText = "Add",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { if (name.isNotBlank()) onAdd(name.trim()) }
    ) {
        Text("Create and assign a new category.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("e.g., Gym, Books, Gifts") },
            singleLine = true,
            shape = RoundedCornerShape(AppRadius.small),
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SheetFixedCostSuggestion(suggestion: NecessityManager.BillSuggestion, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary

    // Muted the card background, normalized the visual hierarchy
    SheetCard(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    ) {
        SheetInfoRow(
            icon = Icons.Default.Event,
            tint = primary,
            title = "Add ${NecessityManager.cleanBillName(suggestion.payee)} to Fixed Costs",
            subtitle = "₹${formatSheetAmount(suggestion.amount.toDouble())} · ${suggestion.frequency.label}",
            trailing = {
                // FIX: Solid, grounded background pill to remain visible in pure dark mode
                Surface(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(AppRadius.pill)
                ) {
                    Text(
                        "Add",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDatePill(millis: Long, onDateSelected: (Long) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val pill = RoundedCornerShape(AppRadius.pill)
    var showPicker by remember { mutableStateOf(false) }
    val label = remember(millis) { sheetShortDateLabel(millis) }

    Surface(
        shape = pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(pill)
            .clickable {
                haptic.tap()
                showPicker = true
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CalendarToday, contentDescription = "Change date", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = millis,
            selectableDates = object : SelectableDates {
                // FIXED: Prevent selecting dates in the future
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    return utcTimeMillis <= System.currentTimeMillis()
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { pickedUtc ->
                        // FIXED: Convert picked UTC midnight to local time, keeping the original hour/minute
                        // This prevents 00:00 - 05:30 IST from jumping back to yesterday (PO-I)
                        val cal = Calendar.getInstance()
                        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
                        val currentMin = cal.get(Calendar.MINUTE)
                        val currentSec = cal.get(Calendar.SECOND)

                        cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        cal.timeInMillis = pickedUtc

                        val localCal = Calendar.getInstance()
                        localCal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DATE), currentHour, currentMin, currentSec)

                        onDateSelected(localCal.timeInMillis)
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetDetailsSection(
    note: String,
    onNoteChange: (String) -> Unit,
    receiptUri: String?,
    onReceiptChange: (String?) -> Unit,
    receiptFilePrefix: String
) {
    var noteOpen by rememberSaveable { mutableStateOf(note.isNotBlank()) }
    var receiptOpen by rememberSaveable { mutableStateOf(receiptUri != null) }
    var focusNoteOnOpen by remember { mutableStateOf(false) }

    val pickReceipt = rememberReceiptPicker(receiptFilePrefix) { path ->
        onReceiptChange(path)
        receiptOpen = true
    }
    val receiptBitmap = rememberReceiptBitmap(receiptUri)

    val noteActive = noteOpen || note.isNotBlank()
    val receiptActive = receiptUri != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Checkboxes removed. Pills now act as clean tab-like expanders.
            SheetChip(
                text = if (noteActive) "Note" else "Add note",
                isSelected = noteActive,
                icon = if (noteActive) null else Icons.Default.Add,
                accent = MaterialTheme.colorScheme.primary,
                onClick = {
                    noteOpen = !noteOpen
                    focusNoteOnOpen = noteOpen
                }
            )
            SheetChip(
                text = if (receiptActive) "Receipt" else "Add receipt",
                isSelected = receiptActive,
                icon = if (receiptActive) null else Icons.Default.Add,
                accent = MaterialTheme.colorScheme.primary,
                onClick = {
                    if (receiptUri == null) pickReceipt() else receiptOpen = !receiptOpen
                }
            )
        }

        AnimatedVisibility(visible = noteOpen) {
            val focusRequester = remember { FocusRequester() }
            val bringIntoViewRequester = remember { BringIntoViewRequester() }
            val coroutineScope = rememberCoroutineScope()
            
            LaunchedEffect(Unit) {
                if (focusNoteOnOpen) runCatching { focusRequester.requestFocus() }
            }
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .onFocusChanged { 
                        if (it.isFocused) {
                            coroutineScope.launch { 
                                bringIntoViewRequester.bringIntoView() 
                            }
                        }
                    }
            ) {
                SheetTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = "Notes / Items Summary",
                    placeholder = "e.g., Shawarma, Lime Juice, Groceries",
                    leadingIcon = Icons.Default.Fastfood,
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.focusRequester(focusRequester)
                )
            }
        }

        AnimatedVisibility(visible = receiptOpen && receiptUri != null) {
            Box(modifier = Modifier.padding(top = 16.dp)) {
                SheetReceiptCard(
                    receiptBitmap = receiptBitmap,
                    onPick = pickReceipt,
                    onRemove = {
                        onReceiptChange(null)
                        receiptOpen = false
                    }
                )
            }
        }
    }
}

@Composable
fun SheetReceiptCard(
    receiptBitmap: Bitmap?,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showFull by remember { mutableStateOf(false) }

    SheetCard(
        modifier = Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SheetInfoRow(
            icon = Icons.Default.ReceiptLong,
            title = "Bill / Receipt Photo",
            subtitle = if (receiptBitmap == null) "Optional" else null,
            trailing = if (receiptBitmap != null) {
                { SheetBadge("Attached", AppColors.positive, Icons.Default.CheckCircle) }
            } else null
        )

        if (receiptBitmap != null) {
            val previewShape = RoundedCornerShape(AppRadius.small)
            Surface(
                shape = previewShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(previewShape)
                    .clickable {
                        haptic.tap()
                        showFull = true
                    }
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = receiptBitmap.asImageBitmap(),
                        contentDescription = "Bill Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    ) {
                        Text("Tap to view full", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }

            if (showFull) {
                FullScreenImageViewer(bitmap = receiptBitmap, onDismiss = { showFull = false })
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { haptic.tap(); onPick() },
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Change", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton( // Swapped from TextButton
                    onClick = { haptic.tap(); onRemove() },
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    // Added the 0.3f border to perfectly match the visual weight of the 'Change' button
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            OutlinedButton(
                onClick = { haptic.tap(); onPick() },
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth().height(48.dp), // or Modifier.weight(1f) for the Change button
                // FIX: Neutral text/icon instead of default primary
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp)) // or Edit
                Spacer(modifier = Modifier.width(8.dp))
                Text("Upload Bill Photo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) // or "Change"
            }
        }
    }
}

@Composable
fun SheetFooter(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            content = content
        )
    }
}

@Composable
fun SheetPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(AppRadius.small),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            // Drastically increased opacity for confident disabled states
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun rememberReceiptPicker(filePrefix: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { copyReceipt(context, uri, filePrefix) }
            if (path != null) currentOnPicked(path)
            else Toast.makeText(context, "Couldn't attach receipt", Toast.LENGTH_SHORT).show()
        }
    }
    return remember(launcher) { { launcher.launch("image/*") } }
}

private suspend fun copyReceipt(context: Context, uri: Uri, prefix: String): String? = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "receipts").apply { mkdirs() }
    val target = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    return@withContext try {
        // FIXED: Re-encode as JPEG Q80 to strip EXIF GPS location data and bound storage size (PO-I)
        val bitmap = ImageUtil.loadScaledBitmap(context, uri.toString(), 1600) ?: return@withContext null
        target.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bitmap.recycle()
        target.absolutePath
    } catch (e: Exception) {
        Log.e("BillUpload", "Error copying receipt", e)
        target.delete()
        null
    }
}

fun deleteReceiptFile(context: Context, path: String?) {
    if (path == null) return
    val f = File(path)
    if (f.parentFile?.canonicalPath == File(context.filesDir, "receipts").canonicalPath) f.delete()
}

@Composable
fun rememberReceiptBitmap(path: String?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    // FIXED: Load bitmap asynchronously off-thread to prevent UI freezing (PO-I)
    // REMOVED: bitmap.recycle() on dispose, as Compose manages bitmap memory internally.
    // Manual recycling causes "Canvas: trying to use a recycled bitmap" crashes.
    LaunchedEffect(path) {
        bitmap = if (path != null) {
            withContext(Dispatchers.IO) { ImageUtil.loadScaledBitmap(context, path, 1024) }
        } else null
    }

    return bitmap
}

internal fun formatSheetAmount(amount: Double): String =
    if (amount % 1.0 == 0.0) String.format(Locale.US, "%,.0f", amount) else String.format(Locale.US, "%,.2f", amount)

private fun sheetIsSameDay(millis: Long, other: Calendar): Boolean {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
}

private fun sheetShortDateLabel(millis: Long): String {
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sheetIsSameDay(millis, today)) return "Today"
    if (sheetIsSameDay(millis, yesterday)) return "Yesterday"
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
}