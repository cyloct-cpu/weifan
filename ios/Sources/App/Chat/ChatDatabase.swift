// 微翻 iOS —— 本地聊天库（系统 SQLite3）
//
// 阶段 1（docs/17 §11.6）：表结构对齐 Android ChatDb（v9 + v10 reaction），列名 snake_case。
// 实现说明：原计划用 GRDB（SPM），实测 GRDB 的 clang 子模块（GRDBSQLite）在 Xcode 27 下
// 会通过 -fmodule-map-file 破坏同一条编译命令里对 gomobile ObjC 框架的发现
// （见 docs/19 §2.12 的排查），故改用系统 import SQLite3 —— 与本项目「零第三方依赖」
// 的既有原则一致（Android 端亦是无 ORM 裸 SQL）。线程模型：单写连接 + 串行队列。

import Foundation
import SQLite3

/// 系统 SQLite3 的 Swift 导入不带 SQLITE_TRANSIENT（sqlite3.h 内部宏），手工补一个等价物
internal let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

struct ChatRow: Codable, Identifiable, Equatable {
    var jid: String
    var title: String
    var lastMessage: String
    var lastTs: Int64
    var unread: Int
    var pinned: Bool
    var archived: Bool
    var muted: Bool
    var blocked: Bool
    var note: String

    var id: String { jid }
}

struct MessageRow: Codable, Identifiable, Equatable {
    var id: String
    var chatJid: String
    var senderJid: String
    var fromMe: Bool
    var text: String
    var original: String
    var translated: String
    var translatedLang: String
    var ts: Int64
    var mediaKind: String
    var mediaMime: String
    var mediaName: String
    var mediaSize: Int64
    var mediaPath: String
    var mediaThumb: String
    var mediaMeta: String
    var reaction: String
}

struct LabelRow: Codable, Identifiable, Equatable {
    var id: Int64?
    var name: String
    var color: String
}

struct TodoRow: Codable, Identifiable, Equatable {
    var id: Int64?
    var chatJid: String
    var content: String
    var dueTs: Int64
    var done: Bool
    var createdTs: Int64
}

final class ChatDatabase: @unchecked Sendable {
    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "weifan.chatdb")

    init(path: String) throws {
        var handle: OpaquePointer?
        guard sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            throw NSError(domain: "weifan.db", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "打不开数据库 \(path)"])
        }
        db = handle
        migrate()
    }

    deinit { if let db { sqlite3_close(db) } }

    /// 对齐 Android ChatDb 的版本链（v1 建表 chats/messages → v2 投票/待办 → v3 标签；
    /// Android 侧 v10 的 reaction 直接并入 v1 建表——iOS 全新装机不需要中途加列）
    private func migrate() {
        exec("""
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
        );
        """)
        exec("""
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
          reaction TEXT NOT NULL DEFAULT '',
          PRIMARY KEY(chat_jid, id)
        );
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(chat_jid, ts DESC)")
        exec("""
        CREATE TABLE IF NOT EXISTS poll_votes(
          chat_jid TEXT NOT NULL,
          poll_msg_id TEXT NOT NULL,
          voter_jid TEXT NOT NULL,
          option_name TEXT NOT NULL,
          voter_name TEXT NOT NULL DEFAULT '',
          ts INTEGER NOT NULL,
          PRIMARY KEY(chat_jid, poll_msg_id, voter_jid, option_name)
        );
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS todos(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          chat_jid TEXT NOT NULL DEFAULT '',
          content TEXT NOT NULL,
          due_ts INTEGER NOT NULL DEFAULT 0,
          done INTEGER NOT NULL DEFAULT 0,
          created_ts INTEGER NOT NULL
        );
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_todos_done ON todos(done, due_ts)")
        exec("""
        CREATE TABLE IF NOT EXISTS labels(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL UNIQUE,
          color TEXT NOT NULL DEFAULT ''
        );
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS chat_labels(
          chat_jid TEXT NOT NULL,
          label_id INTEGER NOT NULL,
          PRIMARY KEY(chat_jid, label_id)
        );
        """)
    }

    // MARK: - 底层

    @discardableResult
    private func exec(_ sql: String, args: [Any] = []) -> Bool {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                logErr(sql); return false
            }
            defer { sqlite3_finalize(stmt) }
            bind(args, to: stmt)
            if sqlite3_step(stmt) != SQLITE_DONE && sqlite3_column_count(stmt) == 0 {
                logErr(sql); return false
            }
            return true
        }
    }

    private func query(_ sql: String, args: [Any] = [], row: (OpaquePointer) -> Void) {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                logErr(sql); return
            }
            defer { sqlite3_finalize(stmt) }
            bind(args, to: stmt)
            while sqlite3_step(stmt) == SQLITE_ROW { row(stmt!) }
        }
    }

    private func bind(_ args: [Any], to stmt: OpaquePointer?) {
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case let s as String: sqlite3_bind_text(stmt, idx, s, -1, SQLITE_TRANSIENT)
            case let n as Int64: sqlite3_bind_int64(stmt, idx, n)
            case let n as Int: sqlite3_bind_int64(stmt, idx, Int64(n))
            case let b as Bool: sqlite3_bind_int(stmt, idx, b ? 1 : 0)
            case let d as Double: sqlite3_bind_double(stmt, idx, d)
            default: sqlite3_bind_text(stmt, idx, "\(a)", -1, SQLITE_TRANSIENT)
            }
        }
    }

    private func logErr(_ sql: String) {
        let msg = String(cString: sqlite3_errmsg(db))
        NSLog("WeFlipDB error: \(msg) | sql: \(sql.prefix(120))")
    }

    private func text(_ stmt: OpaquePointer?, _ col: Int32) -> String {
        guard let c = sqlite3_column_text(stmt, col) else { return "" }
        return String(cString: c)
    }

    // MARK: - 会话

    func upsertChat(jid: String, title: String, lastMessage: String, lastTs: Int64, incrementUnread: Bool) {
        exec("""
        INSERT INTO chats(jid, title, last_message, last_ts, unread)
        VALUES(?, ?, ?, ?, ?)
        ON CONFLICT(jid) DO UPDATE SET
          title = CASE WHEN ? != '' THEN ? ELSE chats.title END,
          last_message = CASE WHEN ? != '' THEN ? ELSE chats.last_message END,
          last_ts = MAX(chats.last_ts, ?),
          unread = unread + ?
        """, args: [jid, title, lastMessage, lastTs, incrementUnread ? 1 : 0,
                    title, title, lastMessage, lastMessage, lastTs, incrementUnread ? 1 : 0])
    }

    func chats(includeArchived: Bool = false) -> [ChatRow] {
        var out: [ChatRow] = []
        query("SELECT * FROM chats \(includeArchived ? "" : "WHERE archived=0") ORDER BY pinned DESC, last_ts DESC") { stmt in
            out.append(ChatRow(
                jid: text(stmt, 0), title: text(stmt, 1), lastMessage: text(stmt, 2),
                lastTs: sqlite3_column_int64(stmt, 3), unread: Int(sqlite3_column_int(stmt, 4)),
                pinned: sqlite3_column_int(stmt, 5) != 0, archived: sqlite3_column_int(stmt, 6) != 0,
                muted: sqlite3_column_int(stmt, 7) != 0, blocked: sqlite3_column_int(stmt, 8) != 0,
                note: text(stmt, 9)))
        }
        return out
    }

    func markChatRead(jid: String) {
        exec("UPDATE chats SET unread = 0 WHERE jid = ?", args: [jid])
    }

    // MARK: - 消息

    func insertMessage(_ m: MessageRow) {
        exec("""
        INSERT OR REPLACE INTO messages(
          id, chat_jid, sender_jid, from_me, text, original, translated, translated_lang,
          ts, media_kind, media_mime, media_name, media_size, media_path, media_thumb, media_meta, reaction)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, args: [m.id, m.chatJid, m.senderJid, m.fromMe, m.text, m.original, m.translated,
                    m.translatedLang, m.ts, m.mediaKind, m.mediaMime, m.mediaName,
                    m.mediaSize, m.mediaPath, m.mediaThumb, m.mediaMeta, m.reaction])
    }

    func messages(chatJid: String, limit: Int = 300) -> [MessageRow] {
        var out: [MessageRow] = []
        // 注意：不能写「SELECT * FROM (子查询) ORDER BY rowid」——子查询投影会丢掉 rowid
        // （实测 no such column）。单层查询取倒序后 Swift 侧反转。
        query("""
        SELECT rowid, * FROM messages WHERE chat_jid = ? ORDER BY ts DESC, rowid DESC LIMIT ?
        """, args: [chatJid, limit]) { stmt in
            out.append(MessageRow(
                id: text(stmt, 1), chatJid: text(stmt, 2), senderJid: text(stmt, 3),
                fromMe: sqlite3_column_int(stmt, 4) != 0, text: text(stmt, 5),
                original: text(stmt, 6), translated: text(stmt, 7), translatedLang: text(stmt, 8),
                ts: sqlite3_column_int64(stmt, 9), mediaKind: text(stmt, 10), mediaMime: text(stmt, 11),
                mediaName: text(stmt, 12), mediaSize: sqlite3_column_int64(stmt, 13),
                mediaPath: text(stmt, 14), mediaThumb: text(stmt, 15), mediaMeta: text(stmt, 16),
                reaction: text(stmt, 17)))
        }
        return out.reversed()
    }

    func updateTranslation(chatJid: String, id: String, translated: String, lang: String) {
        exec("UPDATE messages SET translated = ?, translated_lang = ? WHERE chat_jid = ? AND id = ?",
             args: [translated, lang, chatJid, id])
    }

    func setReaction(chatJid: String, id: String, reaction: String) {
        exec("UPDATE messages SET reaction = ? WHERE chat_jid = ? AND id = ?",
             args: [reaction, chatJid, id])
    }
}
