package com.teamshryne.kino.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity

private val Accent = Color(0xFF1B4ED8)
private val Canvas = Color(0xFFFCFCFC)
private val Ink = Color(0xFF27272A)
private val Muted = Color(0xFF71717B)
private val Line = Color(0xFFE4E4E7)

@Composable
fun KinoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF7FA8FF), onPrimary = Color(0xFF07162E),
        background = Color(0xFF0A0A0A), surface = Color(0xFF111111),
        surfaceVariant = Color(0xFF1F1F23), onSurface = Color(0xFFF5F5F5),
        onSurfaceVariant = Color(0xFFC8C8C8), outline = Color(0xFF2A2A2E),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White,
        background = Canvas, surface = Color.White,
        surfaceVariant = Color(0xFFF4F4F5), onSurface = Ink,
        onSurfaceVariant = Muted, outline = Line,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
