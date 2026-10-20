package com.expensetracker.offline.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.expensetracker.offline.engine.split.ReceiptScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetDetailsSection(
    note: String,
    onNoteChange: (String) -> Unit,
    receiptUri: String?,
    onReceiptChange: (String?) -> Unit,
    receiptFilePrefix: String,
    currentBillAmountPaise: Long? = null,
    initialPeople: List<String> = emptyList(),
    onReceiptScanned: ((itemsSummary: String, totalPaise: Long?) -> Unit)? = null,
    onItemizedSplitComputed: ((participants: List<SplitParticipant>, totalPaise: Long?) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteOpen by rememberSaveable { mutableStateOf(note.isNotBlank()) }
    var receiptOpen by rememberSaveable { mutableStateOf(receiptUri != null) }
    var focusNoteOnOpen by remember { mutableStateOf(false) }
    var isScanningOcr by remember { mutableStateOf(false) }

    var scannedItems by remember { mutableStateOf<List<ReceiptScanner.ScannedItem>>(emptyList()) }
    var scannedGrandTotalPaise by remember { mutableStateOf<Long?>(null) }
    var scannedTaxPaise by remember { mutableStateOf<Long?>(null) }

    fun triggerScan(path: String, autoTriggered: Boolean = false) {
        isScanningOcr = true
        scope.launch {
            try {
                val file = File(path)
                val uri = if (file.exists()) Uri.fromFile(file) else Uri.parse(path)
                val receipt = withContext(Dispatchers.IO) {
                    ReceiptScanner.extractReceipt(context, uri)
                }
                if (receipt.items.isNotEmpty()) {
                    scannedItems = receipt.items
                    scannedGrandTotalPaise = receipt.grandTotalPaise ?: receipt.subtotalPaise ?: receipt.items.sumOf { it.pricePaise }
                    scannedTaxPaise = receipt.taxPaise
                    val summary = ReceiptScanner.formatItemsSummary(receipt.items)
                    onNoteChange(summary)
                    onReceiptScanned?.invoke(summary, scannedGrandTotalPaise)
                    if (!autoTriggered) {
                        Toast.makeText(context, "Extracted ${receipt.items.size} items from bill", Toast.LENGTH_SHORT).show()
                    }
                } else if (!autoTriggered) {
                    Toast.makeText(context, "Could not detect priced items on this photo. You can add items manually below.", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isScanningOcr = false
            }
        }
    }

    val pickReceipt = rememberReceiptPicker(receiptFilePrefix) { path ->
        onReceiptChange(path)
        receiptOpen = true
        triggerScan(path, autoTriggered = true)
    }
    val receiptBitmap = rememberReceiptBitmap(receiptUri)

    val noteActive = noteOpen || note.isNotBlank()
    val receiptActive = receiptUri != null

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetChip(
                text = if (noteActive) "Note" else "Add note",
                isSelected = noteActive,
                icon = if (noteActive) Icons.Default.Check else Icons.Default.Add,
                onClick = {
                    noteOpen = !noteOpen
                    focusNoteOnOpen = noteOpen
                }
            )
            SheetChip(
                text = if (receiptActive) "Bill / Receipt" else "Attach bill",
                isSelected = receiptActive,
                icon = if (receiptActive) Icons.Default.ReceiptLong else Icons.Default.Add,
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
                    placeholder = "e.g., Peri Peri Chicken, Kerala Paratta",
                    leadingIcon = Icons.Default.Fastfood,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.focusRequester(focusRequester)
                )
            }
        }

        AnimatedVisibility(visible = receiptOpen && receiptUri != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetReceiptCard(
                    receiptBitmap = receiptBitmap,
                    isScanningOcr = isScanningOcr,
                    hasScannedItems = scannedItems.isNotEmpty(),
                    onScanItems = {
                        val path = receiptUri ?: return@SheetReceiptCard
                        triggerScan(path, autoTriggered = false)
                    },
                    onPick = pickReceipt,
                    onRemove = {
                        scannedItems = emptyList()
                        scannedGrandTotalPaise = null
                        onReceiptChange(null)
                        receiptOpen = false
                    }
                )

                if (scannedItems.isNotEmpty()) {
                    ItemizedBillSplitCard(
                        items = scannedItems,
                        taxPaise = scannedTaxPaise,
                        receiptTotalPaise = scannedGrandTotalPaise,
                        currentBillAmountPaise = currentBillAmountPaise,
                        initialPeople = initialPeople,
                        onItemsModified = { updatedItems ->
                            scannedItems = updatedItems
                            val summary = ReceiptScanner.formatItemsSummary(updatedItems)
                            onNoteChange(summary)
                        },
                        onSyncBillTotal = { newTotalPaise ->
                            onReceiptScanned?.invoke(ReceiptScanner.formatItemsSummary(scannedItems), newTotalPaise)
                        },
                        onComputedSplitChanged = { splitParticipants, effectiveTotal ->
                            val summary = ReceiptScanner.formatItemsSummary(scannedItems)
                            onNoteChange(summary)
                            onItemizedSplitComputed?.invoke(splitParticipants, effectiveTotal)
                        }
                    )
                }
            }
        }
    }
}