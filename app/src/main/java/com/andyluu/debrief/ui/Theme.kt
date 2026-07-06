package com.andyluu.debrief.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF173D33),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8FF8A),
    onPrimaryContainer = Color(0xFF102A23),
    secondary = Color(0xFF4F635D),
    background = Color(0xFFF7F7F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8ECE8),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7D8CC),
    primaryContainer = Color(0xFF31584C),
    secondary = Color(0xFFB7CBC3),
    background = Color(0xFF101512),
    surface = Color(0xFF171D1A),
)

@Composable
fun DebriefTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
