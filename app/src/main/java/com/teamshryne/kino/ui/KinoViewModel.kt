package com.teamshryne.kino.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teamshryne.kino.data.ConversationRepository
import com.teamshryne.kino.data.LocalConversationRepository
import com.teamshryne.kino.data.local.KinoDatabase
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.ToolActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KinoViewModel(
    application: Application,
    private val conversations: ConversationRepository,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        LocalConversationRepository(KinoDatabase(application)),
    )
    private val preferences = application.getSharedPreferences("kino-settings", 0)
    private val _chats = MutableStateFlow<List<KinoChat>>(emptyList())
    val chats: StateFlow<List<KinoChat>> = _chats.asStateFlow()
    private val _activeChat = MutableStateFlow<String?>(null)
    val activeChat: StateFlow<String?> = _activeChat.asStateFlow()
    private val _messages = MutableStateFlow<List<KinoMessage>>(emptyList())
    val messages: StateFlow<List<KinoMessage>> = _messages.asStateFlow()
    private val _darkTheme = MutableStateFlow(preferences.getBoolean("dark", false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()
    private val _showToolDetails = MutableStateFlow(preferences.getBoolean("tool_details", true))
    val showToolDetails: StateFlow<Boolean> = _showToolDetails.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _chats.value = conversations.chats()
    }

    fun openChat(id: String) = viewModelScope.launch {
        _activeChat.value = id
        val loaded = conversations.messages(id)
        if (_activeChat.value == id) _messages.value = loaded
    }

    fun closeChat() {
        _activeChat.value = null
        _messages.value = emptyList()
        refresh()
    }

    fun newChat() = viewModelScope.launch {
        val id = conversations.createChat()
        _activeChat.value = id
        _messages.value = emptyList()
        _chats.value = conversations.chats()
    }

    fun send(text: String) = viewModelScope.launch {
        val body = text.trim()
        if (body.isEmpty()) return@launch
        val chatId = _activeChat.value ?: conversations.createChat(body.take(48)).also { _activeChat.value = it }
        conversations.sendUserMessage(chatId, body)
        _messages.value = conversations.messages(chatId)
        _chats.value = conversations.chats()
    }

    /** Entry point for streaming responses and tool events when the provider layer is added. */
    fun appendAgentEvent(chatId: String, role: MessageRole, text: String, tool: ToolActivity? = null) =
        viewModelScope.launch {
            conversations.appendAgentEvent(chatId, role, text, tool)
            val loaded = conversations.messages(chatId)
            if (_activeChat.value == chatId) _messages.value = loaded
            _chats.value = conversations.chats()
        }

    fun deleteChat(id: String) = viewModelScope.launch {
        conversations.deleteChat(id)
        if (_activeChat.value == id) closeChat() else _chats.value = conversations.chats()
    }

    fun setDarkTheme(enabled: Boolean) {
        preferences.edit().putBoolean("dark", enabled).apply()
        _darkTheme.value = enabled
    }

    fun setShowToolDetails(enabled: Boolean) {
        preferences.edit().putBoolean("tool_details", enabled).apply()
        _showToolDetails.value = enabled
    }
}
