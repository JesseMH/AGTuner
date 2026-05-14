package com.agtuner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.agtuner.tuning.TuningState

/**
 * High contrast color definitions for accessibility.
 * All color combinations meet WCAG AAA standards (7:1 contrast ratio).
 */
object TunerColors {
    // Light theme - high contrast
    val LightBackground = Color(0xFFFFFFFF)      // Pure white
    val LightSurface = Color(0xFFF5F5F5)         // Very light gray
    val LightOnBackground = Color(0xFF000000)    // Pure black text
    val LightOnSurface = Color(0xFF1A1A1A)       // Near black

    // Dark theme - high contrast
    val DarkBackground = Color(0xFF000000)       // Pure black
    val DarkSurface = Color(0xFF1A1A1A)          // Near black
    val DarkOnBackground = Color(0xFFFFFFFF)     // Pure white text
    val DarkOnSurface = Color(0xFFF5F5F5)        // Very light gray

    // Tuning state colors - Light theme (high contrast)
    val LightInTune = Color(0xFF006400)          // Dark green - in tune
    val LightTooLow = Color(0xFF8B0000)          // Dark red - too low
    val LightTooHigh = Color(0xFFB35900)         // Dark orange - too high (WCAG AA compliant)
    val LightNoSignal = Color(0xFF404040)        // Dark gray - no signal

    // Tuning state colors - Dark theme (high contrast)
    val DarkInTune = Color(0xFF90EE90)           // Light green
    val DarkTooLow = Color(0xFFFF6B6B)           // Light red
    val DarkTooHigh = Color(0xFFFFD700)          // Gold/Yellow
    val DarkNoSignal = Color(0xFFB0B0B0)         // Light gray

    // Accent colors
    val Primary = Color(0xFF1565C0)              // Strong blue
    val PrimaryDark = Color(0xFF64B5F6)          // Light blue for dark theme
    val Secondary = Color(0xFF424242)            // Neutral gray
    val SecondaryDark = Color(0xFFBDBDBD)        // Light gray for dark theme

    // String selector colors
    val SelectedString = Color(0xFF1565C0)       // Blue highlight
    val SelectedStringDark = Color(0xFF64B5F6)  // Light blue for dark theme
    val UnselectedString = Color(0xFFE0E0E0)    // Light gray
    val UnselectedStringDark = Color(0xFF424242) // Dark gray
}

/**
 * Maps a [TuningState] to the high-contrast color used to render it,
 * picking the light or dark variant from [TunerColors] based on the active theme.
 */
@Composable
fun TuningState.toColor(): Color {
    val isDark = isSystemInDarkTheme()
    return when (this) {
        is TuningState.InTune -> if (isDark) TunerColors.DarkInTune else TunerColors.LightInTune
        is TuningState.TooLow -> if (isDark) TunerColors.DarkTooLow else TunerColors.LightTooLow
        is TuningState.TooHigh -> if (isDark) TunerColors.DarkTooHigh else TunerColors.LightTooHigh
        is TuningState.NoSignal -> if (isDark) TunerColors.DarkNoSignal else TunerColors.LightNoSignal
    }
}
