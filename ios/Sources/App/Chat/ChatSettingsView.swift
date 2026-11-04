// 微翻 iOS —— 聊天栈设置 + 待办 + 备份导出（阶段 3 核心）
//
// 引擎凭据沿用翻译工具形态的设置（Keychain + WeiFanCore），这里补聊天栈专属项：
//   wa_proxy（国内连 WhatsApp 必需，与 Android 同名键）、收件自动翻译开关、
//   跟进待办（本地库 todos）、备份导出（SQLite JSON 打包到「文件」App）。

import SwiftUI
import UniformTypeIdentifiers
import SQLite3

// MARK: - 聊天设置

struct ChatSettingsView: View {
    @ObservedObject var core = ProtocolCore.shared
    @AppStorage("wa_proxy") private var waProxy = ""
    @AppStorage("wa_auto_translate_incoming") private var autoTranslate = true
    @State private var backupURL: URL?
    @State private var exporting = false
    @State private var showRestore = false
    @State private var restoreMsg: String?

    var body: some View {
        Form {
            Section("WhatsApp 连接") {
                VStack(alignment: .leading, spacing: 4) {
                    Text("代理（国内网络必需）").font(.caption).foregroundStyle(.secondary)
                    TextField("http://127.0.0.1:7892 或 socks5://…", text: $waProxy)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Text("改动后重启 App 生效。Android 端同名配置 wa_proxy。")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                LabeledContent("状态", value: core.connected ? "已连接" : "未连接")
            }
            Section("翻译") {
                Toggle("收件自动翻译", isOn: $autoTranslate)
                NavigationLink("翻译服务与密钥") {
                    // 引擎凭据沿用工具形态设置（同 Keychain，两端同规格）
                    SettingsView()
                }
            }
            Section("数据") {
                NavigationLink("跟进待办") { TodoListView() }
                Button {
                    exporting = true
                    DispatchQueue.global(qos: .userInitiated).async {
                        let url = BackupExporter.exportURL()
                        DispatchQueue.main.async {
                            exporting = false
                            backupURL = url
                        }
                    }
                } label: {
                    HStack {
                        Label("导出备份（会话 JSON）", systemImage: "archivebox")
                        if exporting { ProgressView().scaleEffect(0.7) }
                    }
                }
                .disabled(exporting)
                Button {
                    showRestore = true
                } label: {
                    Label("从 JSON 恢复", systemImage: "arrow.down.doc")
                }
                Text("备份含会话与消息 JSON，不含媒体文件；恢复按主键覆盖合并。")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Section("关于") {
                LabeledContent("协议层", value: "WeifanProtocol (gomobile)")
                LabeledContent("配对状态", value: core.paired ? "已配对" : "未配对")
            }
        }
        .navigationTitle("聊天设置")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $backupURL) { url in
            ShareLink(item: url, preview: SharePreview("微翻备份"))
        }
        .fileImporter(isPresented: $showRestore, allowedContentTypes: [.json],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let src = urls.first {
                let sec = src.startAccessingSecurityScopedResource()
                let msg = BackupExporter.restore(from: src)
                if sec { src.stopAccessingSecurityScopedResource() }
                restoreMsg = msg
            }
        }
        .alert("恢复结果", isPresented: .init(get: { restoreMsg != nil }, set: { if !$0 { restoreMsg = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(restoreMsg ?? "") }
    }
}

// MARK: - 待办（本地库 todos，对齐 Android FollowupTodosActivity 最小集）

struct TodoRowUI: Identifiable {
    let id: Int64
    let content: String
    let dueTs: Int64
    let done: Bool
}

struct TodoListView: View {
    @ObservedObject var core = ProtocolCore.shared
    @State private var todos: [TodoRowUI] = []
    @State private var newContent = ""
    @State private var showNew = false
    @State private var newDue: Date? = nil

    var body: some View {
        List {
            ForEach(todos) { t in
                HStack {
                    Image(systemName: t.done ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(t.done ? Color.secondary : Color.teal)
                        .onTapGesture { toggle(t) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.content)
                            .strikethrough(t.done)
                            .foregroundStyle(t.done ? Color.secondary : Color.primary)
                        if t.dueTs > 0 {
                            let overdue = !t.done && Double(t.dueTs) < Date().timeIntervalSince1970 * 1000
                            Text(Self.day(t.dueTs)).font(.caption2)
                                .foregroundStyle(overdue ? Color.red : Color.secondary)
                        }
                    }
                    Spacer()
                }
            }
            .onDelete { sets in
                for i in sets {
                    TodoNotifications.cancel(todoId: todos[i].id)
                    try? core.db.exec("DELETE FROM todos WHERE id = ?", args: [todos[i].id])
                }
                reload()
            }
        }
        .navigationTitle("跟进待办")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showNew = true } label: { Image(systemName: "plus") }
            }
        }
        .alert("新建待办", isPresented: $showNew) {
            TextField("要跟进什么", text: $newContent)
            Button("保存") {
                let text = newContent.trimmingCharacters(in: .whitespaces)
                guard !text.isEmpty else { return }
                let due = newDue.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
                _ = try? core.db.exec(
                    "INSERT INTO todos(chat_jid,content,due_ts,done,created_ts) VALUES(?,?,?,0,?)",
                    args: ["", text, due, Int64(Date().timeIntervalSince1970 * 1000)])
                if due > 0 {
                    // P1：到期本地通知（对齐参考 followup_perm_*；与 Android TodoNotifications 同构）
                    TodoNotifications.ensureAuthorized { _ in }
                    TodoNotifications.schedule(todoId: Int64(Date().timeIntervalSince1970), content: text, dueTs: due)
                }
                newContent = ""; newDue = nil
                reload()
            }
            Button("取消", role: .cancel) { newContent = "" }
        } message: {
            Text("到期提醒与 Android 端同构（本地闹钟在阶段 3 后续接入）")
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        var out: [TodoRowUI] = []
        core.db.query("SELECT id, content, due_ts, done FROM todos ORDER BY done ASC, due_ts ASC") { stmt in
            out.append(TodoRowUI(id: sqlite3_column_int64(stmt, 0),
                                 content: dbText(stmt, 1), dueTs: sqlite3_column_int64(stmt, 2),
                                 done: sqlite3_column_int(stmt, 3) != 0))
        }
        todos = out
    }

    private func toggle(_ t: TodoRowUI) {
        try? core.db.exec("UPDATE todos SET done = ? WHERE id = ?", args: [!t.done, t.id])
        if t.done { TodoNotifications.cancel(todoId: t.id) }
        reload()
    }

    static func day(_ ms: Int64) -> String {
        let df = DateFormatter(); df.dateFormat = "M月d日 HH:mm"
        return "到期 " + df.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

func dbText(_ stmt: OpaquePointer?, _ col: Int32) -> String {
    guard let c = sqlite3_column_text(stmt, col) else { return "" }
    return String(cString: c)
}

// MARK: - 备份导出（会话/消息/待办 → JSON → 「文件」分享面板）

enum BackupExporter {
    /// 恢复：JSON → chats/messages 逐条覆盖合并（主键冲突以文件为准）
    static func restore(from url: URL) -> String {
        guard let data = try? Data(contentsOf: url),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return "读取失败：不是有效的备份文件"
        }
        var chats = 0, msgs = 0
        let core = ProtocolCore.shared
        if let arr = o["chats"] as? [[String: Any]] {
            for c in arr {
                guard let jid = c["jid"] as? String else { continue }
                core.db.exec("INSERT OR REPLACE INTO chats(jid,title,last_message,last_ts,unread,pinned,archived) VALUES(?,?,?,?,?,?,?)",
                    args: [jid, c["title"] ?? "", c["last_message"] ?? "",
                           c["last_ts"] ?? 0, c["unread"] ?? 0,
                           (c["pinned"] as? Bool) == true ? 1 : 0,
                           (c["archived"] as? Bool) == true ? 1 : 0])
                chats += 1
            }
        }
        if let arr = o["messages"] as? [[String: Any]] {
            for m in arr {
                guard let id = m["id"] as? String, let jid = m["chat_jid"] as? String else { continue }
                core.db.exec("INSERT OR REPLACE INTO messages(id,chat_jid,sender_jid,from_me,text,original,translated,translated_lang,ts,media_kind,reaction) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    args: [id, jid, m["sender_jid"] ?? "", (m["from_me"] as? Bool) == true ? 1 : 0,
                           m["text"] ?? "", m["original"] ?? "", m["translated"] ?? "",
                           m["translated_lang"] ?? "", m["ts"] ?? 0, m["media_kind"] ?? "",
                           m["reaction"] ?? ""])
                msgs += 1
            }
        }
        return "已恢复 \(chats) 个会话、\(msgs) 条消息"
    }

    static func exportURL() -> URL {
        let core = ProtocolCore.shared
        var payload: [String: Any] = [:]
        var chats: [[String: Any]] = []
        core.db.query("SELECT jid,title,last_message,last_ts,unread,pinned,archived FROM chats") { stmt in
            chats.append(["jid": dbText(stmt, 0), "title": dbText(stmt, 1),
                          "last_message": dbText(stmt, 2), "last_ts": sqlite3_column_int64(stmt, 3),
                          "unread": sqlite3_column_int(stmt, 4), "pinned": sqlite3_column_int(stmt, 5) != 0,
                          "archived": sqlite3_column_int(stmt, 6) != 0])
        }
        var msgs: [[String: Any]] = []
        core.db.query("SELECT id,chat_jid,sender_jid,from_me,text,original,translated,translated_lang,ts,media_kind,reaction FROM messages") { stmt in
            msgs.append(["id": dbText(stmt, 0), "chat_jid": dbText(stmt, 1), "sender_jid": dbText(stmt, 2),
                         "from_me": sqlite3_column_int(stmt, 3) != 0, "text": dbText(stmt, 4),
                         "original": dbText(stmt, 5), "translated": dbText(stmt, 6),
                         "translated_lang": dbText(stmt, 7), "ts": sqlite3_column_int64(stmt, 8),
                         "media_kind": dbText(stmt, 9), "reaction": dbText(stmt, 10)])
        }
        payload["chats"] = chats
        payload["messages"] = msgs
        payload["exported_at"] = Int64(Date().timeIntervalSince1970 * 1000)
        payload["version"] = 1
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("weifan-backup-\(Int(Date().timeIntervalSince1970)).json")
        if let data = try? JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted]) {
            try? data.write(to: url)
        }
        return url
    }
}
