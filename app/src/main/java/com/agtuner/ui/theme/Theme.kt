package com.agtuner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = TunerColors.Primary,
    onPrimary = TunerColors.LightBackground,
    secondary = TunerColors.Secondary,
    onSecondary = TunerColors.LightBackground,
    background = TunerColors.LightBackground,
    onBackground = TunerColors.LightOnBackground,
    surface = TunerColors.LightSurface,
    onSurface = TunerColors.LightOnSurface,
    error = TunerColors.LightTooLow,
    onError = TunerColors.LightBackground
)

private val DarkColorScheme = darkColorScheme(
    primary = TunerColors.PrimaryDark,
    onPrimary = TunerColors.DarkBackground,
    secondary = TunerColors.SecondaryDark,
    onSecondary = TunerColors.DarkBackground,
    background = TunerColors.DarkBackground,
    onBackground = TunerColors.DarkOnBackground,
    surface = TunerColors.DarkSurface,
    onSurface = TunerColors.DarkOnSurface,
    error = TunerColors.DarkTooLow,
    onError = TunerColors.DarkBackground
)

@Composable
fun TunerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TunerTypography,
        content = content
    )
}

/**
 * Get tuning state color based on current theme.
 */
@Composable
fun tuningStateColor(
    isInTune: Boolean,
    isTooLow: Boolean,
    isTooHigh: Boolean,
    isNoSignal: Boolean
): androidx.compose.ui.graphics.Color {
    val isDark = isSystemInDarkTheme()

    return when {
        isInTune -> if (isDark) TunerColors.DarkInTune else TunerColors.LightInTune
        isTooLow -> if (isDark) TunerColors.DarkTooLow else TunerColors.LightTooLow
        isTooHigh -> if (isDark) TunerColors.DarkTooHigh else TunerColors.LightTooHigh
        else -> if (isDark) TunerColors.DarkNoSignal else TunerColors.LightNoSignal
    }
}
