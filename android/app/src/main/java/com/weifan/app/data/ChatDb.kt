package com.weifan.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 会话与消息存储。零依赖：直接 SQLiteOpenHelper，不引 Room。
 * 表结构对齐 Go 层 whatsmeow store（它自己存协议状态；这里只存 UI 需要的会话/消息/译文）。
 */
class ChatDb(context: Context) : SQLiteOpenHelper(context, "chats.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE chats(
              jid TEXT PRIMARY KEY,
              title TEXT NOT NULL DEFAULT '',
              last_message TEXT NOT NULL DEFAULT '',
              last_ts INTEGER NOT NULL DEFAULT 0,
              unread INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE messages(
              id TEXT NOT NULL,
              chat_jid TEXT NOT NULL,
              sender_jid TEXT NOT NULL,
              from_me INTEGER NOT NULL,
              text TEXT NOT NULL,
              translated TEXT NOT NULL DEFAULT '',
              translated_lang TEXT NOT NULL DEFAULT '',
              ts INTEGER NOT NULL,
              PRIMARY KEY(chat_jid, id)
            )
        """)
        db.execSQL("CREATE INDEX idx_messages_ts ON messages(chat_jid, ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 无升级
    }

    // MARK: - 会话

    /** 新消息到达时 upsert 会话摘要；也用于重算 last_message */
    fun upsertChat(jid: String, title: String, lastMessage: String, ts: Long, incrementUnread: Boolean) {
        val db = writableDatabase
        val existing = readableDatabase.rawQuery(
            "SELECT title, unread FROM chats WHERE jid=?", arrayOf(jid)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null
        }
        val values = ContentValues().apply {
            put("jid", jid)
            put("title", title.ifBlank { existing?.first ?: jid })
            put("last_message", lastMessage)
            put("last_ts", ts)
            put("unread", (existing?.second ?: 0) + if (incrementUnread) 1 else 0)
        }
        db.insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markChatRead(jid: String) {
        writableDatabase.execSQL("UPDATE chats SET unread=0 WHERE jid=?", arrayOf(jid))
    }

    fun setChatTitle(jid: String, title: String) {
        writableDatabase.execSQL("UPDATE chats SET title=? WHERE jid=?", arrayOf(title, jid))
    }

    fun chats(): List<ChatRow> {
        return readableDatabase.rawQuery(
            "SELECT jid, title, last_message, last_ts, unread FROM chats ORDER BY last_ts DESC", null
        ).use { c ->
            val out = mutableListOf<ChatRow>()
            while (c.moveToNext()) {
                out += ChatRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))
            }
            out
        }
    }

    // MARK: - 消息

    fun insertMessage(m: MessageRow): Boolean {
        val values = ContentValues().apply {
            put("id", m.id)
            put("chat_jid", m.chatJid)
            put("sender_jid", m.senderJid)
            put("from_me", if (m.fromMe) 1 else 0)
            put("text", m.text)
            put("translated", m.translated)
            put("translated_lang", m.translatedLang)
            put("ts", m.ts)
        }
        return writableDatabase.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun updateTranslation(chatJid: String, id: String, translated: String, lang: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET translated=?, translated_lang=? WHERE chat_jid=? AND id=?",
            arrayOf(translated, lang, chatJid, id))
    }

    fun messages(chatJid: String, limit: Int = 200): List<MessageRow> {
        return readableDatabase.rawQuery(
            "SELECT id, chat_jid, sender_jid, from_me, text, translated, translated_lang, ts " +
            "FROM messages WHERE chat_jid=? ORDER BY ts ASC LIMIT $limit", arrayOf(chatJid)
        ).use { c ->
            val out = mutableListOf<MessageRow>()
            while (c.moveToNext()) {
                out += MessageRow(
                    id = c.getString(0), chatJid = c.getString(1), senderJid = c.getString(2),
                    fromMe = c.getInt(3) == 1, text = c.getString(4),
                    translated = c.getString(5), translatedLang = c.getString(6), ts = c.getLong(7))
            }
            out
        }
    }
}

data class ChatRow(
    val jid: String,
    var title: String,
    var lastMessage: String,
    var lastTs: Long,
    var unread: Int
)

data class MessageRow(
    val id: String,
    val chatJid: String,
    val senderJid: String,
    val fromMe: Boolean,
    val text: String,
    var translated: String,
    var translatedLang: String,
    val ts: Long
)
