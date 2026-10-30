package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
import androidx.compose.material.icons.filled.Contacts
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.ReceiptLong
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.engine.split.ReceiptScanner
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.SplitPresetManager
import com.expensetracker.offline.util.UserSplitPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

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

private data class InitialSplit(
    val mode: SplitMode,
    val includeMe: Boolean,
    val paidBy: String,
    val participants: List<SplitParticipant>
)

private fun buildInitialSplit(
    totalPaise: Long,
    initialPaidBy: String?,
    debts: List<SplitDebtEntity>,
    contextualNames: List<String>
): InitialSplit {
    val payer = initialPaidBy?.trim()?.takeIf { it.isNotBlank() } ?: "You"
    if (debts.isEmpty()) {
        val prefilledP2 = if (contextualNames.isNotEmpty()) {
            SplitParticipant(name = contextualNames.first())
        } else {
            SplitParticipant()
        }
        return InitialSplit(SplitMode.EQUAL, true, payer, listOf(SplitParticipant(isMe = true, name = "You"), prefilledP2))
    }
    val merged = LinkedHashMap<String, Triple<String, Long, Long?>>()
    debts.forEach { d ->
        val name = d.debtorName.trim()
        val key = name.lowercase()
        val paise = d.amountOwed.toLong()
        val prev = merged[key]
        merged[key] = if (prev == null) Triple(name, paise, d.id) else Triple(prev.first, prev.second + paise, prev.third)
    }

    val isPaidByMe = payer.equals("You", ignoreCase = true)
    if (isPaidByMe) {
        val others = merged.values.filter { !it.first.equals("You", ignoreCase = true) }
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
        return InitialSplit(if (looksEqual) SplitMode.EQUAL else SplitMode.EXACT, includeMe, "You", participants)
    } else {
        val myEntry = merged["you"]
        val myShare = myEntry?.second ?: 0L
        val nonMeDebts = merged.values.filter { !it.first.equals("You", ignoreCase = true) && !it.first.equals(payer, ignoreCase = true) }
        val payerShare = (totalPaise - myShare - nonMeDebts.sumOf { it.second }).coerceAtLeast(0L)
        val names = listOf("You", payer) + nonMeDebts.map { it.first }
        val amounts = listOf(myShare, payerShare) + nonMeDebts.map { it.second }
        val debtIds = listOf(myEntry?.third, null) + nonMeDebts.map { it.third }
        val includeMe = myShare > 0L
        val activeIdx = amounts.indices.filter { it != 0 || includeMe }
        val looksEqual = activeIdx.size >= 2 && allocate(totalPaise, activeIdx.map { 1L }) == activeIdx.map { amounts[it] }
        val participants = names.mapIndexed { i, n ->
            SplitParticipant(isMe = i == 0, name = n, debtId = debtIds[i], isIncludedInEqual = true, exactAmountStr = if (amounts[i] > 0L) plain(amounts[i]) else "")
        }
        return InitialSplit(if (looksEqual) SplitMode.EQUAL else SplitMode.EXACT, includeMe, payer, participants)
    }
}

private fun splitSignature(mode: SplitMode, includeMe: Boolean, paidBy: String, participants: List<SplitParticipant>): String =
    mode.name + "#" + includeMe + "#" + paidBy.trim().lowercase() + "#" + participants.joinToString("|") {
        "${it.isMe}:${it.name.trim()}:${it.exactAmountStr}:${it.percentageStr}:${it.sharesStr}"
    }

private val ParticipantsSaver = listSaver<List<SplitParticipant>, Any>(
    save = { list ->
        list.flatMap {
            listOf(it.id, it.debtId ?: -1L, it.isMe, it.name, it.isIncludedInEqual, it.exactAmountStr, it.percentageStr, it.sharesStr)
        }
    },
    restore = { flat ->
        flat.chunked(8).map {
            SplitParticipant(
                id = it[0] as String,
                debtId = (it[1] as Long).takeIf { id -> id != -1L },
                isMe = it[2] as Boolean,
                name = it[3] as String,
                isIncludedInEqual = it[4] as Boolean,
                exactAmountStr = it[5] as String,
                percentageStr = it[6] as String,
                sharesStr = it[7] as String
            )
        }
    }
)

private data class Hint(val text: String, val isError: Boolean)
private data class QuickFixAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MultiSplitShareSheet(
    transaction: TransactionEntity,
    onDismiss: () -> Unit,
    onSaveSplit: (participants: List<SplitParticipant>, paidBy: String) -> Unit,
    initialDebts: List<SplitDebtEntity> = emptyList(),
    onResetSplit: (() -> Unit)? = null,
    contextualNames: List<String> = emptyList(),
    recentNames: List<String> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    var userPresets by remember { mutableStateOf(SplitPresetManager.loadPresets(prefs)) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetNameInput by remember { mutableStateOf("") }

    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val isEditing = initialDebts.isNotEmpty()

    val totalPaise = remember(transaction.amount) { transaction.amount }
    val initial = remember { buildInitialSplit(totalPaise, transaction.paidBy, initialDebts, contextualNames) }
    val initialSignature = remember(initial) { splitSignature(initial.mode, initial.includeMe, initial.paidBy, initial.participants) }

    var selectedMode by rememberSaveable { mutableStateOf(initial.mode) }
    var participants by rememberSaveable(stateSaver = ParticipantsSaver) { mutableStateOf(initial.participants) }
    var includeMe by rememberSaveable { mutableStateOf(initial.includeMe) }
    var paidBy by rememberSaveable { mutableStateOf(initial.paidBy) }
    var exactTouched by rememberSaveable { mutableStateOf(isEditing && initial.mode == SplitMode.EXACT) }
    var percentTouched by rememberSaveable { mutableStateOf(false) }

    // Itemized Receipt OCR Splitting state
    var scannedReceiptItems by remember { mutableStateOf<List<ReceiptScanner.ScannedItem>>(emptyList()) }
    var itemAssignmentsVersion by remember { mutableIntStateOf(0) }
    var isScanningReceipt by remember { mutableStateOf(false) }

    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    var nudgeId by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }

    fun tick() = haptic.tap()
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

    val availablePayers = remember(active) {
        listOf("You") + active.filter { !it.isMe && it.name.trim().isNotEmpty() }.map { it.name.trim() }.distinct()
    }
    LaunchedEffect(availablePayers) {
        if (availablePayers.none { it.equals(paidBy, ignoreCase = true) }) {
            paidBy = "You"
        }
    }

    fun applyProRataReceiptSplit(items: List<ReceiptScanner.ScannedItem>) {
        val validPeople = active.mapNotNull { p ->
            if (p.isMe) "You" else p.name.trim().takeIf { it.isNotEmpty() }
        }
        if (items.isEmpty() || validPeople.isEmpty()) return
        val proRataMap = ReceiptScanner.calculateProRataShares(items, totalPaise, validPeople).toMutableMap()

        // Zero-cognitive-load helper: if the user assigned specific items to friends and hasn't explicitly
        // tapped "You" on the remaining items yet, automatically assign the remaining bill balance to "You"
        // so the total always sums to totalPaise without requiring 10 extra taps!
        val youExplicitlyAssigned = items.any { item -> item.assignedTo.any { it.equals("You", ignoreCase = true) } }
        val othersSum = proRataMap.entries.filterNot { it.key.equals("You", ignoreCase = true) }.sumOf { it.value }
        if (includeMe && !youExplicitlyAssigned && othersSum in 1L..totalPaise) {
            proRataMap["You"] = (totalPaise - othersSum).coerceAtLeast(0L)
        }

        selectedMode = SplitMode.EXACT
        exactTouched = true
        participants = participants.map { p ->
            val key = if (p.isMe) "You" else p.name.trim()
            val share = proRataMap[key] ?: 0L
            p.copy(exactAmountStr = if (share > 0L) plain(share) else "")
        }
    }

    fun scanReceiptUri(pathOrUri: String) {
        isScanningReceipt = true
        scope.launch {
            try {
                val file = File(pathOrUri)
                val uri = if (file.exists()) Uri.fromFile(file) else Uri.parse(pathOrUri)
                val extracted = withContext(Dispatchers.IO) {
                    ReceiptScanner.extractReceipt(context, uri).items
                }
                if (extracted.isNotEmpty()) {
                    scannedReceiptItems = extracted
                    Toast.makeText(context, "Found ${extracted.size} items! Tap names under each item to assign.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not detect priced items on receipt.", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isScanningReceipt = false
            }
        }
    }

    val pickReceiptForSplit = rememberReceiptPicker("split_receipt_${transaction.id}") { path ->
        scanReceiptUri(path)
    }

    val blankNameCount = active.count { !it.isMe && it.name.isBlank() }
    val badNames: Set<String> = remember(active) {
        active.filter { !it.isMe }.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().filter { it.value > 1 || it.key == "you" }.keys
    }
    val hasDebtor = active.any { !it.isMe && (shares[it.id] ?: 0L) > 0L }

    val blocking: Hint? = when {
        totalPaise <= 0L -> Hint("This transaction has no amount to split.", true)
        blankNameCount == 1 -> Hint("Enter a name for the person you're splitting with (or remove the empty row).", false)
        blankNameCount > 1 -> Hint("Enter names for everyone you're splitting with (or remove empty rows).", false)
        badNames.isNotEmpty() -> Hint("Each person needs a unique name.", true)
        selectedMode == SplitMode.EXACT && remaining > 0L -> Hint("${money(remaining)} left unassigned — pick a quick fix below:", false)
        selectedMode == SplitMode.EXACT && remaining < 0L -> Hint("Shares exceed the bill by ${money(-remaining)} — pick a quick fix below:", true)
        selectedMode == SplitMode.PERCENTAGE && bpRemaining > 0L -> Hint("${fmtBp(bpRemaining)}% left unassigned — pick a quick fix below:", false)
        selectedMode == SplitMode.PERCENTAGE && bpRemaining < 0L -> Hint("Percentages exceed 100% by ${fmtBp(-bpRemaining)}% — pick a quick fix below:", true)
        !hasDebtor -> Hint("At least one other person needs a share greater than ₹0.", false)
        else -> null
    }

    val isDirty = splitSignature(selectedMode, includeMe, paidBy, participants) != initialSignature
    val dirtyState = rememberUpdatedState(isDirty)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyState.value) {
                showDiscardDialog = true
                false
            } else {
                true
            }
        }
    )

    // Safety net: if ModalBottomSheet animated to Hidden (e.g. scrim tap or system back) before showing
    // the discard dialog, and the user tapped "Keep Editing", immediately re-show the sheet so the UI never freezes!
    LaunchedEffect(showDiscardDialog, sheetState.currentValue) {
        if (!showDiscardDialog && sheetState.currentValue == SheetValue.Hidden && !isClosing) {
            runCatching { sheetState.show() }
        }
    }

    BackHandler(enabled = isDirty) {
        showDiscardDialog = true
    }

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

    fun addPerson(name: String = "", initialExactPaise: Long = 0L, initialPercentBp: Long = 0L) {
        val exactFill = if (initialExactPaise > 0L) plain(initialExactPaise) else ""
        val pctFill = if (initialPercentBp > 0L) fmtBp(initialPercentBp) else ""
        val blank = participants.firstOrNull { !it.isMe && it.name.isBlank() }
        when {
            blank != null && name.isNotBlank() -> update(blank.id) {
                it.copy(
                    name = name,
                    exactAmountStr = exactFill.ifEmpty { it.exactAmountStr },
                    percentageStr = pctFill.ifEmpty { it.percentageStr }
                )
            }
            blank != null -> {
                tick()
                if (exactFill.isNotEmpty() || pctFill.isNotEmpty()) {
                    update(blank.id) {
                        it.copy(
                            exactAmountStr = exactFill.ifEmpty { it.exactAmountStr },
                            percentageStr = pctFill.ifEmpty { it.percentageStr }
                        )
                    }
                }
                pendingFocusId = blank.id
                nudgeId = blank.id
            }
            active.size < MAX_PEOPLE -> {
                val p = SplitParticipant(
                    name = name,
                    exactAmountStr = exactFill,
                    percentageStr = pctFill
                )
                participants = participants + p
                if (name.isBlank()) pendingFocusId = p.id
            }
        }
    }

    // Build 1-tap Smart Fix actions whenever there is a blocking condition so users never have to think or do mental math
    val quickFixes: List<QuickFixAction> = remember(
        blocking, selectedMode, remaining, bpRemaining, active, shares, includeMe, myShare, blankNameCount, otherCount, totalPaise
    ) {
        if (blocking == null || totalPaise <= 0L) return@remember emptyList()
        val fixes = mutableListOf<QuickFixAction>()

        if (blankNameCount > 0) {
            val firstBlank = active.firstOrNull { !it.isMe && it.name.isBlank() }
            if (firstBlank != null) {
                fixes += QuickFixAction("Type name") {
                    tick()
                    pendingFocusId = firstBlank.id
                    nudgeId = firstBlank.id
                }
            }
            if (otherCount > blankNameCount) {
                fixes += QuickFixAction(if (blankNameCount == 1) "Remove empty row" else "Remove $blankNameCount empty rows") {
                    tick()
                    participants = participants.filter { it.isMe || it.name.isNotBlank() }
                }
            }
            return@remember fixes
        }

        if (badNames.isNotEmpty()) {
            val dup = active.lastOrNull { !it.isMe && it.name.trim().lowercase() in badNames }
            if (dup != null) {
                fixes += QuickFixAction("Edit duplicate \"${dup.name.trim()}\"") {
                    tick()
                    pendingFocusId = dup.id
                }
            }
            return@remember fixes
        }

        if (selectedMode == SplitMode.EXACT && remaining != 0L) {
            // 1. Adjust "You" if included and resulting share >= 0
            val meParticipant = active.firstOrNull { it.isMe }
            if (meParticipant != null && myShare + remaining >= 0L) {
                val newMyShare = myShare + remaining
                val actionLabel = if (remaining > 0L) {
                    "Add ${money(remaining)} to You (→ ${money(newMyShare)})"
                } else {
                    "Reduce You by ${money(-remaining)} (→ ${money(newMyShare)})"
                }
                fixes += QuickFixAction(actionLabel) {
                    tick()
                    exactTouched = true
                    update(meParticipant.id) { it.copy(exactAmountStr = if (newMyShare > 0L) plain(newMyShare) else "") }
                }
            }

            // 2. Adjust named friend(s) if their share + remaining >= 0
            active.filter { !it.isMe && it.name.isNotBlank() }.forEach { friend ->
                val fShare = shares[friend.id] ?: 0L
                val newFriendShare = fShare + remaining
                if (newFriendShare >= 0L && fixes.size < 4) {
                    val fName = friend.name.trim()
                    val label = if (remaining > 0L) {
                        "Add ${money(remaining)} to $fName (→ ${money(newFriendShare)})"
                    } else {
                        "Reduce $fName by ${money(-remaining)} (→ ${money(newFriendShare)})"
                    }
                    fixes += QuickFixAction(label) {
                        tick()
                        exactTouched = true
                        update(friend.id) { it.copy(exactAmountStr = if (newFriendShare > 0L) plain(newFriendShare) else "") }
                    }
                }
            }

            // 3. Split difference equally across all active people
            if (active.size >= 2) {
                val deltas = allocate(abs(remaining), active.map { 1L })
                val canApplyEqualDelta = active.indices.all { i ->
                    val cur = shares[active[i].id] ?: 0L
                    val next = if (remaining > 0L) cur + deltas[i] else cur - deltas[i]
                    next >= 0L
                }
                if (canApplyEqualDelta) {
                    val perPerson = abs(remaining) / active.size
                    val label = if (remaining > 0L) {
                        "Split +${money(remaining)} equally (+${money(perPerson)}/each)"
                    } else {
                        "Reduce ${money(-remaining)} equally (−${money(perPerson)}/each)"
                    }
                    fixes += QuickFixAction(label) {
                        tick()
                        exactTouched = true
                        val newById = active.indices.associate { i ->
                            val p = active[i]
                            val cur = shares[p.id] ?: 0L
                            val next = if (remaining > 0L) cur + deltas[i] else cur - deltas[i]
                            p.id to next
                        }
                        participants = participants.map { p ->
                            val updatedPaise = newById[p.id] ?: return@map p
                            p.copy(exactAmountStr = if (updatedPaise > 0L) plain(updatedPaise) else "")
                        }
                    }
                }
            }

            // 4. Add another person for the unassigned remainder
            if (remaining > 0L && active.size < MAX_PEOPLE) {
                fixes += QuickFixAction("+ Add person for ${money(remaining)}") {
                    tick()
                    exactTouched = true
                    addPerson(initialExactPaise = remaining)
                }
            }

            // 5. Scale current amounts proportionally to totalPaise
            val nonZeroCount = active.count { (shares[it.id] ?: 0L) > 0L }
            if (assigned > 0L && nonZeroCount >= 2) {
                fixes += QuickFixAction("Scale proportionally to ${money(totalPaise)}") {
                    tick()
                    exactTouched = true
                    val scaled = allocate(totalPaise, active.map { (shares[it.id] ?: 0L).coerceAtLeast(0L) })
                    val scaledMap = active.indices.associate { i -> active[i].id to scaled[i] }
                    participants = participants.map { p ->
                        val s = scaledMap[p.id] ?: return@map p
                        p.copy(exactAmountStr = if (s > 0L) plain(s) else "")
                    }
                }
            }
        } else if (selectedMode == SplitMode.PERCENTAGE && bpRemaining != 0L) {
            val meParticipant = active.firstOrNull { it.isMe }
            val myBp = meParticipant?.percentageStr?.toMinorUnits() ?: 0L
            if (meParticipant != null && myBp + bpRemaining in 0L..FULL_BP) {
                val newBp = myBp + bpRemaining
                val label = if (bpRemaining > 0L) {
                    "Add ${fmtBp(bpRemaining)}% to You (→ ${fmtBp(newBp)}%)"
                } else {
                    "Reduce You by ${fmtBp(-bpRemaining)}% (→ ${fmtBp(newBp)}%)"
                }
                fixes += QuickFixAction(label) {
                    tick()
                    percentTouched = true
                    update(meParticipant.id) { it.copy(percentageStr = if (newBp > 0L) fmtBp(newBp) else "") }
                }
            }

            if (active.size >= 2) {
                val deltas = allocate(abs(bpRemaining), active.map { 1L })
                val canApply = active.indices.all { i ->
                    val cur = active[i].percentageStr.toMinorUnits()
                    val next = if (bpRemaining > 0L) cur + deltas[i] else cur - deltas[i]
                    next in 0L..FULL_BP
                }
                if (canApply) {
                    fixes += QuickFixAction(
                        if (bpRemaining > 0L) "Split +${fmtBp(bpRemaining)}% equally" else "Reduce ${fmtBp(-bpRemaining)}% equally"
                    ) {
                        tick()
                        percentTouched = true
                        val newById = active.indices.associate { i ->
                            val p = active[i]
                            val cur = p.percentageStr.toMinorUnits()
                            val next = if (bpRemaining > 0L) cur + deltas[i] else cur - deltas[i]
                            p.id to next
                        }
                        participants = participants.map { p ->
                            val updatedBp = newById[p.id] ?: return@map p
                            p.copy(percentageStr = if (updatedBp > 0L) fmtBp(updatedBp) else "")
                        }
                    }
                }
            }

            if (bpSum > 0L) {
                fixes += QuickFixAction("Scale proportionally to 100%") {
                    tick()
                    percentTouched = true
                    val scaled = allocate(FULL_BP, active.map { it.percentageStr.toMinorUnits().coerceAtLeast(0L) })
                    val scaledMap = active.indices.associate { i -> active[i].id to scaled[i] }
                    participants = participants.map { p ->
                        val s = scaledMap[p.id] ?: return@map p
                        p.copy(percentageStr = if (s > 0L) fmtBp(s) else "")
                    }
                }
            }

            if (bpRemaining > 0L && active.size < MAX_PEOPLE) {
                fixes += QuickFixAction("+ Add person for ${fmtBp(bpRemaining)}%") {
                    tick()
                    percentTouched = true
                    addPerson(initialPercentBp = bpRemaining)
                }
            }
        } else if (!hasDebtor && active.size >= 2) {
            fixes += QuickFixAction("Split equally (${money(totalPaise / active.size)}/each)") {
                tick()
                switchMode(SplitMode.EQUAL)
            }
        }

        fixes
    }

    // Picks one phone number from the system contact picker. The picker hands us read access to just
    // that entry, so no READ_CONTACTS permission is needed.
    val splitContext = androidx.compose.ui.platform.LocalContext.current
    val contactPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val picked = runCatching {
            splitContext.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
        }.getOrNull()
        if (picked != null) {
            val contactName = picked.first.orEmpty().trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)
            if (contactName.isNotBlank() && active.none { it.name.trim().equals(contactName, ignoreCase = true) }) {
                runCatching {
                    com.expensetracker.offline.data.repository.SettingsRepository(splitContext)
                        .linkContact(contactName, picked.second.orEmpty())
                }
                tick()
                addPerson(name = contactName)
            }
        }
    }

    fun save() {
        if (blocking != null) return
        isClosing = true
        focusManager.clearFocus()
        haptic.confirm()
        val me = participants.first { it.isMe }.let {
            it.copy(name = "You", isIncludedInEqual = includeMe, exactAmountStr = plain(if (includeMe) shares[it.id] ?: 0L else 0L))
        }
        val others = active.filter { !it.isMe && (shares[it.id] ?: 0L) > 0L }.map {
            it.copy(name = it.name.trim().replace(Regex("\\s+"), " "), isIncludedInEqual = true, exactAmountStr = plain(shares[it.id] ?: 0L))
        }
        onSaveSplit(listOf(me) + others, paidBy)
        onDismiss()
    }

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
                haptic.confirm()
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
                    colors = sheetFieldColors(),
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDiscardDialog) {
        AppDialog(
            title = "Discard changes?",
            onDismiss = {
                tick()
                showDiscardDialog = false
                scope.launch { runCatching { sheetState.show() } }
            },
            dismissText = "Keep editing",
            confirmText = "Discard",
            destructive = true,
            onConfirm = {
                isClosing = true
                haptic.confirm()
                showDiscardDialog = false
                onDismiss()
            }
        ) {
            Text(
                "Your split hasn't been saved yet. Discard your changes?",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    if (showRemoveDialog) {
        AppDialog(
            title = "Remove split?",
            onDismiss = {
                tick()
                showRemoveDialog = false
            },
            confirmText = "Remove",
            destructive = true,
            onConfirm = {
                isClosing = true
                haptic.confirm()
                showRemoveDialog = false
                onResetSplit?.invoke()
                onDismiss()
            }
        ) {
            Text(
                "All shares on this bill will be cleared, and any repayments already logged against them will be unlinked.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    ResistantModalBottomSheet(
        onDismissRequest = { if (isDirty) showDiscardDialog = true else { isClosing = true; onDismiss() } },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        // Intercept Back presses inside the ModalBottomSheet window so the sheet stays visible while asking to discard
        BackHandler(enabled = isDirty) {
            showDiscardDialog = true
        }

        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fadingEdges(scrollState, vertical = true, edge = 14.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
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
                            onClick = { tick(); showRemoveDialog = true },
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            shape = RoundedCornerShape(AppRadius.pill),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Clear Split", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }

                SplitSummaryCard(totalPaise, selectedMode, assigned, bpSum, includeMe, myShare, othersOwe, paidBy)

                if (availablePayers.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Paid by",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availablePayers.forEach { payerName ->
                                val selected = paidBy.equals(payerName, ignoreCase = true)
                                SheetChip(
                                    text = if (payerName.equals("You", ignoreCase = true)) "You paid" else "$payerName paid",
                                    isSelected = selected,
                                    icon = if (selected) Icons.Default.Check else Icons.Default.Person,
                                    onClick = {
                                        tick()
                                        paidBy = payerName
                                    }
                                )
                            }
                        }
                    }
                }

                SheetSegmentedControl(
                    options = SplitMode.entries.map { it.label },
                    selectedIndex = SplitMode.entries.indexOf(selectedMode),
                    onSelect = { switchMode(SplitMode.entries[it]) }
                )

                // Monochromatic Itemized Receipt OCR Split Card
                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Split by Bill Items (OCR)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Assign who ate what · tax splits proportionally", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(AppRadius.pill),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.pill))
                                    .clickable(enabled = !isScanningReceipt) {
                                        tick()
                                        val existingUri = transaction.receiptImageUri
                                        if (!existingUri.isNullOrBlank() && scannedReceiptItems.isEmpty()) {
                                            scanReceiptUri(existingUri)
                                        } else {
                                            pickReceiptForSplit()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isScanningReceipt) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                                    } else {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (!transaction.receiptImageUri.isNullOrBlank() && scannedReceiptItems.isEmpty()) "Scan Attached" else "Scan Bill",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.surface
                                        )
                                    }
                                }
                            }
                        }

                        if (scannedReceiptItems.isNotEmpty()) {
                            key(itemAssignmentsVersion) {
                                val assignableNames = remember(active) {
                                    active.mapNotNull { p -> if (p.isMe) "You" else p.name.trim().takeIf { it.isNotEmpty() } }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    scannedReceiptItems.forEach { item ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(AppRadius.small))
                                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f), RoundedCornerShape(AppRadius.small))
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (item.qty > 1) "${item.name} ×${item.qty}" else item.name,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(money(item.pricePaise), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val allAssigned = assignableNames.isNotEmpty() && item.assignedTo.containsAll(assignableNames)
                                                Surface(
                                                    shape = RoundedCornerShape(AppRadius.pill),
                                                    color = if (allAssigned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                                                    border = if (allAssigned) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(AppRadius.pill))
                                                        .clickable {
                                                            tick()
                                                            if (allAssigned) item.assignedTo.clear() else item.assignedTo.addAll(assignableNames)
                                                            itemAssignmentsVersion++
                                                            applyProRataReceiptSplit(scannedReceiptItems)
                                                        }
                                                ) {
                                                    Text(
                                                        text = "All",
                                                        fontSize = 12.sp,
                                                        fontWeight = if (allAssigned) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (allAssigned) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                    )
                                                }

                                                assignableNames.forEach { personName ->
                                                    val isAssigned = item.assignedTo.contains(personName)
                                                    Surface(
                                                        shape = RoundedCornerShape(AppRadius.pill),
                                                        color = if (isAssigned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                                                        border = if (isAssigned) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(AppRadius.pill))
                                                            .clickable {
                                                                tick()
                                                                if (isAssigned) item.assignedTo.remove(personName) else item.assignedTo.add(personName)
                                                                itemAssignmentsVersion++
                                                                applyProRataReceiptSplit(scannedReceiptItems)
                                                            }
                                                    ) {
                                                        Text(
                                                            text = personName,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isAssigned) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isAssigned) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("People", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${active.size} of $MAX_PEOPLE", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    }

                    Surface(
                        shape = RoundedCornerShape(AppRadius.medium),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                            active.forEachIndexed { index, p ->
                                key(p.id) {
                                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = dividerColor)
                                    val pDisplayName = if (p.isMe) "You" else p.name.trim()
                                    val isThisPersonPayer = pDisplayName.isNotEmpty() && pDisplayName.equals(paidBy, ignoreCase = true)
                                    ParticipantRow(
                                        participant = p, mode = selectedMode, sharePaise = shares[p.id] ?: 0L,
                                        isPayer = isThisPersonPayer,
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
                            if (active.size < MAX_PEOPLE) {
                                HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = dividerColor)
                                ActionRow(Icons.Default.Contacts, "Add from contacts", true) {
                                    tick()
                                    runCatching {
                                        contactPicker.launch(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_PICK,
                                                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // User Custom Group Presets
                    if (userPresets.isNotEmpty() || active.any { !it.isMe && it.name.isNotBlank() }) {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Group presets", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val nonBlankMembers = active.filter { !it.isMe && it.name.isNotBlank() }
                            if (nonBlankMembers.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.pill),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.pill))
                                        .clickable { tick(); showSavePresetDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save as preset", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }

                        if (userPresets.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                userPresets.forEach { preset ->
                                    Surface(
                                        shape = RoundedCornerShape(AppRadius.pill),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppRadius.pill))
                                            .clickable {
                                                preset.members.forEach { memberName ->
                                                    if (active.none { it.name.trim().equals(memberName, ignoreCase = true) }) {
                                                        addPerson(memberName)
                                                    }
                                                }
                                                tick()
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("${preset.name} (${preset.members.size})", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Delete preset",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .size(15.dp)
                                                    .clickable {
                                                        tick()
                                                        val updated = userPresets.filter { it.id != preset.id }
                                                        userPresets = updated
                                                        SplitPresetManager.savePresets(prefs, updated)
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                "Tap 'Save as preset' to reuse this group next time.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
}

                    if (active.size < MAX_PEOPLE && (contextNamesFiltered.isNotEmpty() || recentNamesFiltered.isNotEmpty())) {
                        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                            if (contextNamesFiltered.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Usually splits this", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        contextNamesFiltered.forEach { name ->
                                            SheetChip(
                                                text = name,
                                                isSelected = false,
                                                onClick = { addPerson(name) },
                                                icon = Icons.Default.Person
                                            )
                                        }
                                    }
                                }
                            }

                            if (recentNamesFiltered.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Recent people", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        recentNamesFiltered.forEach { name ->
                                            SheetChip(
                                                text = name,
                                                isSelected = false,
                                                onClick = { addPerson(name) },
                                                icon = Icons.Default.Person
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 14.dp, bottom = 24.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (blocking != null) {
                    val tint = if (blocking.isError) AppColors.negative else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.small))
                            .background(tint.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (blocking.isError) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(blocking.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint)
                        }

                        if (quickFixes.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                quickFixes.forEachIndexed { idx, fix ->
                                    val isPrimaryFix = idx == 0
                                    Surface(
                                        shape = RoundedCornerShape(AppRadius.pill),
                                        color = if (isPrimaryFix) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                                        border = if (isPrimaryFix) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppRadius.pill))
                                            .clickable { fix.onClick() }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (isPrimaryFix) Icons.Default.AutoAwesome else Icons.Default.Check,
                                                contentDescription = null,
                                                tint = if (isPrimaryFix) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = fix.label,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPrimaryFix) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
private fun SplitSummaryCard(
    totalPaise: Long,
    mode: SplitMode,
    assignedPaise: Long,
    bpSum: Long,
    includeMe: Boolean,
    myShare: Long,
    othersOwe: Long,
    paidBy: String = "You"
) {
    val isPaidByMe = paidBy.equals("You", ignoreCase = true)
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

    Surface(shape = RoundedCornerShape(AppRadius.medium), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth()) {
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
                    Text(
                        if (isPaidByMe) "Others owe you" else "You owe $paidBy",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val displayOwed = if (isPaidByMe) othersOwe else if (includeMe) myShare else 0L
                    val displayColor = when {
                        displayOwed <= 0L -> MaterialTheme.colorScheme.onSurface
                        isPaidByMe -> AppColors.positive
                        else -> AppColors.warning
                    }
                    Text(money(displayOwed), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = displayColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: SplitParticipant,
    mode: SplitMode,
    sharePaise: Long,
    isPayer: Boolean = false,
    nameError: String?,
    showNameNudge: Boolean,
    isLast: Boolean,
    canRemove: Boolean,
    remainingPaise: Long,
    remainingBp: Long,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    onNameChange: (String) -> Unit,
    onExactChange: (String) -> Unit,
    onPercentChange: (String) -> Unit,
    onSharesChange: (String) -> Unit,
    onRemove: () -> Unit
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
                (mode == SplitMode.PERCENTAGE || mode == SplitMode.SHARES) && sharePaise > 0L -> SubLabel(if (isPayer) "${money(sharePaise)} · Paid the bill" else money(sharePaise), variant)
                isPayer -> SubLabel("Paid the bill", AppColors.positive, bold = true)
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