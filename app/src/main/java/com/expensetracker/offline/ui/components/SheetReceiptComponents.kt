package com.expensetracker.offline.ui.components
import androidx.compose.ui.text.style.TextOverflow

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SheetReceiptCard(
    receiptBitmap: Bitmap?,
    isScanningOcr: Boolean = false,
    hasScannedItems: Boolean = false,
    onScanItems: (() -> Unit)? = null,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showFull by remember { mutableStateOf(false) }

    SheetCard(
        modifier = Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SheetInfoRow(
            icon = Icons.Default.ReceiptLong,
            title = "Bill / Receipt Photo",
            subtitle = if (receiptBitmap == null) "Optional" else if (hasScannedItems) "Items extracted below" else "Extract line items & split by dish",
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
                    .height(160.dp)
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
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    ) {
                        Text("Tap to view full", color = Color.White, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }

            if (showFull) {
                FullScreenImageViewer(bitmap = receiptBitmap, onDismiss = { showFull = false })
            }

            if (onScanItems != null) {
                Button(
                    onClick = { haptic.tap(); onScanItems() },
                    enabled = !isScanningOcr,
                    shape = RoundedCornerShape(AppRadius.small),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    if (isScanningOcr) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning bill items…", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (hasScannedItems) "Rescan Bill Items (OCR)" else "Scan Bill Items (OCR)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { haptic.tap(); onPick() },
                    shape = RoundedCornerShape(AppRadius.small),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Change",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = { haptic.tap(); onRemove() },
                    shape = RoundedCornerShape(AppRadius.small),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Remove",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            OutlinedButton(
                onClick = { haptic.tap(); onPick() },
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Upload Bill Photo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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

    LaunchedEffect(path) {
        bitmap = if (path != null) {
            withContext(Dispatchers.IO) { ImageUtil.loadScaledBitmap(context, path, 1024) }
        } else null
    }

    return bitmap
}
