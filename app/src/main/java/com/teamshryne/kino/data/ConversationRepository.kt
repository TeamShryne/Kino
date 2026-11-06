package com.teamshryne.kino.data

import com.teamshryne.kino.data.local.KinoDatabase
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.ChatProviderState
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.ToolActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Storage boundary used by the presentation layer; provider and DB work stay outside Compose. */
interface ConversationRepository {
    suspend fun chats(): List<KinoChat>
    suspend fun messages(chatId: String): List<KinoMessage>
    suspend fun createChat(title: String = "New chat"): String
    suspend fun sendUserMessage(chatId: String, text: String, metadata: String? = null)
    suspend fun appendAgentEvent(chatId: String, role: MessageRole, text: String, tool: ToolActivity? = null, kind: String? = null, metadata: String? = null, tokenUsage: Long? = null)
    suspend fun providerState(chatId: String): ChatProviderState?
    suspend fun saveProviderState(state: ChatProviderState)
    suspend fun chatForSession(sessionId: String): String?
    suspend fun renameChat(chatId: String, title: String)
    suspend fun deleteChat(chatId: String)
}

class LocalConversationRepository(private val database: KinoDatabase) : ConversationRepository {
    override suspend fun chats() = withContext(Dispatchers.IO) { database.listChats() }
    override suspend fun messages(chatId: String) = withContext(Dispatchers.IO) { database.listMessages(chatId) }
    override suspend fun createChat(title: String) = withContext(Dispatchers.IO) { database.createChat(title) }
    override suspend fun sendUserMessage(chatId: String, text: String, metadata: String?) = withContext(Dispatchers.IO) {
        database.addUserMessage(chatId, text, metadata)
    }
    override suspend fun appendAgentEvent(chatId: String, role: MessageRole, text: String, tool: ToolActivity?, kind: String?, metadata: String?, tokenUsage: Long?) =
        withContext(Dispatchers.IO) { database.addAgentMessage(chatId, role, text, tool, kind, metadata, tokenUsage) }
    override suspend fun providerState(chatId: String) = withContext(Dispatchers.IO) { database.providerState(chatId) }
    override suspend fun saveProviderState(state: ChatProviderState) = withContext(Dispatchers.IO) { database.saveProviderState(state) }
    override suspend fun chatForSession(sessionId: String) = withContext(Dispatchers.IO) { database.chatForSession(sessionId) }
    override suspend fun renameChat(chatId: String, title: String) = withContext(Dispatchers.IO) { database.renameChat(chatId, title); Unit }
    override suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        database.deleteChat(chatId)
        Unit
    }
}
