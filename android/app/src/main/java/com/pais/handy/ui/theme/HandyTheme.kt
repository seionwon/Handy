package com.pais.handy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pais.handy.data.ThemeMode

// Translated directly from Handy's src/styles/theme.css palette
val HandyPinkPrimary = Color(0xFFDA5893)
val HandyPinkSoftLight = Color(0xFFFAA2CA)
val HandyPinkSoftDark = Color(0xFFF28CBB)
val HandyPlumStroke = Color(0xFF382731)
val HandyLightBg = Color(0xFFFBFBFB)
val HandyLightSurface = Color(0xFFFFFFFF)
val HandyLightSurfaceVariant = Color(0xFFF2F0F1)
val HandyDarkBg = Color(0xFF2C2B29)
val HandyDarkSurface = Color(0xFF353431)
val HandyDarkSurfaceVariant = Color(0xFF42403C)
val HandyMossLight = Color(0xFF2F7D5B)
val HandyMossDark = Color(0xFF86C9A4)
val HandyWarningLight = Color(0xFFD97706)
val HandyWarningDark = Color(0xFFFBBF24)
val HandyErrorLight = Color(0xFFDC2626)
val HandyErrorDark = Color(0xFFF87171)

private val HandyLightColors = lightColorScheme(
    primary = HandyPinkPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4EF),
    onPrimaryContainer = HandyPlumStroke,
    secondary = HandyMossLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F2EA),
    onSecondaryContainer = Color(0xFF194532),
    tertiary = HandyWarningLight,
    background = HandyLightBg,
    onBackground = Color(0xFF0F0F0F),
    surface = HandyLightSurface,
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = HandyLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5F5D5F),
    outline = Color(0xFFD6D3D5),
    outlineVariant = Color(0xFFE7E5E6),
    error = HandyErrorLight,
    onError = Color.White,
)

private val HandyDarkColors = darkColorScheme(
    primary = HandyPinkSoftDark,
    onPrimary = Color(0xFF361224),
    primaryContainer = Color(0xFF54283D),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = HandyMossDark,
    onSecondary = Color(0xFF123726),
    secondaryContainer = Color(0xFF244736),
    onSecondaryContainer = Color(0xFFD2F5E1),
    tertiary = HandyWarningDark,
    background = HandyDarkBg,
    onBackground = Color(0xFFFBFBFB),
    surface = HandyDarkSurface,
    onSurface = Color(0xFFFBFBFB),
    surfaceVariant = HandyDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC5C2BC),
    outline = Color(0xFF63605A),
    outlineVariant = Color(0xFF474540),
    error = HandyErrorDark,
    onError = Color(0xFF4A1010),
)

@Composable
fun HandyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) HandyDarkColors else HandyLightColors,
        content = content,
    )
}
