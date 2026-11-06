package com.teamshryne.kino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.teamshryne.kino.ui.screens.ChatScreen
import com.teamshryne.kino.ui.screens.ChatsScreen
import com.teamshryne.kino.ui.screens.SettingsScreen
import com.teamshryne.kino.ui.screens.DeepSeekLoginScreen

private enum class MainTab(val title: String) { Chats("Chats"), Settings("Settings") }

@Composable
fun KinoApp(model: KinoViewModel) {
    val chats by model.chats.collectAsState()
    val activeChat by model.activeChat.collectAsState()
    val messages by model.messages.collectAsState()
    val dark by model.darkTheme.collectAsState()
    val showToolDetails by model.showToolDetails.collectAsState()
    val deepSeekConnected by model.deepSeekConnected.collectAsState()
    val capabilities by model.capabilities.collectAsState()
    val selectedModel by model.selectedModel.collectAsState()
    val thinkingEnabled by model.thinkingEnabled.collectAsState()
    val searchEnabled by model.searchEnabled.collectAsState()
    val providerError by model.providerError.collectAsState()
    val providerBusy by model.providerBusy.collectAsState()
    val isStreaming by model.isStreaming.collectAsState()
    val streamingText by model.streamingText.collectAsState()
    val streamingThinking by model.streamingThinking.collectAsState()
    val streamingSearch by model.streamingSearch.collectAsState()
    val contextTokens by model.contextTokens.collectAsState()
    val attachedFiles by model.attachedFiles.collectAsState()
    val remoteSessions by model.remoteSessions.collectAsState()
    var tab by rememberSaveable { mutableStateOf(MainTab.Chats) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedChat = chats.firstOrNull { it.id == activeChat }
    // Keep in-app destinations above the Activity back action. Settings detail ->
    // settings list -> chats; an open conversation -> chats.
    BackHandler(enabled = activeChat != null || tab == MainTab.Settings) {
        when {
            activeChat != null -> model.closeChat()
            settingsPage == "deepseek-login" -> settingsPage = "provider"
            settingsPage != null -> settingsPage = null
            else -> tab = MainTab.Chats
        }
    }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = { Text("This deletes the conversation from DeepSeek and removes its saved messages from this device.") },
            confirmButton = {
                TextButton(onClick = { model.deleteChat(id); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    if (activeChat != null) {
        ChatScreen(
            title = selectedChat?.title ?: "New chat",
            messages = messages,
            showToolDetails = showToolDetails,
            onBack = model::closeChat,
            onDelete = { deleteTarget = activeChat },
            onSend = model::send,
            models = capabilities?.models.orEmpty(),
            selectedModel = selectedModel,
            thinkingEnabled = thinkingEnabled,
            searchEnabled = searchEnabled,
            contextTokens = contextTokens,
            contextLimit = if (thinkingEnabled) capabilities?.thinkingContextLimit ?: 0 else capabilities?.normalContextLimit ?: 0,
            isBusy = isStreaming,
            liveText = streamingText,
            liveThinking = streamingThinking,
            liveSearch = streamingSearch,
            error = providerError,
            attachedFiles = attachedFiles,
            onModelChange = model::setSelectedModel,
            onThinkingChange = model::setThinking,
            onSearchChange = model::setSearch,
            onAttach = model::attachFiles,
            onRemoveAttachment = model::removeAttachment,
            onStop = model::stopGeneration,
            onFeedback = model::submitFeedback,
            onProviderSettings = { model.closeChat(); tab = MainTab.Settings; settingsPage = "provider" },
        )
    } else {
        Scaffold(
            bottomBar = {
                if (settingsPage == null) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        MainTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(if (item == MainTab.Chats) Icons.Rounded.AutoAwesome else Icons.Rounded.Settings, item.title) },
                                label = { Text(item.title) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (tab == MainTab.Chats) {
                    ExtendedFloatingActionButton(
                        onClick = { model.newChat() },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text("New chat") },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                if (tab == MainTab.Settings && settingsPage == "deepseek-login") DeepSeekLoginScreen(
                    connected = deepSeekConnected,
                    onBack = { settingsPage = "provider" },
                ) else if (tab == MainTab.Settings) SettingsScreen(
                    page = settingsPage,
                    dark = dark,
                    showToolDetails = showToolDetails,
                    onBack = { settingsPage = null },
                    onOpen = { settingsPage = it },
                    onThemeChange = model::setDarkTheme,
                    onToolDetailChange = model::setShowToolDetails,
                    deepSeekConnected = deepSeekConnected,
                    models = capabilities?.models.orEmpty(),
                    selectedModel = selectedModel,
                    thinkingEnabled = thinkingEnabled,
                    searchEnabled = searchEnabled,
                    providerError = providerError,
                    providerBusy = providerBusy,
                    onConnect = { settingsPage = "deepseek-login" },
                    onDisconnect = model::disconnectDeepSeek,
                    onRefreshModels = model::refreshCapabilities,
                    onModelChange = model::setSelectedModel,
                    onThinkingChange = model::setThinking,
                    onSearchChange = model::setSearch,
                    remoteSessions = remoteSessions,
                    onRefreshSessions = model::refreshRemoteSessions,
                    onOpenRemoteSession = model::openRemoteSession,
                    onPinRemoteSession = model::pinRemoteSession,
                    onDeleteRemoteSession = model::deleteRemoteSession,
                    onRenameRemoteSession = model::renameRemoteSession,
                ) else ChatsScreen(
                    chats = chats.filter { it.title.contains(query, true) || it.preview.contains(query, true) },
                    query = query,
                    onQuery = { query = it },
                    onOpen = model::openChat,
                    onDelete = { deleteTarget = it },
                    onRefresh = model::refresh,
                    onSettings = { tab = MainTab.Settings; settingsPage = null },
                )
            }
        }
    }
}
