package com.teamshryne.kino.data.provider

import android.content.Context
import com.teamshryne.kinocore.mobile.EventListener
import com.teamshryne.kinocore.mobile.Mobile
import com.teamshryne.kinocore.mobile.MobileCore
import com.teamshryne.kino.data.security.DeepSeekCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class ProviderModel(
    val id: String,
    val name: String,
    val description: String,
    val available: Boolean,
    val supportsThinking: Boolean,
    val supportsSearch: Boolean,
    val supportsFiles: Boolean,
    val supportsVision: Boolean,
    val inputCharacterLimit: Long,
    val isDefault: Boolean,
    val maxInputFileCount: Int,
    val maxUploadFileBytes: Long,
    val supportedExtensions: List<String>,
)

data class ProviderCapabilities(
    val models: List<ProviderModel>,
    val normalContextLimit: Long,
    val thinkingContextLimit: Long,
)

data class ProviderSession(val id: String, val title: String, val pinned: Boolean, val updatedAt: Double, val modelType: String)

class DeepSeekCoreClient(context: Context) : AutoCloseable {
    private val credentialStore = com.teamshryne.kino.data.security.DeepSeekCredentialStore(context)
    private val core: MobileCore = Mobile.newMobileCore()

    suspend fun capabilities(): ProviderCapabilities = withContext(Dispatchers.IO) {
        val result = JSONObject(core.capabilities(credentialsJson(), credentialStore.deviceId()))
        val modelArray = result.optJSONArray("models") ?: JSONArray()
        val models = buildList {
            for (index in 0 until modelArray.length()) {
                val model = modelArray.optJSONObject(index) ?: continue
                if (model.optBoolean("enabled", false) && model.optBoolean("switchable", false)) {
                    add(
                        // The API's feature object carries provider-specific upload limits.
                        ProviderModel(
                            id = model.optString("model_type"),
                            name = model.optString("name", model.optString("model_type")),
                            description = model.optString("description"),
                            available = true,
                            supportsThinking = model.has("think_feature") && !model.isNull("think_feature") && model.opt("think_feature") != false,
                            supportsSearch = model.has("search_feature") && !model.isNull("search_feature") && model.opt("search_feature") != false,
                            supportsFiles = model.optJSONObject("file_feature") != null,
                            supportsVision = model.optJSONObject("file_feature")?.optBoolean("vision") == true,
                            inputCharacterLimit = model.optLong("input_character_limit"),
                            isDefault = model.optBoolean("is_default"),
                            maxInputFileCount = model.optJSONObject("file_feature")?.optInt("max_input_file_count") ?: 0,
                            maxUploadFileBytes = model.optJSONObject("file_feature")?.optLong("max_upload_file_size") ?: 0,
                            supportedExtensions = model.optJSONObject("file_feature")?.optJSONArray("support_file_exts")?.let { exts ->
                                buildList { for (i in 0 until exts.length()) add(exts.optString(i).removePrefix("." ).lowercase()) }
                            }.orEmpty(),
                        ),
                    )
                }
            }
        }
        val limits = result.optJSONObject("token_limits") ?: JSONObject()
        ProviderCapabilities(
            models = models,
            normalContextLimit = limits.optLong("normal_history_and_files"),
            thinkingContextLimit = limits.optLong("thinking_history_and_files"),
        )
    }

    suspend fun createSession(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(core.createSession(credentialsJson()))
    }

    suspend fun complete(
        sessionId: String,
        parentMessageId: Long,
        modelType: String,
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        fileIds: List<String> = emptyList(),
        cancelled: AtomicBoolean,
        onEvent: (JSONObject) -> Unit,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = JSONObject()
            .put("device_id", credentialStore.deviceId())
            .put("timeout_ms", 300_000)
            .put("session_id", sessionId)
            .put("parent_message_id", parentMessageId)
            .put("model_type", modelType)
            .put("prompt", prompt)
            .put("thinking_enabled", thinking)
            .put("search_enabled", search)
            .put("ref_file_ids", JSONArray(fileIds))
            .put("source", "default")
        val listener = object : EventListener {
            override fun isCancelled(): Boolean = cancelled.get()
            override fun onEvent(eventJSON: String) = onEvent(JSONObject(eventJSON))
        }
        JSONObject(core.complete(credentialsJson(), request.toString(), listener))
    }

    suspend fun history(sessionId: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(core.history(credentialsJson(), sessionId))
    }

    suspend fun sessions(limit: Int = 30, updatedAt: Double = 0.0): JSONObject = withContext(Dispatchers.IO) {
        val request = JSONObject().put("limit", limit).put("updated_at", updatedAt).put("mode", "lte")
        JSONObject(core.sessions(credentialsJson(), request.toString()))
    }

    suspend fun remoteSessions(limit: Int = 50): List<ProviderSession> = withContext(Dispatchers.IO) {
        val credentialJSON = credentialsJson()
        buildList {
            for (pinned in listOf(false, true)) {
                val request = JSONObject().put("limit", limit).put("mode", "lte").put("pinned", pinned)
                val rows = JSONObject(core.sessions(credentialJSON, request.toString())).optJSONArray("sessions") ?: JSONArray()
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    add(ProviderSession(row.optString("id"), row.optString("title", "DeepSeek chat"), row.optBoolean("pinned"), row.optDouble("updated_at"), row.optString("model_type")))
                }
            }
        }.distinctBy { it.id }.sortedByDescending { it.updatedAt }
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        core.updateSessionTitle(credentialsJson(), sessionId, title)
    }

    suspend fun deleteSessions(ids: List<String>) = withContext(Dispatchers.IO) {
        core.deleteSessions(credentialsJson(), JSONArray(ids).toString())
    }

    suspend fun setPinned(ids: List<String>, pinned: Boolean) = withContext(Dispatchers.IO) {
        core.setSessionsPinned(credentialsJson(), JSONArray(ids).toString(), pinned)
    }

    suspend fun uploadFile(path: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(core.uploadFile(credentialsJson(), path))
    }

    suspend fun fetchFiles(ids: List<String>): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(core.fetchFiles(credentialsJson(), JSONArray(ids).toString()))
    }

    suspend fun submitFeedback(sessionId: String, messageId: Long, type: String, description: String = "") = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("session_id", sessionId).put("message_id", messageId).put("type", type).put("description", description)
        core.submitFeedback(credentialsJson(), payload.toString())
    }

    fun credentialStore() = credentialStore

    private fun credentialsJson(): String = requireNotNull(credentialStore.load()) {
        "Connect your DeepSeek account in Settings before chatting."
    }.toCoreJson()

    override fun close() {
        runCatching { core.close() }
    }
}
