package com.teamshryne.kino.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teamshryne.kino.ui.components.SettingHeading
import com.teamshryne.kino.ui.components.SettingNote
import com.teamshryne.kino.ui.components.SettingSwitch

private data class SettingEntry(val id: String, val name: String, val description: String, val icon: ImageVector)

@Composable
fun SettingsScreen(
    page: String?,
    dark: Boolean,
    showToolDetails: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onToolDetailChange: (Boolean) -> Unit,
) {
    val entries = listOf(
        SettingEntry("general", "General", "App behavior and defaults", Icons.Rounded.Settings),
        SettingEntry("appearance", "Appearance", "Theme and display", Icons.Rounded.Palette),
        SettingEntry("chat", "Chat", "Messages and tool activity", Icons.Rounded.AutoAwesome),
        SettingEntry("provider", "Provider", "DeepSeek connection", Icons.Rounded.SmartToy),
        SettingEntry("storage", "Data & storage", "Local conversations", Icons.Rounded.Storage),
        SettingEntry("about", "About Kino", "Version and credits", Icons.Rounded.Info),
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 18.dp, top = 11.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != null) IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(if (page == null) "Settings" else entries.firstOrNull { it.id == page }?.name ?: "Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (page == null) Text("Make Kino feel like yours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (page) {
            null -> LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.id) },
                        shape = RoundedCornerShape(17.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            "appearance" -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SettingHeading("Theme")
                SettingSwitch("Dark theme", "Use Kino in a darker palette", dark, onThemeChange)
                SettingNote("System theme support and additional palettes can be added as Kino grows.")
            }
            "chat" -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SettingHeading("Conversation")
                SettingSwitch("Expand tool details", "Show tool activity details when opening a chat", showToolDetails, onToolDetailChange)
                SettingNote("Tool calls are stored with their conversation and appear inline in the chat timeline.")
            }
            "provider" -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SettingHeading("Chat provider")
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("DeepSeek", style = MaterialTheme.typography.titleSmall)
                            Text("Connection setup is coming next", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SettingNote("This screen is a placeholder. No sign-in or credential capture is implemented yet.")
            }
            "general" -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SettingHeading("General")
                SettingNote("Kino is a private chat space for quick conversations and lightweight agent work. Chats stay on this device.")
            }
            "storage" -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SettingHeading("On-device storage")
                SettingNote("Kino saves conversations and tool activity in its local database. Clearing app data removes them.")
            }
            "about" -> Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SettingHeading("Kino 0.1.0")
                SettingNote("An Android chat app by TeamShryne. Interface direction inspired by T3 Code Android by Shrawan-13.")
            }
        }
    }
}
