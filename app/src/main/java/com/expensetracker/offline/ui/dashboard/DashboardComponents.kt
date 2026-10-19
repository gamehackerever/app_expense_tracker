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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.hasCategoryIcon
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


@Composable
internal fun TransactionUndoBar(
    label: String,
    startTimeMs: Long,
    modifier: Modifier = Modifier,
    durationMs: Int = 5000,
    onUndo: () -> Unit
) {
    val initialElapsed = System.currentTimeMillis() - startTimeMs
    val remainingMs = maxOf(0L, durationMs - initialElapsed)
    val initialProgress = (remainingMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val progress = remember(startTimeMs) { Animatable(initialProgress) }

    LaunchedEffect(startTimeMs) {
        if (remainingMs > 0) {
            progress.animateTo(0f, animationSpec = tween(remainingMs.toInt(), easing = LinearEasing))
        }
    }

    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
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
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text("UNDO", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction = progress.value.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────
// HELPER COMPOSABLES
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun ManageCategoriesDialog(
    customCategories: Set<String>,
    onDismiss: () -> Unit,
    onAddCategory: (String) -> Unit,
    onDeleteCategory: (String) -> Unit
) {
    var categoryName by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 400.dp), shape = RoundedCornerShape(AppRadius.large), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Manage Categories", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Add new custom categories for your expenses.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(24.dp))

                val focusManager = LocalFocusManager.current

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = categoryName,
                        onValueChange = { categoryName = it },
                        placeholder = { Text("New category name", fontSize = 14.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (categoryName.isNotBlank()) {
                                    onAddCategory(categoryName.trim())
                                    categoryName = ""
                                }
                                focusManager.clearFocus()
                            }
                        ),
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
                    )
                    Button(
                        onClick = {
                            if (categoryName.isNotBlank()) {
                                onAddCategory(categoryName.trim())
                                categoryName = ""
                            }
                        },
                        enabled = categoryName.isNotBlank(),
                        shape = RoundedCornerShape(AppRadius.small),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(52.dp)
                    ) { Text("Add") }
                }

                if (customCategories.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text("Your categories", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(customCategories.toList(), key = { it }) { cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cat, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                IconButton(onClick = { onDeleteCategory(cat) }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { haptic.tap(); onDismiss() }, modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp), shape = RoundedCornerShape(AppRadius.small)) { Text("Done", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun DeleteCategoryDialog(
    categoryToDelete: String,
    transactionCount: Int,
    availableCategories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (reassignTo: String?) -> Unit
) {
    var actionType by remember { mutableStateOf("reassign") }
    val haptic = LocalHapticFeedback.current
    val mergeTargets = remember(availableCategories) {
        (listOf("Uncategorized") + availableCategories)
            .filter { !it.equals(categoryToDelete, ignoreCase = true) }
            .distinct()
    }
    var selectedMergeTarget by remember { mutableStateOf(mergeTargets.firstOrNull() ?: "Uncategorized") }

    AppDialog(
        title = "Delete Category",
        onDismiss = { haptic.tap(); onDismiss() },
        confirmText = "Delete",
        destructive = true,
        onConfirm = {
            haptic.confirm()
            onConfirm(if (actionType == "reassign") selectedMergeTarget else null)
        }
    ) {
        Text(
            "You have $transactionCount transactions labeled as '$categoryToDelete'. What would you like to do with them?",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.small))
                .quietClickable { haptic.tap(); actionType = "reassign" },
            shape = RoundedCornerShape(AppRadius.small),
            color = if (actionType == "reassign") MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            border = BorderStroke(1.dp, if (actionType == "reassign") MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = actionType == "reassign",
                        onClick = { haptic.tap(); actionType = "reassign" },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Move to another category", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                if (actionType == "reassign") {
                    Spacer(modifier = Modifier.height(16.dp))
                    PremiumDropdownField(
                        selectedOption = selectedMergeTarget,
                        options = mergeTargets,
                        onOptionSelected = { selectedMergeTarget = it }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.small))
                .quietClickable { haptic.tap(); actionType = "legacy" },
            shape = RoundedCornerShape(AppRadius.small),
            color = if (actionType == "legacy") MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            border = BorderStroke(1.dp, if (actionType == "legacy") MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = actionType == "legacy",
                    onClick = { haptic.tap(); actionType = "legacy" },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Keep as legacy", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Past transactions stay as '$categoryToDelete'", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun SelectTargetDialog(currentTarget: Long, onDismiss: () -> Unit, onSaveTarget: (Long) -> Unit) {
    var textValue by remember { mutableStateOf(if (currentTarget > 0) currentTarget.toString().removeSuffix(".0") else "") }
    val haptic = LocalHapticFeedback.current
    val parsedValue = textValue.toLongOrNull()
    val isValid = textValue.isNotBlank() && parsedValue != null && parsedValue > 0L
    val focusManager = LocalFocusManager.current

    AppDialog(
        title = "Set monthly budget",
        onDismiss = { haptic.tap(); onDismiss() },
        confirmText = "Save",
        confirmEnabled = isValid,
        onConfirm = { parsedValue?.let(onSaveTarget) }
    ) {
        Text(
            "Enter your monthly spending limit to track progress against it.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        // Inside SelectTargetDialog, replace the OutlinedTextField with this:
        BasicTextField(
            value = textValue,
            onValueChange = { input ->
                val cleaned = input.replace(",", "").replace(" ", "")
                if (cleaned.isEmpty() || (cleaned.matches(Regex("""^\d{0,10}$""")))) {
                    textValue = cleaned
                }
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                if (isValid) parsedValue?.let(onSaveTarget)
            }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.small))
                        // FIX: Sleek unified background instead of a cutout border
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        .border(
                            1.dp,
                            if (textValue.isNotBlank() && !isValid) AppColors.negative
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(AppRadius.small)
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "₹",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (textValue.isEmpty()) {
                            Text(
                                "0",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        inner()
                    }
                }
            }
        )
    }
}

data class OnboardingTask(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val isDone: Boolean,
    val onClick: () -> Unit,
)

@Composable
internal fun OnboardingTaskItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isDone: Boolean,
    onClick: () -> Unit,
    isNext: Boolean = false,
    quickAction: Pair<String, () -> Unit>? = null
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(enabled = !isDone) { haptic.tap(); onClick() },
        color = when {
            isDone -> Color.Transparent
            isNext -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (isNext) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
        shape = RoundedCornerShape(AppRadius.small)
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp, horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (isDone) AppColors.positive.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDone) Icons.Default.Check else icon,
                        contentDescription = null,
                        tint = if (isDone) AppColors.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = if (isDone) FontWeight.Medium else FontWeight.SemiBold,
                        color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (!isDone && subtitle != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
                if (!isDone) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            if (!isDone && quickAction != null) {
                val (label, action) = quickAction
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 52.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .quietClickable { haptic.tap(); action() }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

/** Text that shrinks (down to [minSize]) instead of truncating when it doesn't fit its width. */
@Composable
internal fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: Float = 24f,
    minSize: Float = 15f,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    var size by remember(text) { mutableFloatStateOf(maxSize) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = fontWeight,
        letterSpacing = (-0.4).sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && size > minSize) size = (size - 1f).coerceAtLeast(minSize)
        }
    )
}

@Composable
internal fun DashboardHeader(
    monthLabel: String,
    cycleLabel: String,
    statusColor: Color,
    searchActive: Boolean,
    showSettingsBadge: Boolean,
    onCycleClick: () -> Unit,
    onSearch: () -> Unit,
    onAnalytics: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = AppSpacing.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AppRadius.small))
                .quietClickable(onClick = onCycleClick)
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    monthLabel.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(text = cycleLabel, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Change cycle",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        HeaderToolbar(
            searchActive = searchActive,
            showSettingsBadge = showSettingsBadge,
            onSearch = onSearch,
            onAnalytics = onAnalytics,
            onSettings = onSettings
        )
    }
}

/** One grouped pill with hairline dividers instead of three loose tiles. */
@Composable
internal fun HeaderToolbar(
    searchActive: Boolean,
    showSettingsBadge: Boolean,
    onSearch: () -> Unit,
    onAnalytics: () -> Unit,
    onSettings: () -> Unit
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Surface(
        shape = RoundedCornerShape(AppRadius.small),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, hairline)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolbarCell(Icons.Default.Search, "Search", active = searchActive, onClick = onSearch)
            Box(Modifier.width(1.dp).height(16.dp).background(hairline))
            ToolbarCell(Icons.Default.BarChart, "Analytics", onClick = onAnalytics)
            Box(Modifier.width(1.dp).height(16.dp).background(hairline))
            ToolbarCell(Icons.Default.Settings, "Settings", showBadge = showSettingsBadge, onClick = onSettings)
        }
    }
}

@Composable
internal fun ToolbarCell(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    showBadge: Boolean = false,
    onClick: () -> Unit
) {
    val highlight by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f) else Color.Transparent,
        tween(150), label = "toolbarActive"
    )
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 40.dp)
            .quietClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(highlight)
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 9.dp)
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
internal fun QuickTryItChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AppRadius.pill),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppRadius.pill))
            .quietClickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun StatusBadge(text: String, color: Color) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(AppRadius.pill)) {
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingHeaderContent: (@Composable () -> Unit)? = null,
    valueContent: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.medium))
            .quietCombinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    AnimatedContent(
                        targetState = icon,
                        transitionSpec = { (scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn()) togetherWith (scaleOut(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) },
                        label = "quickStatIcon"
                    ) { targetIcon ->
                        Icon(targetIcon, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { (fadeIn(tween(200)) togetherWith fadeOut(tween(100))).using(SizeTransform(clip = false)) },
                    modifier = Modifier.weight(1f),
                    label = "quickStatLabel"
                ) { targetLabel ->
                    Text(
                        targetLabel, 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Medium, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        maxLines = 2, 
                        overflow = TextOverflow.Ellipsis
                    )
                }
                trailingHeaderContent?.invoke()
            }
            Spacer(modifier = Modifier.height(16.dp))
            valueContent()
        }
    }
}

@Composable
internal fun NecessityBreakdownDialog(
    breakdown: SafeToSpendBreakdown,
    items: List<NecessityItem>,
    onDismiss: () -> Unit,
    onManage: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
            shape = RoundedCornerShape(AppRadius.large),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Safe to Spend breakdown", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(20.dp))

                @Composable
                fun BreakdownRow(label: String, value: String, emphasize: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium)
                        Text(value, fontSize = 14.sp, color = color, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold)
                    }
                }

                val hasActiveReserve = breakdown.remainingReserve > 0L
                BreakdownRow("Reserved for necessities", "− ${CurrencyFormat.withSymbol(breakdown.remainingReserve, 0)}")
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                BreakdownRow(
                    "Safe to spend",
                    if (hasActiveReserve) CurrencyFormat.withSymbol(breakdown.safeToSpend, 0) else "Nothing reserved",
                    emphasize = true,
                    color = if (breakdown.isShortOnReserve) AppColors.warning else MaterialTheme.colorScheme.primary
                )

                if (breakdown.reservedTotal > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val targetNecessitiesProgress = (breakdown.paidTowardNecessities.toFloat() / breakdown.reservedTotal.toFloat()).coerceIn(0f, 1f)
                    val animatedNecessitiesProgress by animateFloatAsState(
                        targetValue = targetNecessitiesProgress,
                        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                        label = "necessitiesProgress"
                    )
                    Text(
                        "Paid this month: ${CurrencyFormat.withSymbol(breakdown.paidTowardNecessities, 0)} of ${CurrencyFormat.withSymbol(breakdown.reservedTotal, 0)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { animatedNecessitiesProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(AppRadius.pill)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                if (breakdown.isShortOnReserve) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(AppRadius.small),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                    ) {
                        Text(
                            "Your balance is lower than what's still reserved for necessities this month. Spending now may eat into rent or bills.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp),
                            lineHeight = 18.sp
                        )
                    }
                }

                if (breakdown.upcomingNextMonth.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(AppRadius.small),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "Heads up for next month",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            breakdown.upcomingNextMonth.forEach { bill ->
                                Text(
                                    "• ${bill.name} (${CurrencyFormat.withSymbol(bill.amount, 0)}) is due.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (items.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Necessities", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(CurrencyFormat.withSymbol(item.amount, 0), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onManage() },
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) { Text("Manage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDismiss() },
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) { Text("Close", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

internal val dayLabelFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

internal fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterdayStart = todayStart - 86_400_000L

    return when {
        ts >= todayStart -> "Today"
        ts in yesterdayStart until todayStart -> "Yesterday"
        else -> dayLabelFormat.format(Date(ts))
    }
}

@Composable
internal fun DayHeader(label: String, total: String? = null) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            // Soft fade just below the header, drawn outside its bounds. It is invisible over the plain
            // background and only shows when rows scroll underneath, so they dissolve instead of being sliced.
            .drawBehind {
                val fadeHeight = 14.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(backgroundColor, backgroundColor.copy(alpha = 0f)),
                        startY = size.height,
                        endY = size.height + fadeHeight
                    ),
                    topLeft = Offset(0f, size.height),
                    size = Size(size.width, fadeHeight)
                )
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (total != null) Text(total, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun SafeToSpendCard(
    modifier: Modifier,
    breakdown: SafeToSpendBreakdown,
    revealed: Boolean,
    checking: Boolean,
    isFlipped: Boolean,
    onClick: () -> Unit
) {
    val showSafeToSpend = isFlipped
    val label = if (showSafeToSpend) "Safe to spend" else "Available balance"
    val hasReserve = breakdown.remainingReserve > 0L

    QuickStatCard(
        modifier = modifier,
        icon = if (revealed) Icons.Default.AccountBalanceWallet else Icons.Default.Lock,
        label = label,
        onClick = onClick
    ) {
        val amount = if (showSafeToSpend) breakdown.safeToSpend else (breakdown.balance ?: 0L)
        val animatedAmount = remember { Animatable(0f) }
        
        LaunchedEffect(revealed, amount) {
            if (revealed) {
                delay(150) // Wait for Biometric prompt to fully disappear before animating
                animatedAmount.animateTo(amount.toFloat(), animationSpec = tween(800, easing = FastOutSlowInEasing))
            } else {
                animatedAmount.snapTo(0f)
            }
        }
        
        AnimatedContent(
            targetState = checking to revealed,
            transitionSpec = {
                (fadeIn(tween(220)) togetherWith fadeOut(tween(110))).using(SizeTransform(clip = false))
            },
            label = "safeToSpendContent"
        ) { (isChecking, isRevealed) ->
            when {
                isChecking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                !isRevealed -> Text("₹ ••••••", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                showSafeToSpend && !hasReserve -> Text("Set Fixed Costs", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                else -> {
                    val color = if (showSafeToSpend && breakdown.isShortOnReserve) AppColors.warning else MaterialTheme.colorScheme.onSurface
                    Text(
                        CurrencyFormat.withSymbol(animatedAmount.value.toLong(), 0),
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = color
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = when {
                !revealed -> "Tap to unlock"
                showSafeToSpend && breakdown.isShortOnReserve -> "Short on reserve · details"
                !breakdown.hasBalanceData -> "Waiting for sync"
                else -> "Tap for breakdown"
            },
            transitionSpec = {
                (fadeIn(tween(220)) togetherWith fadeOut(tween(110))).using(SizeTransform(clip = false))
            },
            label = "safeToSpendSubtitle"
        ) { subtitle ->
            Text(
                subtitle,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (revealed && showSafeToSpend && breakdown.isShortOnReserve) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun startMillisFor(range: TimeRange, startDayOfMonth: Int): Long {
    return when (range) {
        TimeRange.ALL_TIME -> 0L
        TimeRange.TODAY -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        TimeRange.THIS_WEEK -> Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        TimeRange.THIS_MONTH -> BillingCycleHelper.getCycleRange(startDayOfMonth).first
        TimeRange.THIS_YEAR -> Calendar.getInstance().apply {
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

internal fun startOfCurrentMonthMillis(startDayOfMonth: Int): Long =
    BillingCycleHelper.getCycleRange(startDayOfMonth).first

internal fun endOfTodayMillis(): Long =
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

fun Context.findFragmentActivity(): FragmentActivity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is FragmentActivity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

// ───────────────────────── Quiet interactions (no ripple) ─────────────────────────

/** Click handler with no ripple; presses fade the element slightly instead. */
@Composable
internal fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.7f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { this.alpha = alpha }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

/** Tap + long-press with no ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.quietCombinedClickable(
    pressedAlpha: Float = 0.85f,
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

/** Selectable (tabs, radio-style rows) with no ripple. */
@Composable
internal fun Modifier.quietSelectable(
    selected: Boolean,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = this.selectable(
    selected = selected,
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    role = role,
    onClick = onClick
)


@Composable
fun DashboardProgressBar(
    progress: Float,
    color: Color,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    height: androidx.compose.ui.unit.Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(trackColor)
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(color)
            )
        }
    }
}

@Composable
internal fun CategoryBadgeTile(
    category: String,
    size: androidx.compose.ui.unit.Dp = 38.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 10.dp,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp
) {
    val hasIcon = remember(category) { hasCategoryIcon(category) }
    val initial = remember(category) {
        category.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }
    val neutralTint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (hasIcon) {
                Icon(
                    getCategoryIcon(category),
                    contentDescription = null,
                    tint = neutralTint,
                    modifier = Modifier.size(iconSize)
                )
            } else {
                Text(
                    text = initial,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = neutralTint
                )
            }
        }
    }
}

@Composable
fun CategoryBudgetsDashboardWidget(
    statuses: List<CategoryBudgetStatus>,
    onManageClick: () -> Unit,
    onCategoryClick: (String) -> Unit = { onManageClick() },
    onDismissPromo: () -> Unit = {}
) {
    if (statuses.isEmpty()) return

    val haptic = LocalHapticFeedback.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val previewCount = 3
    val overCount = remember(statuses) { statuses.count { it.isOverBudget } }
    val nearCount = remember(statuses) { statuses.count { !it.isOverBudget && (it.isNearLimit || it.isAtLimit) } }

    val (badgeText, badgeColor, badgeBg) = when {
        overCount > 0 -> Triple(
            "$overCount exceeded",
            AppColors.negative,
            AppColors.negative.copy(alpha = 0.10f)
        )
        nearCount > 0 -> Triple(
            "$nearCount near limit",
            AppColors.warning,
            AppColors.warning.copy(alpha = 0.10f)
        )
        else -> Triple(
            "${statuses.size} on track",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.small))
                    .quietClickable {
                        haptic.tap()
                        onManageClick()
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Category budgets",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val displayed = if (showAll) statuses else statuses.take(previewCount)
            displayed.forEachIndexed { index, status ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
                CategoryBudgetCompactRow(
                    status = status,
                    onClick = {
                        haptic.tap()
                        onCategoryClick(status.category)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (statuses.size > previewCount) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.small))
                            .quietClickable {
                                haptic.tap()
                                showAll = !showAll
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showAll) "Show less" else "Show all ${statuses.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            if (showAll) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    val totalLimited = statuses.sumOf { it.limitPaise }
                    val totalSpent = statuses.sumOf { it.spentPaise }
                    Text(
                        "${CurrencyFormat.withSymbol(totalSpent, 0)} of ${CurrencyFormat.withSymbol(totalLimited, 0)} used",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.small))
                        .quietClickable {
                            haptic.tap()
                            onManageClick()
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Manage",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryBudgetCompactRow(
    status: CategoryBudgetStatus,
    onClick: () -> Unit
) {
    val pct = (status.progress * 100f).roundToInt()
    val barColor = when {
        status.isOverBudget -> AppColors.negative
        status.isNearLimit || status.isAtLimit -> AppColors.warning
        else -> MaterialTheme.colorScheme.onSurface
    }
    val subtitleText = when {
        status.isOverBudget -> "Exceeded by ${CurrencyFormat.withSymbol(status.overagePaise, 0)}"
        status.isAtLimit -> "Limit reached · ₹0 left"
        status.dailyAllowancePaise > 0L -> "${CurrencyFormat.withSymbol(status.remainingPaise, 0)} left · ≈ ${CurrencyFormat.withSymbol(status.dailyAllowancePaise, 0)}/day"
        status.isPacingHigh -> "${CurrencyFormat.withSymbol(status.remainingPaise, 0)} left · pacing high"
        else -> "${CurrencyFormat.withSymbol(status.remainingPaise, 0)} left"
    }
    val subtitleColor = when {
        status.isOverBudget -> AppColors.negative
        status.isAtLimit || status.isNearLimit -> AppColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val animatedProgress by animateFloatAsState(
        targetValue = status.progress.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "catBudgetRowProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .quietClickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadgeTile(category = status.category)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.category,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    subtitleText,
                    fontSize = 12.sp,
                    fontWeight = if (status.isOverBudget || status.isAtLimit) FontWeight.Medium else FontWeight.Normal,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CurrencyFormat.withSymbol(status.spentPaise, 0),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.isOverBudget) AppColors.negative else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "of ${CurrencyFormat.withSymbol(status.limitPaise, 0)} · $pct%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        DashboardProgressBar(
            progress = animatedProgress,
            color = barColor,
            height = 4.dp
        )
    }
}

@Composable
fun CategoryBudgetWarningBanner(
    statuses: List<CategoryBudgetStatus>,
    onManageClick: () -> Unit,
    onDismissClick: (List<String>) -> Unit
) {
    val warningCategories = statuses.filter { it.isOverBudget || it.isAtLimit || it.isNearLimit }
    if (warningCategories.isEmpty()) return

    val anyOver = warningCategories.any { it.isOverBudget }
    val accentColor = if (anyOver) AppColors.negative else AppColors.warning

    Surface(
        shape = RoundedCornerShape(AppRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.10f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .quietClickable { onManageClick() }
            ) {
                Text(
                    if (anyOver) "Category limit exceeded" else "Approaching category limit",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    warningCategories.joinToString("") { status ->
                        when {
                            status.isOverBudget ->
                                "${status.category}: exceeded ${CurrencyFormat.withSymbol(status.limitPaise, 0)} by ${CurrencyFormat.withSymbol(status.overagePaise, 0)}"
                            status.isAtLimit ->
                                "${status.category}: reached ${CurrencyFormat.withSymbol(status.limitPaise, 0)} cap"
                            else ->
                                "${status.category}: ${CurrencyFormat.withSymbol(status.remainingPaise, 0)} left of ${CurrencyFormat.withSymbol(status.limitPaise, 0)} (${(status.progress * 100f).roundToInt()}%)"
                        }
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
            IconButton(
                onClick = { onDismissClick(warningCategories.map { it.category }) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss warning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun CategoryBudgetsCard(
    statuses: List<CategoryBudgetStatus>,
    onManageClick: () -> Unit
) {
    CategoryBudgetsDashboardWidget(
        statuses = statuses,
        onManageClick = onManageClick
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManageCategoryBudgetsSheet(
    availableCategories: List<String>,
    currentBudgets: Map<String, Long>,
    onSaveBudget: (category: String, limitPaise: Long) -> Unit,
    onRemoveBudget: (category: String) -> Unit,
    onDismiss: () -> Unit,
    initialCategory: String? = null,
    transactions: List<TransactionWithDebts> = emptyList(),
    pendingDeleteIds: Set<Long> = emptySet(),
    monthlyBudgetPaise: Long = 0L,
    startDayOfMonth: Int = 1
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategoryForEdit by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }

    val cleanCategories = remember(availableCategories, currentBudgets, transactions) {
        val nonExpenseCategories = setOf(
            "split settlement", "uncategorized", "refunds", "refund", "rewards", "reward", "cashback", "salary", "income"
        )
        val budgetedKeys = currentBudgets.keys.map { it.trim().lowercase() }.toSet()
        val debitCategories = transactions
            .asSequence()
            .take(500)
            .filter { it.transaction.type == TransactionType.DEBIT && !it.transaction.excludeFromSpend }
            .map { it.transaction.category }
            .toList()
        (availableCategories + currentBudgets.keys + debitCategories)
            .map { it.trim() }
            .filter {
                val lower = it.lowercase()
                it.isNotEmpty() && (lower in budgetedKeys || lower !in nonExpenseCategories)
            }
            .distinctBy { it.lowercase() }
    }

    val statuses = remember(transactions, currentBudgets, startDayOfMonth, pendingDeleteIds) {
        computeCategoryBudgetStatuses(
            transactions = transactions,
            categoryBudgets = currentBudgets,
            startDayOfMonth = startDayOfMonth,
            pendingDeleteIds = pendingDeleteIds
        )
    }

    val spendStatsMap = remember(transactions, cleanCategories, startDayOfMonth, pendingDeleteIds) {
        computeCategorySpendStats(
            transactions = transactions,
            availableCategories = cleanCategories,
            startDayOfMonth = startDayOfMonth,
            pendingDeleteIds = pendingDeleteIds
        )
    }

    BackHandler(enabled = selectedCategoryForEdit != null && initialCategory == null) {
        haptic.tap()
        selectedCategoryForEdit = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            AnimatedContent(
                targetState = selectedCategoryForEdit,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally { width -> width / 3 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut(tween(120)))
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { width -> width / 3 } + fadeOut(tween(120)))
                    }
                },
                label = "categoryBudgetSheetTransition"
            ) { editingCategory ->
                if (editingCategory == null) {
                    CategoryBudgetListContent(
                        availableCategories = cleanCategories,
                        statuses = statuses,
                        spendStatsMap = spendStatsMap,
                        monthlyBudgetPaise = monthlyBudgetPaise,
                        startDayOfMonth = startDayOfMonth,
                        onCategorySelect = { cat ->
                            haptic.tap()
                            selectedCategoryForEdit = cat
                        },
                        onDone = {
                            haptic.tap()
                            onDismiss()
                        }
                    )
                } else {
                    val existingLimit = currentBudgets.entries
                        .firstOrNull { it.key.equals(editingCategory, ignoreCase = true) }
                        ?.value
                    val stats = spendStatsMap.entries
                        .firstOrNull { it.key.equals(editingCategory, ignoreCase = true) }
                        ?.value
                    val otherAllocatedPaise = statuses
                        .filterNot { it.category.equals(editingCategory, ignoreCase = true) }
                        .sumOf { it.limitPaise }

                    CategoryBudgetFormContent(
                        category = editingCategory,
                        currentLimitPaise = existingLimit,
                        stats = stats,
                        otherAllocatedPaise = otherAllocatedPaise,
                        monthlyBudgetPaise = monthlyBudgetPaise,
                        startDayOfMonth = startDayOfMonth,
                        onBack = {
                            haptic.tap()
                            if (initialCategory != null) onDismiss() else selectedCategoryForEdit = null
                        },
                        onSave = { newLimit ->
                            haptic.confirm()
                            onSaveBudget(editingCategory, newLimit)
                            if (initialCategory != null) onDismiss() else selectedCategoryForEdit = null
                        },
                        onRemove = {
                            haptic.delete()
                            onRemoveBudget(editingCategory)
                            if (initialCategory != null) onDismiss() else selectedCategoryForEdit = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryBudgetListContent(
    availableCategories: List<String>,
    statuses: List<CategoryBudgetStatus>,
    spendStatsMap: Map<String, CategorySpendStats>,
    monthlyBudgetPaise: Long,
    startDayOfMonth: Int,
    onCategorySelect: (String) -> Unit,
    onDone: () -> Unit
) {
    val activeKeys = remember(statuses) { statuses.map { it.category.lowercase() }.toSet() }
    val unbudgetedCategories = remember(availableCategories, activeKeys, spendStatsMap) {
        availableCategories
            .filter { it.lowercase() !in activeKeys }
            .sortedWith(
                compareByDescending<String> { spendStatsMap[it]?.currentCycleSpentPaise ?: 0L }
                    .thenByDescending { spendStatsMap[it]?.threeCycleAvgPaise ?: 0L }
                    .thenBy { it.lowercase() }
            )
    }

    val totalAllocatedPaise = remember(statuses) { statuses.sumOf { it.limitPaise } }
    val totalCappedSpentPaise = remember(statuses) { statuses.sumOf { it.spentPaise } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 16.dp)
        ) {
            SheetHeader(
                title = "Category budgets",
                subtitle = "Monthly spending caps · Resets Day $startDayOfMonth"
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // Allocation & summary card scrolls smoothly with the list so items never clip mid-sheet
                if (statuses.isNotEmpty() || monthlyBudgetPaise > 0L) {
                    item(key = "allocation_summary_card") {
                        SheetCard(contentPadding = 16.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Total allocated",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        CurrencyFormat.withSymbol(totalAllocatedPaise, 0),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        if (statuses.isEmpty()) "No category caps set yet"
                                        else "${statuses.size} ${if (statuses.size == 1) "category" else "categories"} capped",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (monthlyBudgetPaise > 0L) {
                                    val isOverAllocated = totalAllocatedPaise > monthlyBudgetPaise
                                    val diffPaise = kotlin.math.abs(monthlyBudgetPaise - totalAllocatedPaise)
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "Monthly target",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            CurrencyFormat.withSymbol(monthlyBudgetPaise, 0),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            when {
                                                totalAllocatedPaise == 0L -> "100% unallocated"
                                                isOverAllocated -> "${CurrencyFormat.withSymbol(diffPaise, 0)} above target"
                                                else -> "${CurrencyFormat.withSymbol(diffPaise, 0)} unallocated"
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = if (isOverAllocated) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isOverAllocated) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (totalAllocatedPaise > 0L) {
                                val overallProgress = (totalCappedSpentPaise.toFloat() / totalAllocatedPaise.toFloat()).coerceIn(0f, 1f)
                                val isTotalOver = totalCappedSpentPaise > totalAllocatedPaise
                                Spacer(modifier = Modifier.height(14.dp))
                                DashboardProgressBar(
                                    progress = overallProgress,
                                    color = if (isTotalOver) AppColors.negative else MaterialTheme.colorScheme.onSurface,
                                    height = 4.dp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Spent across capped categories",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${CurrencyFormat.withSymbol(totalCappedSpentPaise, 0)} (${((totalCappedSpentPaise.toDouble() / totalAllocatedPaise.toDouble()) * 100.0).roundToInt()}%)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isTotalOver) AppColors.negative else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
                if (statuses.isNotEmpty()) {
                    item(key = "active_limits_section") {
                        SheetSection(
                            title = "Active limits",
                            trailing = "${statuses.size} ${if (statuses.size == 1) "category" else "categories"}"
                        ) {
                            TransactionGroup {
                                statuses.forEachIndexed { index, status ->
                                    if (index > 0) TransactionRowDivider()
                                    ActiveCategoryBudgetSheetRow(
                                        status = status,
                                        onClick = { onCategorySelect(status.category) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (unbudgetedCategories.isNotEmpty()) {
                    item(key = "unbudgeted_categories_section") {
                        SheetSection(
                            title = if (statuses.isEmpty()) "Categories" else "Available categories",
                            trailing = "Tap to set cap"
                        ) {
                            TransactionGroup {
                                unbudgetedCategories.forEachIndexed { index, cat ->
                                    if (index > 0) TransactionRowDivider()
                                    val stats = spendStatsMap[cat]
                                    UnbudgetedCategorySheetRow(
                                        category = cat,
                                        stats = stats,
                                        onClick = { onCategorySelect(cat) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SheetFooter {
            SheetPrimaryButton(
                text = "Done",
                enabled = true,
                onClick = onDone
            )
        }
    }
}

@Composable
private fun ActiveCategoryBudgetSheetRow(
    status: CategoryBudgetStatus,
    onClick: () -> Unit
) {
    val barColor = when {
        status.isOverBudget -> AppColors.negative
        status.isAtLimit || status.isNearLimit -> AppColors.warning
        else -> MaterialTheme.colorScheme.onSurface
    }
    val statusDetail = when {
        status.isOverBudget -> "${CurrencyFormat.withSymbol(status.spentPaise, 0)} spent · ${CurrencyFormat.withSymbol(status.overagePaise, 0)} over"
        status.isAtLimit -> "${CurrencyFormat.withSymbol(status.spentPaise, 0)} spent · Limit reached"
        else -> "${CurrencyFormat.withSymbol(status.spentPaise, 0)} spent · ${CurrencyFormat.withSymbol(status.remainingPaise, 0)} left"
    }
    val detailColor = when {
        status.isOverBudget -> AppColors.negative
        status.isAtLimit || status.isNearLimit -> AppColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadgeTile(category = status.category, size = 40.dp, cornerRadius = 10.dp, iconSize = 20.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.category,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    statusDetail,
                    fontSize = 12.sp,
                    fontWeight = if (status.isOverBudget || status.isAtLimit) FontWeight.Medium else FontWeight.Normal,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                CurrencyFormat.withSymbol(status.limitPaise, 0),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        DashboardProgressBar(
            progress = status.progress.coerceIn(0f, 1f),
            color = barColor,
            height = 3.dp
        )
    }
}

@Composable
private fun UnbudgetedCategorySheetRow(
    category: String,
    stats: CategorySpendStats?,
    onClick: () -> Unit
) {
    val curSpent = stats?.currentCycleSpentPaise ?: 0L
    val avgSpent = stats?.threeCycleAvgPaise ?: 0L
    val subtitle = when {
        curSpent > 0L -> "${CurrencyFormat.withSymbol(curSpent, 0)} spent this cycle"
        avgSpent > 0L -> "Avg ${CurrencyFormat.withSymbol(avgSpent, 0)}/mo"
        else -> "No spend this cycle"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadgeTile(category = category, size = 40.dp, cornerRadius = 10.dp, iconSize = 20.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    category,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(AppRadius.pill),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ) {
            Text(
                "+ Set cap",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBudgetFormContent(
    category: String,
    currentLimitPaise: Long?,
    stats: CategorySpendStats?,
    otherAllocatedPaise: Long,
    monthlyBudgetPaise: Long,
    startDayOfMonth: Int,
    onBack: () -> Unit,
    onSave: (Long) -> Unit,
    onRemove: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val hasLimit = currentLimitPaise != null && currentLimitPaise > 0L

    var amountStr by remember(category, currentLimitPaise) {
        mutableStateOf(
            if (hasLimit && currentLimitPaise != null) {
                if (currentLimitPaise % 100L == 0L) (currentLimitPaise / 100L).toString()
                else String.format(Locale.US, "%.2f", currentLimitPaise / 100.0)
            } else ""
        )
    }

    val calculatedPaise = remember(amountStr) { evaluateBasicMath(amountStr) }
    val validPaise = calculatedPaise?.takeIf { it > 0L }
    val curSpentPaise = stats?.currentCycleSpentPaise ?: 0L
    val lastCyclePaise = stats?.lastCycleSpentPaise ?: 0L
    val avgPaise = stats?.threeCycleAvgPaise ?: 0L
    val suggestedPaise = stats?.suggestedLimitPaise ?: 0L

    val (_, cycleEnd) = remember(startDayOfMonth) { BillingCycleHelper.getCycleRange(startDayOfMonth) }
    val daysLeft = remember(cycleEnd) {
        ceil((cycleEnd - System.currentTimeMillis()).toDouble() / 86_400_000.0).toInt().coerceAtLeast(0)
    }

    // Build smart quick-fill chips in Rupees
    val quickPickChips = remember(suggestedPaise, lastCyclePaise, monthlyBudgetPaise) {
        val chips = linkedMapOf<Long, String>()
        if (suggestedPaise >= 100_00L) {
            val r = suggestedPaise / 100L
            chips[r] = "Suggested · ${CurrencyFormat.withSymbol(suggestedPaise, 0)}"
        }
        if (lastCyclePaise >= 100_00L) {
            val roundedLast = ((lastCyclePaise / 100L + 99L) / 100L) * 100L
            if (roundedLast !in chips) {
                chips[roundedLast] = "Last cycle · ${CurrencyFormat.withSymbol(roundedLast * 100L, 0)}"
            }
        }
        listOf(1000L, 2000L, 3000L, 5000L, 10000L, 15000L).forEach { preset ->
            if (chips.size < 6 && preset !in chips) {
                chips[preset] = CurrencyFormat.withSymbol(preset * 100L, 0)
            }
        }
        chips.entries.toList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SheetIconTile(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = MaterialTheme.colorScheme.onSurface,
                    contentDescription = "Back",
                    onClick = onBack
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        category,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        letterSpacing = (-0.3).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (hasLimit) "Update monthly spending cap" else "Set a monthly spending cap",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SheetIconTile(
                    icon = getCategoryIcon(category),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SheetAmountCard(
                amountStr = amountStr,
                onAmountChange = { amountStr = it },
                calculatedAmount = calculatedPaise?.let { it / 100.0 },
                isDebit = true
            )

            // Quick suggestions & presets
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Quick select",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPickChips.forEach { (rupeesVal, label) ->
                        val isSelected = validPaise == rupeesVal * 100L
                        SheetChip(
                            text = label,
                            isSelected = isSelected,
                            onClick = { amountStr = rupeesVal.toString() }
                        )
                    }
                }
            }

            // Spending context & live impact preview card
            SheetCard(contentPadding = 16.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Spending history",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("This cycle", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            CurrencyFormat.withSymbol(curSpentPaise, 0),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Last cycle", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (lastCyclePaise > 0L) CurrencyFormat.withSymbol(lastCyclePaise, 0) else "—",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Monthly avg", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (avgPaise > 0L) CurrencyFormat.withSymbol(avgPaise, 0) else "—",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (validPaise != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    val previewPct = ((curSpentPaise.toDouble() / validPaise.toDouble()) * 100.0).roundToInt()
                    val isAlreadyOver = curSpentPaise > validPaise
                    val remainingPaise = (validPaise - curSpentPaise).coerceAtLeast(0L)
                    val dailyPaise = if (daysLeft > 0 && remainingPaise > 0L) remainingPaise / daysLeft else 0L

                    val previewText = when {
                        isAlreadyOver ->
                            "You've already spent ${CurrencyFormat.withSymbol(curSpentPaise - validPaise, 0)} over this cap this cycle ($previewPct% used)."
                        curSpentPaise == validPaise ->
                            "This matches your exact spend so far this cycle (₹0 remaining)."
                        dailyPaise > 0L ->
                            "Leaves ${CurrencyFormat.withSymbol(remainingPaise, 0)} for the next $daysLeft ${if (daysLeft == 1) "day" else "days"} (≈ ${CurrencyFormat.withSymbol(dailyPaise, 0)}/day)."
                        else ->
                            "Leaves ${CurrencyFormat.withSymbol(remainingPaise, 0)} remaining in this cycle."
                    }

                    Text(
                        previewText,
                        fontSize = 13.sp,
                        color = if (isAlreadyOver) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    if (monthlyBudgetPaise > 0L && (otherAllocatedPaise + validPaise) > monthlyBudgetPaise) {
                        val excess = (otherAllocatedPaise + validPaise) - monthlyBudgetPaise
                        Text(
                            "Heads up: Total category caps will exceed your ${CurrencyFormat.withSymbol(monthlyBudgetPaise, 0)} monthly budget by ${CurrencyFormat.withSymbol(excess, 0)}.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.warning,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        SheetFooter {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasLimit) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onRemove()
                        },
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Remove", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.weight(if (hasLimit) 1.3f else 1f)) {
                    SheetPrimaryButton(
                        text = if (hasLimit) "Update cap" else "Save cap",
                        enabled = validPaise != null && validPaise != currentLimitPaise,
                        onClick = {
                            focusManager.clearFocus()
                            if (validPaise != null) onSave(validPaise)
                        }
                    )
                }
            }
        }
    }
}
