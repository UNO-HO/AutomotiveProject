package com.pyeonangil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pyeonangil.engine.RiskLevel

object LevelColors {
    val calm = Color(0xFF2E9E6A)
    val moderate = Color(0xFFE0A100)
    val high = Color(0xFFD9483B)

    fun of(level: RiskLevel): Color = when (level) {
        RiskLevel.CALM -> calm
        RiskLevel.MODERATE -> moderate
        RiskLevel.HIGH -> high
    }
}

private val Dark = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    surface = Color(0xFF15171C),
    background = Color(0xFF0E1014),
    surfaceVariant = Color(0xFF22252C),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FD0),
    surface = Color(0xFFF7F8FA),
    background = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9ECF1),
)

/** 다크/라이트는 시스템 설정을 따른다. */
@Composable
fun PyeonanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
