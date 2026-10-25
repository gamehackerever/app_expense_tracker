@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.offline.ui.settings
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.core.content.ContextCompat

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.repository.SettingsRepository
import com.expensetracker.offline.data.repository.isValidUpiVpa
import com.expensetracker.offline.engine.backup.AutoBackupWorker
import com.expensetracker.offline.engine.backup.SecurePassphraseStore
import com.expensetracker.offline.engine.scanner.ScanResponse
import com.expensetracker.offline.engine.scanner.SmsHistoryScanner
import com.expensetracker.offline.ui.components.AppDialog
import com.expensetracker.offline.ui.components.PremiumDropdownField
import com.expensetracker.offline.ui.components.SheetSegmentedControl
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.BackupManager
import com.expensetracker.offline.util.BackupResult
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.CsvEngine
import com.expensetracker.offline.util.CsvResult
import com.expensetracker.offline.util.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.offline.ui.components.BillingCycleDialog

private const val MIN_BACKUP_PASSPHRASE_LENGTH = 8

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
    val transactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val scrollState = rememberScrollState()
    val sectionPositions = remember { mutableStateMapOf<String, Float>() }
    var highlightedSection by remember { mutableStateOf<String?>(null) }

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

    val settingsRepo = remember { SettingsRepository(context) }
    var isRestoringBackup by remember { mutableStateOf(false) }

    fun handleRestoreResult(result: RestoreResult) {
        when (result) {
            is RestoreResult.SuccessRestart -> {
                isRestoringBackup = true
                viewModel.refreshAfterRestore()
                showSnack("Backup restored successfully!")
                Handler(Looper.getMainLooper()).postDelayed({
                    val activity = (context as? android.app.Activity)
                    if (activity != null && !activity.isFinishing) {
                        activity.recreate()
                    }
                }, 250L)
            }
            is RestoreResult.WrongPassphrase -> showSnack("Incorrect passphrase!")
            is RestoreResult.Error -> showSnack(result.message)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SecurePassphraseStore.migrateLegacy(context) }
    }

    var isLowBalanceEnabled by remember { mutableStateOf(settingsRepo.lowBalanceAlertEnabled) }
    var isBillRemindersEnabled by remember { mutableStateOf(settingsRepo.billRemindersEnabled) }
    var thresholdText by remember { mutableStateOf(settingsRepo.lowBalanceThreshold.toInt().toString()) }
    val vmMonthlyBudget by viewModel.monthlyBudget.collectAsStateWithLifecycle()
    val allTransactionsWithDebts by viewModel.allTransactionsWithDebts.collectAsStateWithLifecycle()
    val pendingDeleteIds by viewModel.pendingDeleteIds.collectAsStateWithLifecycle()
    var budgetText by remember(vmMonthlyBudget) { mutableStateOf(vmMonthlyBudget.toString()) }

    var startDayOfMonth by remember { mutableIntStateOf(settingsRepo.startDayOfMonth) }
    val isManualModePreferred by viewModel.isManualModePreferred.collectAsStateWithLifecycle()
    var skipReviewQueue by remember { mutableStateOf(settingsRepo.skipReviewQueue) }
    var defaultFallbackPayee by remember { mutableStateOf(settingsRepo.fallbackPayee) }
    var autoSaveUnknown by remember { mutableStateOf(settingsRepo.autoSaveUnknownDeductions) }
    var skipScanReview by remember { mutableStateOf(settingsRepo.skipScanReview) }
    var scanFallbackPayee by remember { mutableStateOf(settingsRepo.scanFallbackPayee) }
    var autoBackupEnabled by remember { mutableStateOf(settingsRepo.autoBackupEnabled) }
    var backupFrequency by remember { mutableStateOf(settingsRepo.backupFrequency) }
    var backupFolderUri by remember { mutableStateOf(settingsRepo.backupFolderUri) }
    var lastBackupTimestamp by remember { mutableLongStateOf(prefs.getLong("key_last_backup_timestamp", 0L)) }
    var autoBackupStatus by remember { mutableStateOf(AutoBackupWorker.lastRunSummary(context)) }
    var autoBackupStatusFailed by remember {
        mutableStateOf(prefs.contains(AutoBackupWorker.KEY_LAST_RUN_OK) && !prefs.getBoolean(AutoBackupWorker.KEY_LAST_RUN_OK, true))
    }
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

    DisposableEffect(Unit) {
        onDispose {
            if (!isRestoringBackup) {
                val pending = myUpiInput.trim()
                if (pending != settingsRepo.myUpiVpa && isValidUpiVpa(pending)) {
                    settingsRepo.myUpiVpa = pending
                }
                val pendingBudget = budgetText.toLongOrNull()?.takeIf { it > 0L }
                if (pendingBudget != null && pendingBudget != settingsRepo.monthlyBudget) {
                    viewModel.updateMonthlyBudget(pendingBudget)
                }
            }
        }
    }

    var showBackupPassphraseDialog by remember { mutableStateOf(false) }
    var showRestorePassphraseDialog by remember { mutableStateOf<Uri?>(null) }
    com.expensetracker.offline.ui.components.SecureScreenEffect(
        enabled = showBackupPassphraseDialog || showRestorePassphraseDialog != null
    )
    var tempPassphrase by remember { mutableStateOf("") }
    var tempConfirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

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
                AutoBackupWorker.schedule(context)
                if (wasAutoBackupOn) showSnack("Backup folder access was lost. Please choose a folder again.")
            }
        }
    }

    fun commitBudget() {
        val parsed = budgetText.toLongOrNull()?.takeIf { it > 0L }
        if (parsed != null && parsed != settingsRepo.monthlyBudget) {
            viewModel.updateMonthlyBudget(parsed)
            budgetText = parsed.toString()
            showSnack("Budget saved")
        } else {
            budgetText = settingsRepo.monthlyBudget.toString()
        }
    }

    var showStartDayDialog by remember { mutableStateOf(false) }
    var showScanConfirmDialog by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isProTipsDismissed by remember { mutableStateOf(prefs.getBoolean("key_pro_tips_dismissed", false)) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    var hasNotificationPermission by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                hasNotificationPermission = isNotificationPermissionGranted(context)
                hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                lastBackupTimestamp = prefs.getLong("key_last_backup_timestamp", 0L)
                autoBackupEnabled = prefs.getBoolean("key_auto_backup_enabled", false)
                autoBackupStatus = AutoBackupWorker.lastRunSummary(context)
                autoBackupStatusFailed = prefs.contains(AutoBackupWorker.KEY_LAST_RUN_OK) &&
                        !prefs.getBoolean(AutoBackupWorker.KEY_LAST_RUN_OK, true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingAutoBackupEnable by remember { mutableStateOf(false) }
    var pendingEnableAfterPassphrase by remember { mutableStateOf(false) }

    fun turnOnAutoBackup() {
        pendingEnableAfterPassphrase = false
        autoBackupEnabled = true
        prefs.edit {
            putBoolean("key_auto_backup_enabled", true)
            remove(AutoBackupWorker.KEY_LAST_RUN_OK)
            remove(AutoBackupWorker.KEY_LAST_RUN_TIME)
            remove(AutoBackupWorker.KEY_LAST_RUN_REASON)
        }
        autoBackupStatus = null
        autoBackupStatusFailed = false
        AutoBackupWorker.schedule(context)
    }

    fun requestAutoBackupOn() {
        val existingPassphrase = SecurePassphraseStore.load(context)
        if (existingPassphrase.isNullOrBlank() || existingPassphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH) {
            pendingEnableAfterPassphrase = true
            tempPassphrase = ""
            tempConfirmPassphrase = ""
            showPassphrase = false
            showBackupPassphraseDialog = true
        } else {
            turnOnAutoBackup()
        }
    }
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
            val previous = backupFolderUri
            if (previous.isNotBlank() && previous != uri.toString()) {
                try {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags)
                } catch (_: Exception) {
                }
            }
            backupFolderUri = uri.toString()
            prefs.edit { putString("key_backup_folder_uri", uri.toString()) }
            if (shouldEnableAutoBackup) requestAutoBackupOn()
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
                    reportCsv(
                        CsvEngine.exportWithDebtsToCsv(context, allTransactionsWithDebts, uri.toString()),
                        "CSV Exported successfully!"
                    )
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
                    val result = CsvEngine.importFromCsv(
                        context = context,
                        uri = uri,
                        onImportedWithDebts = { importedList, splitsMap ->
                            viewModel.importTransactions(importedList, splitsMap)
                        },
                        onImported = { importedList ->
                            viewModel.importTransactions(importedList)
                        }
                    )
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

            val needsHealthFix = !isManualModePreferred && (!(hasNotificationPermission || hasSmsPermission) || !isIgnoringBatteryOptimizations)

            if (needsHealthFix) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.errorContainer,
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
                                color = MaterialTheme.colorScheme.onErrorContainer
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
                                    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    val ok = runCatching { context.startActivity(direct); true }.getOrDefault(false)
                                    if (!ok) {
                                        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivitySafely(fallback)
                                    }
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

            val healthIssues = if (isManualModePreferred) 0
            else listOf(hasSmsPermission, hasNotificationPermission, isIgnoringBatteryOptimizations).count { !it }
            SettingsStatusStrip(
                items = listOf(
                    SettingsStatus(
                        label = "Capture",
                        value = if (isManualModePreferred) "Paused" else "Active",
                        state = if (isManualModePreferred) 1 else 0,
                        key = "Live"
                    ),
                    SettingsStatus(
                        label = "Backup",
                        value = when {
                            autoBackupEnabled && autoBackupStatusFailed -> "Failed"
                            autoBackupEnabled -> backupFrequency
                            else -> "Off"
                        },
                        state = when {
                            autoBackupEnabled && autoBackupStatusFailed -> 2
                            autoBackupEnabled -> 0
                            else -> 1
                        },
                        key = "Backup"
                    ),
                    SettingsStatus(
                        label = "Health",
                        value = when {
                            isManualModePreferred -> "Manual mode"
                            healthIssues == 0 -> "All good"
                            else -> "$healthIssues to fix"
                        },
                        state = if (healthIssues == 0) 0 else 2,
                        key = "Health"
                    )
                ),
                onClick = { scrollToAndHighlight(it) }
            )

            SettingsQuickNav(
                items = listOf(
                    "Budget" to "Cycle",
                    "Capture" to "Live",
                    "Import" to "SMS",
                    "Alerts" to "Alerts",
                    "Backup" to "Backup",
                    "Appearance" to "Theme",
                    "Health" to "Health"
                ),
                selectedKey = highlightedSection,
                onSelect = { scrollToAndHighlight(it) }
            )

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
                        if (targetKey.equals("Necessities", ignoreCase = true) || targetKey.equals("necessities", ignoreCase = true)) onNavigateToNecessities()
                        else scrollToAndHighlight(targetKey)
                    }
                )
            }

            // ─────────────────────────────────────────────────────────────────
            // 1. Budget & Cycle
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Budget & Cycle",
                isHighlighted = highlightedSection == "Cycle",
                modifier = Modifier.trackSection("Cycle", sectionPositions)
            ) {
                SettingsItem(
                    icon = Icons.Default.Savings,
                    title = "Target Spending Limit",
                    subtitle = "Powers the monthly gauge on your dashboard"
                )

                SettingsInset {
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
                }

                SettingsDivider()

                val categoryBudgets by viewModel.categoryBudgets.collectAsStateWithLifecycle()
                var showManageCategoryBudgetsDialog by remember { mutableStateOf(false) }

                SettingsItem(
                    icon = Icons.Default.Tune,
                    title = "Category Budgets",
                    subtitle = if (categoryBudgets.isEmpty()) "Set spending caps for Food, Shopping, etc."
                    else "${categoryBudgets.size} ${if (categoryBudgets.size == 1) "limit" else "limits"} active · ${com.expensetracker.offline.ui.components.CurrencyFormat.withSymbol(categoryBudgets.values.sum(), 0)} allocated",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showManageCategoryBudgetsDialog = true
                    }
                )

                var showManageCategoriesSheet by remember { mutableStateOf(false) }
                var settingsCategoryToDelete by remember { mutableStateOf<String?>(null) }
                var settingsCustomCategories by remember {
                    mutableStateOf(prefs.getStringSet("key_custom_categories", emptySet()) ?: emptySet())
                }
                var initialCatForSettingsBudget by remember { mutableStateOf<String?>(null) }
                var returnToSettingsCatManage by remember { mutableStateOf<String?>(null) }
                var initialCatForSettingsManage by remember { mutableStateOf<String?>(null) }

                val allAvailableCategories = remember(transactions, settingsCustomCategories) {
                    (com.expensetracker.offline.engine.categorizer.CategoryClassifier.PREDEFINED_CATEGORIES +
                            settingsCustomCategories +
                            transactions.asSequence().take(500).map { it.category }.toList())
                        .filter { it.isNotBlank() }
                        .distinct()
                }

                if (showManageCategoryBudgetsDialog) {
                    com.expensetracker.offline.ui.dashboard.ManageCategoryBudgetsSheet(
                        availableCategories = allAvailableCategories,
                        currentBudgets = categoryBudgets,
                        onSaveBudget = { cat, limit ->
                            viewModel.setCategoryBudget(cat, limit)
                        },
                        onRemoveBudget = { cat ->
                            viewModel.removeCategoryBudget(cat)
                        },
                        onDismiss = {
                            showManageCategoryBudgetsDialog = false
                            initialCatForSettingsBudget = null
                            val ret = returnToSettingsCatManage
                            if (ret != null) {
                                returnToSettingsCatManage = null
                                initialCatForSettingsManage = ret
                                showManageCategoriesSheet = true
                            }
                        },
                        initialCategory = initialCatForSettingsBudget,
                        transactions = allTransactionsWithDebts,
                        pendingDeleteIds = pendingDeleteIds.keys,
                        monthlyBudgetPaise = vmMonthlyBudget * 100L,
                        startDayOfMonth = startDayOfMonth
                    )
                }

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.Category,
                    title = "Manage Categories",
                    subtitle = if (settingsCustomCategories.isEmpty()) "Add, rename, or merge expense categories"
                    else "${settingsCustomCategories.size} custom · Rename, merge, or add categories",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        initialCatForSettingsManage = null
                        showManageCategoriesSheet = true
                    }
                )

                if (showManageCategoriesSheet) {
                    com.expensetracker.offline.ui.dashboard.ManageCategoriesDialog(
                        customCategories = settingsCustomCategories,
                        onDismiss = {
                            showManageCategoriesSheet = false
                            initialCatForSettingsManage = null
                        },
                        allCategories = allAvailableCategories,
                        transactions = allTransactionsWithDebts,
                        categoryBudgets = categoryBudgets,
                        startDayOfMonth = startDayOfMonth,
                        pendingDeleteIds = pendingDeleteIds.keys,
                        initialCategory = initialCatForSettingsManage,
                        onAddCategory = { newCat ->
                            val trimmed = newCat.trim()
                            if (allAvailableCategories.any { it.equals(trimmed, ignoreCase = true) }) {
                                showSnack("Category \"$trimmed\" already exists")
                            } else {
                                val updated = settingsCustomCategories + trimmed
                                settingsCustomCategories = updated
                                prefs.edit().putStringSet("key_custom_categories", updated).apply()
                                showSnack("Category \"$trimmed\" created")
                            }
                        },
                        onRenameCategory = { oldName, newName ->
                            val trimmedNew = newName.trim()
                            if (trimmedNew.isNotEmpty() && !oldName.equals(trimmedNew, ignoreCase = true)) {
                                val updated = (settingsCustomCategories.filterNot { it.equals(oldName, ignoreCase = true) } + trimmedNew).toSet()
                                settingsCustomCategories = updated
                                prefs.edit().putStringSet("key_custom_categories", updated).apply()
                                viewModel.renameCategory(oldName, trimmedNew)
                                showSnack("Renamed \"$oldName\" to \"$trimmedNew\"")
                            }
                        },
                        onMergeCategory = { fromCategory, intoCategory ->
                            if (!fromCategory.equals(intoCategory, ignoreCase = true)) {
                                viewModel.reassignCategory(fromCategory, intoCategory)
                                showSnack("Moved transactions from \"$fromCategory\" to \"$intoCategory\"")
                            }
                        },
                        onOpenCategoryBudget = { catName ->
                            returnToSettingsCatManage = catName
                            showManageCategoriesSheet = false
                            initialCatForSettingsBudget = catName
                            showManageCategoryBudgetsDialog = true
                        },
                        onDeleteCategory = { catToDelete ->
                            settingsCategoryToDelete = catToDelete
                        }
                    )
                }

                if (settingsCategoryToDelete != null) {
                    val targetCat = settingsCategoryToDelete!!
                    val count = transactions.count { it.category.equals(targetCat, ignoreCase = true) }
                    com.expensetracker.offline.ui.dashboard.DeleteCategoryDialog(
                        categoryToDelete = targetCat,
                        transactionCount = count,
                        availableCategories = allAvailableCategories,
                        onDismiss = { settingsCategoryToDelete = null },
                        onConfirm = { reassignTarget ->
                            val updated = settingsCustomCategories.filterNot { it.equals(targetCat, ignoreCase = true) }.toSet()
                            settingsCustomCategories = updated
                            prefs.edit().putStringSet("key_custom_categories", updated).apply()
                            viewModel.onCategoryDeleted(deletedCategory = targetCat, reassignTo = reassignTarget)
                            showSnack("Category \"$targetCat\" deleted")
                            settingsCategoryToDelete = null
                        }
                    )
                }

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.CalendarMonth,
                    title = "Billing Cycle Reset",
                    subtitle = "Current: ${BillingCycleHelper.getCycleLabel(startDayOfMonth)}",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showStartDayDialog = true
                    },
                    trailing = { SettingsValueChip("Day $startDayOfMonth") }
                )

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.Event,
                    title = "Fixed Costs & Bills",
                    subtitle = "Rent, subscriptions & yearly premiums",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToNecessities()
                    }
                )

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.AccountBalanceWallet,
                    title = "UPI Settlement",
                    subtitle = "Used when sending split repayment requests"
                )

                SettingsInset {
                    SettingsTextField(
                        value = myUpiInput,
                        onValueChange = { myUpiInput = it.filter { c -> !c.isWhitespace() }.take(100) },
                        label = "Your UPI ID (to receive payments)",
                        placeholder = "e.g., name@okaxis",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.onFocusLost { commitMyUpi() }
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 2. Live Capture Automation
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Live Capture Automation",
                isHighlighted = highlightedSection == "Live",
                modifier = Modifier.trackSection("Live", sectionPositions)
            ) {
                SettingsSwitchItem(
                    icon = Icons.Default.FlashOn,
                    title = "Auto-Capture (SMS & Notifications)",
                    subtitle = if (!isManualModePreferred) "Active — automatically detects bank SMS & UPI payment alerts."
                    else "Paused (Manual Mode) — turn on anytime to auto-detect bank & UPI payments.",
                    iconTint = if (!isManualModePreferred) AppColors.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                    checked = !isManualModePreferred,
                    onCheckedChange = { enableAuto ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setManualModePreferred(!enableAuto)
                        if (enableAuto) {
                            viewModel.setAutoCaptureBannerDismissed(false)
                            showSnack("Auto-capture enabled")
                        } else {
                            showSnack("Auto-capture paused — switched to Manual Mode")
                        }
                    }
                )

                SettingsDivider()

                SettingsSwitchItem(
                    icon = Icons.Default.FlashOn,
                    title = "Skip Review (Live Captures)",
                    subtitle = "Skips the Review queue. Misread merchants or amounts get saved as-is.",
                    checked = skipReviewQueue,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        skipReviewQueue = enabled
                        prefs.edit { putBoolean("key_skip_review_queue", enabled) }
                    }
                )

                if (skipReviewQueue) {
                    SettingsInset {
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
                }

                SettingsDivider()

                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = "Auto-save Silent Deductions",
                    subtitle = "If your bank balance drops without an SMS alert, skip review and auto-log the missing amount.",
                    checked = autoSaveUnknown,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        autoSaveUnknown = enabled
                        prefs.edit { putBoolean("key_autosave_unknown_deductions", enabled) }
                    }
                )
            }

            // ─────────────────────────────────────────────────────────────────
            // 3. Inbox Import & Review
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Inbox Import & Review",
                isHighlighted = highlightedSection == "SMS",
                modifier = Modifier.trackSection("SMS", sectionPositions)
            ) {
                SettingsItem(
                    icon = Icons.Default.Sms,
                    title = "Scan SMS for Current Month",
                    subtitle = if (skipScanReview) "Direct import active. Scans from ${BillingCycleHelper.getCycleStartDateFormatted(startDayOfMonth)} to now."
                    else "Scans from ${BillingCycleHelper.getCycleStartDateFormatted(startDayOfMonth)}. High values go to Review."
                )

                SettingsDivider()

                SettingsSwitchItem(
                    icon = Icons.Default.Bolt,
                    title = "Skip Review for Inbox Scan",
                    subtitle = "Skips review for the inbox scan. Check Undo Last Scan if results look off.",
                    checked = skipScanReview,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        skipScanReview = enabled
                        prefs.edit { putBoolean("key_skip_scan_review", enabled) }
                    }
                )

                if (skipScanReview) {
                    SettingsInset {
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
                }

                SettingsInset(start = 16.dp, top = 4.dp) {
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
                }

                val totalBatchCount = lastImportedTxnIds.size + lastImportedReviewIds.size
                if (totalBatchCount > 0) {
                    SettingsInset(start = 16.dp, top = 0.dp) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.undoScan(lastImportedTxnIds, lastImportedReviewIds)

                                val count = lastImportedTxnIds.size + lastImportedReviewIds.size

                                lastImportedTxnIds = emptyList()
                                lastImportedReviewIds = emptyList()

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
                                Icons.AutoMirrored.Filled.Undo,
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
            }

            // ─────────────────────────────────────────────────────────────────
            // 4. Security & Alerts
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Security & Alerts",
                isHighlighted = highlightedSection == "Alerts",
                modifier = Modifier.trackSection("Alerts", sectionPositions)
            ) {
                SettingsSwitchItem(
                    icon = Icons.Default.Lock,
                    title = "App Lock",
                    subtitle = "Require biometric unlock & hide from Recents",
                    checked = isAppLockEnabled,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (enabled) {
                            val biometricManager = BiometricManager.from(context)
                            val canAuthenticate = biometricManager.canAuthenticate(APP_LOCK_AUTHENTICATORS)

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
                    }
                )

                SettingsDivider()

                SettingsSwitchItem(
                    icon = Icons.Default.NotificationsActive,
                    title = "Low Balance Alert",
                    subtitle = "Warns when bank balance drops low",
                    checked = isLowBalanceEnabled,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isLowBalanceEnabled = enabled
                        prefs.edit { putBoolean("key_low_balance_alert_enabled", enabled) }
                    }
                )

                if (isLowBalanceEnabled) {
                    SettingsInset {
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

                SettingsDivider()

                SettingsSwitchItem(
                    icon = Icons.Default.Event,
                    title = "Smart Bill Reminders",
                    subtitle = "Notify 2 days before fixed costs or subscriptions are due",
                    checked = isBillRemindersEnabled,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isBillRemindersEnabled = enabled
                        settingsRepo.billRemindersEnabled = enabled
                        if (enabled) {
                            coroutineScope.launch(Dispatchers.IO) {
                                com.expensetracker.offline.engine.notification.DailySummaryReceiver.checkAndNotifyUpcomingBills(context, transactions)
                            }
                        }
                    }
                )
            }

            // ─────────────────────────────────────────────────────────────────
            // 5. Data & Backup
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Data & Backup",
                isHighlighted = highlightedSection == "Backup",
                modifier = Modifier.trackSection("Backup", sectionPositions)
            ) {
                SettingsSwitchItem(
                    icon = Icons.Default.CloudSync,
                    title = "Auto Backup",
                    subtitle = "Encrypt and save data automatically",
                    checked = autoBackupEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && backupFolderUri.isBlank()) {
                            showSnack("Please select a backup folder first")
                            pendingAutoBackupEnable = true
                            folderPickerLauncher.launch(null)
                        } else if (enabled) {
                            requestAutoBackupOn()
                        } else {
                            autoBackupEnabled = false
                            prefs.edit { putBoolean("key_auto_backup_enabled", false) }
                            AutoBackupWorker.schedule(context)
                        }
                    }
                )

                if (autoBackupEnabled || autoBackupStatus != null) {
                    SettingsInset(top = 0.dp, bottom = 14.dp) {
                        Text(
                            autoBackupStatus ?: "No auto-backup has run yet",
                            fontSize = 12.sp,
                            color = if (autoBackupStatusFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (autoBackupEnabled) {
                    SettingsInset(start = 16.dp) {
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
                }

                SettingsInset(start = 16.dp) {
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
                }

                SettingsDivider()

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

                SettingsInset(start = 16.dp, top = 14.dp, bottom = 12.dp) {
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
                }

                SettingsInset(start = 16.dp) {
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Restore",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
                            Text(
                                "Backup Now",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.TableView,
                    title = "CSV Management",
                    subtitle = "Full spreadsheet import & export (with splits)"
                )

                SettingsInset(start = 16.dp) {
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
                            Text(
                                "Import",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                csvFolderLauncher.launch(null)
                            },
                            enabled = !isCsvBusy,
                            shape = RoundedCornerShape(AppRadius.small),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
                            Text(
                                "Export",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 6. Appearance & Theme
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Appearance & Theme",
                isHighlighted = highlightedSection == "Theme",
                modifier = Modifier.trackSection("Theme", sectionPositions)
            ) {
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = "App Theme",
                    subtitle = "Choose your preferred theme mode"
                )

                val themeModes = remember { listOf("SYSTEM" to "System", "DARK" to "Dark", "LIGHT" to "Light") }
                val selectedThemeIndex = themeModes.indexOfFirst { it.first == currentThemeMode }.coerceAtLeast(0)
                SettingsInset(start = 16.dp) {
                    SheetSegmentedControl(
                        options = themeModes.map { it.second },
                        selectedIndex = selectedThemeIndex,
                        onSelect = { idx ->
                            val mode = themeModes[idx].first
                            currentThemeMode = mode
                            settingsRepo.appThemeMode = mode
                        }
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 7. System Health
            // ─────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "System Health",
                isHighlighted = highlightedSection == "Health",
                modifier = Modifier.trackSection("Health", sectionPositions)
            ) {
                SettingsItem(
                    icon = if (hasSmsPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                    iconTint = if (hasSmsPermission) AppColors.positive else MaterialTheme.colorScheme.error,
                    title = "Bank SMS Access",
                    subtitle = if (hasSmsPermission) "Active & Reading Bank SMS Alerts" else "Permission required for bank SMS capture"
                )

                if (!hasSmsPermission) {
                    SettingsInset(start = 16.dp) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivitySafely(intent)
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(AppRadius.small),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text("Open App Permissions (SMS)", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                SettingsDivider()

                SettingsItem(
                    icon = if (hasNotificationPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                    iconTint = if (hasNotificationPermission) AppColors.positive
                    else if (hasSmsPermission) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    title = "Notification Interceptor",
                    subtitle = if (hasNotificationPermission) "Active & Listening (GPay, PhonePe, etc.)" else "Access required in Android Settings"
                )

                if (!hasNotificationPermission) {
                    SettingsInset(start = 16.dp) {
                        Surface(
                            shape = RoundedCornerShape(AppRadius.small),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Privacy",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Privacy First: Your data never leaves this device. This app has no internet permission.",
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))

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
                                    Text("Open Notification Access", fontWeight = FontWeight.SemiBold)
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Is it greyed out (Restricted Setting)?",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Tap 'Unlock Settings' below, then tap the 3 dots (⋮) in the top right corner and select 'Allow restricted settings'.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = android.net.Uri.fromParts("package", context.packageName, null)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivitySafely(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(AppRadius.small),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) {
                                    Text("Unlock Settings", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                SettingsDivider()

                SettingsItem(
                    icon = if (isIgnoringBatteryOptimizations) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
                    iconTint = if (isIgnoringBatteryOptimizations) AppColors.positive else MaterialTheme.colorScheme.error,
                    title = "Battery Optimization",
                    subtitle = if (isIgnoringBatteryOptimizations) "Unrestricted (OS won't kill listener)" else "Restricted (OS may kill background listener)"
                )

                if (!isIgnoringBatteryOptimizations) {
                    SettingsInset(start = 16.dp) {
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
                                        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        val ok = runCatching { context.startActivity(direct); true }.getOrDefault(false)
                                        if (!ok) {
                                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                            }
                                            context.startActivitySafely(fallback)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(AppRadius.small),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) {
                                    Text("Allow Unrestricted Battery", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // 8. Developer Sandbox (Strictly gated behind debuggable builds)
            // ─────────────────────────────────────────────────────────────────
            if (isDebugBuild) {
                SettingsSection(title = "Developer") {
                    SettingsItem(
                        icon = Icons.Default.BugReport,
                        title = "Developer Sandbox",
                        subtitle = "Live AI/ML telemetry, automated test suite & capture simulator",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigateToDebug()
                        }
                    )
                }
            }

            val appVersionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.2.0"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Expense Tracker v$appVersionName",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "100% Offline · Zero Network Access",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOM DIALOGS
    // ─────────────────────────────────────────────────────────────────────────
    if (showStartDayDialog) {
        BillingCycleDialog(
            currentDay = startDayOfMonth,
            onDismiss = { showStartDayDialog = false },
            onSave = { day ->
                startDayOfMonth = day
                viewModel.updateStartDayOfMonth(day)
                runCatching { com.expensetracker.offline.widget.ExpenseWidget.updateAll(context) }
                showStartDayDialog = false
            }
        )
    }

    if (showBackupPassphraseDialog) {
        val tooShort = tempPassphrase.isNotEmpty() && tempPassphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH
        AppDialog(
            title = "Set Backup Passphrase",
            secure = true,
            onDismiss = {
                showBackupPassphraseDialog = false
                pendingEnableAfterPassphrase = false
                tempPassphrase = ""; tempConfirmPassphrase = ""; showPassphrase = false
            },
            confirmText = "Encrypt",
            confirmEnabled = tempPassphrase.isNotBlank() &&
                    tempPassphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH &&
                    tempPassphrase == tempConfirmPassphrase,
            onConfirm = {
                val finalPassphrase = tempPassphrase
                showBackupPassphraseDialog = false
                val passphraseStored = SecurePassphraseStore.save(context, finalPassphrase)
                if (!passphraseStored) {
                    pendingEnableAfterPassphrase = false
                } else if (pendingEnableAfterPassphrase) {
                    turnOnAutoBackup()
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
                                showSnack(
                                    if (passphraseStored) "Backup encrypted and saved securely!"
                                    else "Backup saved, but the passphrase couldn't be stored securely, so auto-backup won't run."
                                )
                            }
                            is BackupResult.Error -> showSnack(
                                if (passphraseStored) result.message
                                else "${result.message} The passphrase also couldn't be stored securely."
                            )
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
            Text(
                "Your backup is encrypted with AES-256-GCM. Use at least $MIN_BACKUP_PASSPHRASE_LENGTH characters. If you forget this passphrase, your backup cannot be recovered.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            val passphraseTransformation =
                if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = tempPassphrase,
                onValueChange = { tempPassphrase = it },
                placeholder = { Text("Enter passphrase (min $MIN_BACKUP_PASSPHRASE_LENGTH chars)") },
                singleLine = true,
                isError = tooShort,
                supportingText = if (tooShort) ({ Text("Use at least $MIN_BACKUP_PASSPHRASE_LENGTH characters") }) else null,
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
            secure = true,
            onDismiss = { showRestorePassphraseDialog = null; tempPassphrase = "" },
            confirmText = "Restore",
            destructive = true,
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
        var scanDay by remember { mutableFloatStateOf(startDayOfMonth.toFloat()) }

        AppDialog(
            title = "Confirm Scan",
            onDismiss = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); showScanConfirmDialog = false },
            confirmText = "Scan",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showScanConfirmDialog = false

                val cycleStartMillis = BillingCycleHelper.getCycleRange(scanDay.toInt()).first
                isScanning = true

                coroutineScope.launch {
                    try {
                        val scanner = SmsHistoryScanner(context, app.database)
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
