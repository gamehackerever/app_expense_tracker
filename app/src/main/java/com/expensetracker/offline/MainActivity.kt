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
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.expensetracker.offline.ui.dashboard.DashboardScreen
import com.expensetracker.offline.ui.debug.DebugTestingScreen
import com.expensetracker.offline.ui.insights.InsightsScreen
import com.expensetracker.offline.ui.necessities.ManageNecessitiesScreen
import com.expensetracker.offline.ui.review.ReviewQueueScreen
import com.expensetracker.offline.ui.settings.SettingsScreen
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("expense_tracker_prefs", MODE_PRIVATE)

        setContent {
            var appThemeMode by remember {
                mutableStateOf(prefs.getString("key_app_theme_mode", "SYSTEM") ?: "SYSTEM")
            }

            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "key_app_theme_mode") {
                        appThemeMode = p.getString(key, "SYSTEM") ?: "SYSTEM"
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
                    mutableStateOf(prefs.getBoolean("key_app_lock_enabled", false) && AppLockState.isLocked)
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (!prefs.getBoolean("key_app_lock_enabled", false)) return@LifecycleEventObserver
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
                        onAccept = {
                            prefs.edit().putBoolean("key_disclosure_accepted", true).apply()
                            isDisclosureAccepted = true
                            checkAndRequestPermissions()
                        },
                        onDecline = { finish() }
                    )
                } else {
                    LaunchedEffect(isAppLocked) {
                        if (!isAppLocked) checkAndRequestPermissions()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        AppNavigation(viewModel = viewModel, isAppLocked = isAppLocked)

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

        val status = BiometricManager.from(context).canAuthenticate(authenticators)
        when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> { errorMessage = null }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
                    .edit { putBoolean("key_app_lock_enabled", false) }
                Toast.makeText(context, "App Lock turned off: No device lock setup.", Toast.LENGTH_LONG).show()
                currentOnUnlock()
                return
            }
            else -> {
                errorMessage = "Biometric hardware unavailable. Please use PIN/Password."
                return
            }
        }

        isPrompting = true
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
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

@Composable
fun ProminentDisclosureScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Privacy First Tracker", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Expense Tracker optionally uses SMS and Notification access to auto-detect bank transactions.\n\n" +
                        "Data is processed securely on your device. User-initiated backups or CSV exports may leave the device depending on where you save them.",
                fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onAccept, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)
            ) { Text("I Understand & Agree", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onDecline) {
                Text("Decline & Exit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    isAppLocked: Boolean = false
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { (it * 0.14f).toInt() } +
                    scaleIn(animationSpec = tween(260, easing = FastOutSlowInEasing), initialScale = 0.965f)
        },
        exitTransition = {
            fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    slideOutHorizontally(animationSpec = tween(240, easing = FastOutSlowInEasing)) { -(it * 0.06f).toInt() } +
                    scaleOut(animationSpec = tween(240, easing = FastOutSlowInEasing), targetScale = 0.975f)
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(animationSpec = tween(240, easing = FastOutSlowInEasing)) { -(it * 0.06f).toInt() } +
                    scaleIn(animationSpec = tween(240, easing = FastOutSlowInEasing), initialScale = 0.975f)
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    slideOutHorizontally(animationSpec = tween(250, easing = FastOutSlowInEasing)) { (it * 0.14f).toInt() } +
                    scaleOut(animationSpec = tween(250, easing = FastOutSlowInEasing), targetScale = 0.965f)
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

/** Safely unwraps a [ContextWrapper] chain to find the [FragmentActivity], returning null
 *  if the context is not backed by one (e.g. TintContextWrapper in Compose previews). */
fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
