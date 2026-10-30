package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.dao.TransactionWithDebts
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.data.repository.isValidUpiVpa
import com.expensetracker.offline.engine.split.DebtSimplification
import com.expensetracker.offline.ui.dashboard.DebtInfo
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.UpiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PersonGroup(val name: String, val debts: List<DebtInfo>, val isYouOwe: Boolean = false) {
    val total: Long get() = debts.sumOf { it.outstanding }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleDebtDialog(
    activeDebts: List<DebtInfo>,
    onDismiss: () -> Unit,
    onRepaymentLogged: (debtId: Long, amount: Long, isForgiveness: Boolean) -> Unit
) {
    val context = LocalContext.current
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var lastIntentTime by remember { mutableLongStateOf(0L) }
    var forgiveTarget by remember { mutableStateOf<Long?>(null) }
    var upiDialogDebt by remember { mutableStateOf<DebtInfo?>(null) }
    var qrModalDebt by remember { mutableStateOf<Pair<DebtInfo, String>?>(null) }
    var upiIdInput by remember { mutableStateOf("") }
    var openQrAfterVpaSave by remember { mutableStateOf(false) }

    val settingsRepo = remember { SettingsRepository(context) }

    val shareUpiRequest: (DebtInfo, String) -> Unit = { targetDebt, vpa ->
        val now = System.currentTimeMillis()
        if (now - lastIntentTime > 1000) {
            lastIntentTime = now
            val firstName = targetDebt.debt.debtorName.trim().split(" ").firstOrNull { it.isNotBlank() }
                ?.takeIf { it != "Unspecified" }
            val greeting = if (firstName != null) "Hey $firstName! " else "Hey! "
            val bill = targetDebt.parentTransaction.payee
            val paise = targetDebt.outstanding
            val amount = CurrencyFormat.withSymbol(paise, if (paise % 100L == 0L) 0 else 2)
            val upiUri = UpiUtils.buildUpiUri(
                payeeVpa = vpa,
                payeeName = vpa.substringBefore('@'),
                amountPaise = paise,
                note = "Settling $bill"
            ).toString()
            val msg = "${greeting}You owe me $amount for $bill.\n\n" +
                    "Pay via UPI\n" +
                    "UPI ID: $vpa\n" +
                    "Amount: ${String.format(Locale.US, "%d.%02d", paise / 100, paise % 100)}\n\n" +
                    "Tap to pay (on phone): $upiUri"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Send payment request"))
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open the share sheet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Separate debts owed TO YOU vs debts YOU OWE to someone else
    val (youOweList, owedToYouList) = remember(activeDebts) {
        activeDebts.partition { info ->
            val payer = info.parentTransaction.paidBy?.trim()
            payer != null && !payer.equals("You", ignoreCase = true) &&
                    info.debt.debtorName.trim().equals("You", ignoreCase = true)
        }
    }

    val owedToYouGroups = remember(owedToYouList) {
        owedToYouList
            .sortedByDescending { it.parentTransaction.timestamp }
            .groupBy { it.debt.debtorName.trim().lowercase() }
            .map { (_, debts) -> PersonGroup(debts.first().debt.debtorName.trim(), debts, isYouOwe = false) }
            .sortedByDescending { it.total }
    }

    val youOweGroups = remember(youOweList) {
        youOweList
            .sortedByDescending { it.parentTransaction.timestamp }
            .groupBy { (it.parentTransaction.paidBy ?: "Friend").trim().lowercase() }
            .map { (_, debts) -> PersonGroup((debts.first().parentTransaction.paidBy ?: "Friend").trim(), debts, isYouOwe = true) }
            .sortedByDescending { it.total }
    }

    val totalOwedToYou = remember(owedToYouGroups) { owedToYouGroups.sumOf { it.total } }
    val totalYouOwe = remember(youOweGroups) { youOweGroups.sumOf { it.total } }
    val dateFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }

    val neutralSelection = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.onSurface,
        backgroundColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ResistantModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large),
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
        CompositionLocalProvider(LocalTextSelectionColors provides neutralSelection) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
            ) {
                // ── Header ──
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Handshake, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Settle debt", fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onSurface)
                        val subtitle = when {
                            owedToYouGroups.isEmpty() && youOweGroups.isEmpty() -> "Nothing owed right now"
                            totalOwedToYou > 0L && totalYouOwe > 0L ->
                                "${CurrencyFormat.withSymbol(totalOwedToYou, 0)} owed to you · ${CurrencyFormat.withSymbol(totalYouOwe, 0)} you owe"
                            totalYouOwe > 0L ->
                                "You owe ${CurrencyFormat.withSymbol(totalYouOwe, 0)} to ${youOweGroups.size} ${if (youOweGroups.size == 1) "person" else "people"}"
                            else ->
                                "${CurrencyFormat.withSymbol(totalOwedToYou, 0)} owed by ${owedToYouGroups.size} ${if (owedToYouGroups.size == 1) "person" else "people"}"
                        }
                        Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (owedToYouGroups.isEmpty() && youOweGroups.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppRadius.small),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No active split debts found.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                } else {
                    val simplifiedTransfers = remember(activeDebts) {
                        val txnsWithDebts = activeDebts.groupBy { it.parentTransaction.id }.map { (_, debtInfos) ->
                            TransactionWithDebts(
                                transaction = debtInfos.first().parentTransaction,
                                debts = debtInfos.map { it.debt }
                            )
                        }
                        DebtSimplification.simplifyTripDebts(
                            txnsWithDebts,
                            outstandingByDebtId = activeDebts.associate { it.debt.id to it.outstanding }
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (simplifiedTransfers.isNotEmpty()) {
                            item(key = "smart_settlement_plan") {
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.medium),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Default.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Smart Net Settlement",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        "Simplified net balances across all payers",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Spacer(Modifier.width(8.dp))

                                            Surface(
                                                shape = RoundedCornerShape(AppRadius.pill),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(AppRadius.pill))
                                                    .clickable {
                                                        val summary = simplifiedTransfers.joinToString("\n") { "• ${it.from} pays ${it.to}: ${CurrencyFormat.withSymbol(it.amountPaise, 0)}" }
                                                        val msg = "Hey everyone! Here is the simplified net settlement plan for our group expenses:\n\n$summary"
                                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(Intent.EXTRA_TEXT, msg)
                                                        }
                                                        context.startActivity(Intent.createChooser(intent, "Share Settlement Plan"))
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Default.Share,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        "Share Plan",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            simplifiedTransfers.take(5).forEach { transfer ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            transfer.from,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            " pays ",
                                                            fontSize = 14.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Text(
                                                            transfer.to,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                    Text(
                                                        CurrencyFormat.withSymbol(transfer.amountPaise, 0),
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── Debts Owed To You ──
                        owedToYouGroups.forEachIndexed { index, group ->
                            item(key = "header_owed_${group.name.lowercase()}") {
                                PersonGroupHeader(group = group, isFirst = index == 0)
                            }
                            items(group.debts, key = { it.debt.id }) { debtInfo ->
                                DebtCard(
                                    debtInfo = debtInfo,
                                    isYouOwe = false,
                                    creditorName = null,
                                    dateLabel = dateFmt.format(Date(debtInfo.parentTransaction.timestamp)),
                                    isExpanded = expandedId == debtInfo.debt.id,
                                    onToggle = { expandedId = if (expandedId == debtInfo.debt.id) null else debtInfo.debt.id },
                                    onSettleFull = {
                                        expandedId = null
                                        onRepaymentLogged(debtInfo.debt.id, debtInfo.outstanding, false)
                                    },
                                    onLogCash = { amount ->
                                        expandedId = null
                                        onRepaymentLogged(debtInfo.debt.id, amount, false)
                                    },
                                    onForgive = { forgiveTarget = debtInfo.debt.id },
                                    onRemind = { amount ->
                                        val now = System.currentTimeMillis()
                                        if (now - lastIntentTime > 1000) {
                                            lastIntentTime = now
                                            val firstName = group.name.split(" ").firstOrNull { it.isNotBlank() } ?: ""
                                            val greeting = if (firstName.isNotBlank() && firstName != "Unspecified") "Hey $firstName! " else "Hey! "
                                            val msg = "${greeting}Just a reminder you owe me ${CurrencyFormat.withSymbol(amount, 0)} for ${debtInfo.parentTransaction.payee}."
                                            val linkedPhone = settingsRepo.getLinkedPhone(group.name)
                                            val directChat = linkedPhone?.let {
                                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/$it?text=${android.net.Uri.encode(msg)}"))
                                            }
                                            val opened = directChat != null && runCatching { context.startActivity(directChat) }.isSuccess
                                            if (!opened) {
                                            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg); setPackage("com.whatsapp") }
                                            try { context.startActivity(intent) } catch (e: Exception) {
                                                try { intent.setPackage("com.whatsapp.w4b"); context.startActivity(intent) } catch (e2: Exception) {
                                                    val fallback = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg) }
                                                    context.startActivity(Intent.createChooser(fallback, "Share via"))
                                                }
                                            }
                                            }
                                        }
                                    },
                                    onPayUpi = { amount ->
                                        val vpa = settingsRepo.myUpiVpa
                                        if (isValidUpiVpa(vpa)) {
                                            shareUpiRequest(debtInfo.copy(outstanding = amount), vpa)
                                        } else {
                                            openQrAfterVpaSave = false
                                            upiIdInput = vpa
                                            upiDialogDebt = debtInfo.copy(outstanding = amount)
                                        }
                                    },
                                    onShowQr = { amount ->
                                        val vpa = settingsRepo.myUpiVpa
                                        if (isValidUpiVpa(vpa)) {
                                            qrModalDebt = debtInfo.copy(outstanding = amount) to vpa
                                        } else {
                                            openQrAfterVpaSave = true
                                            upiIdInput = vpa
                                            upiDialogDebt = debtInfo.copy(outstanding = amount)
                                        }
                                    }
                                )
                            }
                        }

                        // ── Debts You Owe Others ──
                        youOweGroups.forEachIndexed { index, group ->
                            item(key = "header_youowe_${group.name.lowercase()}") {
                                PersonGroupHeader(group = group, isFirst = owedToYouGroups.isEmpty() && index == 0)
                            }
                            items(group.debts, key = { it.debt.id }) { debtInfo ->
                                DebtCard(
                                    debtInfo = debtInfo,
                                    isYouOwe = true,
                                    creditorName = group.name,
                                    dateLabel = dateFmt.format(Date(debtInfo.parentTransaction.timestamp)),
                                    isExpanded = expandedId == debtInfo.debt.id,
                                    onToggle = { expandedId = if (expandedId == debtInfo.debt.id) null else debtInfo.debt.id },
                                    onSettleFull = {
                                        expandedId = null
                                        onRepaymentLogged(debtInfo.debt.id, debtInfo.outstanding, false)
                                    },
                                    onLogCash = { amount ->
                                        expandedId = null
                                        onRepaymentLogged(debtInfo.debt.id, amount, false)
                                    },
                                    onForgive = { forgiveTarget = debtInfo.debt.id },
                                    onRemind = {},
                                    onPayUpi = {},
                                    onShowQr = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    forgiveTarget?.let { id ->
        AppDialog(
            title = "Forgive this debt?",
            onDismiss = { forgiveTarget = null },
            confirmText = "Forgive",
            destructive = true,
            onConfirm = {
                expandedId = null
                onRepaymentLogged(id, 0L, true)
                forgiveTarget = null
            }
        ) {
            Text(
                "The remaining amount is written off and won't count as owed. This can't be reversed.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
            )
        }
    }

    upiDialogDebt?.let { targetDebt ->
        val vpaValid = isValidUpiVpa(upiIdInput)
        AppDialog(
            title = "Enter Your UPI ID",
            onDismiss = { upiDialogDebt = null },
            confirmText = if (openQrAfterVpaSave) "Save & Show QR" else "Save & Share",
            confirmEnabled = vpaValid,
            onConfirm = {
                val vpa = upiIdInput.trim()
                settingsRepo.myUpiVpa = vpa
                upiDialogDebt = null
                if (openQrAfterVpaSave) {
                    qrModalDebt = targetDebt to vpa
                } else {
                    shareUpiRequest(targetDebt, vpa)
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your own UPI ID (e.g. yourname@okaxis). It's saved and used to generate scan-to-pay QR codes and UPI payment links.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
                )
                OutlinedTextField(
                    value = upiIdInput,
                    onValueChange = { upiIdInput = it.filter { c -> !c.isWhitespace() }.take(100) },
                    label = { Text("Your UPI ID (VPA)") },
                    isError = upiIdInput.isNotEmpty() && !vpaValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.small)
                )
            }
        }
    }

    // In-Person Scan-to-Pay UPI QR Code Modal (Quick Win 3: Wired UpiUtils.generateQrBitmap)
    qrModalDebt?.let { (targetDebt, vpa) ->
        var qrBitmap by remember(targetDebt, vpa) { mutableStateOf<Bitmap?>(null) }
        val upiUri = remember(targetDebt, vpa) {
            UpiUtils.buildUpiUri(
                payeeVpa = vpa,
                payeeName = vpa.substringBefore('@'),
                amountPaise = targetDebt.outstanding,
                note = "Settling ${targetDebt.parentTransaction.payee}"
            ).toString()
        }

        LaunchedEffect(upiUri) {
            qrBitmap = withContext(Dispatchers.Default) {
                runCatching { UpiUtils.generateQrBitmap(upiUri, 512) }.getOrNull()
            }
        }

        AppDialog(
            title = "Scan to Pay via UPI",
            onDismiss = { qrModalDebt = null },
            dismissText = "Close",
            confirmText = "Share Link",
            onConfirm = {
                qrModalDebt = null
                shareUpiRequest(targetDebt, vpa)
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "${targetDebt.debt.debtorName} owes ${CurrencyFormat.withSymbol(targetDebt.outstanding, 2)} for ${targetDebt.parentTransaction.payee}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = Color.White,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.size(220.dp).padding(8.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "UPI QR Code",
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                Text(
                    "VPA: $vpa",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PersonGroupHeader(group: PersonGroup, isFirst: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirst) 0.dp else 12.dp, bottom = 2.dp, start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                group.name.take(1).uppercase().ifBlank { "?" },
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (group.isYouOwe) "You owe ${group.name}" else group.name.ifBlank { "Unknown" },
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = if (group.isYouOwe) AppColors.warning else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (group.debts.size > 1) {
                Spacer(Modifier.width(8.dp))
                Text("${group.debts.size} bills", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (group.debts.size > 1) {
            Text(
                CurrencyFormat.withSymbol(group.total, 0),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = if (group.isYouOwe) AppColors.warning else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DebtCard(
    debtInfo: DebtInfo,
    isYouOwe: Boolean = false,
    creditorName: String? = null,
    dateLabel: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSettleFull: () -> Unit,
    onLogCash: (Long) -> Unit,
    onForgive: () -> Unit,
    onRemind: (Long) -> Unit,
    onPayUpi: (Long) -> Unit,
    onShowQr: (Long) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val tap = { haptic.tap() }
    val confirm = { haptic.confirm() }
    val progress = if (debtInfo.totalOwed > 0L) (debtInfo.repaidSoFar.toFloat() / debtInfo.totalOwed.toFloat()).coerceIn(0f, 1f) else 0f

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val cardColor by animateColorAsState(
        when {
            isExpanded -> scheme.onSurface.copy(alpha = 0.04f)
            pressed -> scheme.onSurface.copy(alpha = 0.04f)
            else -> scheme.surface
        },
        tween(240), label = "debtCardColor"
    )
    val cardBorder by animateColorAsState(
        if (isExpanded) scheme.onSurface.copy(alpha = 0.55f) else scheme.outlineVariant.copy(alpha = 0.5f),
        tween(240), label = "debtCardBorder"
    )

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.medium)),
        shape = RoundedCornerShape(AppRadius.medium),
        color = cardColor,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interaction, indication = null) { tap(); onToggle() }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        debtInfo.parentTransaction.payee,
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = scheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "$dateLabel · ${CurrencyFormat.withSymbol(debtInfo.parentTransaction.amount, 0)} bill" +
                                (if (isYouOwe && creditorName != null) " · Paid by $creditorName" else ""),
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (isYouOwe) "YOU OWE" else "OWES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = if (isYouOwe) AppColors.warning else scheme.onSurfaceVariant
                    )
                    Text(
                        CurrencyFormat.withSymbol(debtInfo.outstanding, 0),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.3).sp,
                        color = if (isYouOwe) AppColors.warning else scheme.onSurface
                    )
                }
            }

            if (debtInfo.repaidSoFar > 0L) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                        .background(scheme.onSurface.copy(alpha = 0.10f))
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(scheme.onSurface))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Paid ${CurrencyFormat.withSymbol(debtInfo.repaidSoFar, 0)} of ${CurrencyFormat.withSymbol(debtInfo.totalOwed, 0)}",
                    fontSize = 12.sp, color = scheme.onSurfaceVariant
                )
            }

            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 90)) togetherWith fadeOut(tween(110)))
                        .using(SizeTransform(clip = false) { _, _ ->
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                        })
                },
                label = "debtCardContent"
            ) { expanded ->
                if (!expanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { confirm(); onSettleFull() },
                            modifier = Modifier.weight(1.4f).height(40.dp),
                            shape = RoundedCornerShape(AppRadius.small),
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface)
                        ) {
                            Text(
                                if (isYouOwe) "Mark Paid ${CurrencyFormat.withSymbol(debtInfo.outstanding, 0)}"
                                else "Settle ${CurrencyFormat.withSymbol(debtInfo.outstanding, 0)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = { tap(); onToggle() },
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(AppRadius.small),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface)
                        ) {
                            Text(if (isYouOwe) "Options" else "Partial / QR", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                } else {
                    var amountInput by remember {
                        val initial = String.format(Locale.US, "%.2f", debtInfo.outstanding / 100.0)
                        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
                    }
                    val amountFocus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(Unit) {
                        delay(320)
                        runCatching { amountFocus.requestFocus() }
                        keyboard?.show()
                    }
                    val typedRupees = amountInput.text.toDoubleOrNull() ?: 0.0
                    val typedPaise = (typedRupees * 100).toLong()
                    val exceedsOwed = typedPaise > debtInfo.outstanding

                    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        DebtAmountField(
                            value = amountInput,
                            onValueChange = { if (it.text.isEmpty() || it.text.matches(Regex("""^\d*(\.\d{0,2})?$"""))) amountInput = it },
                            focusRequester = amountFocus,
                            onDone = { if (typedPaise > 0L && !exceedsOwed) { confirm(); onLogCash(typedPaise) } },
                            label = if (isYouOwe) "Amount paid back (₹)" else "Amount received (₹)",
                            errorText = if (exceedsOwed) "Only ${CurrencyFormat.withSymbol(debtInfo.outstanding, 0)} is owed" else null
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(
                                onClick = { tap(); onForgive() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = scheme.onSurface.copy(alpha = 0.06f),
                                    contentColor = scheme.onSurface
                                )
                            ) {
                                Text(
                                    "Forgive",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { if (typedPaise > 0L) { confirm(); onLogCash(typedPaise) } },
                                enabled = typedPaise > 0L && !exceedsOwed,
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = scheme.onSurface,
                                    contentColor = scheme.surface,
                                    disabledContainerColor = scheme.onSurface.copy(alpha = 0.08f),
                                    disabledContentColor = scheme.onSurface.copy(alpha = 0.40f)
                                )
                            ) {
                                Text(
                                    if (isYouOwe) "Record paid" else "Log cash",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!isYouOwe) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val amountToSend = if (typedPaise > 0L && !exceedsOwed) typedPaise else debtInfo.outstanding
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(AppRadius.small))
                                        .background(scheme.onSurface.copy(alpha = 0.04f))
                                        .clickable { tap(); onRemind(amountToSend) }
                                        .padding(vertical = 13.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("WhatsApp", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                }

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(AppRadius.small))
                                        .background(scheme.onSurface.copy(alpha = 0.04f))
                                        .clickable { tap(); onPayUpi(amountToSend) }
                                        .padding(vertical = 13.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("UPI Link", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                }

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(AppRadius.small))
                                        .background(scheme.onSurface.copy(alpha = 0.04f))
                                        .clickable { tap(); onShowQr(amountToSend) }
                                        .padding(vertical = 13.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.QrCode2, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Show QR", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                }
                            }
                        }

                        Text(
                            "Cancel",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(AppRadius.small))
                                .clickable { tap(); onToggle() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtAmountField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    label: String,
    errorText: String?
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppRadius.small)
    val isError = errorText != null
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> colors.error
            focused -> colors.onSurface
            else -> colors.outlineVariant.copy(alpha = 0.6f)
        },
        label = "debtFieldBorder"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(if (isError) colors.error else colors.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorationBox = { inner ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.surface)
                        .border(1.dp, borderColor, shape)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            isError -> colors.error
                            focused -> colors.onSurface
                            else -> colors.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    inner()
                }
            }
        )
        if (errorText != null) {
            Text(errorText, fontSize = 12.sp, color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}