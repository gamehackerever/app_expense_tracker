package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditScore
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.repository.ManualAccount
import com.expensetracker.offline.data.repository.ManualAccountKind
import com.expensetracker.offline.data.repository.NetWorth
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import java.util.UUID
import kotlin.math.roundToLong

private fun ManualAccountKind.icon(): ImageVector = when (this) {
    ManualAccountKind.SAVINGS -> Icons.Default.Savings
    ManualAccountKind.FD -> Icons.Default.AccountBalance
    ManualAccountKind.INVESTMENT -> Icons.AutoMirrored.Filled.TrendingUp
    ManualAccountKind.LOAN -> Icons.Default.CreditScore
    ManualAccountKind.OTHER_DEBT -> Icons.Default.Payments
}

private fun ordinalDay(d: Int): String = when {
    d in 11..13 -> "${d}th"
    d % 10 == 1 -> "${d}st"
    d % 10 == 2 -> "${d}nd"
    d % 10 == 3 -> "${d}rd"
    else -> "${d}th"
}

/** Headline: what you own minus what you owe, across bank balances, deposits, investments, cards and loans. */
@Composable
internal fun NetWorthStrip(netWorth: NetWorth, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(AppRadius.medium),
        color = scheme.onSurface.copy(alpha = 0.03f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Net worth", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                text = (if (netWorth.netPaise < 0) "-" else "") + CurrencyFormat.withSymbol(kotlin.math.abs(netWorth.netPaise), 0),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = if (netWorth.netPaise < 0) AppColors.negative else scheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                NetWorthStat("Assets", netWorth.assetsPaise, AppColors.positive)
                NetWorthStat("Debts", netWorth.debtsPaise, if (netWorth.debtsPaise > 0) AppColors.negative else scheme.onSurfaceVariant)
                if (netWorth.monthlyCommitmentsPaise > 0) {
                    NetWorthStat("EMIs & SIPs / mo", netWorth.monthlyCommitmentsPaise, scheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun NetWorthStat(label: String, paise: Long, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(CurrencyFormat.withSymbol(paise, 0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** Savings, fixed deposits, investments and loans entered by hand. */
@Composable
internal fun ManualAccountsSection(
    accounts: List<ManualAccount>,
    onAdd: () -> Unit,
    onEdit: (ManualAccount) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Savings, investments & loans", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .quietClickable(onClick = onAdd)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (accounts.isEmpty()) {
            Text(
                "Track fixed deposits, mutual funds, loans and EMIs here. They count towards your net worth.",
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant
            )
        } else {
            Surface(
                shape = RoundedCornerShape(AppRadius.medium),
                color = scheme.surface,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    accounts.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(
                            modifier = Modifier.padding(start = 64.dp),
                            color = scheme.outlineVariant.copy(alpha = 0.35f)
                        )
                        ManualAccountRow(a, onClick = { onEdit(a) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualAccountRow(a: ManualAccount, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (a.kind.isLiability) AppColors.negative else scheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = 0.12f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(a.kind.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(a.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append(a.kind.label)
                if (a.monthlyPaise > 0) {
                    append(" · ")
                    append(CurrencyFormat.withSymbol(a.monthlyPaise, 0))
                    append("/mo")
                    if (a.dueDay > 0) append(" on the ${ordinalDay(a.dueDay)}")
                }
            }
            Text(sub, fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (if (a.kind.isLiability) "-" else "") + CurrencyFormat.withSymbol(a.amountPaise, 0),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (a.kind.isLiability) AppColors.negative else scheme.onSurface,
            maxLines = 1
        )
    }
}

/** Add or edit one manual account. [onSave] gets the account and whether to also create a Fixed Costs reminder. */
@Composable
internal fun ManualAccountDialog(
    initial: ManualAccount?,
    onDismiss: () -> Unit,
    onSave: (account: ManualAccount, addToFixedCosts: Boolean) -> Unit,
    onDelete: ((ManualAccount) -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    var kind by remember { mutableStateOf(initial?.kind ?: ManualAccountKind.FD) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var amountStr by remember { mutableStateOf(initial?.amountPaise?.let { (it / 100.0).toString().removeSuffix(".0") }.orEmpty()) }
    var monthlyStr by remember { mutableStateOf(initial?.monthlyPaise?.takeIf { it > 0 }?.let { (it / 100.0).toString().removeSuffix(".0") }.orEmpty()) }
    var dueDayStr by remember { mutableStateOf(initial?.dueDay?.takeIf { it > 0 }?.toString().orEmpty()) }
    var addToFixed by remember { mutableStateOf(initial == null) }

    val amountPaise = amountStr.replace(",", "").toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { (it * 100.0).roundToLong() }
    val monthlyPaise = monthlyStr.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * 100.0).roundToLong() } ?: 0L
    val dueDay = dueDayStr.toIntOrNull()?.coerceIn(1, 28) ?: 0
    val hasRecurring = kind.recurringLabel.isNotEmpty()

    AppDialog(
        title = if (initial == null) "Add to net worth" else "Edit ${initial.name}",
        onDismiss = { haptic.tap(); onDismiss() },
        confirmText = "Save",
        confirmEnabled = name.trim().isNotEmpty() && amountPaise != null,
        onConfirm = {
            val paise = amountPaise ?: return@AppDialog
            haptic.confirm()
            onSave(
                ManualAccount(
                    id = initial?.id ?: UUID.randomUUID().toString(),
                    kind = kind,
                    name = name.trim(),
                    amountPaise = paise,
                    monthlyPaise = if (hasRecurring) monthlyPaise else 0L,
                    dueDay = if (hasRecurring && monthlyPaise > 0) dueDay else 0,
                    updatedAt = System.currentTimeMillis()
                ),
                addToFixed && hasRecurring && monthlyPaise > 0
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualAccountKind.values().forEach { k ->
                    val selected = k == kind
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = if (selected) scheme.onSurface else scheme.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .quietClickable { haptic.tap(); kind = k }
                    ) {
                        Text(
                            k.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            color = if (selected) scheme.surface else scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name") },
                placeholder = { Text(if (kind.isLiability) "e.g., Car loan" else "e.g., SBI FD") },
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.small),
                colors = sheetFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it.filter { c -> c.isDigit() || c == '.' }.take(13) },
                label = { Text("${kind.amountLabel} (₹)") },
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.small),
                colors = sheetFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            if (hasRecurring) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = monthlyStr,
                        onValueChange = { monthlyStr = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                        label = { Text("${kind.recurringLabel} (₹)") },
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = sheetFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = dueDayStr,
                        onValueChange = { dueDayStr = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Day") },
                        placeholder = { Text("1-28") },
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.small),
                        colors = sheetFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                }
                if (monthlyPaise > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remind me in Fixed Costs", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                            Text(
                                "Adds it to bills so it counts towards what to set aside each month.",
                                fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = addToFixed, onCheckedChange = { addToFixed = it }, colors = appSwitchColors())
                    }
                }
            }
            if (initial != null && onDelete != null) {
                Text(
                    "Remove this entry",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.negative,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.small))
                        .quietClickable { haptic.confirm(); onDelete(initial) }
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}

