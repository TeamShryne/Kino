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
import com.teamshryne.kino.data.browser.KinoBrowserSession
import com.teamshryne.kino.data.browser.BrowserUiState
import com.teamshryne.kino.data.libraries.RuntimeLibraries
import com.teamshryne.kino.data.libraries.LibraryState
import com.teamshryne.kino.data.provider.DeepSeekCoreClient
import com.teamshryne.kino.data.provider.ProviderCapabilities
import com.teamshryne.kino.data.provider.ProviderSession
import com.teamshryne.kino.data.tools.HtmlPdfRenderer
import com.teamshryne.kino.data.tools.LocalToolRegistry
import com.teamshryne.kino.data.tools.ToolCallProtocol
import com.teamshryne.kino.domain.model.ChatProviderState
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.SearchSource
import com.teamshryne.kino.domain.model.SearchSourceCodec
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
    private val libraryManager = RuntimeLibraries(application)
    private val provider = DeepSeekCoreClient(application)
    val browserSession = KinoBrowserSession()
    val browserState: StateFlow<BrowserUiState> = browserSession.state
    private val _browserOpen = MutableStateFlow(false)
    val browserOpen: StateFlow<Boolean> = _browserOpen.asStateFlow()
    private val _libraries = MutableStateFlow<List<LibraryState>>(emptyList())
    val libraries: StateFlow<List<LibraryState>> = _libraries.asStateFlow()
    private val _libraryBusy = MutableStateFlow<String?>(null)
    val libraryBusy: StateFlow<String?> = _libraryBusy.asStateFlow()
    private val _libraryProgress = MutableStateFlow(0f)
    val libraryProgress: StateFlow<Float> = _libraryProgress.asStateFlow()
    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()
    private val _firstRunCorePrompt = MutableStateFlow(!preferences.getBoolean("core_prompt_seen", false))
    val firstRunCorePrompt: StateFlow<Boolean> = _firstRunCorePrompt.asStateFlow()
    private val _pdfLibraryPrompt = MutableStateFlow(false)
    val pdfLibraryPrompt: StateFlow<Boolean> = _pdfLibraryPrompt.asStateFlow()
    private val toolRegistry = LocalToolRegistry(
        messages = { chatId -> conversations.messages(chatId) },
        renderPdf = { title, html -> HtmlPdfRenderer(application).create(title, html) },
        browse = { arguments ->
            kotlinx.coroutines.delay(100)
            browserSession.execute(arguments)
        },
    )
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
    private val _tableCellMinWidth = MutableStateFlow(preferences.getInt("table_cell_min_width", 112).coerceIn(64, 240))
    val tableCellMinWidth: StateFlow<Int> = _tableCellMinWidth.asStateFlow()
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
    private val _streamingTools = MutableStateFlow<List<KinoMessage>>(emptyList())
    val streamingTools: StateFlow<List<KinoMessage>> = _streamingTools.asStateFlow()
    private var nextLiveToolId = -10L
    private var liveSearchToolId: Long? = null
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
        refreshLibraries()
    }

    private fun coreReady(): Boolean = _libraries.value.any { it.library.id == "kino-core" && it.installed }

    fun refreshLibraries() = viewModelScope.launch {
        try {
            _libraries.value = libraryManager.catalog().map(libraryManager::state)
            _libraryError.value = null
            val core = _libraries.value.firstOrNull { it.library.id == "kino-core" }
            if (core?.installed == true) {
                if (!_firstRunCorePrompt.value) preferences.edit().putBoolean("core_prompt_seen", true).apply()
                else dismissCorePrompt()
                if (_deepSeekConnected.value) { refreshCapabilities(); refreshRemoteSessions() }
            } else if (core?.installedVersion != null) {
                _firstRunCorePrompt.value = true
            }
        } catch (error: Exception) { _libraryError.value = error.message?.take(240) ?: "Could not load available libraries." }
    }

    fun installLibrary(id: String) = viewModelScope.launch {
        val item = _libraries.value.firstOrNull { it.library.id == id }?.library ?: run { _libraryError.value = "Library is not in the catalog."; return@launch }
        if (_libraryBusy.value != null) return@launch
        _libraryBusy.value = id; _libraryProgress.value = 0f; _libraryError.value = null
        try {
            libraryManager.install(item) { received, total -> _libraryProgress.value = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f }
            refreshLibraries()
            if (id == "kino-core") dismissCorePrompt()
            if (id == "ironpress-pdf") _pdfLibraryPrompt.value = false
        } catch (error: Exception) { _libraryError.value = error.message?.take(300) ?: "Library installation failed." }
        finally { _libraryBusy.value = null; _libraryProgress.value = 0f }
    }

    fun dismissCorePrompt() { preferences.edit().putBoolean("core_prompt_seen", true).apply(); _firstRunCorePrompt.value = false }
    fun dismissPdfPrompt() { _pdfLibraryPrompt.value = false }

    fun openBrowser() { _browserOpen.value = true }
    fun toggleBrowser() { if (_browserOpen.value) closeBrowser() else openBrowser() }
    fun closeBrowser() { _browserOpen.value = false; browserSession.setUserControl(false) }

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

    private fun startThinkingTool(chatId: String) {
        val message = KinoMessage(
            id = nextLiveToolId--,
            chatId = chatId,
            role = MessageRole.TOOL,
            text = "Thinking",
            createdAt = System.currentTimeMillis(),
            tool = ToolActivity("thinking", "Working", ""),
            kind = "thinking",
        )
        _streamingTools.update { it + message }
    }

    private fun startLocalTool(chatId: String, name: String, label: String, summary: String, status: String = "Working"): Long {
        val message = KinoMessage(
            id = nextLiveToolId--,
            chatId = chatId,
            role = MessageRole.TOOL,
            text = label,
            createdAt = System.currentTimeMillis(),
            tool = ToolActivity(name, status, summary),
            kind = name,
        )
        _streamingTools.update { it + message }
        return message.id
    }

    private fun finishLocalTool(id: Long, status: String, detail: String) {
        _streamingTools.update { current -> current.map { message ->
            if (message.id == id) message.copy(tool = message.tool?.copy(status = status, detail = detail)) else message
        } }
    }

    private fun appendThinkingTool(chatId: String, content: String) {
        if (content.isEmpty()) return
        _streamingTools.update { current ->
            val last = current.lastOrNull()
            val activity = last?.tool
            if (last != null && activity?.name == "thinking") {
                current.dropLast(1) + last.copy(tool = activity.copy(detail = activity.detail + content))
            } else {
                current + KinoMessage(
                    id = nextLiveToolId--,
                    chatId = chatId,
                    role = MessageRole.TOOL,
                    text = "Thinking",
                    createdAt = System.currentTimeMillis(),
                    tool = ToolActivity("thinking", "Working", content),
                    kind = "thinking",
                )
            }
        }
    }

    private fun handleProviderFragment(chatId: String, fragment: JSONObject?) {
        if (fragment == null) return
        when (fragment.optString("type").uppercase()) {
            "TOOL_SEARCH" -> {
                startSearchTool(chatId)
                addSearchSources(chatId, SearchSourceCodec.fromFragment(fragment))
            }
            "TOOL_OPEN" -> {
                ensureSearchTool(chatId)
                SearchSourceCodec.fromOpenFragment(fragment)?.copy(content = "")?.let { mergeOpenedPage(chatId, it) }
            }
            "SEARCH" -> addSearchSources(chatId, SearchSourceCodec.fromFragment(fragment))
        }
    }

    private fun startSearchTool(chatId: String) {
        val message = KinoMessage(
            id = nextLiveToolId--,
            chatId = chatId,
            role = MessageRole.TOOL,
            text = "Web search",
            createdAt = System.currentTimeMillis(),
            tool = ToolActivity("search", "Working", SearchSourceCodec.encode(emptyList())),
            kind = "search",
        )
        liveSearchToolId = message.id
        _streamingTools.update { it + message }
    }

    private fun ensureSearchTool(chatId: String) {
        val id = liveSearchToolId
        if (id == null || _streamingTools.value.none { it.id == id }) startSearchTool(chatId)
    }

    private fun addSearchSources(chatId: String, incoming: List<SearchSource>) {
        ensureSearchTool(chatId)
        if (incoming.isEmpty()) return
        val id = liveSearchToolId ?: return
        _streamingTools.update { current ->
            current.map { message ->
                if (message.id != id) message
                else {
                    val detail = SearchSourceCodec.encode(
                        SearchSourceCodec.merge(SearchSourceCodec.decode(message.tool?.detail.orEmpty()), incoming),
                    )
                    message.copy(tool = message.tool?.copy(detail = detail))
                }
            }
        }
    }

    private fun mergeOpenedPage(chatId: String, page: SearchSource) {
        ensureSearchTool(chatId)
        val id = liveSearchToolId ?: return
        _streamingTools.update { current ->
            current.map { message ->
                if (message.id != id) message
                else {
                    val sources = SearchSourceCodec.mergeOpen(SearchSourceCodec.decode(message.tool?.detail.orEmpty()), page)
                    message.copy(tool = message.tool?.copy(detail = SearchSourceCodec.encode(sources)))
                }
            }
        }
    }

    private fun appendOpenedPageContent(chatId: String, content: String) {
        if (content.isEmpty()) return
        ensureSearchTool(chatId)
        val id = liveSearchToolId ?: return
        _streamingTools.update { current ->
            current.map { message ->
                if (message.id != id) message
                else {
                    val sources = SearchSourceCodec.appendLastContent(SearchSourceCodec.decode(message.tool?.detail.orEmpty()), content)
                    message.copy(tool = message.tool?.copy(detail = SearchSourceCodec.encode(sources)))
                }
            }
        }
    }

    fun closeChat() {
        stopGeneration()
        _browserOpen.value = false
        browserSession.setUserControl(false)
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
        if (!coreReady()) {
            _firstRunCorePrompt.value = true
            _providerError.value = "Install Kino Core from Settings > Libraries before chatting."
            return@launch
        }
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
        val filesForMessage = _attachedFiles.value.toList()
        val userMetadata = if (filesForMessage.isEmpty()) null else JSONObject()
            .put("files", JSONArray().apply { filesForMessage.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) } })
            .toString()
        conversations.sendUserMessage(chatId, body, userMetadata)
        _messages.value = conversations.messages(chatId)
        _chats.value = conversations.chats()
        val previous = conversations.providerState(chatId) ?: ChatProviderState(chatId = chatId, modelType = model.id)
        val active = previous.copy(
            modelType = model.id,
            thinkingEnabled = _thinkingEnabled.value && model.supportsThinking,
            searchEnabled = _searchEnabled.value,
        )
        conversations.saveProviderState(active)
        completionCancellation = AtomicBoolean(false)
        _providerBusy.value = true
        _isStreaming.value = true
        _providerError.value = null
        _streamingText.value = ""
        _streamingTools.value = emptyList()
        nextLiveToolId = -10L
        liveSearchToolId = null
        completionJob = viewModelScope.launch {
            var toolActivitiesPersisted = false
            var session = active.sessionId
            var parent = active.parentMessageId
            var usage = active.contextTokens
            try {
                if (session.isBlank()) {
                    session = provider.createSession().optString("id")
                    check(session.isNotBlank()) { "DeepSeek did not return a chat session." }
                    conversations.saveProviderState(active.copy(sessionId = session))
                }
                var toolProtocolVersion = active.toolProtocolVersion
                var nextPrompt = when (toolProtocolVersion) {
                    0 -> ToolCallProtocol.firstPrompt(body)
                    1 -> ToolCallProtocol.browserUpgradePrompt(body)
                    2 -> ToolCallProtocol.unlimitedToolsUpgradePrompt(body)
                    else -> body
                }
                var toolCalls = 0
                while (true) {
                    val turnText = StringBuilder()
                    var responseFragmentText: String? = null
                    var pendingToolName: String? = null
                    var pendingToolId: Long? = null
                    fun updateTurnText() {
                        if (pendingToolName == null) {
                            val snapshot = turnText.toString()
                            ToolCallProtocol.peekName(snapshot)?.let { name ->
                                pendingToolName = name
                                pendingToolId = startLocalTool(chatId, name, LocalToolRegistry.progress(name, JSONObject()).first, "Waiting for the model to finish the tool request.", "Preparing")
                            }
                            _streamingText.value = ToolCallProtocol.visibleText(snapshot)
                        }
                    }
                    fun captureResponse(content: String) {
                        val remaining = ToolCallProtocol.MAX_RESPONSE_CHARS - turnText.length
                        if (remaining > 0) turnText.append(content.take(remaining))
                        updateTurnText()
                    }
                    val result = provider.complete(
                        sessionId = session,
                        parentMessageId = parent,
                        modelType = model.id,
                        prompt = nextPrompt,
                        thinking = active.thinkingEnabled,
                        // Provider search is intentionally off. Kino's browser tool returns a bounded, reusable result within the same local-tool loop.
                        search = false,
                        fileIds = if (toolCalls == 0) filesForMessage.map { it.first } else emptyList(),
                        cancelled = completionCancellation ?: AtomicBoolean(false),
                        onEvent = { event ->
                            when (event.optString("kind")) {
                                "text_delta" -> {
                                    captureResponse(event.optString("content"))
                                }
                                "thinking_fragment" -> if (event.optJSONObject("fragment")?.optString("type") == "THINK") startThinkingTool(chatId)
                                "thinking_delta" -> appendThinkingTool(chatId, event.optString("content"))
                                "fragment_delta" -> when (event.optString("fragment_type")) {
                                    "RESPONSE" -> {
                                        captureResponse(event.optString("content"))
                                    }
                                    "THINK" -> appendThinkingTool(chatId, event.optString("content"))
                                    "TOOL_OPEN" -> appendOpenedPageContent(chatId, event.optString("content"))
                                }
                                "fragment" -> {
                                    val fragment = event.optJSONObject("fragment")
                                    if (fragment?.optString("type")?.uppercase() == "RESPONSE") {
                                        responseFragmentText = fragment.optString("content").take(ToolCallProtocol.MAX_RESPONSE_CHARS)
                                    } else {
                                        handleProviderFragment(chatId, fragment)
                                    }
                                }
                                "usage" -> _contextTokens.value = event.optLong("accumulated_token_usage", _contextTokens.value)
                                "search_fragment" -> addSearchSources(chatId, SearchSourceCodec.fromFragment(event.optJSONObject("fragment") ?: JSONObject()))
                                "search_update" -> addSearchSources(chatId, SearchSourceCodec.fromRaw(event.opt("raw")))
                            }
                        },
                    )
                    session = result.optString("session_id", session)
                    parent = result.optLong("response_message_id", parent)
                    usage = result.optLong("accumulated_token_usage", usage)
                    toolProtocolVersion = 3
                    conversations.saveProviderState(active.copy(sessionId = session, parentMessageId = parent, contextTokens = usage, toolProtocolVersion = toolProtocolVersion))
                    val streamedText = turnText.toString()
                    val rawTurn = responseFragmentText
                        ?.takeIf { streamedText.isBlank() || it.startsWith(streamedText) }
                        ?: streamedText
                    val call = ToolCallProtocol.parse(rawTurn)
                    if (call == null) {
                        pendingToolId?.let { finishLocalTool(it, "Failed", "The model returned an invalid tool request.") }
                        _streamingText.value = if (rawTurn.trim().startsWith(ToolCallProtocol.OPEN)) {
                            "I couldn't read the tool request. Please try again."
                        } else rawTurn
                        break
                    }
                    val (label, summary) = LocalToolRegistry.progress(call.name, call.arguments)
                    _streamingText.value = ""
                    val liveId = if (pendingToolName == call.name) pendingToolId ?: startLocalTool(chatId, call.name, label, summary) else startLocalTool(chatId, call.name, label, summary)
                    finishLocalTool(liveId, "Working", summary)
                    val toolResult = try {
                        toolRegistry.execute(chatId, call.name, call.arguments, browserEnabled = active.searchEnabled)
                    } catch (error: Exception) {
                        if (completionCancellation?.get() == true) throw error
                        if (call.name == "html_to_pdf" && error.message?.contains("Install the PDF renderer", true) == true) _pdfLibraryPrompt.value = true
                        val reason = error.message?.take(500) ?: "Tool failed."
                        finishLocalTool(liveId, "Failed", reason)
                        null
                    }
                    if (toolResult != null) finishLocalTool(liveId, "Done", toolResult.detail)
                    nextPrompt = ToolCallProtocol.resultPrompt(call.name, toolResult?.detail ?: _streamingTools.value.firstOrNull { it.id == liveId }?.tool?.detail.orEmpty(), failed = toolResult == null)
                    toolCalls++
                }
                val answer = _streamingText.value
                _streamingTools.value.forEach { toolMessage ->
                    toolMessage.tool?.let { tool ->
                        conversations.appendAgentEvent(
                            chatId,
                            MessageRole.TOOL,
                            toolMessage.text,
                            tool,
                            tool.name,
                        )
                    }
                }
                toolActivitiesPersisted = true
                if (answer.isNotBlank()) {
                    val metadata = JSONObject().put("session_id", session).put("message_id", parent).toString()
                    conversations.appendAgentEvent(chatId, MessageRole.ASSISTANT, answer, kind = "response", metadata = metadata, tokenUsage = usage)
                }
                conversations.saveProviderState(active.copy(sessionId = session, parentMessageId = parent, contextTokens = usage, toolProtocolVersion = toolProtocolVersion))
                _attachedFiles.value = emptyList()
                _contextTokens.value = usage
            } catch (error: Exception) {
                if (completionCancellation?.get() != true) _providerError.value = error.message?.take(240) ?: "DeepSeek request failed."
                val stopped = completionCancellation?.get() == true
                _streamingTools.update { current -> current.map { message ->
                    val status = message.tool?.status
                    if (status == "Preparing" || status == "Working") {
                        message.copy(tool = message.tool?.copy(status = if (stopped) "Stopped" else "Failed", detail = if (stopped) "Stopped by user." else error.message?.take(500) ?: "Request failed."))
                    } else message
                } }
                if (!toolActivitiesPersisted) {
                    _streamingTools.value.forEach { toolMessage ->
                        toolMessage.tool?.let { tool ->
                            conversations.appendAgentEvent(chatId, MessageRole.TOOL, toolMessage.text, tool, tool.name)
                        }
                    }
                    toolActivitiesPersisted = true
                }
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
                _streamingTools.value = emptyList()
                _providerBusy.value = false
                _isStreaming.value = false
                _streamingText.value = ""
                completionCancellation = null
            }
        }
    }

    fun stopGeneration() {
        completionCancellation?.set(true)
        completionJob?.cancel()
    }

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
        if (!coreReady() || credentialStore.load() == null) return@launch
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
        if (!coreReady() || credentialStore.load() == null) return@launch
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
                var pendingRemoteToolCall: ToolCallProtocol.ToolCall? = null
                var remoteToolProtocolVersion = 0
                for (index in 0 until history.length()) {
                    val message = history.optJSONObject(index) ?: continue
                    val messageId = message.optLong("message_id")
                    val role = message.optString("role").uppercase()
                    val fragments = message.optJSONArray("fragments") ?: JSONArray()
                    val response = StringBuilder()
                    val activities = mutableListOf<ToolActivity>()
                    var lastSearchIndex = -1
                    for (fragmentIndex in 0 until fragments.length()) {
                        val fragment = fragments.optJSONObject(fragmentIndex) ?: continue
                        when (fragment.optString("type").uppercase()) {
                            "RESPONSE" -> response.append(fragment.optString("content"))
                            "THINK" -> activities += ToolActivity("thinking", "Done", fragment.optString("content"))
                            "SEARCH", "TOOL_SEARCH" -> {
                                if (activities.lastOrNull()?.name != "search") {
                                    activities += ToolActivity("search", "Done", SearchSourceCodec.encode(emptyList()))
                                }
                                lastSearchIndex = activities.lastIndex
                                val sources = SearchSourceCodec.merge(
                                    SearchSourceCodec.decode(activities[lastSearchIndex].detail),
                                    SearchSourceCodec.fromFragment(fragment),
                                )
                                activities[lastSearchIndex] = activities[lastSearchIndex].copy(detail = SearchSourceCodec.encode(sources))
                            }
                            "TOOL_OPEN" -> {
                                if (lastSearchIndex < 0) {
                                    activities += ToolActivity("search", "Done", SearchSourceCodec.encode(emptyList()))
                                    lastSearchIndex = activities.lastIndex
                                }
                                SearchSourceCodec.fromOpenFragment(fragment)?.let { page ->
                                    val sources = SearchSourceCodec.mergeOpen(SearchSourceCodec.decode(activities[lastSearchIndex].detail), page)
                                    activities[lastSearchIndex] = activities[lastSearchIndex].copy(detail = SearchSourceCodec.encode(sources))
                                }
                            }
                        }
                    }
                    when (role) {
                        "USER" -> if (response.isNotBlank()) {
                            val prompt = response.toString()
                            if (ToolCallProtocol.hasPromptPrelude(prompt)) remoteToolProtocolVersion = 1
                            val toolResult = ToolCallProtocol.parseResultPrompt(prompt)
                            if (toolResult != null && pendingRemoteToolCall?.name == toolResult.name) {
                                remoteToolProtocolVersion = 1
                                conversations.appendAgentEvent(
                                    localId,
                                    MessageRole.TOOL,
                                    LocalToolRegistry.activityLabel(toolResult.name),
                                    ToolActivity(toolResult.name, if (toolResult.succeeded) "Done" else "Failed", toolResult.output),
                                    toolResult.name,
                                )
                                pendingRemoteToolCall = null
                            } else {
                                pendingRemoteToolCall = null
                                conversations.sendUserMessage(localId, ToolCallProtocol.stripPromptPrelude(prompt))
                            }
                        }
                        "ASSISTANT" -> {
                            val toolCall = ToolCallProtocol.parse(response.toString())
                            activities.forEach { activity ->
                                val label = if (activity.name == "search") "Web search" else "Thinking"
                                conversations.appendAgentEvent(localId, MessageRole.TOOL, label, activity, activity.name)
                            }
                            if (toolCall != null) {
                                pendingRemoteToolCall = toolCall
                            } else if (response.isNotBlank()) {
                                val metadata = JSONObject().put("session_id", remote.id).put("message_id", messageId).toString()
                                conversations.appendAgentEvent(localId, MessageRole.ASSISTANT, response.toString(), kind = "response", metadata = metadata, tokenUsage = message.optLong("accumulated_token_usage"))
                                parent = messageId
                            }
                        }
                    }
                    usage = maxOf(usage, message.optLong("accumulated_token_usage"))
                }
                conversations.saveProviderState(state.copy(parentMessageId = parent, contextTokens = usage, toolProtocolVersion = remoteToolProtocolVersion))
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
        val localChat = conversations.chatForSession(session.id)
        runCatching { provider.updateSessionTitle(session.id, title) }
            .onSuccess {
                localChat?.let { conversations.renameChat(it, title) }
                _chats.value = conversations.chats()
                refreshRemoteSessions()
            }
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

    fun setTableCellMinWidth(widthDp: Int) {
        val width = widthDp.coerceIn(64, 240)
        preferences.edit().putInt("table_cell_min_width", width).apply()
        _tableCellMinWidth.value = width
    }

    override fun onCleared() {
        stopGeneration()
        browserSession.destroy()
        provider.close()
        super.onCleared()
    }
}
