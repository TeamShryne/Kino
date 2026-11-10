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
import com.teamshryne.kino.data.tools.OfficeDocumentRenderer
import com.teamshryne.kino.data.tools.OfficeLimits
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
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val MAX_THINKING_TRACE_CHARS = 1_200
private const val MAX_COMPLETION_ATTEMPTS = 3
private const val COMPLETION_RETRY_DELAY_MS = 3_000L
private const val RATE_LIMIT_RETRY_DELAY_MS = 30_000L

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
    private val runtime = application as KinoApplication
    private val provider = runtime.provider
    val browserSession = runtime.browserSession
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
    private val _onboardingComplete = MutableStateFlow(preferences.getBoolean("onboarding_complete", false))
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()
    private fun currentPdfLimits() = com.teamshryne.kino.data.tools.PdfLimits(
        maxPages = _pdfMaxPages.value,
        maxHtmlChars = _pdfMaxHtmlKb.value * 1024,
        maxPageChars = _pdfMaxPageKb.value * 1024,
        maxPdfBytes = _pdfMaxMb.value.toLong() * 1024 * 1024,
    )
    private val officeRenderer = OfficeDocumentRenderer(application, OfficeLimits())
    private val toolRegistry = LocalToolRegistry(
        messages = { chatId -> conversations.messages(chatId) },
        renderPdf = { title, pages, paged -> HtmlPdfRenderer(application, currentPdfLimits()).create(title, pages, paged) },
        pdfLimits = { currentPdfLimits() },
        browse = { arguments ->
            kotlinx.coroutines.delay(100)
            runtime.browserMutex.withLock { browserSession.execute(arguments) }
        },
        renderOffice = { arguments -> officeRenderer.render(arguments) },
    )
    private val credentialStore = provider.credentialStore()
    private val _chats = MutableStateFlow<List<KinoChat>>(emptyList())
    val chats: StateFlow<List<KinoChat>> = _chats.asStateFlow()
    private val _activeChat = MutableStateFlow<String?>(null)
    val activeChat: StateFlow<String?> = _activeChat.asStateFlow()
    private val _messages = MutableStateFlow<List<KinoMessage>>(emptyList())
    val messages: StateFlow<List<KinoMessage>> = _messages.asStateFlow()
    private val _themeMode = MutableStateFlow(preferences.getString("theme_mode", null) ?: if (preferences.getBoolean("dark", false)) "dark" else "light")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()
    private val _darkTheme = MutableStateFlow(preferences.getBoolean("dark", false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()
    private val _showToolDetails = MutableStateFlow(preferences.getBoolean("tool_details", true))
    val showToolDetails: StateFlow<Boolean> = _showToolDetails.asStateFlow()
    private val _tableCellMinWidth = MutableStateFlow(preferences.getInt("table_cell_min_width", 112).coerceIn(64, 240))
    val tableCellMinWidth: StateFlow<Int> = _tableCellMinWidth.asStateFlow()
    // Defaults are 20 pages / 200 KB / 100 KB / 25 MB; the ranges below are
    // only absolute safety ceilings, so users may customise above defaults.
    private val _pdfMaxPages = MutableStateFlow(preferences.getInt("pdf_max_pages", 20).coerceIn(1, 100))
    val pdfMaxPages: StateFlow<Int> = _pdfMaxPages.asStateFlow()
    private val _pdfMaxHtmlKb = MutableStateFlow(preferences.getInt("pdf_max_html_kb", 200).coerceIn(1, 1024))
    val pdfMaxHtmlKb: StateFlow<Int> = _pdfMaxHtmlKb.asStateFlow()
    private val _pdfMaxPageKb = MutableStateFlow(preferences.getInt("pdf_max_page_kb", 100).coerceIn(1, 512))
    val pdfMaxPageKb: StateFlow<Int> = _pdfMaxPageKb.asStateFlow()
    private val _pdfMaxMb = MutableStateFlow(preferences.getInt("pdf_max_mb", 25).coerceIn(1, 100))
    val pdfMaxMb: StateFlow<Int> = _pdfMaxMb.asStateFlow()
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
    private val generations = runtime.generations
    private val _contextTokens = MutableStateFlow(0L)
    val contextTokens: StateFlow<Long> = _contextTokens.asStateFlow()
    private val _remoteSessions = MutableStateFlow<List<ProviderSession>>(emptyList())
    val remoteSessions: StateFlow<List<ProviderSession>> = _remoteSessions.asStateFlow()
    private val _attachedFiles = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val attachedFiles: StateFlow<List<Pair<String, String>>> = _attachedFiles.asStateFlow()
    private val _draftTitle = MutableStateFlow<String?>(null)
    val draftTitle: StateFlow<String?> = _draftTitle.asStateFlow()

    private fun generation(chatId: String) = generations.getOrPut(chatId) { ChatGeneration(chatId) }
    private fun newLiveToolId(@Suppress("UNUSED_PARAMETER") chatId: String) = runtime.nextLiveToolId.getAndDecrement()
    private fun publishGeneration(chatId: String) {
        if (_activeChat.value != chatId) return
        val state = generations[chatId]
        _streamingText.value = state?.text?.value.orEmpty()
        _streamingTools.value = state?.tools?.value.orEmpty()
        _isStreaming.value = state?.let { it.job?.isActive == true && !it.cancelled.get() } == true
    }
    private fun generationChanged(chatId: String) {
        publishGeneration(chatId)
        _providerBusy.value = generations.values.any { it.job?.isActive == true }
        runtime.generationRevision.update { GenerationRevision(chatId, runtime.nextGenerationRevision.incrementAndGet()) }
        val active = generations.values.any { it.job?.isActive == true }
        val activeCount = generations.values.count { it.job?.isActive == true }
        val changedCount = runtime.activeGenerationCount != activeCount
        runtime.activeGenerationCount = activeCount
        val changedForeground = runtime.serviceForeground.getAndSet(active) != active
        if (changedForeground || changedCount) KinoGenerationService.update(getApplication(), active)
    }
    private fun setLiveText(chatId: String, value: String) {
        generation(chatId).text.value = value
        generationChanged(chatId)
    }
    private fun updateLiveTools(chatId: String, transform: (List<KinoMessage>) -> List<KinoMessage>) {
        generation(chatId).tools.update(transform)
        generationChanged(chatId)
    }

    init {
        viewModelScope.launch {
            runtime.generationRevision.collect { revision ->
                _activeChat.value?.takeIf { it == revision.chatId }?.let { chatId ->
                    publishGeneration(chatId)
                    if (generations[chatId] == null) {
                        _messages.value = conversations.messages(chatId)
                        _chats.value = conversations.chats()
                    }
                }
            }
        }
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
            } else if (core?.updateAvailable == true) {
                _firstRunCorePrompt.value = true
            }
        } catch (error: Exception) { _libraryError.value = error.message?.take(240) ?: "Could not load available libraries." }
    }

    fun installLibrary(id: String) = viewModelScope.launch {
        val item = _libraries.value.firstOrNull { it.library.id == id }?.library ?: run { _libraryError.value = "Library is not in the catalog."; return@launch }
        if (_libraryBusy.value != null) return@launch
        if (id == "kino-core" && runtime.activeGenerationCount > 0) {
            _libraryError.value = "Wait for active chats to finish before updating Kino Core."
            return@launch
        }
        _libraryBusy.value = id; _libraryProgress.value = 0f; _libraryError.value = null
        try {
            libraryManager.install(item) { received, total -> _libraryProgress.value = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f }
            refreshLibraries()
            if (id == "kino-core") dismissCorePrompt()
        } catch (error: Exception) { _libraryError.value = error.message?.take(300) ?: "Library installation failed." }
        finally { _libraryBusy.value = null; _libraryProgress.value = 0f }
    }

    fun dismissCorePrompt() { preferences.edit().putBoolean("core_prompt_seen", true).apply(); _firstRunCorePrompt.value = false }

    fun completeOnboarding() {
        preferences.edit().putBoolean("onboarding_complete", true).apply()
        _onboardingComplete.value = true
    }

    fun openBrowser() { _browserOpen.value = true }
    fun toggleBrowser() { if (_browserOpen.value) closeBrowser() else openBrowser() }
    fun closeBrowser() { _browserOpen.value = false; browserSession.setUserControl(false) }

    fun refresh() = viewModelScope.launch {
        _chats.value = conversations.chats()
    }

    fun openChat(id: String) = viewModelScope.launch {
        _activeChat.value = id
        publishGeneration(id)
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
            id = newLiveToolId(chatId),
            chatId = chatId,
            role = MessageRole.TOOL,
            text = "Thinking",
            createdAt = System.currentTimeMillis(),
            tool = ToolActivity("thinking", "Working", ""),
            kind = "thinking",
        )
        generation(chatId).liveThinkingToolId = message.id
        updateLiveTools(chatId) { it + message }
    }

    private fun ensureThinkingTool(chatId: String) {
        val state = generation(chatId)
        val id = state.liveThinkingToolId
        if (id == null || state.tools.value.none { it.id == id }) startThinkingTool(chatId)
    }

    private fun finishThinkingTools(chatId: String) {
        updateLiveTools(chatId) { current ->
            current.map { message ->
                if (message.tool?.name == "thinking" && message.tool.status in setOf("Preparing", "Working")) {
                    message.copy(tool = message.tool.copy(status = "Done"))
                } else {
                    message
                }
            }
        }
        generation(chatId).liveThinkingToolId = null
    }

    private fun startLocalTool(chatId: String, name: String, label: String, summary: String, status: String = "Working"): Long {
        val message = KinoMessage(
            id = newLiveToolId(chatId),
            chatId = chatId,
            role = MessageRole.TOOL,
            text = label,
            createdAt = System.currentTimeMillis(),
            tool = ToolActivity(name, status, summary),
            kind = name,
        )
        updateLiveTools(chatId) { it + message }
        return message.id
    }

    private fun finishLocalTool(id: Long, status: String, detail: String) {
        val owner = generations.values.firstOrNull { state -> state.tools.value.any { it.id == id } } ?: return
        updateLiveTools(owner.chatId) { current -> current.map { message ->
            if (message.id == id) message.copy(tool = message.tool?.copy(status = status, detail = detail)) else message
        } }
    }

    private fun updateAssistantBeforeTool(chatId: String, beforeId: Long?, existingId: Long?, text: String, metadata: String?): Long? {
        if (text.isBlank()) return existingId
        val eventId = existingId ?: newLiveToolId(chatId)
        val message = KinoMessage(
            id = eventId,
            chatId = chatId,
            role = MessageRole.ASSISTANT,
            text = text,
            createdAt = System.currentTimeMillis(),
            kind = "response",
            metadata = metadata,
        )
        updateLiveTools(chatId) { current ->
            val existingIndex = current.indexOfFirst { it.id == eventId }
            if (existingIndex >= 0) {
                return@updateLiveTools current.toMutableList().apply { this[existingIndex] = message }
            }
            val index = beforeId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
            if (index < 0) current + message
            else current.toMutableList().apply { add(index, message) }
        }
        return eventId
    }

    private suspend fun persistStreamingEvents(chatId: String) {
        generation(chatId).tools.value.toList().forEach { event ->
            if (event.role == MessageRole.ASSISTANT) {
                conversations.appendAgentEvent(
                    chatId,
                    MessageRole.ASSISTANT,
                    event.text,
                    kind = event.kind ?: "response",
                    metadata = event.metadata,
                )
            } else {
                event.tool?.let { activity ->
                    conversations.appendAgentEvent(chatId, MessageRole.TOOL, event.text, activity, activity.name)
                }
            }
        }
    }

    private fun appendThinkingTool(chatId: String, content: String) {
        if (content.isEmpty()) return
        val state = generation(chatId)
        val liveThinkingId = state.liveThinkingToolId
        updateLiveTools(chatId) { current ->
            val liveIndex = liveThinkingId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
            if (liveIndex >= 0) {
                val last = current[liveIndex]
                val activity = last.tool
                if (activity?.name == "thinking") {
                    return@updateLiveTools current.toMutableList().apply {
                        this[liveIndex] = last.copy(tool = activity.copy(detail = (activity.detail + content).takeLast(MAX_THINKING_TRACE_CHARS)))
                    }
                }
            }
            val last = current.lastOrNull()
            val activity = last?.tool
            if (last != null && activity?.name == "thinking") {
                state.liveThinkingToolId = last.id
                current.dropLast(1) + last.copy(tool = activity.copy(detail = (activity.detail + content).takeLast(MAX_THINKING_TRACE_CHARS)))
            } else {
                current + KinoMessage(
                    id = newLiveToolId(chatId),
                    chatId = chatId,
                    role = MessageRole.TOOL,
                    text = "Thinking",
                    createdAt = System.currentTimeMillis(),
                    tool = ToolActivity("thinking", "Working", content),
                    kind = "thinking",
                ).also { state.liveThinkingToolId = it.id }
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
            id = newLiveToolId(chatId),
            chatId = chatId,
            role = MessageRole.TOOL,
            text = "Web search",
            createdAt = System.currentTimeMillis(),
            tool = ToolActivity("search", "Working", SearchSourceCodec.encode(emptyList())),
            kind = "search",
        )
        generation(chatId).liveSearchToolId = message.id
        updateLiveTools(chatId) { it + message }
    }

    private fun ensureSearchTool(chatId: String) {
        val state = generation(chatId)
        val id = state.liveSearchToolId
        if (id == null || state.tools.value.none { it.id == id }) startSearchTool(chatId)
    }

    private fun addSearchSources(chatId: String, incoming: List<SearchSource>) {
        ensureSearchTool(chatId)
        if (incoming.isEmpty()) return
        val id = generation(chatId).liveSearchToolId ?: return
        updateLiveTools(chatId) { current ->
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
        val id = generation(chatId).liveSearchToolId ?: return
        updateLiveTools(chatId) { current ->
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
        val id = generation(chatId).liveSearchToolId ?: return
        updateLiveTools(chatId) { current ->
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
        _browserOpen.value = false
        browserSession.setUserControl(false)
        _activeChat.value = null
        _messages.value = emptyList()
        _streamingText.value = ""
        _streamingTools.value = emptyList()
        _isStreaming.value = false
        _draftTitle.value = null
        refresh()
    }

    fun newChat() {
        // A new chat is kept as an in-memory draft until the first message is
        // sent, so empty conversations never pollute the saved chat list.
        val id = "draft-${UUID.randomUUID()}"
        _activeChat.value = id
        _messages.value = emptyList()
        _contextTokens.value = 0
        _thinkingEnabled.value = false
        _searchEnabled.value = false
        _draftTitle.value = null
        publishGeneration(id)
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
        val currentActive = _activeChat.value
        val chatId = when {
            currentActive == null -> conversations.createChat(body.take(48)).also { _activeChat.value = it }
            currentActive.startsWith("draft-") -> conversations.createChat(_draftTitle.value ?: body.take(48)).also {
                _activeChat.value = it
                _draftTitle.value = null
            }
            else -> currentActive
        }
        val requestedModelId = _selectedModel.value
        val requestedThinking = _thinkingEnabled.value
        val requestedSearch = _searchEnabled.value
        val filesForMessage = _attachedFiles.value.toList()
        val generation = generation(chatId)
        if (generation.job?.isActive == true) {
            if (generation.cancelled.get()) _providerError.value = "Kino is stopping the previous response in this chat. Try sending again in a moment."
            return@launch
        }
        val model = _capabilities.value?.models?.firstOrNull { it.id == requestedModelId }
            ?: _capabilities.value?.models?.firstOrNull { it.isDefault }
            ?: _capabilities.value?.models?.firstOrNull()
        if (model == null) {
            _providerError.value = "Could not load available DeepSeek models. Check your connection in Settings."
            return@launch
        }
        var previous = conversations.providerState(chatId) ?: ChatProviderState(chatId = chatId, modelType = model.id)
        val previousMessages = conversations.messages(chatId)
        val interruptedCompletion = previousMessages.lastOrNull()?.tool?.name == "error"
        if (interruptedCompletion && previous.sessionId.isNotBlank()) {
            try {
                val (remoteHead, remoteUsage) = latestRemoteHead(previous.sessionId)
                previous = previous.copy(parentMessageId = remoteHead, contextTokens = maxOf(previous.contextTokens, remoteUsage))
                conversations.saveProviderState(previous)
            } catch (error: Exception) {
                _providerError.value = "Kino couldn't resync this DeepSeek chat after the interrupted response. Check your connection and send again. ${error.message?.take(120).orEmpty()}"
                return@launch
            }
        }
        val userMetadata = if (filesForMessage.isEmpty()) null else JSONObject()
            .put("files", JSONArray().apply { filesForMessage.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) } })
            .toString()
        conversations.sendUserMessage(chatId, body, userMetadata)
        if (_activeChat.value == chatId) _messages.value = conversations.messages(chatId)
        _chats.value = conversations.chats()
        val active = previous.copy(
            modelType = model.id,
            thinkingEnabled = requestedThinking && model.supportsThinking,
            searchEnabled = requestedSearch,
        )
        conversations.saveProviderState(active)
        generation.cancelled.set(false)
        _providerError.value = null
        generation.text.value = ""
        generation.tools.value = emptyList()
        generation.liveSearchToolId = null
        generation.liveThinkingToolId = null
        generationChanged(chatId)
        generation.job = runtime.scope.launch {
            generationChanged(chatId)
            var toolActivitiesPersisted = false
            var session = active.sessionId
            var parent = active.parentMessageId
            var lastResponseMessageCheckpoint = AtomicLong(parent)
            var usage = active.contextTokens
            var toolProtocolVersion = active.toolProtocolVersion
            try {
                if (session.isBlank()) {
                    session = provider.createSession().optString("id")
                    check(session.isNotBlank()) { "DeepSeek did not return a chat session." }
                    conversations.saveProviderState(active.copy(sessionId = session))
                }
                var nextPrompt = when (toolProtocolVersion) {
                    0 -> ToolCallProtocol.firstPrompt(body)
                    1 -> ToolCallProtocol.browserUpgradePrompt(body)
                    2 -> ToolCallProtocol.unlimitedToolsUpgradePrompt(body)
                    3 -> ToolCallProtocol.unlimitedToolsUpgradePrompt(body)
                    4 -> ToolCallProtocol.browserToolsUpgradePrompt(body)
                    else -> body
                }
                var toolCalls = 0
                while (true) {
                    val responseMessageCheckpoint = AtomicLong(parent)
                    lastResponseMessageCheckpoint = responseMessageCheckpoint
                    val turnText = StringBuilder()
                    var responseFragmentText: String? = null
                    var pendingToolName: String? = null
                    var pendingToolId: Long? = null
                    var pendingPreludeId: Long? = null
                    fun updateTurnText() {
                        val snapshot = turnText.toString()
                        if (pendingToolName == null) {
                            ToolCallProtocol.peekName(snapshot)?.let { name ->
                                pendingToolName = name
                                pendingToolId = startLocalTool(chatId, name, LocalToolRegistry.progress(name, JSONObject()).first, "Waiting for the model to finish the tool request.", "Preparing")
                            }
                        }
                        val visible = ToolCallProtocol.visibleText(snapshot).trim()
                        if (pendingToolName != null) {
                            val metadata = session.takeIf { it.isNotBlank() && parent > 0 }?.let {
                                JSONObject().put("session_id", session).put("message_id", parent).toString()
                            }
                            pendingPreludeId = updateAssistantBeforeTool(chatId, pendingToolId, pendingPreludeId, visible, metadata)
                            setLiveText(chatId, "")
                        } else {
                            setLiveText(chatId, ToolCallProtocol.visibleText(snapshot))
                        }
                    }
                    fun captureResponse(content: String) {
                        val remaining = ToolCallProtocol.MAX_RESPONSE_CHARS - turnText.length
                        if (remaining > 0) turnText.append(content.take(remaining))
                        updateTurnText()
                    }
                    var completionResult: JSONObject? = null
                    var completionError: Exception? = null
                    for (attempt in 1..MAX_COMPLETION_ATTEMPTS) {
                        try {
                            runtime.completionPacer.waitForSlot(generation.cancelled)
                            if (generation.cancelled.get()) error("Generation stopped by user.")
                            completionResult = provider.complete(
                                sessionId = session,
                                parentMessageId = parent,
                                modelType = model.id,
                                prompt = nextPrompt,
                                thinking = active.thinkingEnabled,
                                // Provider search is intentionally off. Kino's browser tool returns a bounded, reusable result within the same local-tool loop.
                                search = false,
                                fileIds = if (toolCalls == 0) filesForMessage.map { it.first } else emptyList(),
                                cancelled = generation.cancelled,
                                onEvent = { event ->
                                    event.optLong("response_message_id").takeIf { it > 0 }?.let { id ->
                                        responseMessageCheckpoint.updateAndGet { current -> maxOf(current, id) }
                                    }
                                    when (event.optString("kind")) {
                                        "text_delta" -> {
                                            captureResponse(event.optString("content"))
                                        }
                                        "thinking_fragment" -> if (event.optJSONObject("fragment")?.optString("type") == "THINK") ensureThinkingTool(chatId)
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
                                        "usage" -> if (_activeChat.value == chatId) _contextTokens.value = event.optLong("accumulated_token_usage", _contextTokens.value)
                                        "search_fragment" -> addSearchSources(chatId, SearchSourceCodec.fromFragment(event.optJSONObject("fragment") ?: JSONObject()))
                                        "search_update" -> addSearchSources(chatId, SearchSourceCodec.fromRaw(event.opt("raw")))
                                    }
                                },
                            )
                            completionError = null
                            break
                        } catch (error: Exception) {
                            if (generation.cancelled.get()) throw error
                            completionError = error
                            if (!error.isTransientCompletionError() || attempt == MAX_COMPLETION_ATTEMPTS) throw error

                            // A failed stream may have been committed as a partial
                            // assistant turn. Resync the branch before repeating
                            // the same prompt so the retry does not fork from a
                            // stale parent message.
                            if (session.isNotBlank()) {
                                runCatching { latestRemoteHead(session) }.onSuccess { (remoteHead, remoteUsage) ->
                                    if (remoteHead > 0) parent = maxOf(parent, remoteHead)
                                    usage = maxOf(usage, remoteUsage)
                                }
                            }
                            // The provider may have streamed partial text that was
                            // never committed. Clear the turn buffer so a retried
                            // response is not glued to that partial text.
                            turnText.setLength(0)
                            responseFragmentText = null
                            setLiveText(chatId, "")
                            kotlinx.coroutines.delay(if (error.hasRateLimit()) RATE_LIMIT_RETRY_DELAY_MS else COMPLETION_RETRY_DELAY_MS)
                        }
                    }
                    val result = completionResult ?: throw completionError ?: error("DeepSeek completion returned no result.")
                    if (generation.cancelled.get()) error("Generation stopped by user.")
                    finishThinkingTools(chatId)
                    session = result.optString("session_id", session)
                    parent = maxOf(result.optLong("response_message_id", parent), responseMessageCheckpoint.get())
                    usage = result.optLong("accumulated_token_usage", usage)
                    toolProtocolVersion = 5
                    conversations.saveProviderState(active.copy(sessionId = session, parentMessageId = parent, contextTokens = usage, toolProtocolVersion = toolProtocolVersion))
                    val streamedText = turnText.toString()
                    val rawTurn = responseFragmentText
                        ?.takeIf { streamedText.isBlank() || it.startsWith(streamedText) }
                        ?: streamedText
                    val call = ToolCallProtocol.parse(rawTurn)
                    if (call == null) {
                        pendingToolId?.let { finishLocalTool(it, "Failed", "The model returned an invalid tool request.") }
                        setLiveText(chatId, if (rawTurn.trim().startsWith(ToolCallProtocol.OPEN)) {
                            "I couldn't read the tool request. Please try again."
                        } else rawTurn)
                        break
                    }
                    val preToolMessage = ToolCallProtocol.visibleText(rawTurn).trim()
                    if (preToolMessage.isNotBlank()) {
                        val metadata = session.takeIf { it.isNotBlank() && parent > 0 }?.let {
                            JSONObject().put("session_id", session).put("message_id", parent).toString()
                        }
                        pendingPreludeId = updateAssistantBeforeTool(chatId, pendingToolId, pendingPreludeId, preToolMessage, metadata)
                    }
                    val (label, summary) = LocalToolRegistry.progress(call.name, call.arguments)
                    setLiveText(chatId, "")
                    val liveId = if (pendingToolName == call.name) pendingToolId ?: startLocalTool(chatId, call.name, label, summary) else startLocalTool(chatId, call.name, label, summary)
                    finishLocalTool(liveId, "Working", summary)
                    val toolResult = try {
                        toolRegistry.execute(chatId, call.name, call.arguments, browserEnabled = active.searchEnabled)
                    } catch (error: Exception) {
                        if (generation.cancelled.get()) throw error
                        val reason = error.message?.take(500) ?: "Tool failed."
                        finishLocalTool(liveId, "Failed", reason)
                        null
                    }
                    if (generation.cancelled.get()) error("Generation stopped by user.")
                    if (toolResult != null) finishLocalTool(liveId, "Done", toolResult.detail)
                    nextPrompt = ToolCallProtocol.resultPrompt(call.name, toolResult?.detail ?: generation.tools.value.firstOrNull { it.id == liveId }?.tool?.detail.orEmpty(), failed = toolResult == null)
                    toolCalls++
                }
                val answer = generation.text.value
                persistStreamingEvents(chatId)
                toolActivitiesPersisted = true
                if (answer.isNotBlank()) {
                    val metadata = JSONObject().put("session_id", session).put("message_id", parent).toString()
                    conversations.appendAgentEvent(chatId, MessageRole.ASSISTANT, answer, kind = "response", metadata = metadata, tokenUsage = usage)
                }
                conversations.saveProviderState(active.copy(sessionId = session, parentMessageId = parent, contextTokens = usage, toolProtocolVersion = toolProtocolVersion))
                if (_activeChat.value == chatId) {
                    _attachedFiles.value = emptyList()
                    _contextTokens.value = usage
                }
                if (answer.isNotBlank() && _activeChat.value == chatId) closeBrowser()
            } catch (error: Exception) {
                if (!generation.cancelled.get()) {
                    _providerError.value = if (error.hasUnexpectedEof()) {
                        "DeepSeek ended the response stream early. Kino saved the chat and will resync its history before your next message."
                    } else error.message?.take(240) ?: "DeepSeek request failed."
                }
                val stopped = generation.cancelled.get()
                parent = maxOf(parent, lastResponseMessageCheckpoint.get())
                if (!stopped && (error.hasUnexpectedEof() || error.hasMissingResponseId()) && session.isNotBlank()) {
                    runCatching { latestRemoteHead(session) }.onSuccess { (remoteHead, remoteUsage) ->
                        if (remoteHead > 0) parent = maxOf(parent, remoteHead)
                        usage = maxOf(usage, remoteUsage)
                    }
                }
                conversations.saveProviderState(active.copy(
                    sessionId = session,
                    parentMessageId = parent,
                    contextTokens = usage,
                    toolProtocolVersion = toolProtocolVersion,
                ))
                updateLiveTools(chatId) { current -> current.map { message ->
                    val status = message.tool?.status
                    if (status == "Preparing" || status == "Working") {
                        message.copy(tool = message.tool?.copy(status = if (stopped) "Stopped" else "Failed", detail = if (stopped) "Stopped by user." else error.message?.take(500) ?: "Request failed."))
                    } else message
                } }
                if (!toolActivitiesPersisted) {
                    persistStreamingEvents(chatId)
                    toolActivitiesPersisted = true
                }
                if (generation.text.value.isNotBlank()) {
                    val metadata = if (session.isNotBlank() && parent > 0) JSONObject().put("session_id", session).put("message_id", parent).toString() else null
                    conversations.appendAgentEvent(chatId, MessageRole.ASSISTANT, generation.text.value, kind = "response", metadata = metadata, tokenUsage = usage)
                }
                if (!generation.cancelled.get()) {
                    conversations.appendAgentEvent(chatId, MessageRole.TOOL, "Request failed", ToolActivity("error", "Failed", _providerError.value.orEmpty()), "error")
                }
            } finally {
                if (_activeChat.value == chatId) _messages.value = conversations.messages(chatId)
                _chats.value = conversations.chats()
                generation.tools.value = emptyList()
                generation.text.value = ""
                generation.job = null
                generations.remove(chatId, generation)
                generationChanged(chatId)
            }
        }
        generationChanged(chatId)
    }

    fun stopGeneration() {
        val chatId = _activeChat.value ?: return
        val generation = generations[chatId] ?: return
        generation.cancelled.set(true)
        generationChanged(chatId)
    }

    private suspend fun latestRemoteHead(sessionId: String): Pair<Long, Long> {
        val response = provider.history(sessionId)
        val messages = response.optJSONArray("messages") ?: error("DeepSeek did not return chat history.")
        var messageId = 0L
        var usage = 0L
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val candidateId = message.optLong("message_id")
            if (candidateId > 0) messageId = maxOf(messageId, candidateId)
            usage = maxOf(usage, message.optLong("accumulated_token_usage"))
        }
        return messageId to usage
    }

    private fun Throwable.hasUnexpectedEof(): Boolean {
        var cause: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause.message?.contains("eof", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun Throwable.hasMissingResponseId(): Boolean {
        var cause: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause.message?.contains("without a response message id", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun Throwable.hasRateLimit(): Boolean {
        var cause: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            val message = cause.message?.lowercase().orEmpty()
            if (message.contains("rate_limit_reached") || message.contains("rate limit") || message.contains("messages too frequent")) return true
            cause = cause.cause
        }
        return false
    }

    private fun Throwable.isTransientCompletionError(): Boolean {
        var cause: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause.hasUnexpectedEof() || cause.hasMissingResponseId() || cause.hasRateLimit()) return true
            val className = cause::class.java.name.lowercase()
            val message = cause.message?.lowercase().orEmpty()
            if (className.contains("socket") ||
                className.contains("ssl") ||
                className.contains("unknownhost") ||
                className.contains("timeout")
            ) return true
            if (message.contains("http 401") || message.contains("http 403") || message.contains("biz_code") || message.contains("invalid json")) {
                return false
            }
            if (listOf(
                    "connection reset",
                    "connection closed",
                    "connection aborted",
                    "broken pipe",
                    "network is unreachable",
                    "temporary failure",
                    "socket closed",
                    "tls handshake",
                    "timed out"
                ).any { it in message }
            ) return true
            cause = cause.cause
        }
        return false
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
                                val preToolMessage = ToolCallProtocol.visibleText(response.toString()).trim()
                                if (preToolMessage.isNotBlank()) {
                                    val metadata = JSONObject().put("session_id", remote.id).put("message_id", messageId).toString()
                                    conversations.appendAgentEvent(localId, MessageRole.ASSISTANT, preToolMessage, kind = "response", metadata = metadata)
                                }
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

    /** Renames a chat locally and keeps the linked DeepSeek session title in sync. */
    fun renameChat(id: String, title: String) = viewModelScope.launch {
        val clean = title.replace('\n', ' ').trim().take(80)
        if (clean.isEmpty()) return@launch
        if (id.startsWith("draft-")) {
            // A draft chat has no saved row yet, so hold the title until the
            // first message turns the draft into a real conversation.
            _draftTitle.value = clean
            return@launch
        }
        conversations.renameChat(id, clean)
        _chats.value = conversations.chats()
        val sessionId = conversations.providerState(id)?.sessionId
        if (!sessionId.isNullOrBlank() && credentialStore.load() != null) {
            runCatching { provider.updateSessionTitle(sessionId, clean) }
                .onFailure { _providerError.value = it.message?.take(240) ?: "Could not rename the DeepSeek chat." }
        }
    }

    /** Pins or unpins a chat and mirrors the state onto the linked DeepSeek session. */
    fun setChatPinned(id: String, pinned: Boolean) = viewModelScope.launch {
        conversations.setChatPinned(id, pinned)
        _chats.value = conversations.chats()
        val sessionId = conversations.providerState(id)?.sessionId
        if (!sessionId.isNullOrBlank() && credentialStore.load() != null) {
            runCatching { provider.setPinned(listOf(sessionId), pinned) }
                .onFailure { _providerError.value = it.message?.take(240) ?: "Could not update the pinned chat." }
        }
    }

    fun deleteChat(id: String) = viewModelScope.launch {
        if (id.startsWith("draft-")) { closeChat(); return@launch }
        generations[id]?.let { generation ->
            generation.cancelled.set(true)
            generationChanged(id)
            generation.job?.join()
        }
        val remote = conversations.providerState(id)?.sessionId
        if (!remote.isNullOrBlank() && credentialStore.load() != null) {
            runCatching { provider.deleteSessions(listOf(remote)) }
                .onFailure { _providerError.value = it.message?.take(240) ?: "Could not remove the remote DeepSeek chat." }
        }
        conversations.deleteChat(id)
        if (_activeChat.value == id) closeChat() else _chats.value = conversations.chats()
    }

    fun setThemeMode(mode: String) {
        val normalized = when (mode) { "dark", "light", "auto" -> mode; else -> "light" }
        preferences.edit().putString("theme_mode", normalized).putBoolean("dark", normalized == "dark").apply()
        _themeMode.value = normalized
        _darkTheme.value = normalized == "dark"
    }

    fun setDarkTheme(enabled: Boolean) {
        setThemeMode(if (enabled) "dark" else "light")
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

    fun setPdfMaxPages(pages: Int) {
        val value = pages.coerceIn(1, 100)
        preferences.edit().putInt("pdf_max_pages", value).apply()
        _pdfMaxPages.value = value
    }

    fun setPdfMaxHtmlKb(kb: Int) {
        val value = kb.coerceIn(1, 1024)
        preferences.edit().putInt("pdf_max_html_kb", value).apply()
        _pdfMaxHtmlKb.value = value
    }

    fun setPdfMaxPageKb(kb: Int) {
        val value = kb.coerceIn(1, 512)
        preferences.edit().putInt("pdf_max_page_kb", value).apply()
        _pdfMaxPageKb.value = value
    }

    fun setPdfMaxMb(mb: Int) {
        val value = mb.coerceIn(1, 100)
        preferences.edit().putInt("pdf_max_mb", value).apply()
        _pdfMaxMb.value = value
    }

    override fun onCleared() {
        super.onCleared()
    }
}
