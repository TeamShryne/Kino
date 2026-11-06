package com.teamshryne.kino.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7155D9)
private val Canvas = Color(0xFFFAF9FC)
private val Ink = Color(0xFF25232B)
private val Muted = Color(0xFF777480)
private val Line = Color(0xFFE9E7ED)

@Composable
fun KinoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFB9A7FF), onPrimary = Color(0xFF26105E),
        background = Color(0xFF141218), surface = Color(0xFF1D1A22),
        surfaceVariant = Color(0xFF2B2731), onSurface = Color(0xFFEAE6F0),
        onSurfaceVariant = Color(0xFFB5AEBE), outline = Color(0xFF403B47),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White,
        background = Canvas, surface = Color.White,
        surfaceVariant = Color(0xFFF1EFF5), onSurface = Ink,
        onSurfaceVariant = Muted, outline = Line,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
