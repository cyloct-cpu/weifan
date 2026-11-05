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
    @AppStorage("wa_auto_translate_incoming") private var autoTranslate = true
    @State private var backupURL: URL?
    @State private var exporting = false
    @State private var showRestore = false
    @State private var restoreMsg: String?
    @State private var showDevices = false
    @State private var showLogout = false
    @State private var syncing = false
    @State private var syncMsg: String?
    @State private var deviceMsg = "正在查询…"

    var body: some View {
        // 段结构与行顺序完全对齐 Android SettingsActivity：
        // 通用 / WhatsApp 连接 / 翻译设置 / 客户管理 / 隐私与屏蔽 / 关于
        Form {
            Section("通用") {
                LabeledContent("检查更新", value: "3.9.1（55）")
            }

            Section("WhatsApp 连接") {
                // 与 Android 完全同构：本段只有收件自动翻译开关。
                // wa_proxy 不在 UI 暴露（Android 亦只在 Prefs 层），真机特殊网络用 defaults 配置。
                Toggle("收件自动翻译", isOn: $autoTranslate)
            }

            Section("翻译设置") {
                NavigationLink("翻译服务（九家 + 自定义接口）") { TranslationServiceView() }
            }

            Section("客户管理") {
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
                        Label("备份数据", systemImage: "archivebox")
                        if exporting { ProgressView().scaleEffect(0.7) }
                    }
                }
                .disabled(exporting)
                Button { showRestore = true } label: {
                    Label("恢复数据", systemImage: "arrow.down.doc")
                }
                NavigationLink("新建联系人") { NewContactView() }
                // 以下三项对齐 Android SettingsActivity 客户管理段
                Button { showDevices = true } label: { Label("设备管理", systemImage: "iphone.gen3") }
                Button { doSync() } label: {
                    HStack {
                        Label("同步 WhatsApp", systemImage: "arrow.triangle.2.circlepath")
                        if syncing { ProgressView().scaleEffect(0.7) }
                    }
                }.disabled(syncing)
                Button(role: .destructive) { showLogout = true } label: {
                    Label("退出 WhatsApp 账号", systemImage: "rectangle.portrait.and.arrow.right")
                }
            }

            Section("隐私与屏蔽") {
                Text("已拉黑联系人：可在会话长按菜单中拉黑/解除（iOS 暂无独立列表页）")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            Section("关于") {
                LabeledContent("协议层", value: "WeifanProtocol (gomobile)")
                LabeledContent("版本", value: "3.9.1（55）")
            }
        }
        .onAppear { loadDevices() }
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
        .alert("设备管理", isPresented: $showDevices) {
            Button("好", role: .cancel) {}
        } message: { Text(deviceMsg) }
        .alert("同步 WhatsApp", isPresented: .init(get: { syncMsg != nil },
                                                  set: { if !$0 { syncMsg = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(syncMsg ?? "") }
        .alert("退出 WhatsApp 账号？", isPresented: $showLogout) {
            Button("退出账号", role: .destructive) { doLogout() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("本设备会从该 WhatsApp 账号注销，手机「已连接的设备」里也会消失。退出后需重新配对。")
        }
        .alert("恢复结果", isPresented: .init(get: { restoreMsg != nil }, set: { if !$0 { restoreMsg = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(restoreMsg ?? "") }
    }
}

extension ChatSettingsView {
    /// 设备管理（对齐 Android showDevicesDialog：只读，协议层无注销接口）
    func loadDevices() {
        deviceMsg = "正在查询…"
        DispatchQueue.global(qos: .userInitiated).async {
            var derr: NSError?
            let json = ProtocolCore.shared.client.listMyDevices(&derr) ?? "[]"
            let n = (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [Any])?.count ?? 0
            DispatchQueue.main.async {
                deviceMsg = n > 0
                    ? "本账号共 \(n) 台设备在线。\n\n只能查看数量，无法远程注销——注销需要 WhatsApp 未公开的管理接口。若发现陌生设备，请在手机 WhatsApp 的「已连接的设备」里退出。"
                    : "没有查到设备信息（需已连接）。"
            }
        }
    }

    /// 同步 WhatsApp（对齐 Android：请求整体历史，需原装 WhatsApp 在线后台运行）
    func doSync() {
        syncing = true
        syncMsg = nil
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try ProtocolCore.shared.client.requestFullHistorySync(50)
                DispatchQueue.main.async {
                    syncing = false
                    syncMsg = "已发出请求。若原装 WhatsApp 在线且未被清理后台，历史会陆续出现。"
                }
            } catch {
                DispatchQueue.main.async {
                    syncing = false
                    syncMsg = "同步失败：\(error.localizedDescription)"
                }
            }
        }
    }

    /// 退出账号（对齐 Android logoutWhatsApp：不可逆）
    func doLogout() {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try ProtocolCore.shared.client.logoutWhatsApp()
                DispatchQueue.main.async { restoreMsg = "已退出 WhatsApp 账号，请重新配对。" }
            } catch {
                DispatchQueue.main.async { restoreMsg = "退出失败：\(error.localizedDescription)" }
            }
        }
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

    /// 当前标签：ALL / OVERDUE / TODAY / DONE（对齐 Android FollowupTodosActivity 4 标签）
    @State private var tab = "TODAY"

    private let tabs: [(String, String)] = [
        ("全部", "ALL"), ("逾期", "OVERDUE"), ("今日", "TODAY"), ("已完成", "DONE")
    ]

    /// 按标签过滤（对齐 Android todosByFilter）
    private var shown: [TodoRowUI] {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let cal = Calendar.current
        let dayStart = Int64(cal.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
        let dayEnd = dayStart + 86_400_000
        switch tab {
        case "OVERDUE": return todos.filter { !$0.done && $0.dueTs > 0 && $0.dueTs < now }
        case "TODAY": return todos.filter { !$0.done && $0.dueTs >= dayStart && $0.dueTs < dayEnd }
        case "DONE": return todos.filter { $0.done }
        default: return todos
        }
    }

    private var overdueCount: Int {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return todos.filter { !$0.done && $0.dueTs > 0 && $0.dueTs < now }.count
    }
    private var pendingCount: Int { todos.filter { !$0.done }.count }

    var body: some View {
        VStack(spacing: 0) {
            // 标签条（对齐 Android：HorizontalScrollView padding 12/4/12/10，chip 间距 8）
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs, id: \.1) { t in
                        Text(t.0)
                            .font(.system(size: 15))
                            .foregroundStyle(tab == t.1 ? Color(hex: 0x5E5E5E) : Color(hex: 0x111111))
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Capsule().fill(tab == t.1 ? Color(hex: 0xEBEBEF) : .white))
                            .overlay(Capsule().stroke(
                                tab == t.1 ? Color(hex: 0xD1D1D6) : Color(hex: 0xE0E0E4), lineWidth: 1))
                            .onTapGesture { tab = t.1 }
                    }
                }
                .padding(.horizontal, 12)
            }
            .padding(.top, 4).padding(.bottom, 10)

            // 统计卡片（对齐 Android：padding 16/12/16/4，两卡各 weight=1，间距 6，卡内 14/12，
            // 标签 12sp secondary + 数字 22sp bold primary + marginTop 2）
            HStack(spacing: 6) {
                statCard("逾期", overdueCount, overdueCount > 0 ? Color(hex: 0xE53935) : Color(hex: 0x111111))
                statCard("待跟进", pendingCount, Color(hex: 0x111111))
            }
            .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)

            if shown.isEmpty {
                Spacer()
                Text(tab == "DONE" ? "还没有完成的待办"
                     : tab == "TODAY" ? "今天没有待办" : "点右上角 ＋ 记一条")
                    .font(.system(size: 14)).foregroundStyle(Color(hex: 0x8E8E93))
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(shown) { t in
                            todoRow(t)
                            Rectangle().fill(Color(hex: 0xE5E5EA))
                                .frame(height: 1).padding(.leading, 70)
                        }
                    }
                }
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
            Button("保存") { saveNew() }
            Button("取消", role: .cancel) { newContent = "" }
        }
        .onAppear(perform: reload)
    }

    /// 统计卡片：标签 12sp + 数字 22sp bold（对齐 Android statCard）
    private func statCard(_ label: String, _ value: Int, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: 12)).foregroundStyle(Color(hex: 0x8E8E93))
            Text("\(value)").font(.system(size: 22, weight: .bold)).foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.white))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(hex: 0xE5E5EA), lineWidth: 1))
    }

    /// 待办行（对齐 Android item_followup_todo：外层 16/10/16/2，卡片内上下 12 / start 8 / end 4）
    private func todoRow(_ t: TodoRowUI) -> some View {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let overdue = !t.done && t.dueTs > 0 && t.dueTs < now
        return HStack(spacing: 6) {
            Button { toggle(t) } label: {
                Image(systemName: t.done ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(t.done ? Color(hex: 0x25D366) : Color(hex: 0xC7C7CC))
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(t.content)
                    .font(.system(size: 16))
                    .strikethrough(t.done)
                    .foregroundStyle(t.done ? Color(hex: 0x8E8E93) : Color(hex: 0x111111))
                    .lineLimit(3)
                if t.dueTs > 0 {
                    Text((overdue ? "⚠ 已过期 " : "到期 ") + Self.day(t.dueTs))
                        .font(.system(size: 12))
                        .foregroundStyle(overdue ? Color(hex: 0xE53935) : Color(hex: 0x8E8E93))
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, 8).padding(.trailing, 4).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.white))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(hex: 0xE5E5EA), lineWidth: 1))
        .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 2)
    }

    private func saveNew() {
        let text = newContent.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        let due = newDue.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        _ = try? core.db.exec(
            "INSERT INTO todos(chat_jid,content,due_ts,done,created_ts) VALUES(?,?,?,0,?)",
            args: ["", text, due, Int64(Date().timeIntervalSince1970 * 1000)])
        if due > 0 {
            TodoNotifications.ensureAuthorized { _ in }
            TodoNotifications.schedule(todoId: Int64(Date().timeIntervalSince1970),
                                       content: text, dueTs: due)
        }
        newContent = ""; newDue = nil
        reload()
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
