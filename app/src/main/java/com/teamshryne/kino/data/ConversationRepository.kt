package com.teamshryne.kino.data

import com.teamshryne.kino.data.local.KinoDatabase
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.ToolActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Storage boundary used by the presentation layer; provider and DB work stay outside Compose. */
interface ConversationRepository {
    suspend fun chats(): List<KinoChat>
    suspend fun messages(chatId: String): List<KinoMessage>
    suspend fun createChat(title: String = "New chat"): String
    suspend fun sendUserMessage(chatId: String, text: String)
    suspend fun appendAgentEvent(chatId: String, role: MessageRole, text: String, tool: ToolActivity? = null)
    suspend fun deleteChat(chatId: String)
}

class LocalConversationRepository(private val database: KinoDatabase) : ConversationRepository {
    override suspend fun chats() = withContext(Dispatchers.IO) { database.listChats() }
    override suspend fun messages(chatId: String) = withContext(Dispatchers.IO) { database.listMessages(chatId) }
    override suspend fun createChat(title: String) = withContext(Dispatchers.IO) { database.createChat(title) }
    override suspend fun sendUserMessage(chatId: String, text: String) = withContext(Dispatchers.IO) {
        database.addUserMessage(chatId, text)
    }
    override suspend fun appendAgentEvent(chatId: String, role: MessageRole, text: String, tool: ToolActivity?) =
        withContext(Dispatchers.IO) { database.addAgentMessage(chatId, role, text, tool) }
    override suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        database.deleteChat(chatId)
        Unit
    }
}
