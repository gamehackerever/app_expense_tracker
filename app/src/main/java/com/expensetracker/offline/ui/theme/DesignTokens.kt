package com.expensetracker.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppRadius {
    val large = 24.dp
    val medium = 16.dp
    val small = 12.dp
    val pill = 100.dp
}

object AppSpacing {
    val screenPadding = 16.dp
    val sectionGap = 16.dp
    val cardPadding = 20.dp
}

object AppColors {
    val positive @Composable get() = if (isSystemInDarkTheme()) Color(0xFF10B981) else Color(0xFF059669)
    val positiveContainer @Composable get() = positive.copy(alpha = 0.10f)

    val warning @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF59E0B) else Color(0xFFD97706)
    val warningContainer @Composable get() = warning.copy(alpha = 0.10f)

    val negative @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEF4444) else Color(0xFFDC2626)
    val negativeContainer @Composable get() = negative.copy(alpha = 0.10f)

    val info = Color(0xFF2A5DB0)
}