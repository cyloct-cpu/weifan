package com.weifan.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 会话与消息存储。零依赖：直接 SQLiteOpenHelper，不引 Room。
 * 表结构对齐 Go 层 whatsmeow store（它自己存协议状态；这里只存 UI 需要的会话/消息/译文）。
 */
class ChatDb(context: Context) : SQLiteOpenHelper(context, "chats.db", null, 9) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chats(
              jid TEXT PRIMARY KEY,
              title TEXT NOT NULL DEFAULT '',
              last_message TEXT NOT NULL DEFAULT '',
              last_ts INTEGER NOT NULL DEFAULT 0,
              unread INTEGER NOT NULL DEFAULT 0,
              pinned INTEGER NOT NULL DEFAULT 0,
              archived INTEGER NOT NULL DEFAULT 0,
              muted INTEGER NOT NULL DEFAULT 0,
              blocked INTEGER NOT NULL DEFAULT 0,
              note TEXT NOT NULL DEFAULT ''
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages(
              id TEXT NOT NULL,
              chat_jid TEXT NOT NULL,
              sender_jid TEXT NOT NULL,
              from_me INTEGER NOT NULL,
              text TEXT NOT NULL,
              original TEXT NOT NULL DEFAULT '',
              translated TEXT NOT NULL DEFAULT '',
              translated_lang TEXT NOT NULL DEFAULT '',
              ts INTEGER NOT NULL,
              media_kind TEXT NOT NULL DEFAULT '',
              media_mime TEXT NOT NULL DEFAULT '',
              media_name TEXT NOT NULL DEFAULT '',
              media_size INTEGER NOT NULL DEFAULT 0,
              media_path TEXT NOT NULL DEFAULT '',
              media_thumb TEXT NOT NULL DEFAULT '',
              media_meta TEXT NOT NULL DEFAULT '',
              PRIMARY KEY(chat_jid, id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(chat_jid, ts DESC)")
        // v8: 投票结果（每行 = 一人投的一项；改票时该人旧行删除重插）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS poll_votes(
              chat_jid TEXT NOT NULL,
              poll_msg_id TEXT NOT NULL,
              voter_jid TEXT NOT NULL,
              option_name TEXT NOT NULL,
              voter_name TEXT NOT NULL DEFAULT '',
              ts INTEGER NOT NULL,
              PRIMARY KEY(chat_jid, poll_msg_id, voter_jid, option_name)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_poll_votes ON poll_votes(poll_msg_id)")
        // v9: 跟进待办（本地 CRM；chat_jid 可空 = 不挂客户）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS todos(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              chat_jid TEXT NOT NULL DEFAULT '',
              content TEXT NOT NULL,
              due_ts INTEGER NOT NULL DEFAULT 0,
              done INTEGER NOT NULL DEFAULT 0,
              created_ts INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_done ON todos(done, due_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v9: 跟进待办表
        if (oldVersion < 9) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS todos(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  chat_jid TEXT NOT NULL DEFAULT '',
                  content TEXT NOT NULL,
                  due_ts INTEGER NOT NULL DEFAULT 0,
                  done INTEGER NOT NULL DEFAULT 0,
                  created_ts INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_done ON todos(done, due_ts)")
        }
        // v8: 投票结果表
        if (oldVersion < 8) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS poll_votes(
                  chat_jid TEXT NOT NULL,
                  poll_msg_id TEXT NOT NULL,
                  voter_jid TEXT NOT NULL,
                  option_name TEXT NOT NULL,
                  voter_name TEXT NOT NULL DEFAULT '',
                  ts INTEGER NOT NULL,
                  PRIMARY KEY(chat_jid, poll_msg_id, voter_jid, option_name)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_poll_votes ON poll_votes(poll_msg_id)")
        }
        // v7: 客户备注（本地 CRM；对每会话自由文本，跟设备不跟服务器）
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE chats ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        }
        // v6: 应用内拉黑（来信不入库、会话从列表隐藏；官方端拉黑独立不受影响）
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE chats ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
        }
        // v2: 发件直译直发 —— 发出去的是译文，用户敲的原文存 original 字段
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN original TEXT NOT NULL DEFAULT ''")
        }
        // v5: 本地标签系统（客户分组）
        db.execSQL("CREATE TABLE IF NOT EXISTS labels(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, color TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_labels(" +
            "chat_jid TEXT NOT NULL, label_id INTEGER NOT NULL, " +
            "PRIMARY KEY(chat_jid, label_id))")
        // v4: 会话本地管理（置顶/归档/静音）
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
        }
        // v3: 媒体消息（kind/mime/name/size/path/thumb/下载元数据）
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN media_kind TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_mime TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_thumb TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_meta TEXT NOT NULL DEFAULT ''")
        }
    }

    // MARK: - 会话

    /** 新消息到达时 upsert 会话摘要；也用于重算 last_message */
    fun upsertChat(jid: String, title: String, lastMessage: String, ts: Long, incrementUnread: Boolean) {
        val db = writableDatabase
        val existing = readableDatabase.rawQuery(
            "SELECT title, unread, pinned, archived, muted FROM chats WHERE jid=?", arrayOf(jid)).use { c ->
            if (c.moveToFirst()) ChatRow(jid, c.getString(0), "", 0, c.getInt(1),
                c.getInt(2) == 1, c.getInt(3) == 1, c.getInt(4) == 1) else null
        }
        val values = ContentValues().apply {
            put("jid", jid)
            put("title", title.ifBlank { existing?.title ?: jid })
            put("pinned", if (existing?.pinned == true) 1 else 0)
            put("archived", if (existing?.archived == true) 1 else 0)
            put("muted", if (existing?.muted == true) 1 else 0)
            put("last_message", lastMessage)
            put("last_ts", ts)
            put("unread", (existing?.unread ?: 0) + if (incrementUnread) 1 else 0)
        }
        db.insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markChatRead(jid: String) {
        writableDatabase.execSQL("UPDATE chats SET unread=0 WHERE jid=?", arrayOf(jid))
    }

    fun setChatTitle(jid: String, title: String) {
        writableDatabase.execSQL("UPDATE chats SET title=? WHERE jid=?", arrayOf(title, jid))
    }

    /** 客户备注（本地 CRM）：空串清除 */
    fun setChatNote(jid: String, note: String) {
        writableDatabase.execSQL("UPDATE chats SET note=? WHERE jid=?", arrayOf(note.trim(), jid))
    }

    fun chatNote(jid: String): String = readableDatabase.rawQuery(
        "SELECT note FROM chats WHERE jid=?", arrayOf(jid)).use { c ->
        if (c.moveToFirst()) c.getString(0) ?: "" else ""
    }

    /** 记录一次投票：该人该投票的旧选择先删后插（全量替换，支持改票/取消票） */
    fun replacePollVotes(chatJid: String, pollMsgId: String, voterJid: String,
                         voterName: String, optionNames: List<String>, ts: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM poll_votes WHERE chat_jid=? AND poll_msg_id=? AND voter_jid=?",
                arrayOf(chatJid, pollMsgId, voterJid))
            for (opt in optionNames) {
                db.execSQL("INSERT OR REPLACE INTO poll_votes(chat_jid,poll_msg_id,voter_jid,option_name,voter_name,ts) " +
                    "VALUES(?,?,?,?,?,?)", arrayOf(chatJid, pollMsgId, voterJid, opt, voterName, ts))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 某投票各选项票数：optionName → count */
    fun pollCounts(pollMsgId: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT option_name, COUNT(*) FROM poll_votes WHERE poll_msg_id=? GROUP BY option_name",
            arrayOf(pollMsgId)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** 某投票总投票人数（去重） */
    fun pollVoterCount(pollMsgId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(DISTINCT voter_jid) FROM poll_votes WHERE poll_msg_id=?",
        arrayOf(pollMsgId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun chats(): List<ChatRow> {
        return readableDatabase.rawQuery(
            "SELECT jid, title, last_message, last_ts, unread, pinned, archived, muted, blocked " +
            "FROM chats WHERE blocked=0 ORDER BY pinned DESC, last_ts DESC", null
        ).use { c ->
            val out = mutableListOf<ChatRow>()
            while (c.moveToNext()) {
                out += ChatRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4),
                    c.getInt(5) == 1, c.getInt(6) == 1, c.getInt(7) == 1, c.getInt(8) == 1)
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
            put("original", m.original)
            put("translated", m.translated)
            put("translated_lang", m.translatedLang)
            put("ts", m.ts)
            put("media_kind", m.mediaKind)
            put("media_mime", m.mediaMime)
            put("media_name", m.mediaName)
            put("media_size", m.mediaSize)
            put("media_path", m.mediaPath)
            put("media_thumb", m.mediaThumb)
            put("media_meta", m.mediaMeta)
        }
        return writableDatabase.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** 下载完成后落媒体文件路径 */
    fun updateMediaPath(chatJid: String, id: String, path: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET media_path=? WHERE chat_jid=? AND id=?",
            arrayOf(path, chatJid, id))
    }

    fun updateTranslation(chatJid: String, id: String, translated: String, lang: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET translated=?, translated_lang=? WHERE chat_jid=? AND id=?",
            arrayOf(translated, lang, chatJid, id))
    }

    // MARK: - 标签系统（P2 本地客户分组）

    fun labels(): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT id, name FROM labels ORDER BY id", null).use { c ->
        val out = mutableListOf<Pair<Long, String>>()
        while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
        out
    }

    fun createLabel(name: String): Long {
        val cv = ContentValues().apply { put("name", name); put("color", "#128C7E") }
        return writableDatabase.insertWithOnConflict(
            "labels", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteLabel(id: Long) {
        writableDatabase.execSQL("DELETE FROM labels WHERE id=?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM chat_labels WHERE label_id=?", arrayOf(id))
    }

    /** 给会话设置标签（全量替换） */
    fun setChatLabels(chatJid: String, labelIds: List<Long>) {
        writableDatabase.execSQL("DELETE FROM chat_labels WHERE chat_jid=?", arrayOf(chatJid))
        for (id in labelIds) {
            val cv = ContentValues().apply {
                put("chat_jid", chatJid); put("label_id", id)
            }
            writableDatabase.insertWithOnConflict(
                "chat_labels", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** 会话的标签名列表 */
    fun chatLabelNames(chatJid: String): List<String> = readableDatabase.rawQuery(
        "SELECT l.name FROM labels l JOIN chat_labels cl ON cl.label_id=l.id " +
        "WHERE cl.chat_jid=? ORDER BY l.id", arrayOf(chatJid)).use { c ->
        val out = mutableListOf<String>()
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    /** 某标签下的会话 jid 集合（列表过滤用） */
    fun chatJidsOfLabel(name: String): Set<String> = readableDatabase.rawQuery(
        "SELECT cl.chat_jid FROM chat_labels cl JOIN labels l ON l.id=cl.label_id " +
        "WHERE l.name=?", arrayOf(name)).use { c ->
        val out = mutableSetOf<String>()
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    /** 设置会话本地标记（P1-3 置顶/归档/静音） */
    fun setChatFlags(jid: String, pinned: Boolean? = null, archived: Boolean? = null,
                     muted: Boolean? = null, blocked: Boolean? = null) {
        val cv = ContentValues().apply {
            pinned?.let { put("pinned", if (it) 1 else 0) }
            archived?.let { put("archived", if (it) 1 else 0) }
            muted?.let { put("muted", if (it) 1 else 0) }
            blocked?.let { put("blocked", if (it) 1 else 0) }
        }
        writableDatabase.update("chats", cv, "jid=?", arrayOf(jid))
    }

    fun isBlocked(jid: String): Boolean =
        readableDatabase.rawQuery("SELECT blocked FROM chats WHERE jid=?", arrayOf(jid)).use { c ->
            (c.moveToFirst() && c.getInt(0) == 1)
        }

    /** 已拉黑会话（设置页管理列表用） */
    fun blockedChats(): List<ChatRow> =
        readableDatabase.rawQuery(
            "SELECT jid, title, last_message, last_ts, unread, pinned, archived, muted, blocked " +
            "FROM chats WHERE blocked=1 ORDER BY last_ts DESC", null
        ).use { c ->
            val out = mutableListOf<ChatRow>()
            while (c.moveToNext()) {
                out += ChatRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4),
                    c.getInt(5) == 1, c.getInt(6) == 1, c.getInt(7) == 1, c.getInt(8) == 1)
            }
            out
        }

    /** 会话重命名（本地备注名） */
    fun renameChat(jid: String, title: String) {
        writableDatabase.execSQL("UPDATE chats SET title=? WHERE jid=?", arrayOf(title, jid))
    }

    /** 撤回：正文替换为占位，媒体标记也清掉（官方显示「您撤回了这条消息」） */
    fun applyRevoke(chatJid: String, id: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET text='此消息已撤回', media_kind='', media_thumb='', media_meta='' " +
            "WHERE chat_jid=? AND id=?", arrayOf(chatJid, id))
    }

    /** 编辑：更新正文（译文失效，一并清空） */
    fun applyEdit(chatJid: String, id: String, newText: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET text=?, translated='', translated_lang='' WHERE chat_jid=? AND id=?",
            arrayOf(newText, chatJid, id))
    }


    fun messages(chatJid: String, limit: Int = 200): List<MessageRow> {
        return readableDatabase.rawQuery(
            "SELECT id, chat_jid, sender_jid, from_me, text, original, translated, translated_lang, ts, " +
            "media_kind, media_mime, media_name, media_size, media_path, media_thumb, media_meta " +
            "FROM messages WHERE chat_jid=? ORDER BY ts ASC LIMIT $limit", arrayOf(chatJid)
        ).use { c ->
            val out = mutableListOf<MessageRow>()
            while (c.moveToNext()) {
                out += MessageRow(
                    id = c.getString(0), chatJid = c.getString(1), senderJid = c.getString(2),
                    fromMe = c.getInt(3) == 1, text = c.getString(4),
                    original = c.getString(5),
                    translated = c.getString(6), translatedLang = c.getString(7), ts = c.getLong(8),
                    mediaKind = c.getString(9), mediaMime = c.getString(10),
                    mediaName = c.getString(11), mediaSize = c.getLong(12),
                    mediaPath = c.getString(13), mediaThumb = c.getString(14),
                    mediaMeta = c.getString(15))
            }
            out
        }
    }

    /** 按 ID 取单条消息（投票哈希反查选项表用）；无则 null */
    fun message(chatJid: String, id: String): MessageRow? = readableDatabase.rawQuery(
        "SELECT id, chat_jid, sender_jid, from_me, text, original, translated, translated_lang, ts, " +
        "media_kind, media_mime, media_name, media_size, media_path, media_thumb, media_meta " +
        "FROM messages WHERE chat_jid=? AND id=?", arrayOf(chatJid, id)).use { c ->
        if (!c.moveToFirst()) return null
        MessageRow(
            id = c.getString(0), chatJid = c.getString(1), senderJid = c.getString(2),
            fromMe = c.getInt(3) == 1, text = c.getString(4), original = c.getString(5),
            translated = c.getString(6), translatedLang = c.getString(7), ts = c.getLong(8),
            mediaKind = c.getString(9), mediaMime = c.getString(10), mediaName = c.getString(11),
            mediaSize = c.getLong(12), mediaPath = c.getString(13),
            mediaThumb = c.getString(14), mediaMeta = c.getString(15))
    }

    // MARK: - 跟进待办（批3-a，对齐参考 FollowupTodosActivity）

    fun addTodo(chatJid: String, content: String, dueTs: Long): Long {
        val cv = ContentValues().apply {
            put("chat_jid", chatJid); put("content", content.trim())
            put("due_ts", dueTs); put("done", 0); put("created_ts", System.currentTimeMillis())
        }
        return writableDatabase.insert("todos", null, cv)
    }

    fun setTodoDone(id: Long, done: Boolean) {
        writableDatabase.execSQL("UPDATE todos SET done=? WHERE id=?", arrayOf(if (done) 1 else 0, id))
    }

    fun deleteTodo(id: Long) {
        writableDatabase.execSQL("DELETE FROM todos WHERE id=?", arrayOf(id))
    }

    /** 待办列表：onlyOpen = 只看未完成；chatJid 非空 = 只看该客户 */
    fun todos(onlyOpen: Boolean = true, chatJid: String = ""): List<TodoRow> {
        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (onlyOpen) where.append("done=0")
        if (chatJid.isNotBlank()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("chat_jid=?"); args += chatJid
        }
        val sql = "SELECT t.id, t.chat_jid, t.content, t.due_ts, t.done, t.created_ts, " +
            "COALESCE(c.title,'') FROM todos t LEFT JOIN chats c ON c.jid=t.chat_jid" +
            (if (where.isEmpty()) "" else " WHERE $where") +
            " ORDER BY t.done ASC, (t.due_ts=0) ASC, t.due_ts ASC, t.created_ts DESC"
        val out = mutableListOf<TodoRow>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += TodoRow(
                c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                c.getInt(4) == 1, c.getLong(5), c.getString(6))
        }
        return out
    }


    /**
     * 按参考 activity_followup_todos 的 4 个标签页过滤：
     * ALL=全部 / OVERDUE=逾期 / TODAY=今日 / DONE=已完成
     * 逾期与今日都只算未完成项。
     */
    fun todosByFilter(filter: String, chatJid: String = "", now: Long = System.currentTimeMillis()): List<TodoRow> {
        val where = StringBuilder()
        val args = mutableListOf<String>()
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 24L * 3600_000L
        when (filter) {
            "OVERDUE" -> { where.append("done=0 AND due_ts>0 AND due_ts<?"); args += now.toString() }
            "TODAY" -> { where.append("done=0 AND due_ts>=? AND due_ts<?")
                args += dayStart.toString(); args += dayEnd.toString() }
            "DONE" -> where.append("done=1")
            else -> { }   // ALL：不过滤
        }
        if (chatJid.isNotBlank()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("chat_jid=?"); args += chatJid
        }
        val sql = "SELECT t.id, t.chat_jid, t.content, t.due_ts, t.done, t.created_ts, " +
            "COALESCE(c.title,'') FROM todos t LEFT JOIN chats c ON c.jid=t.chat_jid" +
            (if (where.isEmpty()) "" else " WHERE $where") +
            " ORDER BY t.done ASC, (t.due_ts=0) ASC, t.due_ts ASC, t.created_ts DESC"
        val out = mutableListOf<TodoRow>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += TodoRow(
                c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                c.getInt(4) == 1, c.getLong(5), c.getString(6))
        }
        return out
    }

    /** 统计：逾期数 / 待跟进数（对齐参考两个统计卡片） */
    fun todoStats(now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val overdue = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM todos WHERE done=0 AND due_ts>0 AND due_ts<?",
            arrayOf(now.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val pending = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM todos WHERE done=0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0 }
        return overdue to pending
    }
    fun openTodoCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM todos WHERE done=0", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }
}

/** 一条跟进待办；customerTitle 由 chats 左连得出（会话已删则空） */
data class TodoRow(
    val id: Long,
    val chatJid: String,
    val content: String,
    val dueTs: Long,
    val done: Boolean,
    val createdTs: Long,
    val customerTitle: String
)

data class ChatRow(
    val jid: String,
    var title: String,
    var lastMessage: String,
    var lastTs: Long,
    var unread: Int,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val blocked: Boolean = false
)

data class MessageRow(
    val id: String,
    val chatJid: String,
    val senderJid: String,
    val fromMe: Boolean,
    /** 显示主文本：收件=对方原文；发件=译文（实际发出去的） */
    val text: String,
    /** 副文本：发件=用户敲的中文原文；收件恒空 */
    val original: String,
    var translated: String,
    var translatedLang: String,
    val ts: Long,
    // ---- 媒体（v1.5）----
    val mediaKind: String = "",      // image/video/audio/document/sticker；空=纯文本
    val mediaMime: String = "",
    val mediaName: String = "",
    val mediaSize: Long = 0,
    var mediaPath: String = "",     // 下载后落盘路径
    val mediaThumb: String = "",    // base64 JPEG 缩略图
    val mediaMeta: String = ""      // JSON：url/directPath/mediaKey/sha… 供下载
)
