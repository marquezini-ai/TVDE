package com.daniel.tvdeinsight.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
    AUTOMATIC("Mesmo do smartphone"),
    LIGHT("Claro"),
    DARK("Escuro")
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF001B44),
    primaryContainer = Color(0xFF173A70),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFFB6C8E6),
    secondaryContainer = Color(0xFF263650),
    tertiary = Color(0xFFFFC47C),
    background = Color(0xFF0A1020),
    surface = Color(0xFF111A2E),
    surfaceVariant = Color(0xFF1B2941),
    onBackground = Color(0xFFE6ECF8),
    onSurface = Color(0xFFE6ECF8),
    onSurfaceVariant = Color(0xFFC2CCDD),
    outline = Color(0xFF53627A)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF245AA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF001B44),
    secondary = Color(0xFF4F6078),
    secondaryContainer = Color(0xFFDCE5F4),
    tertiary = Color(0xFF9A5B00),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAF0F8),
    onBackground = Color(0xFF18202C),
    onSurface = Color(0xFF18202C),
    onSurfaceVariant = Color(0xFF4F5D70),
    outline = Color(0xFF728096)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Tipografia compacta, de alto contraste e consistente em todas as abas. */
private val TvdeInsightTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun TVDEInsightTheme(
    themeMode: ThemeMode = ThemeMode.AUTOMATIC,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.AUTOMATIC -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        (view.context as? Activity)?.window?.let { window ->
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = TvdeInsightTypography,
        content = content
    )
}
