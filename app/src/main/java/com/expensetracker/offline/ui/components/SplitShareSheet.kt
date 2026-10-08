package com.expensetracker.offline.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.SplitPresetManager
import com.expensetracker.offline.util.UserSplitPreset
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID

enum class SplitMode(val label: String) {
    EQUAL("Equal"), SHARES("Shares"), EXACT("Exact"), PERCENTAGE("Percent")
}

data class SplitParticipant(
    val id: String = UUID.randomUUID().toString(),
    val debtId: Long? = null,
    val isMe: Boolean = false,
    val name: String = "",
    val isIncludedInEqual: Boolean = true,
    val exactAmountStr: String = "",
    val percentageStr: String = "",
    val sharesStr: String = "1"
)

private const val MAX_PEOPLE = 20
private const val MAX_NAME_LENGTH = 30
private const val CURRENCY = "₹"
private const val FULL_BP = 10_000L

private val AMOUNT_REGEX = Regex("""^\d{0,9}(\.\d{0,2})?$""")
private val PERCENT_REGEX = Regex("""^\d{0,3}(\.\d{0,2})?$""")

private fun String.toMinorUnits(): Long = runCatching {
    BigDecimal(trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
}.getOrDefault(0L)

private fun plain(paise: Long): String = String.format(Locale.US, "%.2f", paise / 100.0)
private fun money(paise: Long): String = CurrencyFormat.withSymbol(paise)
private fun fmtBp(bp: Long): String = String.format(Locale.US, "%.2f", bp / 100.0).trimEnd('0').trimEnd('.').ifEmpty { "0" }

private fun allocate(total: Long, weights: List<Long>): List<Long> {
    val sumW = weights.sum()
    if (total <= 0L || sumW <= 0L) return List(weights.size) { 0L }
    val result = weights.map { total * it / sumW }.toMutableList()
    val remainders = weights.map { total * it % sumW }
    var leftover = total - result.sum()
    weights.indices
        .filter { weights[it] > 0L }
        .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        .forEach { i -> if (leftover > 0L) { result[i] += 1L; leftover-- } }
    return result
}

private fun computeShares(participants: List<SplitParticipant>, mode: SplitMode, totalPaise: Long): Map<String, Long> {
    val values: List<Long> = when (mode) {
        SplitMode.EQUAL -> allocate(totalPaise, participants.map { 1L })
        SplitMode.SHARES -> {
            val weights = participants.map { it.sharesStr.toLongOrNull()?.coerceAtLeast(0L) ?: 1L }
            allocate(totalPaise, weights)
        }
        SplitMode.EXACT -> participants.map { it.exactAmountStr.toMinorUnits() }
        SplitMode.PERCENTAGE -> {
            val bps = participants.map { it.percentageStr.toMinorUnits() }
            if (bps.sum() == FULL_BP) allocate(totalPaise, bps)
            else bps.map { (totalPaise * it + FULL_BP / 2) / FULL_BP }
        }
    }
    return participants.mapIndexed { i, p -> p.id to values[i] }.toMap()
}

private data class InitialSplit(val mode: SplitMode, val includeMe: Boolean, val participants: List<SplitParticipant>)

private fun buildInitialSplit(totalPaise: Long, debts: List<SplitDebtEntity>, contextualNames: List<String>): InitialSplit {
    if (debts.isEmpty()) {
        // NEW: Context-Aware Auto-fill. Pre-populate the first empty slot with the #1 matching contextual name
        val prefilledP2 = if (contextualNames.isNotEmpty()) {
            SplitParticipant(name = contextualNames.first())
        } else {
            SplitParticipant()
        }
        return InitialSplit(SplitMode.EQUAL, true, listOf(SplitParticipant(isMe = true, name = "You"), prefilledP2))
    }
    val merged = LinkedHashMap<String, Triple<String, Long, Long?>>()
    debts.forEach { d ->
        val name = d.debtorName.trim()
        val key = name.lowercase()
        val paise = d.amountOwed.toLong()
        val prev = merged[key]
        merged[key] = if (prev == null) Triple(name, paise, d.id) else Triple(prev.first, prev.second + paise, prev.third)
    }
    val others = merged.values.toList()
    val myShare = (totalPaise - others.sumOf { it.second }).coerceAtLeast(0L)
    val names = listOf("You") + others.map { it.first }
    val amounts = listOf(myShare) + others.map { it.second }
    val debtIds = listOf(null) + others.map { it.third }
    val includeMe = myShare > 0L
    val activeIdx = amounts.indices.filter { it != 0 || includeMe }
    val looksEqual = activeIdx.size >= 2 && allocate(totalPaise, activeIdx.map { 1L }) == activeIdx.map { amounts[it] }
    val participants = names.mapIndexed { i, n ->
        SplitParticipant(isMe = i == 0, name = n, debtId = debtIds[i], isIncludedInEqual = true, exactAmountStr = if (amounts[i] > 0L) plain(amounts[i]) else "")
    }
    return InitialSplit(if (looksEqual) SplitMode.EQUAL else SplitMode.EXACT, includeMe, participants)
}

private fun splitSignature(mode: SplitMode, includeMe: Boolean, participants: List<SplitParticipant>): String =
    mode.name + "#" + includeMe + "#" + participants.joinToString("|") { "${it.isMe}:${it.name.trim()}:${it.exactAmountStr}:${it.percentageStr}" }

private val ParticipantsSaver = listSaver<List<SplitParticipant>, Any>(
    save = { list -> list.flatMap { listOf(it.id, it.debtId ?: -1L, it.isMe, it.name, it.isIncludedInEqual, it.exactAmountStr, it.percentageStr) } },
    restore = { flat -> flat.chunked(7).map { SplitParticipant(id = it[0] as String, debtId = (it[1] as Long).takeIf { id -> id != -1L }, isMe = it[2] as Boolean, name = it[3] as String, isIncludedInEqual = it[4] as Boolean, exactAmountStr = it[5] as String, percentageStr = it[6] as String) } }
)

private data class Hint(val text: String, val isError: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSplitShareSheet(
    transaction: TransactionEntity,
    onDismiss: () -> Unit,
    onSaveSplit: (participants: List<SplitParticipant>) -> Unit,
    initialDebts: List<SplitDebtEntity> = emptyList(),
    onResetSplit: (() -> Unit)? = null,
    contextualNames: List<String> = emptyList(), // NEW
    recentNames: List<String> = emptyList()      // NEW
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    var userPresets by remember { mutableStateOf(SplitPresetManager.loadPresets(prefs)) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetNameInput by remember { mutableStateOf("") }

    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val isEditing = initialDebts.isNotEmpty()

    val totalPaise = remember(transaction.amount) { transaction.amount }
    val initial = remember { buildInitialSplit(totalPaise, initialDebts, contextualNames) }
    val initialSignature = remember(initial) { splitSignature(initial.mode, initial.includeMe, initial.participants) }

    var selectedMode by rememberSaveable { mutableStateOf(initial.mode) }
    var participants by rememberSaveable(stateSaver = ParticipantsSaver) { mutableStateOf(initial.participants) }
    var includeMe by rememberSaveable { mutableStateOf(initial.includeMe) }
    var exactTouched by rememberSaveable { mutableStateOf(isEditing && initial.mode == SplitMode.EXACT) }
    var percentTouched by rememberSaveable { mutableStateOf(false) }

    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    var nudgeId by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun update(id: String, transform: (SplitParticipant) -> SplitParticipant) {
        participants = participants.map { if (it.id == id) transform(it) else it }
    }

    val active = remember(participants, includeMe) { if (includeMe) participants else participants.filter { !it.isMe } }
    val otherCount = active.count { !it.isMe }
    val shares = remember(active, selectedMode, totalPaise) { computeShares(active, selectedMode, totalPaise) }
    val assigned = shares.values.sum()
    val remaining = totalPaise - assigned
    val bpSum = active.sumOf { it.percentageStr.toMinorUnits() }
    val bpRemaining = FULL_BP - bpSum
    val myShare = active.firstOrNull { it.isMe }?.let { shares[it.id] } ?: 0L
    val othersOwe = active.filter { !it.isMe }.sumOf { shares[it.id] ?: 0L }

    val blankNameCount = active.count { !it.isMe && it.name.isBlank() }
    val badNames: Set<String> = remember(active) {
        active.filter { !it.isMe }.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().filter { it.value > 1 || it.key == "you" }.keys
    }
    val hasDebtor = active.any { !it.isMe && (shares[it.id] ?: 0L) > 0L }

    val blocking: Hint? = when {
        totalPaise <= 0L -> Hint("This transaction has no amount to split.", true)
        blankNameCount == 1 -> Hint("Enter a name for the person you're splitting with.", false)
        blankNameCount > 1 -> Hint("Enter a name for everyone you're splitting with.", false)
        badNames.isNotEmpty() -> Hint("Each person needs a unique name.", true)
        selectedMode == SplitMode.EXACT && remaining > 0L -> Hint("${money(remaining)} still needs to be assigned.", false)
        selectedMode == SplitMode.EXACT && remaining < 0L -> Hint("Shares exceed the bill by ${money(-remaining)}.", true)
        selectedMode == SplitMode.PERCENTAGE && bpRemaining > 0L -> Hint("${fmtBp(bpRemaining)}% still needs to be assigned.", false)
        selectedMode == SplitMode.PERCENTAGE && bpRemaining < 0L -> Hint("Percentages exceed 100% by ${fmtBp(-bpRemaining)}.", true)
        !hasDebtor -> Hint("At least one other person needs a share.", false)
        else -> null
    }

    val isDirty = splitSignature(selectedMode, includeMe, participants) != initialSignature
    val dirtyState = rememberUpdatedState(isDirty)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> if (target == SheetValue.Hidden && dirtyState.value) { showDiscardDialog = true; false } else true }
    )

    fun switchMode(target: SplitMode) {
        if (target == selectedMode) return
        val fromEqual = selectedMode == SplitMode.EQUAL
        participants = when (target) {
            SplitMode.EQUAL, SplitMode.SHARES -> participants
            SplitMode.EXACT -> if (fromEqual && exactTouched) participants else participants.map {
                val p = shares[it.id] ?: 0L
                it.copy(exactAmountStr = if (p > 0L) plain(p) else "")
            }
            SplitMode.PERCENTAGE -> if (fromEqual && percentTouched) participants else {
                val bps = allocate(FULL_BP, participants.map { shares[it.id] ?: 0L })
                participants.mapIndexed { i, p -> p.copy(percentageStr = if (bps[i] > 0L) fmtBp(bps[i]) else "") }
            }
        }
        if (target == SplitMode.EXACT && !(fromEqual && exactTouched)) exactTouched = false
        if (target == SplitMode.PERCENTAGE && !(fromEqual && percentTouched)) percentTouched = false
        selectedMode = target
    }

    fun addPerson(name: String = "") {
        val blank = participants.firstOrNull { !it.isMe && it.name.isBlank() }
        when {
            blank != null && name.isNotBlank() -> update(blank.id) { it.copy(name = name) }
            blank != null -> {
                tick()
                pendingFocusId = blank.id
                nudgeId = blank.id
            }
            active.size < MAX_PEOPLE -> {
                val p = SplitParticipant(name = name)
                participants = participants + p
                if (name.isBlank()) pendingFocusId = p.id
            }
        }
    }

    fun save() {
        if (blocking != null) return
        focusManager.clearFocus()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val me = participants.first { it.isMe }.let {
            it.copy(name = "You", isIncludedInEqual = includeMe, exactAmountStr = plain(if (includeMe) shares[it.id] ?: 0L else 0L))
        }
        val others = active.filter { !it.isMe && (shares[it.id] ?: 0L) > 0L }.map {
            it.copy(name = it.name.trim().replace(Regex("\\s+"), " "), isIncludedInEqual = true, exactAmountStr = plain(shares[it.id] ?: 0L))
        }
        onSaveSplit(listOf(me) + others)
        onDismiss()
    }

    // NEW: Smart filtering out of people already in the active list
    val contextNamesFiltered = remember(contextualNames, active) {
        contextualNames.filter { s -> s.isNotEmpty() && active.none { it.name.trim().equals(s, true) } }.take(4)
    }
    val recentNamesFiltered = remember(recentNames, contextNamesFiltered, active) {
        recentNames.filter { s ->
            s.isNotEmpty() && !contextNamesFiltered.contains(s) && active.none { it.name.trim().equals(s, true) }
        }.take(8)
    }

    if (showSavePresetDialog) {
        val nonBlankMembers = active.filter { !it.isMe && it.name.isNotBlank() }.map { it.name.trim() }
        AppDialog(
            title = "Save Group Preset",
            onDismiss = { showSavePresetDialog = false },
            confirmText = "Save Preset",
            confirmEnabled = newPresetNameInput.isNotBlank() && nonBlankMembers.isNotEmpty(),
            onConfirm = {
                val newPreset = UserSplitPreset(
                    id = UUID.randomUUID().toString(),
                    name = newPresetNameInput.trim(),
                    members = nonBlankMembers
                )
                val updated = userPresets + newPreset
                userPresets = updated
                SplitPresetManager.savePresets(prefs, updated)
                newPresetNameInput = ""
                showSavePresetDialog = false
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Save current members (${nonBlankMembers.joinToString(", ")}) as a reusable group preset:",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newPresetNameInput,
                    onValueChange = { newPresetNameInput = it.take(30) },
                    label = { Text("Preset Group Name") },
                    placeholder = { Text("e.g., Flatmates, Goa Trip") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDiscardDialog) {
        AppDialog(title = "Discard changes?", onDismiss = { showDiscardDialog = false }, dismissText = "Keep Editing", confirmText = "Discard", destructive = true, onConfirm = { showDiscardDialog = false; onDismiss() }) {
            Text("Your split hasn't been saved yet.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showRemoveDialog) {
        AppDialog(title = "Remove split?", onDismiss = { showRemoveDialog = false }, confirmText = "Remove", destructive = true, onConfirm = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showRemoveDialog = false; onResetSplit?.invoke(); onDismiss() }) {
            Text("All shares on this bill will be cleared, and any repayments already logged against them will be unlinked.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (isDirty) showDiscardDialog = true else onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (isEditing) "Edit Split" else "Split Bill", fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(transaction.payee, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onResetSplit != null && isEditing) {
                        Spacer(modifier = Modifier.width(12.dp))
                        FilledTonalButton(
                            onClick = { showRemoveDialog = true },
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Clear Split", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                SplitSummaryCard(totalPaise, selectedMode, assigned, bpSum, includeMe, myShare, othersOwe)

                SheetSegmentedControl(
                    options = SplitMode.entries.map { it.label },
                    selectedIndex = SplitMode.entries.indexOf(selectedMode),
                    onSelect = { switchMode(SplitMode.entries[it]) }
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("People", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${active.size} of $MAX_PEOPLE", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    }

                    Surface(
                        shape = RoundedCornerShape(AppRadius.medium),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                            active.forEachIndexed { index, p ->
                                key(p.id) {
                                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = dividerColor)
                                    ParticipantRow(
                                        participant = p, mode = selectedMode, sharePaise = shares[p.id] ?: 0L,
                                        nameError = when {
                                            p.isMe || p.name.trim().lowercase().isEmpty() -> null
                                            p.name.trim().lowercase() == "you" -> "\"You\" already added"
                                            p.name.trim().lowercase() in badNames -> "Duplicate name"
                                            else -> null
                                        },
                                        showNameNudge = nudgeId == p.id, isLast = index == active.lastIndex, canRemove = p.isMe || otherCount > 1,
                                        remainingPaise = remaining, remainingBp = bpRemaining,
                                        requestFocus = pendingFocusId == p.id, onFocusRequested = { pendingFocusId = null },
                                        onNameChange = { v -> nudgeId = null; update(p.id) { it.copy(name = v) } },
                                        onExactChange = { v -> exactTouched = true; update(p.id) { it.copy(exactAmountStr = v) } },
                                        onPercentChange = { v -> percentTouched = true; update(p.id) { it.copy(percentageStr = v) } },
                                        onSharesChange = { v -> update(p.id) { it.copy(sharesStr = v) } },
                                        onRemove = { tick(); if (p.isMe) includeMe = false else participants = participants.filter { it.id != p.id } },
                                    )
                                }
                            }

                            if (!includeMe) {
                                HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = dividerColor)
                                ActionRow(Icons.Default.Person, "Include myself", true) { tick(); includeMe = true }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = dividerColor)
                            ActionRow(Icons.Default.PersonAdd, if (active.size < MAX_PEOPLE) "Add person" else "Maximum $MAX_PEOPLE people", active.size < MAX_PEOPLE) { tick(); addPerson() }
                        }
                    }

                    // User Custom Group Presets
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Group presets", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val nonBlankMembers = active.filter { !it.isMe && it.name.isNotBlank() }
                            if (nonBlankMembers.isNotEmpty()) {
                                TextButton(
                                    onClick = { showSavePresetDialog = true },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save as preset", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (userPresets.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                userPresets.forEach { preset ->
                                    AssistChip(
                                        onClick = {
                                            preset.members.forEach { name -> addPerson(name) }
                                            tick()
                                        },
                                        label = { Text("${preset.name} (${preset.members.size})") },
                                        leadingIcon = { Icon(Icons.Default.Group, null, Modifier.size(16.dp)) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Delete preset",
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable {
                                                        val updated = userPresets.filter { it.id != preset.id }
                                                        userPresets = updated
                                                        SplitPresetManager.savePresets(prefs, updated)
                                                    }
                                            )
                                        }
                                    )
                                }
                            }
                        } else {
                            Text(
                                "No presets saved yet. Add members above and tap 'Save as preset'.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // NEW: Premium Context-Aware Auto-fill Section
                    if (active.size < MAX_PEOPLE && (contextNamesFiltered.isNotEmpty() || recentNamesFiltered.isNotEmpty())) {
                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                            if (contextNamesFiltered.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Usually splits this", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        contextNamesFiltered.forEach { name ->
                                            SheetChip(
                                                text = name,
                                                isSelected = false,
                                                onClick = { addPerson(name) },
                                                icon = Icons.Default.Person,
                                                accent = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            if (recentNamesFiltered.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Recent people", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        recentNamesFiltered.forEach { name ->
                                            SheetChip(
                                                text = name,
                                                isSelected = false,
                                                onClick = { addPerson(name) },
                                                icon = Icons.Default.Person,
                                                accent = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 28.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (blocking != null) {
                    val tint = if (blocking.isError) AppColors.negative else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.small))
                            .background(tint.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(if (blocking.isError) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(blocking.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint)
                    }
                }
                SheetPrimaryButton(
                    text = if (isEditing) "Update Split" else "Save Split",
                    enabled = blocking == null,
                    onClick = { save() }
                )
            }
        }
    }
}

@Composable
private fun SplitSummaryCard(totalPaise: Long, mode: SplitMode, assignedPaise: Long, bpSum: Long, includeMe: Boolean, myShare: Long, othersOwe: Long) {
    val remaining = totalPaise - assignedPaise
    val balanced: Boolean; val over: Boolean; val progress: Float; val statusText: String
    when (mode) {
        SplitMode.EQUAL, SplitMode.SHARES -> {
            balanced = totalPaise > 0L; over = false; progress = if (balanced) 1f else 0f
            statusText = "Fully assigned"
        }
        SplitMode.EXACT -> {
            balanced = remaining == 0L && totalPaise > 0L; over = remaining < 0L
            progress = if (totalPaise > 0L) (assignedPaise.toFloat() / totalPaise).coerceIn(0f, 1f) else 0f
            statusText = when { balanced -> "Fully assigned"; over -> "${money(-remaining)} over"; else -> "${money(remaining)} left" }
        }
        SplitMode.PERCENTAGE -> {
            balanced = bpSum == FULL_BP; over = bpSum > FULL_BP
            progress = (bpSum.toFloat() / FULL_BP).coerceIn(0f, 1f)
            statusText = when { balanced -> "Fully assigned"; over -> "${fmtBp(bpSum - FULL_BP)}% over"; else -> "${fmtBp(FULL_BP - bpSum)}% left" }
        }
    }
    val statusColor = when { over -> AppColors.negative; balanced -> AppColors.positive; else -> AppColors.warning }
    val animatedProgress by animateFloatAsState(progress, animationSpec = tween(300), label = "splitProgress")

    Surface(shape = RoundedCornerShape(AppRadius.medium), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Total Bill", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(money(totalPaise), fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(AppRadius.pill), color = statusColor.copy(alpha = 0.12f)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (balanced) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(statusText, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    }
                }
            }

            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(AppRadius.pill)), color = statusColor, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Your share", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(if (includeMe) money(myShare) else "Not included", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (includeMe) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Others owe you", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(money(othersOwe), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (othersOwe > 0L) AppColors.positive else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: SplitParticipant, mode: SplitMode, sharePaise: Long, nameError: String?, showNameNudge: Boolean, isLast: Boolean, canRemove: Boolean, remainingPaise: Long, remainingBp: Long, requestFocus: Boolean, onFocusRequested: () -> Unit, onNameChange: (String) -> Unit, onExactChange: (String) -> Unit, onPercentChange: (String) -> Unit, onSharesChange: (String) -> Unit, onRemove: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val imeAction = if (isLast) ImeAction.Done else ImeAction.Next
    val actions = KeyboardActions(onDone = { focusManager.clearFocus() })
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(requestFocus) { if (requestFocus) { runCatching { focusRequester.requestFocus() }; onFocusRequested() } }

    val canFillExact = mode == SplitMode.EXACT && remainingPaise > 0L && participant.exactAmountStr.isEmpty()
    val canFillPct = mode == SplitMode.PERCENTAGE && remainingBp > 0L && participant.percentageStr.isEmpty()

    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (participant.isMe) {
                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                } else {
                    Text(participant.name.trim().take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            if (participant.isMe) Text("You", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            else {
                BasicTextField(
                    value = participant.name, onValueChange = { if (it.length <= MAX_NAME_LENGTH) onNameChange(it) },
                    singleLine = true, textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (participant.name.isEmpty()) Text("Enter name", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = variant); inner() } }
                )
            }

            when {
                nameError != null -> SubLabel(nameError, AppColors.negative)
                showNameNudge && participant.name.isBlank() -> SubLabel("Enter a name before adding someone else", AppColors.negative)
                (mode == SplitMode.PERCENTAGE || mode == SplitMode.SHARES) && sharePaise > 0L -> SubLabel(money(sharePaise), variant)
                participant.isMe -> SubLabel("Paid the bill", variant)
            }

            AnimatedVisibility(visible = canFillExact) {
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(AppRadius.pill)).clickable { onExactChange(plain(remainingPaise)) }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Insert ${money(remainingPaise)}", color = MaterialTheme.colorScheme.surface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            AnimatedVisibility(visible = canFillPct) {
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(AppRadius.pill)).clickable { onPercentChange(fmtBp(remainingBp)) }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Insert ${fmtBp(remainingBp)}%", color = MaterialTheme.colorScheme.surface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

        }
        Spacer(Modifier.width(8.dp))
        when (mode) {
            SplitMode.EQUAL -> Text(money(sharePaise), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = 72.dp))
            SplitMode.SHARES -> InlineField(participant.sharesStr, { onSharesChange(it.filter { c -> c.isDigit() }.take(2)) }, "1", Modifier.width(72.dp), null, "x", KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction), actions)
            SplitMode.EXACT -> InlineField(participant.exactAmountStr, { if (it.matches(AMOUNT_REGEX)) onExactChange(it) }, "0.00", Modifier.width(112.dp), CURRENCY, null, KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction), actions)
            SplitMode.PERCENTAGE -> InlineField(participant.percentageStr, { if (it.matches(PERCENT_REGEX) && it.toMinorUnits() <= FULL_BP) onPercentChange(it) }, "0", Modifier.width(88.dp), null, "%", KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction), actions)
        }
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (canRemove) IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = variant, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun SubLabel(text: String, color: Color, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(text, fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).let { if (onClick != null) it.clip(RoundedCornerShape(6.dp)).clickable { onClick() } else it })
}

@Composable
private fun InlineField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, prefix: String? = null, suffix: String? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AppRadius.small)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent

    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), modifier = modifier.height(40.dp).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(modifier = Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).border(1.5.dp, borderColor, shape).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (prefix != null) { Text(prefix, fontSize = 15.sp, color = variant); Spacer(Modifier.width(4.dp)) }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = variant); inner() }
                if (suffix != null) { Spacer(Modifier.width(4.dp)); Text(suffix, fontSize = 15.sp, color = variant) }
            }
        }
    )
}