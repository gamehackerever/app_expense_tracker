package com.expensetracker.offline.ui.settings

import com.expensetracker.offline.util.BackupResult
import com.expensetracker.offline.util.RestoreResult
import com.expensetracker.offline.util.CsvResult
import com.expensetracker.offline.util.tick

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.scanner.SmsHistoryScanner
import com.expensetracker.offline.data.repository.isValidUpiVpa
import com.expensetracker.offline.ui.components.AppDialog
import com.expensetracker.offline.ui.components.PremiumDropdownField
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.BackupManager
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.CsvEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.expensetracker.offline.engine.backup.SecurePassphraseStore
import android.content.ActivityNotFoundException
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.biometric.BiometricManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.app.NotificationManagerCompat
import com.expensetracker.offline.BuildConfig
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.engine.scanner.ScanResponse
import com.expensetracker.offline.util.BackupEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    scrollToSection: String? = null,
    onNavigateToDebug: () -> Unit = {},
    onNavigateToNecessities: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE) }
    val app = context.applicationContext as ExpenseTrackerApp
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val transactions by viewModel.allTransactions.collectAsState()

    val scrollState = rememberScrollState()
    val sectionPositions = remember { mutableStateMapOf<String, Float>() }
    var highlightedSection by remember { mutableStateOf<String?>(null) }

    // Cancel any in-flight highlight so rapid taps can't clear each other's highlight early.
    val highlightJob = remember { mutableStateOf<Job?>(null) }

    fun scrollToAndHighlight(key: String) {
        highlightJob.value?.cancel()
        highlightJob.value = coroutineScope.launch {
            sectionPositions[key]?.let { y ->
                scrollState.animateScrollTo(
                    value = (y.toInt() - 20).coerceAtLeast(0),
                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
                )
            }
            highlightedSection = key
            try {
                delay(1500)
            } finally {
                highlightedSection = null
            }
        }
    }

    fun showSnack(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Short)
        }
    }

    // NEW: Centralized handler to prevent duplicate or overlapping snackbar logic
    fun handleRestoreResult(result: RestoreResult) {
        when (result) {
            is RestoreResult.SuccessRestart -> {
                showSnack("Data restored! Restarting app to apply changes...")
                // Not tied to composition: leaving the screen within the delay must not skip the restart.
                val appContext = context.applicationContext
                Handler(Looper.getMainLooper()).postDelayed({
                    appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        appContext.startActivity(intent)
                    }
                    Process.killProcess(Process.myPid())
                }, 1500L)
            }
            is RestoreResult.WrongPassphrase -> showSnack("Incorrect passphrase!")
            is RestoreResult.Error -> showSnack(result.message)
        }
    }

    // 1. Add this right inside your SettingsScreen composable at the top:
    val settingsRepo = remember { SettingsRepository(context) }

    // Moves a plain-text passphrase from older versions into Keystore-encrypted storage.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SecurePassphraseStore.migrateLegacy(context) }
    }

    var isLowBalanceEnabled by remember { mutableStateOf(settingsRepo.lowBalanceAlertEnabled) }
    var thresholdText by remember { mutableStateOf(settingsRepo.lowBalanceThreshold.toInt().toString()) }
    var budgetText by remember { mutableStateOf(settingsRepo.monthlyBudget.toInt().toString()) }

    var startDayOfMonth by remember { mutableIntStateOf(settingsRepo.startDayOfMonth) }
    var skipReviewQueue by remember { mutableStateOf(settingsRepo.skipReviewQueue) }
    var defaultFallbackPayee by remember { mutableStateOf(settingsRepo.fallbackPayee) }
    var autoSaveUnknown by remember { mutableStateOf(settingsRepo.autoSaveUnknownDeductions) }
    var skipScanReview by remember { mutableStateOf(settingsRepo.skipScanReview) }
    var scanFallbackPayee by remember { mutableStateOf(settingsRepo.scanFallbackPayee) }
    var autoBackupEnabled by remember { mutableStateOf(settingsRepo.autoBackupEnabled) }
    var backupFrequency by remember { mutableStateOf(settingsRepo.backupFrequency) }
    var backupFolderUri by remember { mutableStateOf(settingsRepo.backupFolderUri) }
    var lastBackupTimestamp by remember { mutableLongStateOf(prefs.getLong("key_last_backup_timestamp", 0L)) }
    var isAppLockEnabled by remember { mutableStateOf(settingsRepo.appLockEnabled) }
    var currentThemeMode by remember { mutableStateOf(settingsRepo.appThemeMode) }
    var myUpiInput by remember { mutableStateOf(settingsRepo.myUpiVpa) }

    fun commitMyUpi() {
        val trimmed = myUpiInput.trim()
        when {
            trimmed == settingsRepo.myUpiVpa -> myUpiInput = trimmed
            trimmed.isEmpty() -> {
                settingsRepo.myUpiVpa = ""
                showSnack("UPI ID cleared")
            }
            isValidUpiVpa(trimmed) -> {
                settingsRepo.myUpiVpa = trimmed
                myUpiInput = trimmed
                showSnack("UPI ID saved")
            }
            else -> {
                myUpiInput = settingsRepo.myUpiVpa
                showSnack("That doesn't look like a valid UPI ID")
            }
        }
    }

    // Leaving the screen while the field is still focused never fires onFocusChanged, so the edit
    // would be lost. Persist it (valid IDs only) on the way out.
    DisposableEffect(Unit) {
        onDispose {
            val pending = myUpiInput.trim()
            if (pending != settingsRepo.myUpiVpa && isValidUpiVpa(pending)) {
                settingsRepo.myUpiVpa = pending
            }
            // Same for the budget: leaving while the field is focused must not drop the edit.
            val pendingBudget = budgetText.toLongOrNull()?.takeIf { it > 0L }
            if (pendingBudget != null && pendingBudget != settingsRepo.monthlyBudget) {
                settingsRepo.monthlyBudget = pendingBudget
            }
        }
    }

    var showBackupPassphraseDialog by remember { mutableStateOf(false) }
    var showRestorePassphraseDialog by remember { mutableStateOf<Uri?>(null) }
    var tempPassphrase by remember { mutableStateOf("") }
    var tempConfirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

// FIXED: Initialize Undo state securely from Repository (P1)
    var lastImportedTxnIds by remember { mutableStateOf(settingsRepo.lastImportedTxnIds) }
    var lastImportedReviewIds by remember { mutableStateOf(settingsRepo.lastImportedReviewIds) }
    LaunchedEffect(backupFolderUri) {
        if (backupFolderUri.isNotBlank()) {
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == backupFolderUri
            }
            if (!hasPermission) {
                val wasAutoBackupOn = autoBackupEnabled
                backupFolderUri = ""
                autoBackupEnabled = false
                prefs.edit {
                    remove("key_backup_folder_uri")
                    putBoolean("key_auto_backup_enabled", false)
                }
                if (wasAutoBackupOn) showSnack("Backup folder access was lost. Please choose a folder again.")
            }
        }
    }

    fun commitBudget() {
        val parsed = budgetText.toLongOrNull()?.takeIf { it > 0L }
        if (parsed != null && parsed != settingsRepo.monthlyBudget) {
            settingsRepo.monthlyBudget = parsed
            budgetText = parsed.toString()
            showSnack("Budget saved")
        } else {
            budgetText = settingsRepo.monthlyBudget.toInt().toString()
        }
    }

    var showStartDayDialog by remember { mutableStateOf(false) }
    var showScanConfirmDialog by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isProTipsDismissed by remember { mutableStateOf(prefs.getBoolean("key_pro_tips_dismissed", false)) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    var hasNotificationPermission by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    var estimateBalance by remember { mutableStateOf(prefs.getBoolean("key_estimate_balance_from_wallets", true)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                hasNotificationPermission = isNotificationPermissionGranted(context)
                // The auto-backup worker may have run while we were away.
                lastBackupTimestamp = prefs.getLong("key_last_backup_timestamp", 0L)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingAutoBackupEnable by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    var isCsvBusy by remember { mutableStateOf(false) }

    fun reportCsv(result: CsvResult, successMessage: String) {
        when (result) {
            is CsvResult.Success -> showSnack(successMessage)
            is CsvResult.PartialSuccess -> showSnack(result.message)
            is CsvResult.Error -> showSnack(result.message)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val shouldEnableAutoBackup = pendingAutoBackupEnable
        pendingAutoBackupEnable = false
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                showSnack("Couldn't get access to that folder. Please pick another one.")
                return@rememberLauncherForActivityResult
            }
            // Release the previous grant so we never exhaust the per-app persisted-URI limit.
            val previous = backupFolderUri
            if (previous.isNotBlank() && previous != uri.toString()) {
                try {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags)
                } catch (_: Exception) {
                }
            }
            backupFolderUri = uri.toString()
            prefs.edit { putString("key_backup_folder_uri", uri.toString()) }
            if (shouldEnableAutoBackup) {
                autoBackupEnabled = true
                prefs.edit { putBoolean("key_auto_backup_enabled", true) }
            }
            showSnack("Backup folder selected successfully")
            AutoBackupWorker.schedule(context)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            coroutineScope.launch {
                val isLegacy = withContext(Dispatchers.IO) { isLegacyBackup(context, uri) }
                if (isLegacy) {
                    showSnack("Legacy backup detected. Restoring...")
                    val result = withContext(NonCancellable + Dispatchers.IO) {
                        BackupManager.restoreBackup(context, uri.toString(), "")
                    }
                    handleRestoreResult(result)
                } else {
                    showRestorePassphraseDialog = uri
                }
            }
        }
    }

    val csvFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !isCsvBusy) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            isCsvBusy = true
            coroutineScope.launch {
                try {
                    reportCsv(CsvEngine.exportToCsv(context, transactions, uri.toString()), "CSV Exported successfully!")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showSnack("CSV export failed: ${e.message ?: "unknown error"}")
                } finally {
                    isCsvBusy = false
                }
            }
        }
    }

    val csvImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !isCsvBusy) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            isCsvBusy = true
            coroutineScope.launch {
                try {
                    val result = CsvEngine.importFromCsv(context, uri) { importedList ->
                        viewModel.importTransactions(importedList)
                    }
                    reportCsv(result, "CSV Imported successfully!")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showSnack("CSV import failed: ${e.message ?: "unknown error"}")
                } finally {
                    isCsvBusy = false
                }
            }
        }
    }

    LaunchedEffect(scrollToSection) {
        val target = scrollToSection ?: return@LaunchedEffect
        snapshotFlow { sectionPositions[target] }
            .filterNotNull()
            .first()
        scrollToAndHighlight(target)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppColors.positive,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = data.visuals.message,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.3).sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenPadding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            val needsHealthFix = !hasNotificationPermission || !isIgnoringBatteryOptimizations

            if (needsHealthFix) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.errorContainer, // FIX: Soft error container instead of harsh white
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Auto-capture needs attention",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer // FIX: Adapt text to error container
                            )
                        }
                        if (!hasNotificationPermission) {
                            OutlinedButton(
                                onClick = { context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            ) { Text("Allow notification access", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        }
                        if (!isIgnoringBatteryOptimizations) {
                            OutlinedButton(
                                onClick = {
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data =
                                                Uri.fromParts("package", context.packageName, null)
                                        }
                                    context.startActivitySafely(intent)
                                },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            ) { Text("Set battery to Unrestricted", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Budget" to "Cycle",
                    "Capture" to "Live",
                    "SMS" to "SMS",
                    "Backup" to "Backup",
                    "Alerts" to "Alerts"
                ).forEach { (label, key) ->
                    val isSelected = highlightedSection == key
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.clip(RoundedCornerShape(AppRadius.pill)).quietClickable { scrollToAndHighlight(key) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !isProTipsDismissed,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                ProTipsCarousel(
                    onDismiss = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isProTipsDismissed = true
                        prefs.edit { putBoolean("key_pro_tips_dismissed", true) }
                    },
                    onTipClick = { targetKey ->
                        if (targetKey == "Necessities") onNavigateToNecessities()
                        else scrollToAndHighlight(targetKey)
                    }
                )
            }

            // ─────────────────────────────────────────────────────────────────
            // 0. Appearance & Theme
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Appearance & Theme",
                isHighlighted = highlightedSection == "Theme",
                modifier = Modifier.trackSection("Theme", sectionPositions)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Default.Bolt, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "App Theme",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Choose your preferred theme mode",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("SYSTEM" to "System", "DARK" to "Dark", "LIGHT" to "Light").forEach { (mode, label) ->
                        val isSelected = currentThemeMode == mode
                        Surface(
                            onClick = {
                                haptic.tick()
                                currentThemeMode = mode
                                settingsRepo.appThemeMode = mode
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(AppRadius.small),
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 1. Budget & Cycle
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Budget & Cycle",
                isHighlighted = highlightedSection == "Cycle",
                modifier = Modifier.trackSection("Cycle", sectionPositions)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Default.Savings, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Target Spending Limit",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Powers the monthly gauge on your dashboard",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SettingsTextField(
                    value = budgetText,
                    onValueChange = {
                        budgetText = it.filter(Char::isDigit).take(9)
                    },
                    label = "Target Budget (₹)",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.onFocusLost { commitBudget() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Default.AccountBalanceWallet, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "UPI Settlement",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Used when sending split repayment requests",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SettingsTextField(
                    value = myUpiInput,
                    onValueChange = { myUpiInput = it.filter { c -> !c.isWhitespace() }.take(100) },
                    label = "Your UPI ID (to receive payments)",
                    placeholder = "e.g., name@okaxis",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    // Clearing focus already triggers commitMyUpi() via onFocusChanged below;
                    // calling it here too would commit (and show the snackbar) twice.
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.onFocusLost { commitMyUpi() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.small))
                        .quietClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showStartDayDialog = true
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        SettingsIcon(Icons.Default.CalendarMonth, MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Billing Cycle Reset",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Current: ${BillingCycleHelper.getCycleLabel(startDayOfMonth)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(AppRadius.pill),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Day $startDayOfMonth",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.small))
                        .quietClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigateToNecessities()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        SettingsIcon(Icons.Default.Event, MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Fixed Costs & Bills",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Rent, subscriptions & yearly premiums",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 2. Security & Alerts
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Security & Alerts",
                isHighlighted = highlightedSection == "Alerts",
                modifier = Modifier.trackSection("Alerts", sectionPositions)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        SettingsIcon(Icons.Default.Lock, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                        Text("App Lock", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Require biometric unlock to open app",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = isAppLockEnabled,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (enabled) {
                                    val biometricManager = BiometricManager.from(context)
                                    val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)

                                    if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                                        isAppLockEnabled = true
                                        prefs.edit { putBoolean("key_app_lock_enabled", true) }
                                    } else {
                                        showSnack("Please set up a device screen lock or fingerprint first.")
                                    }
                                } else {
                                    requireDeviceAuth(
                                        context = context,
                                        title = "Turn off App Lock",
                                        onFailure = { message -> showSnack(message) }
                                    ) {
                                        isAppLockEnabled = false
                                        prefs.edit { putBoolean("key_app_lock_enabled", false) }
                                    }
                                }
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        SettingsIcon(Icons.Default.NotificationsActive, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                        Text("Low Balance Alert", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Warns when bank balance drops low",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = isLowBalanceEnabled,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isLowBalanceEnabled = enabled
                                prefs.edit { putBoolean("key_low_balance_alert_enabled", enabled) }
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }

                if (isLowBalanceEnabled) {
                    SettingsTextField(
                        value = thresholdText,
                        onValueChange = { input ->
                            val digits = input.filter { it in '0'..'9' }.take(9)
                            thresholdText = digits
                            digits.toFloatOrNull()?.let {
                                prefs.edit { putFloat("key_low_balance_threshold", it) }
                            }
                        },
                        label = "Threshold (₹)",
                        modifier = Modifier.onFocusLost {
                            if (thresholdText.isBlank()) thresholdText =
                                prefs.getFloat("key_low_balance_threshold", 1000f).toInt().toString()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (thresholdText.isBlank()) thresholdText =
                                    prefs.getFloat("key_low_balance_threshold", 1000f).toInt().toString()
                            }
                        )
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 3. Live Capture Automation
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Live Capture Automation",
                isHighlighted = highlightedSection == "Live",
                modifier = Modifier.trackSection("Live", sectionPositions)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        SettingsIcon(Icons.Default.FlashOn, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                        Text(
                            "Skip Review (Live Captures)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Skips the Review queue. Misread merchants or amounts get saved as-is.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = skipReviewQueue,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                skipReviewQueue = enabled
                                prefs.edit { putBoolean("key_skip_review_queue", enabled) }
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }

                if (skipReviewQueue) {
                    SettingsTextField(
                        value = defaultFallbackPayee,
                        onValueChange = { input ->
                            defaultFallbackPayee = input
                            input.trim().takeIf { it.isNotEmpty() }
                                ?.let { prefs.edit { putString("key_fallback_payee", it) } }
                        },
                        label = "Fallback Payee",
                        modifier = Modifier.onFocusLost {
                            if (defaultFallbackPayee.isBlank()) defaultFallbackPayee =
                                prefs.getString("key_fallback_payee", "UPI Payment") ?: "UPI Payment"
                        },
                        placeholder = "e.g., UPI Payment",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (defaultFallbackPayee.isBlank()) defaultFallbackPayee =
                                    prefs.getString("key_fallback_payee", "UPI Payment")
                                        ?: "UPI Payment"
                            }
                        )
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        SettingsIcon(Icons.Default.HelpOutline, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                        Text("Auto-save Silent Deductions", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "If your bank balance drops without an SMS alert, skip review and auto-log the missing amount.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = autoSaveUnknown,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                autoSaveUnknown = enabled
                                prefs.edit { putBoolean("key_autosave_unknown_deductions", enabled) }
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 4. Inbox Import & Review
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Inbox Import & Review",
                isHighlighted = highlightedSection == "SMS",
                modifier = Modifier.trackSection("SMS", sectionPositions)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Default.Sms, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Scan SMS for Current Month",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (skipScanReview) "Direct import active. Scans from ${
                                BillingCycleHelper.getCycleStartDateFormatted(
                                    startDayOfMonth
                                )
                            } to now."
                            else "Scans from ${
                                BillingCycleHelper.getCycleStartDateFormatted(
                                    startDayOfMonth
                                )
                            }. High values go to Review.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        SettingsIcon(Icons.Default.Bolt, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                        Text(
                            "Skip Review for Inbox Scan",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Skips review for the inbox scan. Check Undo Last Scan if results look off.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = skipScanReview,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                skipScanReview = enabled
                                prefs.edit { putBoolean("key_skip_scan_review", enabled) }
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }

                if (skipScanReview) {
                    SettingsTextField(
                        value = scanFallbackPayee,
                        onValueChange = { input ->
                            scanFallbackPayee = input
                            input.trim().takeIf { it.isNotEmpty() }?.let {
                                prefs.edit { putString("key_scan_fallback_payee", it) }
                            }
                        },
                        label = "Fallback Payee (Scanned SMS)",
                        modifier = Modifier.onFocusLost {
                            if (scanFallbackPayee.isBlank()) scanFallbackPayee =
                                prefs.getString("key_scan_fallback_payee", "Bank Transfer") ?: "Bank Transfer"
                        },
                        placeholder = "e.g., Bank Transfer",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (scanFallbackPayee.isBlank()) scanFallbackPayee =
                                    prefs.getString("key_scan_fallback_payee", "Bank Transfer")
                                        ?: "Bank Transfer"
                            }
                        )
                    )
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showScanConfirmDialog = true
                    },
                    enabled = !isScanning,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(AppRadius.small)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scanning Current Cycle…",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Scan Inbox Now",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                val totalBatchCount = lastImportedTxnIds.size + lastImportedReviewIds.size
                if (totalBatchCount > 0) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.undoScan(lastImportedTxnIds, lastImportedReviewIds)

                            val count = lastImportedTxnIds.size + lastImportedReviewIds.size

                            // Clear state
                            lastImportedTxnIds = emptyList()
                            lastImportedReviewIds = emptyList()

                            // Clear Repo
                            settingsRepo.clearLastScanBatch()

                            showSnack("Scan undone! Removed $count items.")
                        },
                        enabled = !isScanning,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(AppRadius.small)
                    ) {
                        Icon(
                            Icons.Default.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Undo Last Scan ($totalBatchCount items)",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 5. Data & Backup
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Data & Backup",
                isHighlighted = highlightedSection == "Backup",
                modifier = Modifier.trackSection("Backup", sectionPositions)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        SettingsIcon(Icons.Default.CloudSync, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                        Text("Auto Backup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Encrypt and save data automatically",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && backupFolderUri.isBlank()) {
                                    showSnack("Please select a backup folder first")
                                    pendingAutoBackupEnable = true
                                    folderPickerLauncher.launch(null)
                                } else {
                                    autoBackupEnabled = enabled
                                    prefs.edit { putBoolean("key_auto_backup_enabled", enabled) }
                                    AutoBackupWorker.schedule(context)
                                }
                            },
                            colors = settingsSwitchColors()
                        )
                    }
                }

                if (autoBackupEnabled) {
                    PremiumDropdownField(
                        selectedOption = backupFrequency,
                        options = listOf("Daily", "Weekly", "Monthly"),
                        onOptionSelected = {
                            backupFrequency = it
                            prefs.edit { putString("key_backup_frequency", it) }
                            AutoBackupWorker.schedule(context)
                        }
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.small))
                        .quietClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            folderPickerLauncher.launch(null)
                        },
                    shape = RoundedCornerShape(AppRadius.small),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Backup Location",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        val locationName = remember(backupFolderUri) {
                            if (backupFolderUri.isNotBlank()) {
                                try {
                                    val decoded = Uri.decode(backupFolderUri)
                                    val path = decoded.substringAfterLast("tree/", "")
                                        .replace("primary:", "Internal Storage / ")
                                        .replace(":", " / ")
                                    path.ifBlank { "Storage Selected" }
                                } catch (e: Exception) {
                                    "Storage Selected"
                                }
                            } else ""
                        }

                        if (backupFolderUri.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(AppRadius.small),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AppColors.positive,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = locationName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap to choose a secure folder",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 32.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                val lastBackupStr = remember(lastBackupTimestamp) {
                    if (lastBackupTimestamp > 0L) {
                        val cal =
                            Calendar.getInstance().apply { timeInMillis = lastBackupTimestamp }
                        val now = Calendar.getInstance()
                        val isToday =
                            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == now.get(
                                Calendar.DAY_OF_YEAR
                            )

                        val yesterday =
                            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                        val isYesterday =
                            cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && cal.get(
                                Calendar.DAY_OF_YEAR
                            ) == yesterday.get(Calendar.DAY_OF_YEAR)

                        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)

                        when {
                            isToday -> "Today, $time"
                            isYesterday -> "Yesterday, $time"
                            else -> SimpleDateFormat(
                                "dd MMM yyyy, hh:mm a",
                                Locale.getDefault()
                            ).format(cal.time)
                        }
                    } else "Never"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (lastBackupTimestamp > 0L) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (lastBackupTimestamp > 0L) AppColors.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Last backed up: $lastBackupStr",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (lastBackupTimestamp > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            filePickerLauncher.launch("*/*")
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (backupFolderUri.isNotBlank()) {
                                tempPassphrase = SecurePassphraseStore.load(context).orEmpty()
                                tempConfirmPassphrase = tempPassphrase
                                showPassphrase = false
                                showBackupPassphraseDialog = true
                            } else {
                                showSnack("Please select a Backup Location first")
                            }
                        },
                        enabled = !isBackingUp,
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backup Now", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIcon(Icons.Default.TableView, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CSV Management", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Spreadsheet import & export",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            csvImportLauncher.launch("text/*")
                        },
                        enabled = !isCsvBusy,
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            csvFolderLauncher.launch(null)
                        },
                        enabled = !isCsvBusy,
                        shape = RoundedCornerShape(AppRadius.small),
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 6. System Health
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "System Health",
                isHighlighted = highlightedSection == "Health",
                modifier = Modifier.trackSection("Health", sectionPositions)
            ) {
                // --- Notification Interceptor ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIcon(
                        icon = if (hasNotificationPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        tint = if (hasNotificationPermission) AppColors.positive else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Notification Interceptor",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (hasNotificationPermission) "Active & Listening (GPay, PhonePe, etc.)" else "Access required in Android Settings",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!hasNotificationPermission) {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.small),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "How to fix:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "1. Tap the button below to open Device Access.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "2. Find Expense Tracker and toggle Allow.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            ) {
                                Text("Grant Notification Access", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // --- Battery Optimization ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIcon(
                        icon = if (isIgnoringBatteryOptimizations) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
                        tint = if (isIgnoringBatteryOptimizations) AppColors.positive else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery Optimization", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            text = if (isIgnoringBatteryOptimizations) "Unrestricted (OS won't kill listener)" else "Restricted (OS may kill background listener)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isIgnoringBatteryOptimizations) {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.small),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "How to fix (Crucial for background sync):",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "1. Tap the button below to open App Info.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "2. Tap 'App battery usage' and select Unrestricted.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data =
                                                Uri.fromParts("package", context.packageName, null)
                                        }
                                    context.startActivitySafely(intent)
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(AppRadius.small),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            ) {
                                Text("Open App Info", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 7. Developer Sandbox
            // ─────────────────────────────────────────────────────────────────
            if (BuildConfig.DEBUG) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.small))
                        .quietClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigateToDebug()
                        }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Developer Sandbox",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Simulate incoming notifications & tests",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOM DIALOGS
    // ─────────────────────────────────────────────────────────────────────────
    if (showStartDayDialog) {
        var tempDay by remember { mutableFloatStateOf(startDayOfMonth.toFloat()) }
        AppDialog(
            title = "Billing Cycle",
            onDismiss = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showStartDayDialog = false },
            confirmText = "Save",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                startDayOfMonth = tempDay.toInt()
                prefs.edit { putInt("key_start_day_of_month", startDayOfMonth) }
                showStartDayDialog = false
            }
        ) {
            Text("Select the day your monthly spending cycle resets:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Resets on Day: ${tempDay.toInt()}",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = tempDay,
                onValueChange = { value ->
                    if (tempDay != value) {
                        tempDay = value
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                valueRange = 1f..28f,
                steps = 26,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Capped at the 28th to ensure consistent cycles across short months like February.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showBackupPassphraseDialog) {
        AppDialog(
            title = "Set Backup Passphrase",
            onDismiss = { showBackupPassphraseDialog = false; tempPassphrase = ""; tempConfirmPassphrase = ""; showPassphrase = false },
            confirmText = "Encrypt",
            confirmEnabled = tempPassphrase.length >= 4 && tempPassphrase == tempConfirmPassphrase,
            onConfirm = {
                val finalPassphrase = tempPassphrase
                showBackupPassphraseDialog = false
                if (!SecurePassphraseStore.save(context, finalPassphrase)) {
                    showSnack("Couldn't store the passphrase securely, so auto-backup won't run until it's saved.")
                }

                coroutineScope.launch {
                    isBackingUp = true
                    try {
                        val result = withContext(NonCancellable + Dispatchers.IO) {
                            BackupManager.createBackup(context, backupFolderUri, finalPassphrase)
                        }
                        when (result) {
                            is BackupResult.Success -> {
                                val now = System.currentTimeMillis()
                                lastBackupTimestamp = now
                                prefs.edit { putLong("key_last_backup_timestamp", now) }
                                showSnack("Backup encrypted and saved securely!")
                            }
                            is BackupResult.Error -> showSnack(result.message)
                        }
                    } finally {
                        isBackingUp = false
                    }
                }
                tempPassphrase = ""
                tempConfirmPassphrase = ""
                showPassphrase = false
            }
        ) {
            Text("Your backup will be encrypted. If you forget this passphrase, your data cannot be recovered.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            val passphraseTransformation =
                if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = tempPassphrase,
                onValueChange = { tempPassphrase = it },
                placeholder = { Text("Enter a secure passphrase") },
                singleLine = true,
                visualTransformation = passphraseTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassphrase = !showPassphrase }) {
                        Icon(
                            imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassphrase) "Hide passphrase" else "Show passphrase"
                        )
                    }
                },
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            val passphrasesMismatch = tempConfirmPassphrase.isNotEmpty() && tempConfirmPassphrase != tempPassphrase
            OutlinedTextField(
                value = tempConfirmPassphrase,
                onValueChange = { tempConfirmPassphrase = it },
                placeholder = { Text("Confirm passphrase") },
                singleLine = true,
                isError = passphrasesMismatch,
                supportingText = if (passphrasesMismatch) ({ Text("Passphrases don't match") }) else null,
                visualTransformation = passphraseTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    showRestorePassphraseDialog?.let { uri ->
        AppDialog(
            title = "Enter Passphrase",
            onDismiss = { showRestorePassphraseDialog = null; tempPassphrase = "" },
            confirmText = "Restore",
            destructive = true, // Red button because this wipes current data
            confirmEnabled = tempPassphrase.isNotBlank(),
            onConfirm = {
                val enteredPassphrase = tempPassphrase
                showRestorePassphraseDialog = null

                coroutineScope.launch {
                    val result = withContext(NonCancellable + Dispatchers.IO) {
                        BackupManager.restoreBackup(context, uri.toString(), enteredPassphrase)
                    }
                    handleRestoreResult(result)
                }
                tempPassphrase = ""
            }
        ) {
            Text("This replaces all current data on this device. Enter the passphrase used to encrypt this backup.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = tempPassphrase,
                onValueChange = { tempPassphrase = it },
                placeholder = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showScanConfirmDialog) {
        // FIXED: Use a separate isolated state for the scan so it doesn't overwrite settingsRepo.startDayOfMonth (PO-G)
        var scanDay by remember { mutableFloatStateOf(startDayOfMonth.toFloat()) }

        AppDialog(
            title = "Confirm Scan",
            onDismiss = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showScanConfirmDialog = false },
            confirmText = "Scan",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showScanConfirmDialog = false

                // Generate the timestamp from the dialog's picked day, but DO NOT save it to preferences
                val cycleStartMillis = BillingCycleHelper.getCycleRange(scanDay.toInt()).first
                isScanning = true

                coroutineScope.launch {
                    try {
                        val scanner = SmsHistoryScanner(context, app.database)
                        // Run to completion off the main thread and persist the undo batch right away, so
                        // rotating / leaving the screen mid-scan can't orphan imported rows.
                        val response = withContext(NonCancellable + Dispatchers.IO) {
                            scanner.scanSmsSince(cycleStartMillis).also { r ->
                                if (r is ScanResponse.Success) {
                                    settingsRepo.lastImportedTxnIds = r.result.importedTransactionIds
                                    settingsRepo.lastImportedReviewIds = r.result.importedReviewIds
                                }
                            }
                        }

                        when (response) {
                            is ScanResponse.Success -> {
                                val result = response.result
                                // Undo batch already persisted above; just mirror it into UI state.
                                lastImportedTxnIds = result.importedTransactionIds
                                lastImportedReviewIds = result.importedReviewIds

                                val totalFound = result.directImportedCount + result.reviewCount
                                if (totalFound > 0) {
                                    showSnack("Imported ${result.directImportedCount} transactions${if (result.reviewCount > 0) ", ${result.reviewCount} to Review." else "."}")
                                } else {
                                    showSnack("Scanned ${result.scannedCount} SMS • No new transactions")
                                }
                            }
                            is ScanResponse.PermissionDenied -> {
                                showSnack("Scan Failed: SMS Permission is missing.")
                            }
                            is ScanResponse.Failed -> {
                                showSnack("Scan failed: ${response.message}")
                            }
                        }
                    } finally {
                        isScanning = false
                    }
                }
            }
        ) {
            Text(
                text = if (skipScanReview) "Scanning reads SMS strictly from the start of your active billing cycle to now. Direct import is active."
                else "Scanning reads SMS strictly from the start of your active billing cycle. Expenses > ₹1,000 will be added to Review.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Scanning from:\n${BillingCycleHelper.getCycleStartDateFormatted(scanDay.toInt())} to Now",
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp), lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Slider(
                value = scanDay,
                onValueChange = { value ->
                    if (scanDay != value) {
                        scanDay = value
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                valueRange = 1f..28f,
                steps = 26,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Small UI Helpers
// ─────────────────────────────────────────────────────────────────────────
private data class ProTip(val icon: ImageVector, val title: String, val desc: String, val targetKey: String)

@Composable
private fun ProTipsCarousel(onDismiss: () -> Unit, onTipClick: (String) -> Unit) {
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
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(500), label = "highlightBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        animationSpec = tween(500), label = "highlightBorder"
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
private fun SettingsIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
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
private fun SettingsTextField(
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
        cursorBrush = SolidColor(colors.primary),
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

private fun isNotificationPermissionGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** Click handler with no ripple; presses fade the element slightly instead. */
@Composable
private fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.6f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { this.alpha = alpha }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.surface,
    checkedTrackColor = MaterialTheme.colorScheme.onSurface,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = Color.Transparent
)

/** Records this composable's Y offset. Writes only happen when the value actually changes. */
private fun Modifier.trackSection(key: String, positions: MutableMap<String, Float>): Modifier =
    onGloballyPositioned { coordinates ->
        val y = coordinates.positionInParent().y
        if (positions[key] != y) positions[key] = y
    }

/** Runs [onLost] when a field that had focus loses it. */
@Composable
private fun Modifier.onFocusLost(onLost: () -> Unit): Modifier {
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

private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds don't ship every settings screen; ignore instead of crashing.
    } catch (_: SecurityException) {
    }
}

private const val MAX_LEGACY_BACKUP_BYTES = 50 * 1024 * 1024

/** Legacy backups are only identifiable by decrypting them with the old static key. */
private fun isLegacyBackup(context: Context, uri: Uri): Boolean = try {
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
private fun readAtMost(stream: InputStream, limit: Int): ByteArray? {
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


private const val APP_LOCK_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

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
private fun requireDeviceAuth(
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