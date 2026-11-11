package com.teamshryne.kino.domain.model

data class KinoChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val pinned: Boolean = false,
    val hasFiles: Boolean = false,
)

data class KinoMessage(
    val id: Long,
    val chatId: String,
    val role: MessageRole,
    val text: String,
    val createdAt: Long,
    val tool: ToolActivity? = null,
    val kind: String? = null,
    val metadata: String? = null,
    val tokenUsage: Long? = null,
)

data class ChatProviderState(
    val chatId: String,
    val provider: String = "deepseek",
    val sessionId: String = "",
    val parentMessageId: Long = 0,
    val modelType: String = "instant",
    val contextTokens: Long = 0,
    val thinkingEnabled: Boolean = false,
    val searchEnabled: Boolean = false,
    val toolProtocolVersion: Int = 0,
)

enum class MessageRole {
    USER, ASSISTANT, TOOL;

    companion object {
        fun fromStorage(value: String): MessageRole = when (value) {
            "user" -> USER
            "tool" -> TOOL
            else -> ASSISTANT
        }
    }
}

data class ToolActivity(
    val name: String,
    val status: String,
    val detail: String,
)
