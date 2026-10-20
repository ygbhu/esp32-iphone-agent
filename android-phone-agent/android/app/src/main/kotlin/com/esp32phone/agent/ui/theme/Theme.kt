package com.esp32phone.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    secondary = Color(0xFF4E6C50),
    background = Color(0xFFF7F8F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCC65),
    secondary = Color(0xFFB0BEA9),
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
