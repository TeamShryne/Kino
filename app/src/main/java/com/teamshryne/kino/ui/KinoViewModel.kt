package com.teamshryne.kino.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teamshryne.kino.data.ConversationRepository
import com.teamshryne.kino.data.LocalConversationRepository
import com.teamshryne.kino.data.local.KinoDatabase
import com.teamshryne.kino.data.provider.DeepSeekCoreClient
import com.teamshryne.kino.data.provider.ProviderCapabilities
import com.teamshryne.kino.data.provider.ProviderSession
import com.teamshryne.kino.domain.model.ChatProviderState
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.ToolActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.UUID

class KinoViewModel(
    application: Application,
    private val conversations: ConversationRepository,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        LocalConversationRepository(KinoDatabase(application)),
    )
    private val preferences = application.getSharedPreferences("kino-settings", 0)
    private val provider = DeepSeekCoreClient(application)
    private val credentialStore = provider.credentialStore()
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
    private val _deepSeekConnected = MutableStateFlow(credentialStore.load() != null)
    val deepSeekConnected: StateFlow<Boolean> = _deepSeekConnected.asStateFlow()
    private val _capabilities = MutableStateFlow<ProviderCapabilities?>(null)
    val capabilities: StateFlow<ProviderCapabilities?> = _capabilities.asStateFlow()
    private val _providerError = MutableStateFlow<String?>(null)
    val providerError: StateFlow<String?> = _providerError.asStateFlow()
    private val _providerBusy = MutableStateFlow(false)
    val providerBusy: StateFlow<Boolean> = _providerBusy.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _selectedModel = MutableStateFlow(preferences.getString("deepseek_model", "") ?: "")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()
    private val _searchEnabled = MutableStateFlow(false)
    val searchEnabled: StateFlow<Boolean> = _searchEnabled.asStateFlow()
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()
    private val _streamingThinking = MutableStateFlow("")
    val streamingThinking: StateFlow<String> = _streamingThinking.asStateFlow()
    private val _streamingSearch = MutableStateFlow<List<String>>(emptyList())
    val streamingSearch: StateFlow<List<String>> = _streamingSearch.asStateFlow()
    private val _contextTokens = MutableStateFlow(0L)
    val contextTokens: StateFlow<Long> = _contextTokens.asStateFlow()
    private val _remoteSessions = MutableStateFlow<List<ProviderSession>>(emptyList())
    val remoteSessions: StateFlow<List<ProviderSession>> = _remoteSessions.asStateFlow()
    private val _attachedFiles = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val attachedFiles: StateFlow<List<Pair<String, String>>> = _attachedFiles.asStateFlow()
    private var completionCancellation: AtomicBoolean? = null
    private var completionJob: Job? = null

    init {
        refresh()
        if (_deepSeekConnected.value) {
            refreshCapabilities()
            refreshRemoteSessions()
        }
    }

    fun refresh() = viewModelScope.launch {
        _chats.value = conversations.chats()
    }

    fun openChat(id: String) = viewModelScope.launch {
        _activeChat.value = id
        val loaded = conversations.messages(id)
        if (_activeChat.value == id) _messages.value = loaded
        val state = conversations.providerState(id)
        if (_activeChat.value == id) {
            _thinkingEnabled.value = state?.thinkingEnabled ?: false
            _searchEnabled.value = state?.searchEnabled ?: false
            _contextTokens.value = state?.contextTokens ?: loaded.lastOrNull { it.tokenUsage != null }?.tokenUsage ?: 0
            state?.modelType?.let { _selectedModel.value = it }
        }
    }

    fun closeChat() {
        stopGeneration()
        _activeChat.value = null
        _messages.value = emptyList()
        refresh()
    }

    fun newChat() = viewModelScope.launch {
        val id = conversations.createChat()
        _activeChat.value = id
        _messages.value = emptyList()
        _contextTokens.value = 0
        _thinkingEnabled.value = false
        _searchEnabled.value = false
        _chats.value = conversations.chats()
    }

    fun send(text: String) = viewModelScope.launch {
        val body = text.trim()
        if (body.isEmpty()) return@launch
        if (credentialStore.load() == null) {
            _providerError.value = "Connect DeepSeek in Settings before sending a message."
            return@launch
        }
        if (completionJob?.isActive == true) return@launch
        val chatId = _activeChat.value ?: conversations.createChat(body.take(48)).also { _activeChat.value = it }
        val model = _capabilities.value?.models?.firstOrNull { it.id == _selectedModel.value }
            ?: _capabilities.value?.models?.firstOrNull { it.isDefault }
            ?: _capabilities.value?.models?.firstOrNull()
        if (model == null) {
            _providerError.value = "Could not load available DeepSeek models. Check your connection in Settings."
            return@launch
        }
        conversations.sendUserMessage(chatId, body)
        _messages.value = conversations.messages(chatId)
        _chats.value = conversations.chats()
        val previous = conversations.providerState(chatId) ?: ChatProviderState(chatId = chatId, modelType = model.id)
        val active = previous.copy(
            modelType = model.id,
            thinkingEnabled = _thinkingEnabled.value && model.supportsThinking,
            searchEnabled = _searchEnabled.value && model.supportsSearch,
        )
        conversations.saveProviderState(active)
        completionCancellation = AtomicBoolean(false)
        _providerBusy.value = true
        _isStreaming.value = true
        _providerError.value = null
        _streamingText.value = ""
        _streamingThinking.value = ""
        _streamingSearch.value = emptyList()
        completionJob = viewModelScope.launch {
            var session = active.sessionId
            var parent = active.parentMessageId
            var usage = active.contextTokens
            try {
                if (session.isBlank()) {
                    session = provider.createSession().optString("id")
                    check(session.isNotBlank()) { "DeepSeek did not return a chat session." }
                    conversations.saveProviderState(active.copy(sessionId = session))
                }
                val result = provider.complete(
                    sessionId = session,
                    parentMessageId = parent,
                    modelType = model.id,
                    prompt = body,
                    thinking = active.thinkingEnabled,
                    search = active.searchEnabled,
                    fileIds = _attachedFiles.value.map { it.first },
                    cancelled = completionCancellation ?: AtomicBoolean(false),
                    onEvent = { event ->
                        when (event.optString("kind")) {
                            "text_delta" -> _streamingText.update { it + event.optString("content") }
                            "thinking_delta" -> _streamingThinking.update { it + event.optString("content") }
                            "usage" -> {
                                _contextTokens.value = event.optLong("accumulated_token_usage", _contextTokens.value)
                            }
                            "search_fragment", "search_update" -> {
                                val fragment = event.optJSONObject("fragment")
                                val raw = event.opt("raw")
                                val detail = buildString {
                                    fragment?.optJSONArray("queries")?.let { queries ->
                                        for (i in 0 until queries.length()) append("Search: ").append(queries.optJSONObject(i)?.optString("query")).append('\n')
                                    }
                                    fragment?.optJSONArray("results")?.let { results ->
                                        for (i in 0 until results.length()) {
                                            val item = results.optJSONObject(i) ?: continue
                                            append(item.optString("title", item.optString("url"))).append('\n')
                                            if (item.has("url")) append(item.optString("url")).append('\n')
                                        }
                                    }
                                    val rawResults = when (raw) {
                                        is JSONArray -> raw
                                        is JSONObject -> raw.optJSONArray("results") ?: raw.optJSONArray("queries")
                                        else -> null
                                    }
                                    rawResults?.let { results ->
                                        for (i in 0 until results.length()) {
                                            val item = results.optJSONObject(i) ?: continue
                                            append(item.optString("title", item.optString("query", item.optString("url")))).append('\n')
                                            if (item.has("url")) append(item.optString("url")).append('\n')
                                            if (item.has("snippet")) append(item.optString("snippet")).append('\n')
                                        }
                                    }
                                    if (isEmpty()) append(event.optString("content", "Searching the web"))
                                }.trim()
                                if (detail.isNotBlank()) {
                                    _streamingSearch.update { it + detail }
                                }
                            }
                        }
                    },
                )
                session = result.optString("session_id", session)
                parent = result.optLong("response_message_id", parent)
                usage = result.optLong("accumulated_token_usage", usage)
                val answer = _streamingText.value
                val thinking = _streamingThinking.value
                if (thinking.isNotBlank()) {
                    conversations.appendAgentEvent(chatId, MessageRole.TOOL, "Thinking", ToolActivity("thinking", "Done", thinking), "thinking")
                }
                _streamingSearch.value.forEach { detail ->
                    conversations.appendAgentEvent(chatId, MessageRole.TOOL, "Web search", ToolActivity("search", "Done", detail), "search")
                }
                if (answer.isNotBlank()) {
                    val metadata = JSONObject().put("session_id", session).put("message_id", parent).toString()
                    conversations.appendAgentEvent(chatId, MessageRole.ASSISTANT, answer, kind = "response", metadata = metadata, tokenUsage = usage)
                }
                conversations.saveProviderState(active.copy(sessionId = session, parentMessageId = parent, contextTokens = usage))
                _attachedFiles.value = emptyList()
                _contextTokens.value = usage
            } catch (error: Exception) {
                if (completionCancellation?.get() != true) _providerError.value = error.message?.take(240) ?: "DeepSeek request failed."
                if (_streamingText.value.isNotBlank()) {
                    val metadata = if (session.isNotBlank() && parent > 0) JSONObject().put("session_id", session).put("message_id", parent).toString() else null
                    conversations.appendAgentEvent(chatId, MessageRole.ASSISTANT, _streamingText.value, kind = "response", metadata = metadata, tokenUsage = usage)
                }
                if (completionCancellation?.get() != true) {
                    conversations.appendAgentEvent(chatId, MessageRole.TOOL, "Request failed", ToolActivity("error", "Failed", _providerError.value.orEmpty()), "error")
                }
            } finally {
                if (_activeChat.value == chatId) _messages.value = conversations.messages(chatId)
                _chats.value = conversations.chats()
                _providerBusy.value = false
                _isStreaming.value = false
                _streamingText.value = ""
                _streamingThinking.value = ""
                _streamingSearch.value = emptyList()
                completionCancellation = null
            }
        }
    }

    fun stopGeneration() { completionCancellation?.set(true) }

    fun attachFiles(uris: List<Uri>) = viewModelScope.launch {
        if (credentialStore.load() == null) {
            _providerError.value = "Connect DeepSeek before attaching files."
            return@launch
        }
        val model = _capabilities.value?.models?.firstOrNull { it.id == _selectedModel.value }
        if (model?.supportsFiles != true) {
            _providerError.value = "The selected DeepSeek model does not support files."
            return@launch
        }
        if (model.maxInputFileCount > 0 && _attachedFiles.value.size + uris.size > model.maxInputFileCount) {
            _providerError.value = "This model accepts up to ${model.maxInputFileCount} files per message."
            return@launch
        }
        _providerBusy.value = true
        _providerError.value = null
        try {
            for (uri in uris) {
                val displayName = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: "attachment"
                val local = File(getApplication<Application>().cacheDir, "kino-uploads/${UUID.randomUUID()}-${displayName.substringAfterLast('/').take(100)}")
                local.parentFile?.mkdirs()
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> local.outputStream().use { output -> input.copyTo(output) } }
                    ?: error("Could not read $displayName")
                try {
                    val maxBytes = model.maxUploadFileBytes.takeIf { it > 0 } ?: 100L * 1024 * 1024
                    require(local.length() <= maxBytes) { "$displayName is larger than this model's upload limit." }
                    val extension = displayName.substringAfterLast('.', "").lowercase()
                    require(model.supportedExtensions.isEmpty() || extension in model.supportedExtensions) { "${displayName.substringAfterLast('/')} is not supported by this model." }
                    val uploaded = provider.uploadFile(local.absolutePath)
                    val id = uploaded.optString("id")
                    check(id.isNotBlank()) { "DeepSeek did not return a file ID for $displayName." }
                    provider.fetchFiles(listOf(id))
                    _attachedFiles.value = _attachedFiles.value + (id to displayName)
                } finally { local.delete() }
            }
        } catch (error: Exception) {
            _providerError.value = error.message?.take(240) ?: "File upload failed."
        } finally { _providerBusy.value = false }
    }

    fun removeAttachment(id: String) { _attachedFiles.value = _attachedFiles.value.filterNot { it.first == id } }

    fun saveDeepSeekCredentials(credentials: com.teamshryne.kino.data.security.DeepSeekCredentials): Boolean {
        runCatching { credentialStore.save(credentials) }
            .onSuccess { _deepSeekConnected.value = true; _providerError.value = null; refreshCapabilities(); refreshRemoteSessions(); return true }
            .onFailure { _providerError.value = "Could not securely save the DeepSeek session. ${it.message.orEmpty()}"; return false }
        return false
    }

    fun disconnectDeepSeek() {
        credentialStore.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteOrigin("https://chat.deepseek.com")
        _deepSeekConnected.value = false
        _capabilities.value = null
        _remoteSessions.value = emptyList()
        _selectedModel.value = ""
        preferences.edit().remove("deepseek_model").apply()
    }

    fun refreshCapabilities() = viewModelScope.launch {
        if (credentialStore.load() == null) return@launch
        _providerBusy.value = true
        try {
            val loaded = provider.capabilities()
            _capabilities.value = loaded
            val saved = _selectedModel.value
            val chosen = loaded.models.firstOrNull { it.id == saved } ?: loaded.models.firstOrNull { it.isDefault } ?: loaded.models.firstOrNull()
            chosen?.let { setSelectedModel(it.id) }
            _providerError.value = null
        } catch (error: Exception) {
            _providerError.value = error.message?.take(240) ?: "Could not load DeepSeek settings."
        } finally { _providerBusy.value = false }
    }

    fun refreshRemoteSessions() = viewModelScope.launch {
        if (credentialStore.load() == null) return@launch
        _providerBusy.value = true
        try {
            _remoteSessions.value = provider.remoteSessions()
            _providerError.value = null
        } catch (error: Exception) {
            _providerError.value = error.message?.take(240) ?: "Could not load DeepSeek sessions."
        } finally { _providerBusy.value = false }
    }

    fun openRemoteSession(remote: ProviderSession) = viewModelScope.launch {
        _providerBusy.value = true
        _providerError.value = null
        try {
            val existing = conversations.chatForSession(remote.id)
            if (existing != null) {
                openChat(existing).join()
            } else {
                val localId = conversations.createChat(remote.title.ifBlank { "DeepSeek chat" }.take(80))
                val state = ChatProviderState(chatId = localId, sessionId = remote.id, modelType = remote.modelType.ifBlank { _selectedModel.value })
                conversations.saveProviderState(state)
                val history = provider.history(remote.id).optJSONArray("messages") ?: JSONArray()
                var parent = 0L
                var usage = 0L
                for (index in 0 until history.length()) {
                    val message = history.optJSONObject(index) ?: continue
                    val messageId = message.optLong("message_id")
                    val role = message.optString("role").uppercase()
                    val fragments = message.optJSONArray("fragments") ?: JSONArray()
                    val response = StringBuilder()
                    val thinking = StringBuilder()
                    val searches = mutableListOf<String>()
                    for (fragmentIndex in 0 until fragments.length()) {
                        val fragment = fragments.optJSONObject(fragmentIndex) ?: continue
                        when (fragment.optString("type").uppercase()) {
                            "RESPONSE" -> response.append(fragment.optString("content"))
                            "THINK" -> thinking.append(fragment.optString("content"))
                            "SEARCH" -> {
                                val detail = buildString {
                                    fragment.optJSONArray("queries")?.let { qs -> for (i in 0 until qs.length()) append("Search: ").append(qs.optJSONObject(i)?.optString("query")).append('\n') }
                                    fragment.optJSONArray("results")?.let { rs -> for (i in 0 until rs.length()) rs.optJSONObject(i)?.let { append(it.optString("title", it.optString("url"))).append('\n').append(it.optString("url")) } }
                                }.trim()
                                if (detail.isNotBlank()) searches += detail
                            }
                        }
                    }
                    when (role) {
                        "USER" -> if (response.isNotBlank()) conversations.sendUserMessage(localId, response.toString())
                        "ASSISTANT" -> {
                            if (thinking.isNotBlank()) conversations.appendAgentEvent(localId, MessageRole.TOOL, "Thinking", ToolActivity("thinking", "Done", thinking.toString()), "thinking")
                            searches.forEach { detail -> conversations.appendAgentEvent(localId, MessageRole.TOOL, "Web search", ToolActivity("search", "Done", detail), "search") }
                            if (response.isNotBlank()) {
                                val metadata = JSONObject().put("session_id", remote.id).put("message_id", messageId).toString()
                                conversations.appendAgentEvent(localId, MessageRole.ASSISTANT, response.toString(), kind = "response", metadata = metadata, tokenUsage = message.optLong("accumulated_token_usage"))
                                parent = messageId
                            }
                        }
                    }
                    usage = maxOf(usage, message.optLong("accumulated_token_usage"))
                }
                conversations.saveProviderState(state.copy(parentMessageId = parent, contextTokens = usage))
                _activeChat.value = localId
                _messages.value = conversations.messages(localId)
                _chats.value = conversations.chats()
                _contextTokens.value = usage
            }
            _activeChat.value?.let { current ->
                conversations.providerState(current)?.let { _thinkingEnabled.value = it.thinkingEnabled; _searchEnabled.value = it.searchEnabled; _selectedModel.value = it.modelType }
            }
        } catch (error: Exception) {
            _providerError.value = error.message?.take(240) ?: "Could not open this DeepSeek session."
        } finally { _providerBusy.value = false }
    }

    fun pinRemoteSession(session: ProviderSession, pinned: Boolean) = viewModelScope.launch {
        runCatching { provider.setPinned(listOf(session.id), pinned) }
            .onSuccess { refreshRemoteSessions() }
            .onFailure { _providerError.value = it.message?.take(240) ?: "Could not update the pinned session." }
    }

    fun renameRemoteSession(session: ProviderSession, title: String) = viewModelScope.launch {
        runCatching { provider.updateSessionTitle(session.id, title) }
            .onSuccess { refreshRemoteSessions() }
            .onFailure { _providerError.value = it.message?.take(240) ?: "Could not rename the DeepSeek session." }
    }

    fun deleteRemoteSession(session: ProviderSession) = viewModelScope.launch {
        runCatching { provider.deleteSessions(listOf(session.id)) }
            .onSuccess {
                conversations.chatForSession(session.id)?.let { conversations.deleteChat(it) }
                _chats.value = conversations.chats()
                refreshRemoteSessions()
            }
            .onFailure { _providerError.value = it.message?.take(240) ?: "Could not delete the DeepSeek session." }
    }

    fun submitFeedback(message: KinoMessage, type: String) = viewModelScope.launch {
        val metadata = runCatching { JSONObject(message.metadata.orEmpty()) }.getOrNull()
        val sessionId = metadata?.optString("session_id").orEmpty()
        val messageId = metadata?.optLong("message_id") ?: 0L
        if (sessionId.isBlank() || messageId <= 0) return@launch
        runCatching { provider.submitFeedback(sessionId, messageId, type) }
            .onFailure { _providerError.value = it.message?.take(240) ?: "Could not send feedback." }
    }

    fun setSelectedModel(model: String) {
        _selectedModel.value = model
        preferences.edit().putString("deepseek_model", model).apply()
        persistActiveProviderOptions()
    }

    fun setThinking(enabled: Boolean) {
        _thinkingEnabled.value = enabled
        persistActiveProviderOptions()
    }

    fun setSearch(enabled: Boolean) {
        _searchEnabled.value = enabled
        persistActiveProviderOptions()
    }

    private fun persistActiveProviderOptions() {
        val chatId = _activeChat.value ?: return
        viewModelScope.launch {
            val state = conversations.providerState(chatId) ?: ChatProviderState(chatId, modelType = _selectedModel.value)
            conversations.saveProviderState(state.copy(thinkingEnabled = _thinkingEnabled.value, searchEnabled = _searchEnabled.value, modelType = _selectedModel.value))
        }
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
        val remote = conversations.providerState(id)?.sessionId
        if (!remote.isNullOrBlank() && credentialStore.load() != null) {
            runCatching { provider.deleteSessions(listOf(remote)) }
                .onFailure { _providerError.value = it.message?.take(240) ?: "Could not remove the remote DeepSeek chat." }
        }
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

    override fun onCleared() {
        stopGeneration()
        provider.close()
        super.onCleared()
    }
}
