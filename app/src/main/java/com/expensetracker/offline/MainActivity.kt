package com.expensetracker.offline

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.expensetracker.offline.ui.components.CurrencyFormat
import com.expensetracker.offline.ui.dashboard.DashboardScreen
import com.expensetracker.offline.ui.debug.DebugTestingScreen
import com.expensetracker.offline.ui.insights.InsightsScreen
import com.expensetracker.offline.ui.necessities.ManageNecessitiesScreen
import com.expensetracker.offline.ui.review.ReviewQueueScreen
import com.expensetracker.offline.ui.settings.SettingsScreen
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.ExpenseTrackerTheme
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AppLockState {
    var isLocked = true
}

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isRequestingPermissions = false
    private var lastUnlockTime = 0L

    private var intentTrigger by androidx.compose.runtime.mutableIntStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentTrigger++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("expense_tracker_prefs", MODE_PRIVATE)

        setContent {
            var appThemeMode by remember {
                mutableStateOf(prefs.getString("key_app_theme_mode", "SYSTEM") ?: "SYSTEM")
            }

            var isAppLockSettingEnabled by remember {
                mutableStateOf(prefs.getBoolean("key_app_lock_enabled", false))
            }

            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    when (key) {
                        "key_app_theme_mode" -> {
                            appThemeMode = p.getString(key, "SYSTEM") ?: "SYSTEM"
                        }
                        "key_app_lock_enabled" -> {
                            val enabled = p.getBoolean(key, false)
                            isAppLockSettingEnabled = enabled
                        }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            ExpenseTrackerTheme(themeMode = appThemeMode) {
                var isDisclosureAccepted by remember {
                    mutableStateOf(prefs.getBoolean("key_disclosure_accepted", false))
                }

                var isAppLocked by remember {
                    mutableStateOf(isAppLockSettingEnabled && AppLockState.isLocked)
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        val lockEnabled = prefs.getBoolean("key_app_lock_enabled", false)
                        if (!lockEnabled) return@LifecycleEventObserver
                        if (!prefs.getBoolean("key_disclosure_accepted", false)) return@LifecycleEventObserver
                        if (isRequestingPermissions) return@LifecycleEventObserver

                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                if (SystemClock.elapsedRealtime() - lastUnlockTime > 1500) {
                                    AppLockState.isLocked = true
                                    isAppLocked = true
                                }
                            }
                            Lifecycle.Event.ON_STOP -> {
                                AppLockState.isLocked = true
                                isAppLocked = true
                            }
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (!isDisclosureAccepted) {
                    ProminentDisclosureScreen(
                        onCompleteOnboarding = { chosenBudgetRupees, enableAutoCapture, importCurrentCycleSms, importDirectly, openBackupRestore ->
                            prefs.edit()
                                .putBoolean("key_disclosure_accepted", true)
                                .putBoolean("key_setup_dismissed", true)
                                .putBoolean("key_restore_prompt_dismissed", true)
                                .putBoolean("key_manual_mode_preferred", !enableAutoCapture)
                                .putBoolean("key_permissions_prompted_once", true)
                                .apply()
                            viewModel.setManualModePreferred(!enableAutoCapture)
                            if (chosenBudgetRupees != null && chosenBudgetRupees > 0L) {
                                viewModel.updateMonthlyBudget(chosenBudgetRupees)
                            }
                            if (importCurrentCycleSms && enableAutoCapture) {
                                viewModel.scanCurrentCycleSms(importDirectly = importDirectly)
                            }
                            if (openBackupRestore) {
                                intent?.putExtra("OPEN_ROUTE", "settings?target=Backup")
                                intentTrigger++
                            }
                            isDisclosureAccepted = true
                        },
                        onDecline = { finish() }
                    )
                } else {
                    com.expensetracker.offline.ui.components.SecureScreenEffect(enabled = isAppLocked)

                    LaunchedEffect(isAppLocked) {
                        if (!isAppLocked &&
                            !prefs.getBoolean("key_manual_mode_preferred", false) &&
                            !prefs.getBoolean("key_permissions_prompted_once", false)
                        ) {
                            prefs.edit().putBoolean("key_permissions_prompted_once", true).apply()
                            checkAndRequestPermissions()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        AppNavigation(viewModel = viewModel, isAppLocked = isAppLocked, intentTrigger = intentTrigger)

                        AnimatedVisibility(
                            visible = isAppLocked,
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(240, easing = FastOutSlowInEasing)) +
                                    scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = 1.03f)
                        ) {
                            AppLockScreen(onUnlock = {
                                lastUnlockTime = SystemClock.elapsedRealtime()
                                AppLockState.isLocked = false
                                isAppLocked = false
                            })
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            isRequestingPermissions = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isRequestingPermissions = false
    }
}

@Composable
fun AppLockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentOnUnlock by rememberUpdatedState(onUnlock)

    BackHandler { activity.moveTaskToBack(true) }

    var isPrompting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    val biometricPrompt = remember {
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPrompting = false
                    errorMessage = null
                    currentOnUnlock()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    isPrompting = false
                    errorMessage = errString.toString()
                }
            }
        )
    }

    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Expense Tracker")
            .setAllowedAuthenticators(authenticators)
            .build()
    }

    fun authenticate() {
        if (isPrompting) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val bm = BiometricManager.from(context)
        val status = bm.canAuthenticate(authenticators)
        when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> { errorMessage = null }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                val credentialOnly = bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                if (credentialOnly != BiometricManager.BIOMETRIC_SUCCESS) {
                    context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
                        .edit { putBoolean("key_app_lock_enabled", false) }
                    Toast.makeText(context, "App Lock turned off: No device screen lock is set up.", Toast.LENGTH_LONG).show()
                    currentOnUnlock()
                    return
                }
            }
            else -> {
                errorMessage = "Authentication temporarily unavailable. Tap Unlock to try again."
                return
            }
        }

        isPrompting = true
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (_: Exception) {
            isPrompting = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scope.launch {
                    delay(250)
                    authenticate()
                }
                Lifecycle.Event.ON_STOP -> isPrompting = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(24.dp))
            Text("App Locked", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { authenticate() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.height(48.dp).width(160.dp)
            ) {
                Text("Unlock", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Reworked 2-Step Welcoming Onboarding Flow:
 * - Step 1: Warm welcome, 100% offline privacy reassurance, and simple 1-tap selection between
 *   "Automatic Hands-Free Tracking" (Install & Forget) and "Manual & Receipt Entry Only", plus
 *   a 1-tap "Restore from backup" shortcut.
 * - Step 2: Optional 1-tap monthly budget presets (₹10,000 – ₹1,00,000) with a clear summary
 *   before starting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProminentDisclosureScreen(
    onCompleteOnboarding: (
        chosenBudgetRupees: Long?,
        enableAutoCapture: Boolean,
        importCurrentCycleSms: Boolean,
        importDirectly: Boolean,
        openBackupRestore: Boolean
    ) -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) } // 0 = Welcome & Tracking Mode, 1 = Optional Budget
    var enableAutoCapture by remember { mutableStateOf(true) }
    var importPastSms by remember { mutableStateOf(true) } // ON by default in Automatic mode so dashboard is ready immediately
    var importDirectly by remember { mutableStateOf(true) }
    var showPrivacyDetails by remember { mutableStateOf(false) }
    var budgetText by remember { mutableStateOf("") }

    BackHandler(enabled = currentStep > 0) {
        currentStep = 0
    }

    val parsedBudget = budgetText.trim().toLongOrNull()?.takeIf { it > 0L }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onCompleteOnboarding(parsedBudget, enableAutoCapture, importPastSms, importDirectly, false)
    }

    fun launchWithCurrentSettings(budgetOverride: Long? = parsedBudget) {
        if (enableAutoCapture) {
            val perms = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                onCompleteOnboarding(budgetOverride, true, importPastSms, importDirectly, false)
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        } else {
            onCompleteOnboarding(budgetOverride, false, false, true, false)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            // Pinned Top Progress Indicator & 1-Tap Skip (never scrolls under the camera cutout)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(2) { step ->
                        val active = step <= currentStep
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (step == currentStep) 28.dp else 12.dp)
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .background(
                                    if (active) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (currentStep == 0) "Step 1 of 2" else "Step 2 of 2",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = { launchWithCurrentSettings(budgetOverride = null) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Skip setup",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { if (forward) it / 6 else -it / 6 } +
                        fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { if (forward) -it / 8 else it / 8 } +
                            fadeOut(tween(140)))
                },
                label = "onboardingStepTransition"
            ) { step ->
                if (step == 0) {
                    // ─── STEP 1: WARM WELCOME & SIMPLE TRACKING CHOICE ────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Welcome to Expense Tracker",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Your quiet, automatic money companion — crafted for Indian banks & UPI.",
                            fontSize = 13.5.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 100% Offline Trust Badge
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = AppColors.IncomeGreen.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, AppColors.IncomeGreen.copy(alpha = 0.30f))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = AppColors.IncomeGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "100% Offline & Private on Your Phone",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Zero internet permission. No accounts, no cloud servers, no ads.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            "Choose how you'd like to start",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Card 1: Hands-Free Automatic Tracking (Recommended)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.medium))
                                .clickable {
                                    enableAutoCapture = true
                                },
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = if (enableAutoCapture) 1.8.dp else 1.dp,
                                color = if (enableAutoCapture) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Surface(
                                            shape = RoundedCornerShape(AppRadius.pill),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                        ) {
                                            Text(
                                                text = "Install & Forget",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(5.dp))
                                        Text(
                                            "Automatic Hands-Free",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.5.sp,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "Detects bank SMS & UPI payment alerts automatically and learns from your edits.",
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 17.sp
                                        )
                                    }
                                    RadioButton(
                                        selected = enableAutoCapture,
                                        onClick = { enableAutoCapture = true }
                                    )
                                }

                                AnimatedVisibility(
                                    visible = enableAutoCapture,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(AppRadius.small))
                                                .clickable { importPastSms = !importPastSms }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                                Text(
                                                    "Import past 90 days of bank SMS",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    "Fills your recent balances & spending right away so you don't start from zero.",
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    lineHeight = 15.5.sp
                                                )
                                            }
                                            Switch(
                                                checked = importPastSms,
                                                onCheckedChange = { importPastSms = it }
                                            )
                                        }

                                        if (importPastSms) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(IntrinsicSize.Min),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                listOf(
                                                    true to "Add directly",
                                                    false to "Review first"
                                                ).forEach { (directOption, label) ->
                                                    val selected = importDirectly == directOption
                                                    Surface(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight()
                                                            .heightIn(min = 40.dp)
                                                            .clip(RoundedCornerShape(AppRadius.small))
                                                            .clickable { importDirectly = directOption },
                                                        shape = RoundedCornerShape(AppRadius.small),
                                                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
                                                    ) {
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                                        ) {
                                                            Text(
                                                                text = label,
                                                                fontSize = 12.5.sp,
                                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                textAlign = TextAlign.Center,
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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

                        Spacer(modifier = Modifier.height(10.dp))

                        // Card 2: Manual & Receipt Tracking Only
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.medium))
                                .clickable {
                                    enableAutoCapture = false
                                    importPastSms = false
                                },
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = if (!enableAutoCapture) 1.8.dp else 1.dp,
                                color = if (!enableAutoCapture) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.EditNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Manual & Receipt Entry Only",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "Log expenses yourself, scan receipts offline, or import CSV files — no permissions needed.",
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 17.sp
                                    )
                                }
                                RadioButton(
                                    selected = !enableAutoCapture,
                                    onClick = {
                                        enableAutoCapture = false
                                        importPastSms = false
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Expandable Prominent Disclosure Details
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.small))
                                .clickable { showPrivacyDetails = !showPrivacyDetails },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(AppRadius.small)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "How permissions & on-device learning work",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = if (showPrivacyDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                AnimatedVisibility(visible = showPrivacyDetails) {
                                    Text(
                                        "• SMS permission (READ_SMS / RECEIVE_SMS) is used strictly on-device to detect bank alerts, merchants, and balances.\n" +
                                            "• Personal chats and OTPs are ignored automatically.\n" +
                                            "• Every time you approve or discard an item in Review, the app learns that message pattern locally.",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { currentStep = 1 },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { onCompleteOnboarding(null, false, false, true, true) },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Switching phones? Restore from backup",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    // ─── STEP 2: OPTIONAL MONTHLY BUDGET & 1-TAP PRESETS ─────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Savings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            "Set a Monthly Budget (Optional)",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Helps calculate your daily Safe-to-Spend allowance. Pick a preset below or skip for now — you can change this anytime.",
                            fontSize = 13.5.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Monthly target",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (budgetText.isNotEmpty()) {
                                        Text(
                                            "Clear",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { budgetText = "" }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                BasicTextField(
                                    value = budgetText,
                                    onValueChange = { input ->
                                        val cleaned = input.replace(",", "").replace(" ", "")
                                        if (cleaned.isEmpty() || cleaned.matches(Regex("""^\d{0,9}$"""))) {
                                            budgetText = cleaned
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(AppRadius.small))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                .padding(horizontal = 14.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "₹",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(modifier = Modifier.weight(1f)) {
                                                if (budgetText.isEmpty()) {
                                                    Text(
                                                        "Tap a preset below or enter amount",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
                                                }
                                                inner()
                                            }
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(10000L, 15000L, 25000L, 40000L, 60000L, 100000L).forEach { preset ->
                                        val isSelected = parsedBudget == preset
                                        Surface(
                                            shape = RoundedCornerShape(AppRadius.pill),
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(AppRadius.pill))
                                                .clickable {
                                                    budgetText = if (isSelected) "" else preset.toString()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.surface,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                                Text(
                                                    text = CurrencyFormat.withSymbol(preset * 100L, 0),
                                                    fontSize = 12.5.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) MaterialTheme.colorScheme.surface
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Friendly Summary Card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(AppRadius.medium),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (enableAutoCapture) {
                                        if (importPastSms) "Ready! Next, allow SMS access so we can import past 90 days of bank transactions offline."
                                        else "Ready! Next, allow SMS access to auto-detect new payments going forward."
                                    } else {
                                        "Ready! You're starting in Manual & Receipt mode with zero permissions."
                                    },
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        Button(
                            onClick = { launchWithCurrentSettings(parsedBudget) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text("Start Tracking", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = { currentStep = 0 }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Back", fontWeight = FontWeight.SemiBold)
                            }

                            if (parsedBudget != null) {
                                TextButton(onClick = { launchWithCurrentSettings(budgetOverride = null) }) {
                                    Text("Skip budget & start", fontWeight = FontWeight.Medium)
                                }
                            } else {
                                TextButton(onClick = onDecline) {
                                    Text(
                                        "Decline & Exit",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
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

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    isAppLocked: Boolean = false,
    intentTrigger: Int = 0
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context.findFragmentActivity()

    LaunchedEffect(intentTrigger, isAppLocked) {
        if (!isAppLocked) {
            val targetRoute = activity?.intent?.getStringExtra("OPEN_ROUTE")
            if (!targetRoute.isNullOrBlank()) {
                activity.intent?.removeExtra("OPEN_ROUTE")
                navController.navigate(targetRoute) {
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { (it * 0.16f).toInt() } +
                    fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { -(it * 0.07f).toInt() } +
                    fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { -(it * 0.07f).toInt() } +
                    scaleIn(animationSpec = tween(220, easing = FastOutSlowInEasing), initialScale = 0.96f) +
                    fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { (it * 0.18f).toInt() } +
                    scaleOut(animationSpec = tween(220, easing = FastOutSlowInEasing), targetScale = 0.92f) +
                    fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
        }
    ) {
        composable("dashboard") {
            DashboardScreen(
                viewModel = viewModel,
                isAppLocked = isAppLocked,
                onNavigateToReview = { navController.navigate("review") },
                onNavigateToSettings = { target ->
                    navController.navigate("settings?target=${target ?: ""}")
                },
                onNavigateToInsights = { navController.navigate("insights") },
                onNavigateToNecessities = { navController.navigate("necessities") }
            )
        }

        composable("insights") {
            InsightsScreen(
                viewModel = viewModel,
                isAppLocked = isAppLocked,
                onBack = { navController.popBackStack() },
                onNavigateToNecessities = { navController.navigate("necessities") },
                onNavigateToSettings = { target ->
                    navController.navigate("settings?target=${target ?: ""}")
                }
            )
        }

        composable("review") {
            ReviewQueueScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("debug") {
            DebugTestingScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigateToDebug = { navController.navigate("debug") },
                onNavigateToNecessities = { navController.navigate("necessities") }
            )
        }

        composable("necessities") {
            val context = LocalContext.current
            val sharedPrefs = remember {
                context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
            }
            val initialItems = remember { NecessityManager.loadNecessities(sharedPrefs) }
            val txns by viewModel.allTransactionsWithDebts.collectAsState()
            val prefill by viewModel.pendingNecessityPrefill.collectAsState()

            ManageNecessitiesScreen(
                items = initialItems,
                transactions = txns,
                prefill = prefill,
                onPrefillConsumed = { viewModel.consumeNecessityPrefill() },
                onSave = { newList: List<NecessityItem> ->
                    NecessityManager.saveNecessities(sharedPrefs, newList)

                    val totalMonthlyReserve = newList.sumOf { item: NecessityItem ->
                        if (item.frequency == NecessityFrequency.MONTHLY || item.isProrated) {
                            NecessityManager.getMonthlyReserveAmount(item)
                        } else {
                            0L
                        }
                    }
                    viewModel.updateNecessityChunk(totalMonthlyReserve)
                    com.expensetracker.offline.engine.notification.DailySummaryReceiver.checkAndNotifyUpcomingBills(
                        context,
                        txns.map { it.transaction }
                    )
                    com.expensetracker.offline.widget.ExpenseWidget.updateAll(context)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "settings?target={target}",
            arguments = listOf(navArgument("target") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigateToDebug = { navController.navigate("debug") },
                onNavigateToNecessities = { navController.navigate("necessities") },
                scrollToSection = backStackEntry.arguments?.getString("target")
            )
        }
    }
}

fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
