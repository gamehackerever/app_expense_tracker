package com.expensetracker.offline.ui.components

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import com.expensetracker.offline.data.local.dao.AccountBalanceInsight
import com.expensetracker.offline.data.local.dao.AccountInfo
import com.expensetracker.offline.ui.dashboard.SafeToSpendBreakdown
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One soft, non-bouncy spring used for every size change in this sheet. */
private fun <T> smoothSize() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsDialog(
    accountBalances: List<AccountBalanceInsight>,
    selectedAccount: AccountInfo?,
    contextLabel: String,
    contextBalance: Long?,
    safeToSpendBreakdown: SafeToSpendBreakdown?,
    onAccountSelected: (AccountInfo?) -> Unit,
    onRefresh: () -> Unit,
    onManageNecessities: () -> Unit,
    onDismiss: () -> Unit,
    secure: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    // Scale the account list with the screen so the sheet never overflows small or landscape displays.
    val listMaxHeight = minOf(maxOf((LocalConfiguration.current.screenHeightDp * 0.32f).dp, 140.dp), 340.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Inline refresh feedback (the dashboard snackbar is hidden behind a sheet) ──
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

    ModalBottomSheet(
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
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
                        if (accountBalances.size <= 1) "Linked account" else "Linked accounts",
                        fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

            if (accountBalances.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("No accounts detected yet.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val single = accountBalances.size <= 1
                // No key(): the card keeps its flip/bills state when you switch accounts; values crossfade in place.
                BalanceFlipCard(
                    contextLabel = if (single) accountBalances.first().bankName else contextLabel,
                    accountNumber = if (single) accountBalances.first().accountNumber else null,
                    balance = contextBalance,
                    breakdown = safeToSpendBreakdown,
                    lastUpdated = if (single) accountBalances.first().lastUpdated else accountBalances.maxOfOrNull { it.lastUpdated },
                    onManageNecessities = onManageNecessities
                )

                if (!single) {
                    Spacer(Modifier.height(18.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight)
                    ) {
                        item {
                            AccountListItem(
                                title = "All accounts",
                                subtitle = "Combined balance",
                                icon = Icons.Default.AccountTree,
                                isSelected = selectedAccount == null,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAccountSelected(null)
                                }
                            )
                        }
                        items(accountBalances, key = { "${it.bankName}|${it.accountNumber ?: "none"}" }) { account ->
                            val isSelected = selectedAccount?.bankName == account.bankName && selectedAccount?.accountNumber == account.accountNumber
                            AccountListItem(
                                title = account.bankName,
                                subtitle = account.accountNumber?.let { "··$it" },
                                initial = account.bankName.take(1).uppercase(),
                                isSelected = isSelected,
                                balance = account.latestBalance,
                                lastUpdated = account.lastUpdated,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAccountSelected(AccountInfo(account.bankName, account.accountNumber))
                                }
                            )
                        }
                    }
                }
            }
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
    lastUpdated: Long? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // No ripple: a soft tint on press and animated selection state instead.
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> scheme.onSurface.copy(alpha = 0.04f)
            isPressed -> scheme.onSurface.copy(alpha = 0.02f)
            else -> scheme.surfaceVariant.copy(alpha = 0.3f)
        },
        animationSpec = tween(240), label = "accountItemBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) scheme.onSurface.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(240), label = "accountItemBorder"
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
        Column(modifier = Modifier.animateContentSize(smoothSize()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar keeps its identity (icon / initial); selection is a small check badge.
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
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(150)),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(x = 3.dp, y = 3.dp)
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
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }

            if (isSelected && balance != null && lastUpdated != null) {
                val timeStr = remember(lastUpdated) { formatSyncTime(context, lastUpdated) }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Current balance", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = CurrencyFormat.withSymbol(balance, 2),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Last synced", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(timeStr, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                    }
                }
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

    // 1. Initialize SharedPreferences
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }

    // 2. Read the initial state from SharedPreferences instead of defaulting to false
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
        animationSpec = tween(durationMillis = 560),
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
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isFlipped = !isFlipped
                // 3. Save the user's preference immediately when they flip the card
                prefs.edit().putBoolean("key_balance_card_flipped", isFlipped).apply()
            },
        shape = RoundedCornerShape(AppRadius.large),
        color = flipColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp).animateContentSize(smoothSize()).padding(24.dp)) {
            if (showFront) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Available balance", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(AppRadius.pill), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)) {
                            Text(
                                text = if (accountNumber != null) "$contextLabel · ··$accountNumber" else contextLabel,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface, // Changed from primary
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))

                    Crossfade(targetState = balance, animationSpec = tween(220), label = "balanceSwap") { shown ->
                        if (shown != null) {
                            HeroAmount(text = CurrencyFormat.withSymbol(shown, 2), color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            Text("No data yet", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(syncText, fontSize = 13.sp, color = if (isStale) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(24.dp))
                    FlipHint("Tap to view safe to spend")
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
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onManageNecessities()
                            }
                        ) {
                            Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurface // Changed from primary
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
                    Spacer(Modifier.height(18.dp))

                    when {
                        breakdown == null || breakdown.reservedTotal <= 0L -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("No bills reserved", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Tap Manage to add fixed costs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                            Spacer(Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .quietClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showBills = !showBills
                                    }
                                    .heightIn(min = 40.dp)
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

                    Spacer(Modifier.height(24.dp))
                    FlipHint("Tap to view balance")
                }
            }
        }
    }
}

/**
 * Hero figure: big, bold, tabular digits. The currency symbol and decimals are
 * set smaller and softer so the whole-number part reads first.
 */
@Composable
private fun HeroAmount(text: String, color: Color) {
    val symbolColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Shrink gracefully for very large figures so it never wraps or clips.
    val size = when {
        text.length > 14 -> 34.sp
        text.length > 11 -> 40.sp
        else -> 48.sp
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

/** Click handler with no ripple; presses fade the element slightly instead. */
@Composable
private fun Modifier.quietClickable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(if (pressed) 0.55f else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { alpha = pressAlpha }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
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
    val time = DateFormat.getTimeFormat(context).format(Date(millis))   // respects 12/24h setting
    if (DateUtils.isToday(millis)) return "Today, $time"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM")
    return "${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))}, $time"
}
