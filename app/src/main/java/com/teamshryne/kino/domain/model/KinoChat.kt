package com.teamshryne.kino.domain.model

data class KinoChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
)

data class KinoMessage(
    val id: Long,
    val chatId: String,
    val role: MessageRole,
    val text: String,
    val createdAt: Long,
    val tool: ToolActivity? = null,
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
