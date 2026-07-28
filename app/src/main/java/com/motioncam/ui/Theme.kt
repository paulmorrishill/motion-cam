package com.motioncam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0E0E0E),
    surface = Color(0xFF1A1A1A),
    error = Color(0xFFEF5350)
)

@Composable
fun MotionCamTheme(content: @Composable () -> Unit) {
    // Always dark: this is a camera app that mostly shows a black/preview screen.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkColors, content = content)
}
