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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
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
                    val progress = (breakdown.paidTowardNecessities / breakdown.reservedTotal).toFloat().coerceIn(0f, 1f)
                    Text(
                        "Paid this month: ${CurrencyFormat.withSymbol(breakdown.paidTowardNecessities, 0)} of ${CurrencyFormat.withSymbol(breakdown.reservedTotal, 0)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
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
