package com.example.scoreapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ScoreColorScheme = lightColorScheme(
    primary = Tokens.Accent,
    onPrimary = Tokens.AccentFg,
    background = Tokens.BgPage,
    onBackground = Tokens.Text1,
    surface = Tokens.Surface,
    onSurface = Tokens.Text1,
    surfaceVariant = Tokens.Surface2,
    onSurfaceVariant = Tokens.Text2,
    outline = Tokens.Line,
    error = Tokens.DangerFg,
    onError = Color.White,
    errorContainer = Tokens.DangerBg,
    onErrorContainer = Tokens.DangerFg,
)

/**
 * 应用主题。刻意不启用动态取色，保证在任何设备上都是同一套纸面配色。
 */
@Composable
fun ScoreAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScoreColorScheme,
        content = content,
    )
}
