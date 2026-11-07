import SwiftUI

// MARK: - 底部四 Tab（对齐 WhatsApp：聊天/通话/更新/工具）

final class TabRouter: ObservableObject {
    @Published var selection = 0
    /// 跨 Tab 打开会话（通话页点记录 → 跳聊天页并进入会话）
    @MainActor func openChat(_ jid: String, title: String) {
        selection = 0
        ProtocolCore.shared.openChat(jid: jid, title: title)
    }
}

struct MainTabsView: View {
    @ObservedObject var core = ProtocolCore.shared
    @StateObject private var router = TabRouter()
    @ObservedObject private var callEngine = CallEngine.shared

    var body: some View {
        ZStack {
            TabView(selection: $router.selection) {
                NavigationStack { ChatListView() }
                    .tabItem { Label("聊天", systemImage: "message.fill") }
                    .tag(0)
                NavigationStack { CallsView() }
                    .tabItem { Label("通话", systemImage: "phone.fill") }
                    .tag(1)
                NavigationStack { UpdatesView() }
                    .tabItem { Label("更新", systemImage: "arrow.uturn.forward.circle") }
                    .tag(2)
                NavigationStack { ToolsView() }
                    .tabItem { Label("工具", systemImage: "storefront") }
                    .tag(3)
            }
            // 通话浮层：全 Tab 通用（来电/主叫状态）
            .overlay {
                if callEngine.phase != .idle {
                    CallOverlayView(client: core.client) { jid in
                        core.chats().first(where: { $0.jid == jid })?.title ?? jid
                    }
                }
            }
            .alert("通话", isPresented: .init(
                get: { callEngine.error != nil && callEngine.phase == .idle },
                set: { if !$0 { callEngine.clearError() } })) {
                Button("好", role: .cancel) {}
            } message: { Text(callEngine.error ?? "") }
        }
        .onAppear { CallEngine.shared.wire(client: core.client) }
    }
}

// MARK: - 通话页（对齐 WhatsApp：快捷圆钮 + 最近通话）

struct CallsView: View {
    @ObservedObject var core = ProtocolCore.shared
    @StateObject private var router = TabRouter()
    @EnvironmentObject private var model: AppModel
    @State private var records: [ChatDatabase.CallRecord] = []
    @State private var query = ""
    @State private var showPicker = false
    @State private var showComingSoon = false
    @State private var comingSoonText = ""

    private var filtered: [ChatDatabase.CallRecord] {
        guard !query.isEmpty else { return records }
        return records.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.chatJid.contains(query) }
    }

    var body: some View {
        List {
            // 快捷圆钮（对齐 WhatsApp 通话页）
            Section {
                HStack(spacing: 18) {
                    quickCircle(icon: "phone.fill", label: "通话") { showPicker = true }
                    quickCircle(icon: "calendar", label: "定时") {
                        comingSoonText = "定时通话即将推出"; showComingSoon = true
                    }
                    quickCircle(icon: "circle.grid.3x3.fill", label: "数字键盘") {
                        comingSoonText = "数字键盘即将推出"; showComingSoon = true
                    }
                    quickCircle(icon: "heart.fill", label: "特别关注") {
                        comingSoonText = "特别关注通话即将推出"; showComingSoon = true
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .listRowBackground(Color.clear)
            }

            Section("最近通话") {
                if filtered.isEmpty {
                    Text("还没有通话记录\n在聊天里拨打或接听通话后会显示在这里")
                        .font(.callout).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity).padding(.vertical, 18)
                } else {
                    ForEach(Array(filtered.enumerated()), id: \.element.ts) { _, r in
                        callRow(r)
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "搜索")
        .navigationTitle("通话")
        .onAppear { records = core.db.callRecords() }
        .refreshable { records = core.db.callRecords() }
        .sheet(isPresented: $showPicker) { callPickerSheet }
        .alert(comingSoonText, isPresented: $showComingSoon) {
            Button("好", role: .cancel) {}
        }
    }

    private func callRow(_ r: ChatDatabase.CallRecord) -> some View {
        let missed = r.dir == "missed"
        let title = r.title.isEmpty ? r.chatJid : r.title
        return Button {
            router.openChat(r.chatJid, title: title)
        } label: {
            HStack(spacing: 12) {
                AvatarView(jid: r.chatJid, name: title)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(missed ? Color(hex: 0xE53935) : .primary)
                    HStack(spacing: 4) {
                        Image(systemName: r.dir == "out" ? "arrow.up.right" : "arrow.down.left")
                            .font(.caption2)
                            .foregroundStyle(missed ? Color(hex: 0xE53935) : .secondary)
                        Text(Self.time(r.ts)).font(.caption).foregroundStyle(.secondary)
                        if r.duration > 0 {
                            Text("· \(CallOverlayView.dur(r.duration))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer()
                Image(systemName: r.kind == "video" ? "video.fill" : "phone.fill")
                    .foregroundStyle(Color(hex: 0x25D366))
            }
        }
    }

    /// 快捷圆钮
    private func quickCircle(icon: String, label: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundStyle(.primary)
                    .frame(width: 56, height: 56)
                    .background(Circle().fill(Color(uiColor: .tertiarySystemFill)))
            }
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }

    /// 选择会话发起通话
    private var callPickerSheet: some View {
        NavigationStack {
            List(core.chats()) { c in
                Button {
                    showPicker = false
                    CallEngine.shared.startCall(client: core.client, jid: c.jid, video: false)
                } label: {
                    HStack {
                        AvatarView(jid: c.jid, name: c.title)
                        Text(c.title.isEmpty ? c.jid : c.title).font(.callout)
                        Spacer()
                    }
                }
            }
            .navigationTitle("选择联系人通话")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    static func time(_ ms: Int64) -> String {
        let df = DateFormatter()
        df.dateFormat = "MM/dd HH:mm"
        return df.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

// MARK: - 更新页（动态 + 频道；协议层支持前为占位展示）

struct UpdatesView: View {
    @State private var showSoon = false

    var body: some View {
        List {
            Section("动态") {
                Button {
                    showSoon = true
                } label: {
                    HStack(spacing: 12) {
                        ZStack(alignment: .bottomTrailing) {
                            Circle().fill(Color(uiColor: .tertiarySystemFill))
                                .frame(width: 52, height: 52)
                            Image(systemName: "plus.circle.fill")
                                .font(.system(size: 18))
                                .foregroundStyle(.green)
                                .background(Circle().fill(.white))
                                .offset(x: 2, y: 2)
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            Text("添加动态").font(.body)
                            Text("24 小时后自动消失").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section {
                Text("掌握关心主题的最新资讯。你关注的频道将出现在此处。")
                    .font(.callout).foregroundStyle(.secondary)
            } header: {
                HStack {
                    Text("频道").font(.title3.weight(.bold)).foregroundStyle(.primary)
                    Spacer()
                    Button {
                        showSoon = true
                    } label: {
                        Label("探索", systemImage: "square.grid.2x2")
                            .font(.footnote)
                    }
                    .buttonStyle(.bordered)
                }
                .textCase(nil)
            }
        }
        .navigationTitle("更新")
        .alert("即将推出", isPresented: $showSoon) {
            Button("好", role: .cancel) {}
        } message: {
            Text("动态与频道需要协议层支持，正在开发中。")
        }
    }
}

// MARK: - 工具页（映射微翻功能入口）

struct ToolsView: View {
    @ObservedObject var core = ProtocolCore.shared
    @State private var stats: (chats: Int, calls: Int) = (0, 0)
    @State private var syncing = false
    @State private var syncMsg: String?
    @State private var backupURL: URL?
    @State private var exporting = false

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    statCard("message.fill", "\(stats.chats)", "会话")
                    statCard("phone.fill", "\(stats.calls)", "通话次数")
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            } header: {
                Text("过去 7 天的效果").textCase(nil)
            }

            Section("翻译与工具") {
                NavigationLink { TranslationServiceView() } label: {
                    Label("翻译服务", systemImage: "character.bubble")
                }
                NavigationLink { TodoListView() } label: {
                    Label("跟进待办", systemImage: "checklist")
                }
            }

            Section("数据") {
                Button {
                    syncing = true
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
                } label: {
                    Label("同步 WhatsApp", systemImage: "arrow.triangle.2.circlepath")
                    if syncing { Text("请求中…").font(.caption).foregroundStyle(.secondary) }
                }
                .disabled(syncing)

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
                    Label("备份数据", systemImage: "archivebox")
                    if exporting { ProgressView().scaleEffect(0.7) }
                }
                .disabled(exporting)
            }

            Section("关于") {
                LabeledContent("版本", value: "3.9.1（55）")
                LabeledContent("协议层", value: "WeifanProtocol (gomobile)")
            }
        }
        .navigationTitle("工具")
        .onAppear { stats = core.db.statsCounts() }
        .sheet(item: $backupURL) { url in
            ShareLink(item: url, preview: SharePreview("WeFlip 备份"))
        }
        .alert("同步 WhatsApp", isPresented: .init(get: { syncMsg != nil },
                                                   set: { if !$0 { syncMsg = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(syncMsg ?? "") }
    }

    private func statCard(_ icon: String, _ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).font(.title3)
            Text(value).font(.title2.bold())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - 通用头像（协议层 getProfilePicture，带缓存）

struct AvatarView: View {
    let jid: String
    let name: String
    var size: CGFloat = 46
    @State private var avatar: UIImage?
    private static let cache = NSCache<NSString, UIImage>()

    var body: some View {
        Group {
            if let avatar {
                Image(uiImage: avatar).resizable().scaledToFill()
            } else {
                ZStack {
                    Circle().fill(Color(hex: 0x25D366).opacity(0.18))
                    Text(String(name.first ?? "#"))
                        .font(.system(size: size * 0.4)).foregroundStyle(Color(hex: 0x25D366))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .task(id: jid) {
            if let c = AvatarView.cache.object(forKey: jid as NSString) { avatar = c; return }
            let bytes = try? ProtocolCore.shared.client.getProfilePicture(jid)
            if let bytes, let ui = UIImage(data: Data(bytes)) {
                AvatarView.cache.setObject(ui, forKey: jid as NSString)
                avatar = ui
            }
        }
    }
}
