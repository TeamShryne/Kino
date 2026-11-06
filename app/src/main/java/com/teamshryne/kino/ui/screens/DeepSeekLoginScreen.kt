package com.teamshryne.kino.ui.screens

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.foundation.shape.RoundedCornerShape

private const val DEEPSEEK_CHAT_URL = "https://chat.deepseek.com/"

@Composable
fun DeepSeekLoginScreen(
    connected: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text("Connect DeepSeek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sign in to DeepSeek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Kino opens chat.deepseek.com in your default browser, with its normal browser features and security checks.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Before you continue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("• Enter your password only on DeepSeek’s page.")
                    Text("• The browser keeps its sign-in session separate from Kino.")
                    Text("• Kino cannot read the browser’s sign-in session from this screen, so signing in here will not connect Kino automatically.")
                    Text("• Kino can connect once an app-supported sign-in or session handoff is available.")
                }
            }

            if (connected) {
                Text("Kino is already connected to DeepSeek.", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    CustomTabsIntent.Builder()
                        .build()
                        .launchUrl(context, DEEPSEEK_CHAT_URL.toUri())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                Text("Open DeepSeek in browser", modifier = Modifier.padding(start = 9.dp))
            }
        }
    }
}
