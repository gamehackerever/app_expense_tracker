package com.expensetracker.offline.ui.debug

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ExpenseTrackerApp
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.interceptor.CaptureDispatcher
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.ui.components.AppDialog
import com.expensetracker.offline.ui.components.CurrencyFormat
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.AppSpacing
import com.expensetracker.offline.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTestingScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val app = context.applicationContext as ExpenseTrackerApp

    fun showSnack(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    var rawInput by remember {
        mutableStateOf("Rs 450.00 debited from a/c **1234 on 18-Sep to Starbucks using UPI Ref 9283748291. Avl Bal: INR 12,000")
    }
    var senderInput by remember { mutableStateOf("VM-HDFCBK") }
    var executionStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<TestCaseResult>>(emptyList()) }
    var showConfirmWipeDialog by remember { mutableStateOf(false) }

    val liveParse = remember(rawInput, senderInput) {
        FinancialParser.parse(rawInput, senderInput)
    }

    val predictedCategory = remember(liveParse) {
        if (liveParse.isFullyParsed) {
            CategoryClassifier.classifySync(liveParse.payee ?: "", liveParse.rawText)
        } else {
            "Uncategorized"
        }
    }

    if (showConfirmWipeDialog) {
        AppDialog(
            title = "Clear all transactions?",
            onDismiss = { showConfirmWipeDialog = false },
            confirmText = "Delete All",
            destructive = true,
            onConfirm = {
                showConfirmWipeDialog = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.clearAllTransactions()
                context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)
                    .edit().remove("key_latest_bank_balance").apply()
                showSnack("All transactions & balance baselines cleared")
            }
        ) {
            Text(
                "This permanently deletes all transactions and balance baselines on this device. This cannot be undone.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Surface(
                    shape = RoundedCornerShape(AppRadius.pill),
                    color = MaterialTheme.colorScheme.inverseSurface,
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
                            "Offline Engine Testing",
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ── Automated Self-Test Suite Card ─────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                shape = RoundedCornerShape(AppRadius.medium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Automated Verification Suite",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Parser, Classifier, Necessities, OCR Split & Cycle Math",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val ran = AutomatedTestSuite.runAll(context)
                                testResults = ran
                                val passed = ran.count { it.passed }
                                showSnack("Passed $passed / ${ran.size} automated checks")
                            },
                            shape = RoundedCornerShape(AppRadius.pill),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                if (testResults.isEmpty()) "Run All Tests" else "Re-run (${testResults.count { it.passed }}/${testResults.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (testResults.isNotEmpty()) {
                        val passedCount = testResults.count { it.passed }
                        val totalMicros = testResults.sumOf { it.durationMicros }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Text(
                            text = "$passedCount of ${testResults.size} passed in ${String.format(java.util.Locale.US, "%.2f", totalMicros / 1000.0)} ms",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (passedCount == testResults.size) AppColors.positive else MaterialTheme.colorScheme.error
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            testResults.forEach { res ->
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.small),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = if (res.passed) AppColors.positive else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "${res.suite} · ${res.name}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                res.detail,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            "${res.durationMicros}µs",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                "Simulate Inputs (Zero Network)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = senderInput,
                onValueChange = { senderInput = it },
                label = { Text("Sender / Package Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.small)
            )

            OutlinedTextField(
                value = rawInput,
                onValueChange = { rawInput = it },
                label = { Text("Raw Message Content") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = RoundedCornerShape(AppRadius.small)
            )

            val statusColor = when {
                liveParse.isFullyParsed -> AppColors.positive
                liveParse.isFinancial -> AppColors.warning
                else -> MaterialTheme.colorScheme.error
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.medium),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Live Parsing Preview", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = statusColor)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("• Financial: ${liveParse.isFinancial}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Amount: ${liveParse.amount?.let { CurrencyFormat.withSymbol(it, 2) } ?: "None"}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Extracted Balance: ${liveParse.balance?.let { CurrencyFormat.withSymbol(it, 2) } ?: "None"}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Payee: ${liveParse.payee ?: "None"}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Offline ML Category: $predictedCategory", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (rawInput.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                CaptureDispatcher.dispatch(app, liveParse, senderInput, System.currentTimeMillis(), TransactionSource.SMS)
                            }
                            executionStatus = "Dispatched to SMS Interceptor Pipeline!"
                            showSnack("Simulated SMS Intercepted")
                        }
                    },
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Trigger SMS", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        if (rawInput.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                CaptureDispatcher.dispatch(app, liveParse, senderInput, System.currentTimeMillis(), TransactionSource.NOTIFICATION)
                            }
                            executionStatus = "Dispatched to Notification Interceptor Pipeline!"
                            showSnack("Simulated Notification Intercepted")
                        }
                    },
                    shape = RoundedCornerShape(AppRadius.small),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Trigger Notif", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            executionStatus?.let { status ->
                Surface(
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    shape = RoundedCornerShape(AppRadius.small),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showConfirmWipeDialog = true
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear All Test Data", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text("Test: Ghost Transaction Engine", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PresetButton(
                    title = "Step 1: Set Baseline Balance (₹5,000)",
                    sample = "Rs 100.00 debited from a/c **1234 on 18-Sep to ChaiWala. Avl Bal: INR 5,000.00"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    rawInput = it
                    senderInput = "VM-HDFCBK"
                }

                PresetButton(
                    title = "Step 2: Trigger Missing ₹1,000 Expense",
                    sample = "Rs 200.00 debited from a/c **1234 on 18-Sep to Zomato. Avl Bal: INR 3,800.00"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    rawInput = it
                    senderInput = "VM-HDFCBK"
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text("Standard Presets (Tap to Load):", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PresetButton(
                    title = "Starbucks SMS (₹450 -> Food & Dining)",
                    sample = "Rs 450.00 debited from a/c **1234 on 18-Sep-26 to Starbucks using UPI Ref 9283748291. Avl Bal: INR 12,000"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    rawInput = it
                    senderInput = "VM-HDFCBK"
                }

                PresetButton(
                    title = "Uber Cab Notif (₹220 -> Transport)",
                    sample = "Google Pay: Paid ₹220.00 to Uber India on UPI Ref 1928374628"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    rawInput = it
                    senderInput = "com.google.android.apps.nbu.paisa.user"
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun PresetButton(title: String, sample: String, onSelect: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.small))
            .clickable { onSelect(sample) },
        shape = RoundedCornerShape(AppRadius.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(sample, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp, maxLines = 2)
        }
    }
}