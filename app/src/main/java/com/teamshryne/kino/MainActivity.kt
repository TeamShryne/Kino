package com.teamshryne.kino

import android.os.Bundle
import android.os.Build
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teamshryne.kino.ui.KinoViewModel
import com.teamshryne.kino.ui.KinoApp
import com.teamshryne.kino.ui.theme.KinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WebView.setDataDirectorySuffix("kino-browser")
        enableEdgeToEdge()
        setContent {
            val model: KinoViewModel = viewModel()
            val dark by model.darkTheme.collectAsState()
            KinoTheme(dark) { KinoApp(model) }
        }
    }
}
