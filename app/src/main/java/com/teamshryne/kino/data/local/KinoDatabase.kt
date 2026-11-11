package com.teamshryne.kino.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.ChatProviderState
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.ToolActivity
import java.util.UUID

/** Local schema for conversations and their ordered message/tool activity timeline. */
class KinoDatabase(context: android.content.Context) : SQLiteOpenHelper(context, "kino.db", null, 4) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE chats (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            pinned INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
        db.execSQL("""CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK(role IN ('user','assistant','tool')),
            text TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            tool_name TEXT,
            tool_status TEXT,
            tool_detail TEXT,
            core_kind TEXT,
            metadata TEXT,
            token_usage INTEGER
        )""".trimIndent())
        db.execSQL("CREATE INDEX messages_by_chat ON messages(chat_id, id)")
        createProviderStateTable(db)
        seedExample(db)
    }

    private fun createProviderStateTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE chat_provider_state (
            chat_id TEXT PRIMARY KEY NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            provider TEXT NOT NULL DEFAULT 'deepseek',
            session_id TEXT NOT NULL DEFAULT '',
            parent_message_id INTEGER NOT NULL DEFAULT 0,
            model_type TEXT NOT NULL DEFAULT 'instant',
            context_tokens INTEGER NOT NULL DEFAULT 0,
            thinking_enabled INTEGER NOT NULL DEFAULT 0,
            search_enabled INTEGER NOT NULL DEFAULT 0,
            tool_protocol_version INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
    }

    private fun seedExample(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.insertOrThrow("chats", null, ContentValues().apply {
                put("id", "welcome-demo")
                put("title", "Example: A look around Kino")
                put("created_at", now)
                put("updated_at", now)
            })
            insertMessage(db, "welcome-demo", MessageRole.USER, "Can you take a quick look at this project and summarize it?", now)
            insertMessage(
                db, "welcome-demo", MessageRole.TOOL, "Read the project overview", now,
                ToolActivity("read_file", "Done", "README.md\nRead 42 lines · 1.2 KB"),
            )
            insertMessage(
                db, "welcome-demo", MessageRole.TOOL, "Find the main entry points", now,
                ToolActivity("search_files", "Done", "Found 6 source files\nMatched MainActivity.kt and KinoApp.kt"),
            )
            insertMessage(
                db, "welcome-demo", MessageRole.ASSISTANT,
                "Kino keeps the useful parts of an agent chat close at hand: a focused conversation, a readable activity trail, and settings that stay out of your way. This example shows how tool activity can live inline with the conversation.",
                now,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertMessage(
        db: SQLiteDatabase,
        chatId: String,
        role: MessageRole,
        text: String,
        createdAt: Long,
        tool: ToolActivity? = null,
    ): Long = db.insertOrThrow("messages", null, ContentValues().apply {
        put("chat_id", chatId)
        put("role", role.storageValue)
        put("text", text)
        put("created_at", createdAt)
        tool?.let {
            put("tool_name", it.name)
            put("tool_status", it.status)
            put("tool_detail", it.detail)
        }
    })

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN core_kind TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN metadata TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN token_usage INTEGER")
            createProviderStateTable(db)
        }
        if (oldVersion == 2) db.execSQL("ALTER TABLE chat_provider_state ADD COLUMN tool_protocol_version INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4) db.execSQL("ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }

    fun listChats(): List<KinoChat> = readableDatabase.rawQuery(
        """SELECT c.id, c.title, c.updated_at, c.pinned,
            EXISTS(SELECT 1 FROM messages m WHERE m.chat_id=c.id AND m.metadata LIKE '%"files"%'),
            COALESCE((SELECT CASE WHEN m.role='tool' THEN 'Used ' || m.tool_name ELSE m.text END
              FROM messages m WHERE m.chat_id=c.id ORDER BY m.id DESC LIMIT 1), 'New conversation')
            FROM chats c ORDER BY c.pinned DESC, c.updated_at DESC""".trimIndent(), null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toChat()) } }

    fun listMessages(chatId: String): List<KinoMessage> = readableDatabase.rawQuery(
        "SELECT id, chat_id, role, text, created_at, tool_name, tool_status, tool_detail, core_kind, metadata, token_usage FROM messages WHERE chat_id=? ORDER BY id",
        arrayOf(chatId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }

    fun createChat(title: String = "New chat"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("chats", null, ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        })
        return id
    }

    fun addUserMessage(chatId: String, text: String, metadata: String? = null) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            insertMessage(db, chatId, MessageRole.USER, text, now)
            if (metadata != null) db.execSQL("UPDATE messages SET metadata=? WHERE id=last_insert_rowid()", arrayOf(metadata))
            db.rawQuery("SELECT title FROM chats WHERE id=?", arrayOf(chatId)).use { cursor ->
                if (cursor.moveToFirst() && cursor.getString(0) == "New chat") {
                    val title = text.replace('\n', ' ').trim().take(48).ifBlank { "New chat" }
                    db.execSQL("UPDATE chats SET title=? WHERE id=?", arrayOf(title, chatId))
                }
            }
            db.execSQL("UPDATE chats SET updated_at=? WHERE id=?", arrayOf(now, chatId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Persists future provider replies and tool events through the same timeline. */
    fun addAgentMessage(chatId: String, role: MessageRole, text: String, tool: ToolActivity? = null, kind: String? = null, metadata: String? = null, tokenUsage: Long? = null) {
        require(role != MessageRole.USER) { "Use addUserMessage for user content" }
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertMessage(db, chatId, role, text, now, tool)
            db.execSQL("UPDATE messages SET core_kind=?, metadata=?, token_usage=? WHERE id=last_insert_rowid()", arrayOf(kind, metadata, tokenUsage))
            db.execSQL("UPDATE chats SET updated_at=? WHERE id=?", arrayOf(now, chatId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun providerState(chatId: String): ChatProviderState? = readableDatabase.rawQuery(
        "SELECT chat_id, provider, session_id, parent_message_id, model_type, context_tokens, thinking_enabled, search_enabled, tool_protocol_version FROM chat_provider_state WHERE chat_id=?",
        arrayOf(chatId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ChatProviderState(
            chatId = cursor.getString(0), provider = cursor.getString(1), sessionId = cursor.getString(2),
            parentMessageId = cursor.getLong(3), modelType = cursor.getString(4), contextTokens = cursor.getLong(5),
            thinkingEnabled = cursor.getInt(6) != 0, searchEnabled = cursor.getInt(7) != 0,
            toolProtocolVersion = cursor.getInt(8),
        )
    }

    fun saveProviderState(state: ChatProviderState) {
        writableDatabase.insertWithOnConflict("chat_provider_state", null, ContentValues().apply {
            put("chat_id", state.chatId); put("provider", state.provider); put("session_id", state.sessionId)
            put("parent_message_id", state.parentMessageId); put("model_type", state.modelType)
            put("context_tokens", state.contextTokens); put("thinking_enabled", if (state.thinkingEnabled) 1 else 0)
            put("search_enabled", if (state.searchEnabled) 1 else 0); put("tool_protocol_version", state.toolProtocolVersion)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun chatForSession(sessionId: String): String? = readableDatabase.rawQuery(
        "SELECT chat_id FROM chat_provider_state WHERE session_id=? LIMIT 1", arrayOf(sessionId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun deleteChat(chatId: String): Int = writableDatabase.delete("chats", "id=?", arrayOf(chatId))

    fun renameChat(chatId: String, title: String): Int = writableDatabase.update(
        "chats", ContentValues().apply { put("title", title); put("updated_at", System.currentTimeMillis()) },
        "id=?", arrayOf(chatId),
    )

    fun setChatPinned(chatId: String, pinned: Boolean): Int = writableDatabase.update(
        "chats", ContentValues().apply { put("pinned", if (pinned) 1 else 0) },
        "id=?", arrayOf(chatId),
    )

    private fun Cursor.toChat() = KinoChat(
        id = getString(0),
        title = getString(1),
        updatedAt = getLong(2),
        preview = getString(5),
        pinned = getInt(3) != 0,
        hasFiles = getInt(4) != 0,
    )

    private fun Cursor.toMessage(): KinoMessage {
        val toolName = nullableString(5)
        val tool = toolName?.let {
            ToolActivity(it, nullableString(6) ?: "Done", nullableString(7).orEmpty())
        }
        return KinoMessage(
            id = getLong(0), chatId = getString(1), role = MessageRole.fromStorage(getString(2)),
            text = getString(3), createdAt = getLong(4), tool = tool,
            kind = nullableString(8), metadata = nullableString(9), tokenUsage = if (isNull(10)) null else getLong(10),
        )
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
}

private val MessageRole.storageValue: String
    get() = when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
    }
