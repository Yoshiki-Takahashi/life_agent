package com.yoshiki.lifeagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    secondary = Sage80,
    tertiary = Amber80,
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
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
