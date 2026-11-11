package com.teamshryne.kino

import android.os.Bundle
import android.os.Build
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teamshryne.kino.ui.KinoViewModel
import com.teamshryne.kino.ui.KinoApp
import com.teamshryne.kino.ui.KinoGenerationService
import com.teamshryne.kino.ui.theme.KinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KinoGenerationService.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WebView.setDataDirectorySuffix("kino-browser")
        enableEdgeToEdge()
        setContent {
            val model: KinoViewModel = viewModel()
            val themeMode by model.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            KinoTheme(dark) { KinoApp(model) }
        }
    }
}
