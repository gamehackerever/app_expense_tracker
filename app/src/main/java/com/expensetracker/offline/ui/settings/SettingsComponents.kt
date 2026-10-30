@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.offline.ui.settings


import com.expensetracker.offline.ui.components.quietClickable
import com.expensetracker.offline.util.tap
import com.expensetracker.offline.ui.components.fadingHorizontalScroll
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.unit.Dp
import com.expensetracker.offline.ui.theme.AppColors
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
                .fadingHorizontalScroll()
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
                            haptic.tap()
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

// ─────────────────────────────────────────────────────────────────────────
// Settings design system
//   SettingsSection  → small caps label + one rounded card
//   SettingsItem     → icon · title/subtitle · trailing control (the only row layout used)
//   SettingsInset    → content (fields, buttons) aligned under the row text
//   SettingsDivider  → hairline inset to the text column
// ─────────────────────────────────────────────────────────────────────────

/** Left edge of row text: 16dp card padding + 36dp icon tile + 14dp gap. */
internal val SettingsTextInset = 66.dp

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    /** Off when the page's own title already says it (a page with a single section). */
    showTitle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(
        targetValue = if (isHighlighted) scheme.onSurface.copy(alpha = 0.04f) else scheme.surface,
        animationSpec = tween(500), label = "highlightBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHighlighted) scheme.onSurface else scheme.outlineVariant.copy(alpha = 0.4f),
        animationSpec = tween(500), label = "highlightBorder"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) {
            Text(
                text = title.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppRadius.medium),
            color = bgColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * The one row layout for every setting. Pass [onClick] for navigation/action rows (shows a chevron
 * unless [showChevron] is false) and [trailing] for a switch, value chip or status.
 */
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.quietClickable(pressedAlpha = 0.6f, pressedScale = 1f, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIcon(icon, iconTint)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** A setting toggled by a switch. The whole row is tappable, not just the thumb. */
@Composable
internal fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    SettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        iconTint = iconTint,
        modifier = modifier,
        onClick = { onCheckedChange(!checked) },
        showChevron = false,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = settingsSwitchColors()
            )
        }
    )
}

@Composable
internal fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = SettingsTextInset),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

/** Hosts a field/button/panel under a row, aligned to the row text by default. */
@Composable
internal fun SettingsInset(
    modifier: Modifier = Modifier,
    start: Dp = SettingsTextInset,
    top: Dp = 0.dp,
    bottom: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = start, end = 16.dp, top = top, bottom = bottom),
        content = content
    )
}

/** Small read-only value shown at the end of a row (e.g. "Day 28"). */
@Composable
internal fun SettingsValueChip(text: String) {
    Surface(
        shape = RoundedCornerShape(AppRadius.pill),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** state: 0 = good, 1 = needs a look, 2 = broken. */
internal data class SettingsStatus(val label: String, val value: String, val state: Int, val key: String)

/** At-a-glance health of capture, backup and permissions. Tapping a tile jumps to its section. */
@Composable
internal fun SettingsStatusStrip(items: List<SettingsStatus>, onClick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tileShape = RoundedCornerShape(AppRadius.medium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            val dot = when (item.state) {
                0 -> AppColors.positive
                1 -> AppColors.warning
                else -> scheme.error
            }
            Surface(
                shape = tileShape,
                color = scheme.surface,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .weight(1f)
                    .clip(tileShape)
                    .quietClickable { onClick(item.key) }
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(dot, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.value,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Horizontally scrolling jump-links, in the same order as the sections on the page. */
@Composable
internal fun SettingsQuickNav(
    items: List<Pair<String, String>>,
    selectedKey: String?,
    onSelect: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fadingHorizontalScroll(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (label, key) ->
            val selected = selectedKey == key
            Surface(
                shape = RoundedCornerShape(AppRadius.pill),
                color = if (selected) scheme.onSurface else scheme.surface,
                border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .quietClickable { onSelect(key) }
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) scheme.surface else scheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
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

@Composable
internal fun settingsSwitchColors() = com.expensetracker.offline.ui.components.appSwitchColors()

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


// ─────────────────────────── Settings hub ───────────────────────────

internal data class SettingsHubEntry(val icon: ImageVector, val title: String, val summary: String, val page: String)

/** Which page a section key (used by deep links such as "SMS" or "Backup") lives on. */
internal fun settingsPageFor(sectionKey: String): String? = when (sectionKey.lowercase()) {
    "cycle", "budget" -> "budget"
    "live", "sms", "capture" -> "capture"
    "alerts" -> "alerts"
    "backup", "data" -> "data"
    "theme", "look" -> "look"
    "health" -> "health"
    else -> null
}

internal fun settingsPageTitle(page: String?): String = when (page) {
    "budget" -> "Budget & Cycle"
    "capture" -> "Capture & Import"
    "alerts" -> "Security & Alerts"
    "data" -> "Data & Backup"
    "look" -> "Appearance"
    "health" -> "System Health"
    else -> "Settings"
}

/** The Settings landing list: grouped rows, each with a one-line summary, opening a page. */
@Composable
internal fun SettingsHub(groups: List<Pair<String, List<SettingsHubEntry>>>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        groups.forEach { (title, entries) ->
            SettingsSection(title = title) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    SettingsItem(
                        icon = entry.icon,
                        title = entry.title,
                        subtitle = entry.summary,
                        onClick = { onOpen(entry.page) }
                    )
                }
            }
        }
    }
}
