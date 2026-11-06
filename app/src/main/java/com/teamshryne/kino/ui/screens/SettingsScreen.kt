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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teamshryne.kino.data.provider.ProviderModel
import com.teamshryne.kino.data.provider.ProviderSession

private data class SettingEntry(val id: String, val title: String, val icon: ImageVector, val section: String)

private val settingEntries = listOf(
    SettingEntry("general", "General", Icons.Rounded.Tune, "APP"),
    SettingEntry("appearance", "Appearance", Icons.Rounded.Palette, "APP"),
    SettingEntry("chat", "Chat", Icons.Rounded.ChatBubbleOutline, "CHAT"),
    SettingEntry("provider", "Provider", Icons.Rounded.SmartToy, "CHAT"),
    SettingEntry("storage", "Data & storage", Icons.Rounded.Storage, "OTHER"),
    SettingEntry("about", "About Kino", Icons.Rounded.Info, "OTHER"),
)

@Composable
fun SettingsScreen(
    page: String?,
    dark: Boolean,
    showToolDetails: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onToolDetailChange: (Boolean) -> Unit,
    deepSeekConnected: Boolean,
    models: List<ProviderModel>,
    selectedModel: String,
    thinkingEnabled: Boolean,
    searchEnabled: Boolean,
    providerError: String?,
    providerBusy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshModels: () -> Unit,
    onModelChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSearchChange: (Boolean) -> Unit,
    remoteSessions: List<ProviderSession>,
    onRefreshSessions: () -> Unit,
    onOpenRemoteSession: (ProviderSession) -> Unit,
    onPinRemoteSession: (ProviderSession, Boolean) -> Unit,
    onDeleteRemoteSession: (ProviderSession) -> Unit,
    onRenameRemoteSession: (ProviderSession, String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 7.dp, end = 18.dp, top = 8.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page != null) IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text(
                if (page == null) "Settings" else settingEntries.firstOrNull { it.id == page }?.title ?: "Settings",
                style = if (page == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        if (page == null) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search settings") },
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            val visible = settingEntries.filter { it.title.contains(search, ignoreCase = true) }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                visible.groupBy { it.section }.forEach { (section, entries) ->
                    item(key = "section-$section") {
                        Text(section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.1.sp)
                    }
                    item(key = "group-$section") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column {
                                entries.forEachIndexed { index, entry ->
                                    SettingsEntryRow(entry, onOpen)
                                    if (index < entries.lastIndex) HorizontalDivider(
                                        Modifier.padding(start = 47.dp, end = 14.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            SettingsDetail(
                page = page,
                dark = dark,
                showToolDetails = showToolDetails,
                onThemeChange = onThemeChange,
                onToolDetailChange = onToolDetailChange,
                deepSeekConnected = deepSeekConnected,
                models = models,
                selectedModel = selectedModel,
                thinkingEnabled = thinkingEnabled,
                searchEnabled = searchEnabled,
                providerError = providerError,
                providerBusy = providerBusy,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onRefreshModels = onRefreshModels,
                onModelChange = onModelChange,
                onThinkingChange = onThinkingChange,
                onSearchChange = onSearchChange,
                remoteSessions = remoteSessions,
                onRefreshSessions = onRefreshSessions,
                onOpenRemoteSession = onOpenRemoteSession,
                onPinRemoteSession = onPinRemoteSession,
                onDeleteRemoteSession = onDeleteRemoteSession,
                onRenameRemoteSession = onRenameRemoteSession,
            )
        }
    }
}

@Composable
private fun SettingsEntryRow(entry: SettingEntry, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(entry.id) }.padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(entry.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ArrowForwardIos, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SettingsDetail(
    page: String,
    dark: Boolean,
    showToolDetails: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onToolDetailChange: (Boolean) -> Unit,
    deepSeekConnected: Boolean,
    models: List<ProviderModel>,
    selectedModel: String,
    thinkingEnabled: Boolean,
    searchEnabled: Boolean,
    providerError: String?,
    providerBusy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshModels: () -> Unit,
    onModelChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSearchChange: (Boolean) -> Unit,
    remoteSessions: List<ProviderSession>,
    onRefreshSessions: () -> Unit,
    onOpenRemoteSession: (ProviderSession) -> Unit,
    onPinRemoteSession: (ProviderSession, Boolean) -> Unit,
    onDeleteRemoteSession: (ProviderSession) -> Unit,
    onRenameRemoteSession: (ProviderSession, String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (page) {
            "appearance" -> SettingSurface {
                SettingToggle("Dark theme", dark, onThemeChange)
            }
            "chat" -> SettingSurface {
                SettingToggle("Expand tool details", showToolDetails, onToolDetailChange)
            }
            "provider" -> ProviderSettings(
                deepSeekConnected, models, selectedModel, thinkingEnabled, searchEnabled, providerError, providerBusy,
                onConnect, onDisconnect, onRefreshModels, onModelChange, onThinkingChange, onSearchChange,
                remoteSessions, onRefreshSessions, onOpenRemoteSession, onPinRemoteSession, onDeleteRemoteSession,
                onRenameRemoteSession,
            )
            "storage" -> SettingSurface {
                SettingValue(Icons.Rounded.Storage, "Conversation history", "On this device")
                HorizontalDivider(Modifier.padding(start = 46.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                SettingValue(Icons.Rounded.FolderOpen, "Tool activity", "Saved with chats")
            }
            "general" -> SettingSurface {
                SettingValue(Icons.Rounded.Tune, "Kino", "Version 0.1.0")
            }
            "about" -> SettingSurface {
                SettingValue(Icons.Rounded.Info, "Kino", "TeamShryne")
            }
        }
    }
}

@Composable
private fun ProviderSettings(
    connected: Boolean,
    models: List<ProviderModel>,
    selectedModel: String,
    thinkingEnabled: Boolean,
    searchEnabled: Boolean,
    error: String?,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onModel: (String) -> Unit,
    onThinking: (Boolean) -> Unit,
    onSearch: (Boolean) -> Unit,
    remoteSessions: List<ProviderSession>,
    onRefreshSessions: () -> Unit,
    onOpenRemoteSession: (ProviderSession) -> Unit,
    onPinRemoteSession: (ProviderSession, Boolean) -> Unit,
    onDeleteRemoteSession: (ProviderSession) -> Unit,
    onRenameRemoteSession: (ProviderSession, String) -> Unit,
) {
    var deleteSession by remember { mutableStateOf<ProviderSession?>(null) }
    var renameSession by remember { mutableStateOf<ProviderSession?>(null) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    deleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteSession = null },
            title = { Text("Delete DeepSeek chat?") },
            text = { Text("This deletes the conversation from DeepSeek and removes its local copy from Kino." ) },
            confirmButton = { TextButton(onClick = { onDeleteRemoteSession(session); deleteSession = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("Cancel") } },
        )
    }
    renameSession?.let { session ->
        AlertDialog(
            onDismissRequest = { renameSession = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameRemoteSession(session, renameValue.trim()); renameSession = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameSession = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingSurface {
                Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 7.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("DeepSeek", style = MaterialTheme.typography.labelLarge)
                        Text(if (connected) "Connected" else "Not connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = if (connected) onDisconnect else onConnect) { Text(if (connected) "Disconnect" else "Connect") }
                }
            }
        }
        if (connected) {
            item {
                SettingSurface {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 4.dp, top = 7.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "Loading" else "Refresh") }
                        }
                        models.forEach { model ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onModel(model.id) }.padding(horizontal = 15.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                    if (model.description.isNotBlank()) Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                androidx.compose.material3.RadioButton(selected = selectedModel == model.id, onClick = { onModel(model.id) })
                            }
                        }
                        if (models.isEmpty()) Text("Connect and refresh to load available models.", Modifier.padding(15.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val selected = models.firstOrNull { it.id == selectedModel }
            if (selected != null && (selected.supportsThinking || selected.supportsSearch)) {
                item {
                    SettingSurface {
                        Column {
                            if (selected.supportsThinking) SettingToggle("Thinking", thinkingEnabled, onThinking)
                            if (selected.supportsSearch) SettingToggle("Web search", searchEnabled, onSearch)
                        }
                    }
                }
            }
            item {
                SettingSurface {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 4.dp, top = 7.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("DeepSeek chats", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRefreshSessions, enabled = !busy) { Text(if (busy) "Loading" else "Sync") }
                        }
                        if (remoteSessions.isEmpty()) {
                            Text("Your DeepSeek chat list will appear here.", Modifier.padding(horizontal = 15.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        remoteSessions.forEach { session ->
                            Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 8.dp, top = 7.dp, bottom = 7.dp)) {
                                Text(session.title.ifBlank { "DeepSeek chat" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(session.modelType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onOpenRemoteSession(session) }, enabled = !busy) { Text("Open") }
                                    TextButton(onClick = { onPinRemoteSession(session, !session.pinned) }, enabled = !busy) { Text(if (session.pinned) "Unpin" else "Pin") }
                                    TextButton(onClick = { renameValue = session.title; renameSession = session }, enabled = !busy) { Text("Rename") }
                                    TextButton(onClick = { deleteSession = session }, enabled = !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (error != null) item {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun SettingSurface(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = { content() })
    }
}

@Composable
private fun SettingToggle(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingValue(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
