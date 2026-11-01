package com.yoshiki.lifeagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Green80 = Color(0xFF8BD8CE)
val Sage80 = Color(0xFFB9CCBE)
val Amber80 = Color(0xFFFFD18B)
val Green40 = Color(0xFF006B63)
val Sage40 = Color(0xFF496455)
val Amber40 = Color(0xFF855300)
val Paper = Color(0xFFF5F7F7)
val Ink = Color(0xFF18363D)

// Set of Material typography styles to start with
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
)

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF003832),
    primaryContainer = Color(0xFF253E3B),
    onPrimaryContainer = Color(0xFFE5F3F3),
    background = Color(0xFF131C20),
    surface = Color(0xFF131C20),
    onBackground = Color(0xFFE5F3F3),
    onSurface = Color(0xFFE5F3F3),
    onSurfaceVariant = Color(0xFFB2C6CB),
    surfaceContainer = Color(0xFF1C282D),
    surfaceContainerLow = Color(0xFF1C282D),
    secondary = Sage80,
    tertiary = Amber80,
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEEA),
    onPrimaryContainer = Ink,
    onSurfaceVariant = Color(0xFF52666D),
    surfaceContainer = Color(0xFFEAF0EF),
    surfaceContainerLow = Color.White,
    secondary = Sage40,
    tertiary = Amber40,
    background = Paper,
    surface = Paper,
    onBackground = Ink,
    onSurface = Ink,
)

@Composable
fun LifeAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
