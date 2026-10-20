package com.expensetracker.offline.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Complete Monochromatic Zinc Dark Palette — overrides every M3 slot so zero default purple/pink leaks
private val DarkColorScheme = darkColorScheme(
    primary = DarkTextPrimary,
    onPrimary = DarkBackground,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkTextPrimary,
    inversePrimary = LightTextPrimary,
    secondary = DarkTextPrimary,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkTextPrimary,
    tertiary = GreenSecondary,
    onTertiary = DarkBackground,
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = LightSurface,
    inverseOnSurface = LightTextPrimary,
    error = ExpenseRed,
    onError = Color.White,
    errorContainer = Color(0xFF3B1219),
    onErrorContainer = Color(0xFFFCA5A5),
    outline = Color(0xFF52525B),
    outlineVariant = DarkOutline,
    scrim = Color(0xCC000000),
    surfaceBright = Color(0xFF27272A),
    surfaceDim = DarkBackground,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF3F3F46)
)

// Complete Monochromatic Zinc Light Palette — overrides every M3 slot so zero default purple/lavender leaks
private val LightColorScheme = lightColorScheme(
    primary = LightTextPrimary,
    onPrimary = LightSurface,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightTextPrimary,
    inversePrimary = DarkTextPrimary,
    secondary = LightTextPrimary,
    onSecondary = LightSurface,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightTextPrimary,
    tertiary = GreenPrimary,
    onTertiary = LightSurface,
    tertiaryContainer = LightSurfaceVariant,
    onTertiaryContainer = LightTextPrimary,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkTextPrimary,
    error = ExpenseRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    outline = Color(0xFFA1A1AA),
    outlineVariant = LightOutline,
    scrim = Color(0x66000000),
    surfaceBright = LightSurface,
    surfaceDim = Color(0xFFF4F4F5),
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = Color(0xFFE4E4E7),
    surfaceContainerHighest = Color(0xFFD4D4D8)
)

@Composable
fun ExpenseTrackerTheme(
    themeMode: String = "SYSTEM",
    darkTheme: Boolean = when (themeMode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val bgArgb = colorScheme.background.toArgb()
                window.decorView.setBackgroundColor(bgArgb)
                window.setBackgroundDrawable(ColorDrawable(bgArgb))
                window.statusBarColor = bgArgb
                window.navigationBarColor = bgArgb
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}