package com.expensetracker.offline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

object AppRadius {
    val extraSmall = 8.dp
    val extraLarge = 32.dp
    val large = 24.dp
    val medium = 16.dp
    val small = 12.dp
    val pill = 100.dp
}

object AppSpacing {
    val compact = 8.dp
    val itemGap = 12.dp
    val screenPadding = 16.dp
    val sectionGap = 16.dp
    val cardPadding = 20.dp
    val spacious = 24.dp
}

/**
 * True when the app is currently drawn with its dark palette. This reads the theme the app actually applied
 * (Settings -> Theme can force Light or Dark regardless of the phone), not the phone's system setting, so status
 * colors always match the surface they sit on.
 */
@Composable
fun isAppDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

object AppColors {
    val positive @Composable get() = if (isAppDarkTheme()) Color(0xFF10B981) else Color(0xFF059669)
    val positiveContainer @Composable get() = positive.copy(alpha = 0.10f)

    // Alias used by onboarding and income badges
    val IncomeGreen @Composable get() = positive

    val warning @Composable get() = if (isAppDarkTheme()) Color(0xFFF59E0B) else Color(0xFFD97706)
    val warningContainer @Composable get() = warning.copy(alpha = 0.10f)

    val negative @Composable get() = if (isAppDarkTheme()) ExpenseRed else LightExpenseRed
    val negativeContainer @Composable get() = negative.copy(alpha = 0.10f)

    val info @Composable get() = if (isAppDarkTheme()) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
}
