package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import com.expensetracker.offline.util.confirm
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.expensetracker.offline.ui.theme.AppRadius
import java.util.concurrent.atomic.AtomicInteger

private val activeSecureScreenLocks = AtomicInteger(0)

/**
 * Dynamically enables [WindowManager.LayoutParams.FLAG_SECURE] on the host [Activity] window
 * ONLY while [enabled] is true (e.g., when bank balances are revealed, the app lock screen is
 * covering the UI, or a passphrase dialog is open), and clears it immediately afterwards so
 * normal expense screens and insights can be screenshotted freely.
 */
@Composable
fun SecureScreenEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = run {
            var cur = context
            while (cur is ContextWrapper) {
                if (cur is Activity) return@run cur.window
                cur = cur.baseContext
            }
            null
        }
        if (enabled && window != null) {
            if (activeSecureScreenLocks.incrementAndGet() > 0) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            }
            onDispose {
                if (activeSecureScreenLocks.decrementAndGet() <= 0) {
                    activeSecureScreenLocks.set(0)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        } else {
            onDispose { }
        }
    }
}

@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "Cancel",
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    secure: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val buttonShape = RoundedCornerShape(AppRadius.small)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
        )
    ) {
        Surface(
            modifier = modifier.fillMaxWidth().widthIn(max = 400.dp),
            shape = RoundedCornerShape(AppRadius.large),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = (-0.3).sp,
                    color = colors.onSurface
                )
                content()
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dismissText != null) {
                        FilledTonalButton(
                            onClick = {
                                haptic.tap()
                                onDismiss()
                            },
                            shape = buttonShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.onSurface.copy(alpha = 0.06f),
                                contentColor = colors.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                        ) {
                            Text(
                                dismissText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(
                        onClick = {
                            haptic.confirm()
                            onConfirm()
                        },
                        enabled = confirmEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (destructive) colors.error else colors.onSurface,
                            contentColor = if (destructive) colors.onError else colors.surface,
                            disabledContainerColor = colors.onSurface.copy(alpha = 0.08f),
                            disabledContentColor = colors.onSurface.copy(alpha = 0.38f)
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp,
                            disabledElevation = 0.dp
                        ),
                        shape = buttonShape,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) {
                        Text(
                            confirmText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}