// 微翻 iOS —— 聊天栈页面（阶段 1 最小闭环）
//
// 四页：语言选择（首启）→ 配对页 → 会话列表 → 会话页；联系人信息只读。
// 布局规格对齐 Android 端与参考（docs/17 阶段 1 验收）。

import SwiftUI
import CoreImage.CIFilterBuiltins

// MARK: - 根路由

struct ChatRootView: View {
    @ObservedObject var core = ProtocolCore.shared
    @AppStorage("onboard_lang_done") private var langDone = false
    // UI 测试钩子：launch 参数强制回到首启态；用户选完语言即解除（状态门控，时序确定）
    @State private var forceOnboarding = ProcessInfo.processInfo.arguments.contains("UITEST_RESET_ONBOARDING")

    var body: some View {
        if forceOnboarding || !langDone {
            LanguagePickerView(onDone: { forceOnboarding = false })
        } else if !core.paired {
            PairingView()
        } else {
            ChatListView()
        }
    }
}

// MARK: - 语言选择页（首启）

struct LanguagePickerView: View {
    var onDone: (() -> Void)? = nil
    @AppStorage("onboard_lang_done") private var langDone = false
    @AppStorage("wa_target_lang") private var targetLang = "es"
    @State private var query = ""

    private let langs: [(String, String)] = [
        ("es", "西班牙语"), ("en", "英语"), ("pt", "葡萄牙语"), ("fr", "法语"),
        ("de", "德语"), ("it", "意大利语"), ("ru", "俄语"), ("ja", "日语"),
        ("ko", "韩语"), ("ar", "阿拉伯语"), ("th", "泰语"), ("vi", "越南语"),
        ("id", "印尼语"), ("ms", "马来语"), ("tr", "土耳其语"), ("nl", "荷兰语"),
    ]

    var body: some View {
        NavigationStack {
            List(filtered, id: \.0) { code, name in
                Button {
                    targetLang = code
                    langDone = true
                    onDone?()
                } label: {
                    HStack {
                        Text(name).foregroundStyle(.primary)
                        Spacer()
                        if code == targetLang {
                            Image(systemName: "checkmark").foregroundStyle(.teal)
                        }
                    }
                }
                .accessibilityIdentifier("lang_\(code)")
            }
            .searchable(text: $query, prompt: "搜索语言")
            .navigationTitle("选择对方的语言")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var filtered: [(String, String)] {
        query.isEmpty ? langs : langs.filter { $1.contains(query) || $0.contains(query.lowercased()) }
    }
}

// MARK: - 配对页

struct PairingView: View {
    @ObservedObject var core = ProtocolCore.shared
    @State private var pairCode: String?
    @State private var qrImage: UIImage?
    @State private var started = false

    var body: some View {
        VStack(spacing: 20) {
            Text("关联 WhatsApp")
                .font(.title2.bold())
            Text("在手机 WhatsApp → 已链接的设备 → 链接设备，扫描下方二维码")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            ZStack {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color.white)
                    .frame(width: 240, height: 240)
                    .shadow(radius: 4)
                if let qrImage {
                    Image(uiImage: qrImage)
                        .resizable()
                        .interpolation(.none)
                        .scaledToFit()
                        .frame(width: 216, height: 216)
                } else if let pairCode {
                    Text(pairCode)
                        .font(.title3.monospaced())
                } else {
                    ProgressView()
                }
            }

            if let err = core.pairError {
                Text(err).font(.footnote).foregroundStyle(.red)
            }

            Text("二维码约 20 秒自动刷新，白屏请重新进入")
                .font(.caption2).foregroundStyle(.secondary)

            if !started, let err = core.pairError {
                Text(err).font(.footnote).foregroundStyle(.red)
            }
            Spacer()
        }
        .padding(.top, 48)
        // 进页即出码（对齐参考：配对页进入就直接展示二维码，20 秒自动刷新）
        .onAppear {
            guard !started else { return }
            started = true
            core.startQRPiring { png in
                qrImage = UIImage(data: png)
            }
        }
    }

    /// 本地渲染 QR（协议层的 PNG 回调在本版 xcframework 里还没有——用码串现场画，等价）
    static func makeQR(_ code: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(code.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: - 会话列表

struct ChatListView: View {
    @ObservedObject var core = ProtocolCore.shared
    @State private var query = ""
    @State private var chip = "所有"
    private let chips = ["所有", "未读", "特别关注", "群组", "已归档"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // 连接状态条
                if !core.connected {
                    Text("连接中…（需手机 WhatsApp 在线）")
                        .font(.caption)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(Color.orange.opacity(0.15))
                        .foregroundStyle(.orange)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(chips, id: \.self) { c in
                            Text(c)
                                .font(.callout)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(chip == c ? Color.teal.opacity(0.15) : Color(uiColor: .secondarySystemFill))
                                .foregroundStyle(chip == c ? .teal : .secondary)
                                .clipShape(Capsule())
                                .onTapGesture { chip = c }
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .padding(.vertical, 8)

                let list = filtered
                if list.isEmpty {
                    emptyView
                } else {
                    List(list) { chat in
                        NavigationLink(value: chat.jid) {
                            ChatListRow(chat: chat)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("WeFlip")
            .navigationDestination(for: String.self) { jid in
                ChatView(chatJid: jid)
            }
            .searchable(text: $query, prompt: "搜索或开始新聊天")
            .onAppear { core.connectAsync() }
        }
    }

    private var filtered: [ChatRow] {
        var cs = core.chats()
        if query.isEmpty == false {
            cs = cs.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.jid.contains(query) }
        }
        switch chip {
        case "未读": cs = cs.filter { $0.unread > 0 }
        case "群组": cs = cs.filter { $0.jid.contains("@g.us") }
        case "已归档": cs = cs.filter { $0.archived }
        default: break
        }
        return cs
    }

    /// 空态四态：未配对 / 未连接 / 无消息 / 无匹配
    @ViewBuilder private var emptyView: some View {
        VStack(spacing: 10) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 40)).foregroundStyle(.secondary)
            Text(core.chats().isEmpty ? "还没有会话\n收到的消息会出现在这里" : "没有匹配「\(query)」的会话")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ChatListRow: View {
    let chat: ChatRow
    @State private var avatar: UIImage?

    var body: some View {
        HStack(spacing: 12) {
            if let avatar {
                Image(uiImage: avatar).resizable().scaledToFill()
                    .frame(width: 48, height: 48).clipShape(Circle())
            } else {
                ZStack {
                    Circle().fill(Color.teal.opacity(0.2))
                    Text(String(chat.title.first ?? "#"))
                        .font(.headline).foregroundStyle(.teal)
                }
                .frame(width: 48, height: 48)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(chat.title).font(.body.weight(.medium)).lineLimit(1)
                Text(chat.lastMessage).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(Self.time(chat.lastTs)).font(.caption2).foregroundStyle(.secondary)
                if chat.unread > 0 {
                    Text("\(chat.unread)")
                        .font(.caption2.bold()).foregroundStyle(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Color.teal).clipShape(Capsule())
                }
            }
        }
        .task(id: chat.jid) {
            // 头像：协议层 getProfilePicture（字节 → UIImage），失败静默
            let bytes = try? ProtocolCore.shared.client.getProfilePicture(chat.jid)
            if let bytes { avatar = UIImage(data: Data(bytes)) }
        }
    }

    static func time(_ ms: Int64) -> String {
        guard ms > 0 else { return "" }
        let d = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        let df = DateFormatter()
        df.dateFormat = Calendar.current.isDateInToday(d) ? "HH:mm" : "MM/dd"
        return df.string(from: d)
    }
}

// MARK: - 会话页

struct ChatView: View {
    let chatJid: String
    @ObservedObject var core = ProtocolCore.shared
    @State private var title = ""
    @State private var input = ""
    @State private var msgs: [MessageRow] = []
    @State private var sendError: String?

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(msgs) { m in
                            BubbleView(m: m, read: core.readReceipts[m.id] == true)
                                .id(m.id)
                        }
                    }
                    .padding(.horizontal, 8).padding(.vertical, 10)
                }
                .onChange(of: msgs.count) { _ in
                    if let last = msgs.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
                .onChange(of: core.inboxTick) { _ in reload() }
            }
            HStack(spacing: 8) {
                TextField("输入中文，发出自动翻译", text: $input, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button {
                    let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    input = ""
                    core.sendTextAsync(chatJid: chatJid, text: text) { err in
                        sendError = err
                    }
                } label: {
                    Image(systemName: "paperplane.fill")
                        .frame(width: 40, height: 40)
                }
                .buttonStyle(.borderedProminent)
                .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 8).padding(.vertical, 6)
        }
        .navigationTitle(title.isEmpty ? chatJid : title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { ContactInfoView(chatJid: chatJid, title: title) } label: {
                    Image(systemName: "info.circle")
                }
            }
        }
        .alert("发送失败", isPresented: .init(get: { sendError != nil }, set: { if !$0 { sendError = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(sendError ?? "") }
        .onAppear {
            reload()
            core.markRead(chatJid: chatJid)
            core.bindReadReceipts { chat, type, ids in
                if chat == chatJid { core.handleReadReceipts(chatJid: chat, type: type, ids: ids) }
            }
        }
    }

    private func reload() {
        msgs = core.messages(chatJid: chatJid)
        if title.isEmpty {
            title = core.chats().first(where: { $0.jid == chatJid })?.title ?? chatJid
        }
    }
}

/// 气泡：收件白靠左 + 译文行（青蓝 medium）；发件绿靠右 + 中文原文副行；时间+回执
struct BubbleView: View {
    let m: MessageRow
    let read: Bool

    var body: some View {
        HStack {
            if m.fromMe { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 3) {
                Text(m.text)
                    .foregroundStyle(.primary)
                if !m.fromMe, !m.translated.isEmpty {
                    Text(m.translated)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color(hex: 0x0A7EA4))
                }
                if m.fromMe, !m.original.isEmpty, m.original != m.text {
                    Text(m.original)
                        .font(.caption).foregroundStyle(.secondary)
                }
                HStack(spacing: 3) {
                    Text(Self.time(m.ts)).font(.caption2).foregroundStyle(.secondary)
                    if m.fromMe {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption2)
                            .foregroundStyle(read ? Color(hex: 0x34B7F1) : Color.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(10)
            .background(m.fromMe ? Color(hex: 0xD9FDD3) : Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            if !m.fromMe { Spacer(minLength: 48) }
        }
    }

    static func time(_ ms: Int64) -> String {
        let df = DateFormatter(); df.dateFormat = "HH:mm"
        return df.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

// MARK: - 联系人信息（只读）

struct ContactInfoView: View {
    let chatJid: String
    let title: String
    @ObservedObject var core = ProtocolCore.shared
    @State private var avatar: UIImage?

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    if let avatar {
                        Image(uiImage: avatar).resizable().scaledToFill()
                            .frame(width: 96, height: 96).clipShape(Circle())
                    } else {
                        ZStack {
                            Circle().fill(Color.teal.opacity(0.2))
                            Text(String((title.isEmpty ? chatJid : title).first ?? "#"))
                                .font(.largeTitle).foregroundStyle(.teal)
                        }
                        .frame(width: 96, height: 96)
                    }
                    Text(title.isEmpty ? chatJid : title).font(.title3.weight(.medium))
                    Text(chatJid).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
            Section("状态") {
                LabeledContent("连接", value: core.connected ? "在线" : "未连接")
            }
        }
        .navigationTitle("联络人资料")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let b = try? core.client.getProfilePicture(chatJid) { avatar = UIImage(data: Data(b)) } }
    }
}

// MARK: - 工具

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}
