package com.teamshryne.kino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.teamshryne.kino.ui.screens.ChatScreen
import com.teamshryne.kino.ui.screens.ChatsScreen
import com.teamshryne.kino.ui.screens.SettingsScreen
import com.teamshryne.kino.ui.screens.DeepSeekLoginScreen
import com.teamshryne.kino.ui.components.BrowserSessionPane

private enum class MainTab(val title: String) { Chats("Chats"), Settings("Settings") }

@Composable
fun KinoApp(model: KinoViewModel) {
    val chats by model.chats.collectAsState()
    val activeChat by model.activeChat.collectAsState()
    val messages by model.messages.collectAsState()
    val dark by model.darkTheme.collectAsState()
    val showToolDetails by model.showToolDetails.collectAsState()
    val tableCellMinWidth by model.tableCellMinWidth.collectAsState()
    val pdfMaxPages by model.pdfMaxPages.collectAsState()
    val pdfMaxHtmlKb by model.pdfMaxHtmlKb.collectAsState()
    val pdfMaxPageKb by model.pdfMaxPageKb.collectAsState()
    val pdfMaxMb by model.pdfMaxMb.collectAsState()
    val deepSeekConnected by model.deepSeekConnected.collectAsState()
    val capabilities by model.capabilities.collectAsState()
    val selectedModel by model.selectedModel.collectAsState()
    val thinkingEnabled by model.thinkingEnabled.collectAsState()
    val searchEnabled by model.searchEnabled.collectAsState()
    val providerError by model.providerError.collectAsState()
    val providerBusy by model.providerBusy.collectAsState()
    val isStreaming by model.isStreaming.collectAsState()
    val streamingText by model.streamingText.collectAsState()
    val streamingTools by model.streamingTools.collectAsState()
    val contextTokens by model.contextTokens.collectAsState()
    val attachedFiles by model.attachedFiles.collectAsState()
    val remoteSessions by model.remoteSessions.collectAsState()
    val libraryStates by model.libraries.collectAsState()
    val libraryBusy by model.libraryBusy.collectAsState()
    val libraryProgress by model.libraryProgress.collectAsState()
    val libraryError by model.libraryError.collectAsState()
    val firstRunCorePrompt by model.firstRunCorePrompt.collectAsState()
    val browserOpen by model.browserOpen.collectAsState()
    val browserState by model.browserState.collectAsState()
    val browserProgress = streamingTools.lastOrNull { it.tool?.name == "thinking" }?.tool?.detail.orEmpty()
    var tab by rememberSaveable { mutableStateOf(MainTab.Chats) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedChat = chats.firstOrNull { it.id == activeChat }
    // Keep in-app destinations above the Activity back action. Settings detail ->
    // settings list -> chats; an open conversation -> chats.
    BackHandler(enabled = browserOpen || activeChat != null || tab == MainTab.Settings) {
        when {
            browserOpen -> model.closeBrowser()
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

    val kinoCoreState = libraryStates.firstOrNull { it.library.id == "kino-core" }
    if (firstRunCorePrompt) AlertDialog(
        onDismissRequest = model::dismissCorePrompt,
        title = { Text(if (kinoCoreState?.updateAvailable == true) "Update Kino Core" else "Install Kino Core") },
        text = {
            Text(if (kinoCoreState?.updateAvailable == true) {
                "Kino Core ${kinoCoreState.library.version} is available (installed: ${kinoCoreState.installedVersion}). Update it from private app storage to get the latest fixes. Restart Kino after updating to load the new core."
            } else {
                "Kino Core is needed for chat, search, and file support. It will download once and stay in this app’s private storage."
            })
        },
        confirmButton = { TextButton(onClick = { model.installLibrary("kino-core") }) { Text(when {
            libraryBusy == "kino-core" -> "Installing…"
            kinoCoreState?.updateAvailable == true -> "Update"
            else -> "Install"
        }) } },
        dismissButton = { TextButton(onClick = model::dismissCorePrompt) { Text("Later") } },
    )
    if (activeChat != null) {
        Box(Modifier.fillMaxSize()) {
            BrowserSessionPane(
                session = model.browserSession,
                state = browserState,
                visible = browserOpen,
                progressText = browserProgress,
                onClose = model::closeBrowser,
                modifier = Modifier.fillMaxSize().zIndex(if (browserOpen) 1f else -1f),
            )
            Box(Modifier.fillMaxSize().zIndex(if (browserOpen) 0f else 1f)) {
        ChatScreen(
            title = selectedChat?.title ?: "New chat",
            messages = messages,
            showToolDetails = showToolDetails,
            tableCellMinWidth = tableCellMinWidth,
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
            liveTools = streamingTools,
            error = providerError,
            attachedFiles = attachedFiles,
            onModelChange = model::setSelectedModel,
            onThinkingChange = model::setThinking,
            onSearchChange = model::setSearch,
            onAttach = model::attachFiles,
            onRemoveAttachment = model::removeAttachment,
            onStop = model::stopGeneration,
            onProviderSettings = { model.closeChat(); tab = MainTab.Settings; settingsPage = "provider" },
            onRename = { newTitle -> activeChat?.let { model.renameChat(it, newTitle) } },
            browserOpen = browserOpen,
            onToggleBrowser = model::toggleBrowser,
        )
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                if (settingsPage == null) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                            MainTab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = {
                                        Icon(
                                            if (item == MainTab.Chats) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.Settings,
                                            item.title,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    },
                                    label = { Text(item.title) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = Color.Transparent,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                if (tab == MainTab.Settings && settingsPage == "deepseek-login") DeepSeekLoginScreen(
                    connected = deepSeekConnected,
                    onCredentials = model::saveDeepSeekCredentials,
                    onBack = { settingsPage = "provider" },
                ) else if (tab == MainTab.Settings) SettingsScreen(
                    page = settingsPage,
                    dark = dark,
                    showToolDetails = showToolDetails,
                    tableCellMinWidth = tableCellMinWidth,
                    onBack = { settingsPage = null },
                    onOpen = { settingsPage = it },
                    onThemeChange = model::setDarkTheme,
                    onToolDetailChange = model::setShowToolDetails,
                    onTableCellMinWidthChange = model::setTableCellMinWidth,
                    pdfMaxPages = pdfMaxPages,
                    pdfMaxHtmlKb = pdfMaxHtmlKb,
                    pdfMaxPageKb = pdfMaxPageKb,
                    pdfMaxMb = pdfMaxMb,
                    onPdfMaxPagesChange = model::setPdfMaxPages,
                    onPdfMaxHtmlKbChange = model::setPdfMaxHtmlKb,
                    onPdfMaxPageKbChange = model::setPdfMaxPageKb,
                    onPdfMaxMbChange = model::setPdfMaxMb,
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
                    libraryStates = libraryStates,
                    libraryBusy = libraryBusy,
                    libraryProgress = libraryProgress,
                    libraryError = libraryError,
                    onInstallLibrary = model::installLibrary,
                    onRefreshLibraries = model::refreshLibraries,
                ) else ChatsScreen(
                    chats = chats,
                    query = query,
                    onQuery = { query = it },
                    onOpen = model::openChat,
                    onDelete = { deleteTarget = it },
                    onRename = model::renameChat,
                    onPin = model::setChatPinned,
                    onNewChat = model::newChat,
                )
            }
        }
    }
}
