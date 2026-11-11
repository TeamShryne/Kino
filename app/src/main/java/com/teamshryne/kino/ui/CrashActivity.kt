package com.teamshryne.kino.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

class CrashActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LOG_NAME = "crash_log_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logName = intent.getStringExtra(EXTRA_LOG_NAME).orEmpty()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashScreen(logName)
                }
            }
        }
    }
}

@Composable
private fun CrashScreen(logName: String) {
    val context = LocalContext.current
    val logFile = remember(logName) {
        if (logName.isBlank()) null else File(KinoCrashHandler.crashDirectory(context), logName)
    }
    val preview = remember(logFile) {
        runCatching { logFile?.readText()?.take(1200) }.getOrNull().orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Kino crashed", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The app stopped unexpectedly. You can share the crash log to help diagnose the problem. Your chats and settings were not changed by this screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (logFile != null && logFile.exists()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    preview.ifBlank { "No log preview available." },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("Crash log not found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val uri = logFile?.let { file ->
                        if (!file.exists()) null else runCatching {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }.getOrNull()
                    }
                    if (uri == null) {
                        Toast.makeText(context, "Crash log is unavailable.", Toast.LENGTH_SHORT).show()
                    } else {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "Kino crash log", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share crash log"))
                    }
                },
                enabled = logFile?.exists() == true,
            ) { Text("Share log") }
            OutlinedButton(onClick = { (context as? Activity)?.finishAffinity() }) { Text("Close") }
        }
    }
}
