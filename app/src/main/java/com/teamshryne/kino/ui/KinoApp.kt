package com.teamshryne.kino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
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

private enum class MainTab(val title: String) { Chats("Chats"), Settings("Settings") }

@Composable
fun KinoApp(model: KinoViewModel) {
    val chats by model.chats.collectAsState()
    val activeChat by model.activeChat.collectAsState()
    val messages by model.messages.collectAsState()
    val dark by model.darkTheme.collectAsState()
    val showToolDetails by model.showToolDetails.collectAsState()
    var tab by rememberSaveable { mutableStateOf(MainTab.Chats) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedChat = chats.firstOrNull { it.id == activeChat }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes the chat and its saved messages from this device.") },
            confirmButton = {
                TextButton(onClick = { model.deleteChat(id); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (activeChat == null && settingsPage == null) {
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                activeChat != null -> ChatScreen(
                    title = selectedChat?.title ?: "New chat",
                    messages = messages,
                    showToolDetails = showToolDetails,
                    onBack = model::closeChat,
                    onDelete = { deleteTarget = activeChat },
                    onSend = model::send,
                )
                tab == MainTab.Settings -> SettingsScreen(
                    page = settingsPage,
                    dark = dark,
                    showToolDetails = showToolDetails,
                    onBack = { settingsPage = null },
                    onOpen = { settingsPage = it },
                    onThemeChange = model::setDarkTheme,
                    onToolDetailChange = model::setShowToolDetails,
                )
                else -> ChatsScreen(
                    chats = chats.filter { it.title.contains(query, true) || it.preview.contains(query, true) },
                    query = query,
                    onQuery = { query = it },
                    onNew = { model.newChat() },
                    onOpen = model::openChat,
                    onDelete = { deleteTarget = it },
                )
            }
        }
    }
}
