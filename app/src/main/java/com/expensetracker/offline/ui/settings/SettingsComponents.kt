@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.offline.ui.settings


import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppRadius
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.compose.runtime.rememberUpdatedState
import com.expensetracker.offline.util.BackupEngine
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.InputStream


// ─────────────────────────────────────────────────────────────────────────
// Small UI Helpers
// ─────────────────────────────────────────────────────────────────────────
internal data class ProTip(val icon: ImageVector, val title: String, val desc: String, val targetKey: String)

@Composable
internal fun ProTipsCarousel(onDismiss: () -> Unit, onTipClick: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val tips = remember {
        listOf(
            ProTip(Icons.Default.Event, "Reserve Your Bills", "Add rent, subscriptions and yearly premiums under 'Fixed Costs & Bills' so Safe to Spend never eats into them.", "Necessities"),
            ProTip(Icons.Default.Sms, "Import Past SMS", "Just installed? Scroll down to 'Scan Inbox Now' to instantly pull in your past expenses.", "SMS"),
            ProTip(Icons.Default.Bolt, "Zero-Click Logging", "Tired of reviewing? Enable 'Skip Review' below to log live captures automatically.", "Live"),
            ProTip(Icons.Default.CloudSync, "Bulletproof Data", "Turn on Auto-Backup or export your history to a CSV spreadsheet anytime.", "Backup"),
            ProTip(Icons.Default.NotificationsActive, "Balance Alerts", "Set a custom low-balance threshold and get warned before your account runs dry.", "Alerts"),
            ProTip(Icons.Default.Lock, "Secure Your Data", "Enable App Lock below to require fingerprint or screen lock authentication every time you open the app.", "Alerts")
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pro Tips",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            tips.forEach { tip ->
                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .width(280.dp)
                        .clip(RoundedCornerShape(AppRadius.medium))
                        .quietClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTipClick(tip.targetKey)
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(tip.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(tip.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(tip.desc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(200, easing = FastOutSlowInEasing), label = "highlightBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        animationSpec = tween(200, easing = FastOutSlowInEasing), label = "highlightBorder"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppRadius.medium),
            color = bgColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SettingsIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppRadius.small)
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.onSurface else Color.Transparent,
        label = "fieldBorder"
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(colors.onSurface),
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.onSurface.copy(alpha = 0.04f))
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, fontSize = 16.sp, color = colors.onSurfaceVariant.copy(alpha = 0.55f))
                        }
                        inner()
                    }
                }
            }
        }
    )
}

internal fun isNotificationPermissionGranted(context: Context): Boolean =
    com.expensetracker.offline.engine.interceptor.NotificationCaptureService.isPermissionGranted(context)

/** Click handler with no ripple; presses fade the element slightly instead. */
@Composable
internal fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.72f,
    pressedScale: Float = 0.98f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "quietScale"
    )
    return this
        .graphicsLayer {
            this.alpha = alpha
            this.scaleX = scale
            this.scaleY = scale
        }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

@Composable
internal fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.surface,
    checkedTrackColor = MaterialTheme.colorScheme.onSurface,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = Color.Transparent
)

/** Records this composable's Y offset. Writes only happen when the value actually changes. */
internal fun Modifier.trackSection(key: String, positions: MutableMap<String, Float>): Modifier =
    onGloballyPositioned { coordinates ->
        val y = coordinates.positionInParent().y
        if (positions[key] != y) positions[key] = y
    }

/** Runs [onLost] when a field that had focus loses it. */
@Composable
internal fun Modifier.onFocusLost(onLost: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    val currentOnLost by rememberUpdatedState(onLost)
    return onFocusChanged { state ->
        if (state.isFocused) {
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            currentOnLost()
        }
    }
}

internal fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds don't ship every settings screen; ignore instead of crashing.
    } catch (_: SecurityException) {
    }
}

internal const val MAX_LEGACY_BACKUP_BYTES = 50 * 1024 * 1024

/** Legacy backups are only identifiable by decrypting them with the old static key. */
internal fun isLegacyBackup(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val raw = readAtMost(stream, MAX_LEGACY_BACKUP_BYTES) ?: return@use false
        val decrypted = BackupEngine.decryptLegacy(raw)
        if (decrypted.size < 8) {
            false
        } else {
            val header = String(decrypted, 0, 8, Charsets.UTF_8)
            header == "ETBK_V2_" || header == "ETBK_V3_"
        }
    } ?: false
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
} catch (e: OutOfMemoryError) {
    false
}

/** Reads the whole stream, or returns null as soon as it exceeds [limit] bytes (`available()` is unreliable). */
internal fun readAtMost(stream: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}


internal val APP_LOCK_AUTHENTICATORS: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Runs [onSuccess] only after the user passes biometric / screen-lock authentication.
 * If the device no longer has any screen lock there is nothing to verify against, so it
 * proceeds (otherwise the user could never turn the lock off).
 */
internal fun requireDeviceAuth(
    context: Context,
    title: String,
    onFailure: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (BiometricManager.from(context).canAuthenticate(APP_LOCK_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess()
        return
    }
    val activity = context.findFragmentActivity()
    if (activity == null) {
        onFailure("Couldn't open the authentication prompt.")
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(context),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                if (!cancelled) onFailure(errString.toString())
            }
        }
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Confirm it's you")
            .setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS)
            .build()
    )
}
