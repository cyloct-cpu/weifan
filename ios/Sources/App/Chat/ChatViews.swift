// 微翻 iOS —— 聊天栈页面（阶段 1 最小闭环）
//
// 四页：语言选择（首启）→ 配对页 → 会话列表 → 会话页；联系人信息只读。
// 布局规格对齐 Android 端与参考（docs/17 阶段 1 验收）。

import SwiftUI
import CoreImage.CIFilterBuiltins
import WeiFanCore
import AVFoundation
import PhotosUI

// MARK: - 根路由

struct ChatRootView: View {
    @ObservedObject var core = ProtocolCore.shared
    @AppStorage("onboard_lang_done") private var langDone = false
    // UI 测试钩子：launch 参数强制回到首启态；用户选完语言即解除（状态门控，时序确定）
    @State private var forceOnboarding = ProcessInfo.processInfo.arguments.contains("UITEST_RESET_ONBOARDING")

    var body: some View {
        // 调试预览：launch 参数 PREVIEW_LIST=1 直接看会话列表 UI（不需要真实配对），
        // 用于 UI 与 Android 版对比截图
        // 安卓没有「界面语言选择」页（中文单语），这里去掉，直接按配对状态分流
        if ProcessInfo.processInfo.arguments.contains("PREVIEW_LIST") {
            ChatListView()
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
    @State private var mode = 0                  // 0=手机号 1=二维码
    @State private var code = "86"               // 国家码（86 是灰色提示）
    @State private var phone = ""
    @State private var started = false
    @State private var qrStarted = false

    /// 纯数字：国家码 + 手机号（对齐 Android normalizePhone，区号空则默认 86）
    private var digits: String? {
        let c = code.filter(\.isNumber)
        let p = phone.filter(\.isNumber)
        guard p.count >= 6 else { return nil }
        return (c.isEmpty ? "86" : c) + p
    }

    var body: some View {
        ZStack {
            // 浅底 + 品牌动画环境（对齐品牌规范：等待场景用 W 动画）
            Color.dyn(0xFFFFFF, 0x111B21).ignoresSafeArea()
            WLogoPlayer(loop: true)
                .frame(maxWidth: .infinity)
                .opacity(0.14)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 16) {
                    Text("关联 WhatsApp").font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.primary)
                    Picker("", selection: $mode) {
                        Text("手机号").tag(0)
                        Text("二维码").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 24)

                    if mode == 0 { phoneFlow } else { qrFlow }

                    if let err = core.pairError {
                        Text(err).font(.footnote).foregroundStyle(.red)
                            .multilineTextAlignment(.center).padding(.horizontal, 24)
                    }
                }
                .padding(.vertical, 24)
            }
        }
        .onAppear {
            // 默认进手机号流程；切到二维码才发 QR 请求（对齐 Android 进面板才发起）
            if mode == 1 && !qrStarted { requestQR() }
        }
        .onChange(of: mode) { m in
            if m == 1 && !qrStarted { requestQR() }
        }
    }

    // MARK: 手机号流程（对齐 Android：输入手机号 → 拿 8 位码 → 去 WhatsApp 输入）

    @ViewBuilder private var phoneFlow: some View {
        VStack(spacing: 12) {
            Text("在手机 WhatsApp → 已链接的设备 → 使用电话号码关联，输入下方号码")
                .font(.subheadline).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 28)

            // 「+」固定 + 国家码（86 灰提示）+ 手机号
            HStack(spacing: 0) {
                Text("+").font(.system(size: 17)).padding(.leading, 14)
                TextField("86", text: $code)
                    .keyboardType(.numberPad)
                    .frame(width: 56)
                    .font(.system(size: 17))
                    .multilineTextAlignment(.center)
                Divider().frame(height: 22)
                TextField("请输入手机号码", text: $phone)
                    .keyboardType(.numberPad)
                    .font(.system(size: 17))
                    .padding(.horizontal, 12)
            }
            .frame(height: 50)
            .background(Capsule().fill(Color.dyn(0xF2F2F7, 0x202C33)))
            .padding(.horizontal, 24)

            Button {
                guard let d = digits else {
                    core.pairError = "号码不完整（含国家码，至少 6 位手机号）"
                    return
                }
                do {
                    try core.client.pair(withPhone: d)
                    started = true
                    core.pairError = nil
                } catch {
                    core.pairError = error.localizedDescription
                }
            } label: {
                Text("下一步").font(.system(size: 16))
                    .frame(maxWidth: .infinity).frame(height: 52)
                    .background(Capsule().fill(Color(hex: 0x25D366)))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 24)
            .disabled(digits == nil)

            if started || core.pairCode != nil {
                VStack(spacing: 8) {
                    Text("手机 WhatsApp 里会显示 8 位验证码，输入后完成关联")
                        .font(.footnote).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    if let c = core.pairCode {
                        Text(c).font(.system(size: 28, weight: .bold, design: .monospaced))
                            .textSelection(.enabled)
                        Text("已复制到剪贴板，可直接粘贴").font(.caption2).foregroundStyle(.green)
                    } else {
                        ProgressView().padding(.vertical, 8)
                    }
                }
                .padding(.top, 8)
            }
        }
        // 拿到配对码后：模糊背景 + 品牌 W 动画（码保持清晰，供对方输入）
        .overlay {
            if core.pairCode != nil {
                ZStack {
                    Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
                    VStack(spacing: 18) {
                        WLogoPlayer()
                            .frame(width: 220, height: 124)
                        Text("正在等待配对确认…")
                            .font(.callout).foregroundStyle(.primary)
                        if let c = core.pairCode {
                            Text(c).font(.system(size: 34, weight: .bold, design: .monospaced))
                                .textSelection(.enabled)
                            Text("在对方 WhatsApp 输入此码完成关联")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.35), value: core.pairCode)
    }

    // MARK: 二维码流程

    @ViewBuilder private var qrFlow: some View {
        VStack(spacing: 14) {
            Text("在手机 WhatsApp → 已链接的设备 → 链接设备，扫描下方二维码")
                .font(.subheadline).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 28)
            ZStack {
                RoundedRectangle(cornerRadius: 16).fill(Color.dyn(0xFFFFFF, 0x1F2C33))
                    .frame(width: 240, height: 240).shadow(radius: 4)
                if let img = core.qrImage {
                    Image(uiImage: img).resizable().interpolation(.none)
                        .scaledToFit().frame(width: 216, height: 216)
                } else {
                    ProgressView()
                }
            }
            Text("二维码约 20 秒自动刷新，白屏请切回再进入")
                .font(.caption2).foregroundStyle(.secondary)
            Button("重新获取二维码") { qrStarted = false; requestQR() }
                .font(.footnote)
        }
    }

    private func requestQR() {
        qrStarted = true
        core.pairError = nil
        core.pairQR()
    }
}

struct ChatListView: View {
    @ObservedObject var core = ProtocolCore.shared
    @State private var query = ""
    @State private var chip = "所有"
    @State private var showNewChat = false
    @State private var showNewGroup = false
    // 来电浮层/通话错误 alert 的条件在 body 里 → 观察 CallEngine
    @ObservedObject private var callEngine = CallEngine.shared
    private let chips = ["所有", "未读", "特别关注", "群组", "已归档"]

    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                // 自定义 header（对齐 Android：标题 18sp 左对齐 + 右上 ＋ 32dp）
                // 不用系统导航栏：iOS 的 navigationTitle 居中且会截断（实测显示成「W...」）
                HStack(spacing: 0) {
                    Text("WeFlip")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.primary)
                    Spacer()
                    Menu {
                        Button { showNewChat = true } label: { Label("新建会话（加好友）", systemImage: "person.badge.plus") }
                        Button { showNewGroup = true } label: { Label("新建群聊", systemImage: "person.3") }
                        NavigationLink { NewContactView() } label: { Label("新建联系人（存手机通讯录）", systemImage: "person.crop.circle.badge.plus") }
                        Divider()
                        NavigationLink { ChatSettingsView() } label: { Label("设置", systemImage: "gearshape") }
                    } label: {
                        // 安卓是竖三点 ⋮，SF 的 ellipsis 是横的，旋转 90°
                        Image(systemName: "ellipsis")
                            .font(.system(size: 19, weight: .medium))
                            .rotationEffect(.degrees(90))
                            .foregroundStyle(.primary)
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityIdentifier("header_menu")
                }
                .padding(.horizontal, 12)
                .frame(height: 48)
                .background(Color.dyn(0xFFFFFF, 0x111B21))

                // 搜索框：.searchable 依附系统导航栏，隐藏导航栏后失效，改为自绘
                // 对齐安卓：40dp 高、圆角 20、#F2F2F7 底、图标 14sp + hint 15sp
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 14)).foregroundStyle(.secondary)
                    TextField("搜索或开始新聊天", text: $query)
                        .font(.system(size: 15))
                        .textFieldStyle(.plain)
                    if !query.isEmpty {
                        Button { query = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 14)).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.horizontal, 12)
                .frame(height: 40)
                .background(RoundedRectangle(cornerRadius: 20).fill(Color.dyn(0xF2F2F7, 0x202C33)))
                .padding(.horizontal, 12).padding(.vertical, 6)

                // 连接状态条
                if !core.connected {
                    Text(core.connectError.map { "连接失败：\($0) · 重试中…" }
                         ?? "连接中…（需可用的代理/网络出口；扫码与历史同步时需手机在线）")
                        .font(.caption)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(Color.orange.opacity(0.15))
                        .foregroundStyle(.orange)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(chips, id: \.self) { c in
                            // 照搬 Android chip：文字 15sp，内 padding 8/6，
                            // 选中 #EBEBEF + 描边 #E0E0E4 + 文字 #5E5E5E；未选白底 + #E0E0E4 描边
                            Text(c)
                                .font(.system(size: 15))
                                .foregroundStyle(chip == c ? .primary : .primary)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(
                                    Capsule().fill(chip == c ? Color.dyn(0xEBEBEF, 0x2A3942) : Color.dyn(0xFFFFFF, 0x202C33))
                                )
                                .overlay(
                                    Capsule().stroke(chip == c ? Color.dyn(0xD1D1D6, 0x3B4A52) : Color.dyn(0xE0E0E4, 0x2A3942), lineWidth: 1)
                                )
                                .onTapGesture { chip = c }
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .padding(.vertical, 8)

                // 用 ScrollView + VStack 而不是 List：
                // iOS 的 List 自带 > 箭头与缩进分隔线，安卓是通栏行 + 无箭头（实测差异明显）
                let list = filtered
                if list.isEmpty {
                    emptyView
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(list) { chat in
                                NavigationLink(value: chat.jid) {
                                    ChatListRow(chat: chat)
                                }
                                .buttonStyle(.plain)
                                // 通栏 1dp 分隔线，左缩进 70（对齐安卓 dividerInset 46+12+12）
                                Rectangle().fill(Color.dyn(0xE5E5EA, 0x222D34))
                                    .frame(height: 1)
                                    .padding(.leading, 70)
                            }
                        }
                    }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .onAppear { CallEngine.shared.wire(client: core.client); reloadChats() }
            .onChange(of: core.inboxTick) { _ in reloadChats() }
            .alert("通话", isPresented: .init(
                get: { callEngine.error != nil && callEngine.phase == .idle },
                set: { if !$0 { callEngine.clearError() } })) {
                Button("好", role: .cancel) {}
            } message: { Text(callEngine.error ?? "") }
            .overlay {
                if callEngine.phase != .idle {
                    CallOverlayView(client: core.client) { jid in
                        core.chats().first(where: { $0.jid == jid })?.title
                            ?? core.messages(chatJid: jid).last.map { _ in jid } ?? jid
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索或开始新聊天")
            .navigationDestination(for: String.self) { jid in
                ChatView(chatJid: jid)
            }
            .onAppear { core.connectAsync() }
            .onChange(of: core.pendingChatJid) { jid in
                // 新建会话/联系人/建群后程序化进会话（iOS 16 兼容写法）
                if let jid { path.append(jid); core.pendingChatJid = nil }
            }
            .sheet(isPresented: $showNewChat) { NewChatSheet() }
            .sheet(isPresented: $showNewGroup) { NewGroupSheet() }
        }
    }

    // 会话列表缓存：只在 inboxTick/首启时查库（此前每次渲染都全量查 SQLite，按钮/搜索都卡）
    @State private var chatsCache: [ChatRow] = []
    private func reloadChats() { chatsCache = core.chats() }

    private var filtered: [ChatRow] {
        var cs = chatsCache
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
            Text(chatsCache.isEmpty ? "还没有会话\n收到的消息会出现在这里" : "没有匹配「\(query)」的会话")
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
        // 数值全部照搬 Android ChatListActivity.chatRow：
        // 行 padding 12/10、minHeight 68dp、头像 46dp(圆角 23)、标题 16sp、副标题 13sp
        HStack(spacing: 0) {
            if let avatar {
                Image(uiImage: avatar).resizable().scaledToFill()
                    .frame(width: 46, height: 46).clipShape(RoundedRectangle(cornerRadius: 23))
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 23).fill(Color.dyn(0xF2F2F7, 0x202C33))
                    Text(String(chat.title.first ?? "#"))
                        .font(.system(size: 20)).foregroundStyle(.secondary)
                }
                .frame(width: 46, height: 46)
            }
            VStack(alignment: .leading, spacing: 2) {
                // 对齐 Android：title 为空时用 jid 前半段兜底（否则会显示成空白或错位）
                // title 为空时显示「+号码」而不是裸数字（历史同步的消息没有 pushName，
                // 根治需协议层补联系人接口并重编 xcframework，见交付说明）
                Text(chat.title.isEmpty
                     ? "+" + String(String(chat.jid.split(separator: "@").first ?? "").filter(\.isNumber))
                     : chat.title)
                    .font(.system(size: 16)).foregroundStyle(.primary).lineLimit(1)
                Text(chat.lastMessage)
                    .font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.leading, 12)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 4) {
                Text(Self.time(chat.lastTs))
                    .font(.system(size: 12)).foregroundStyle(.secondary)
                if chat.unread > 0 {
                    Text("\(chat.unread)")
                        .font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color(hex: 0x25D366)).clipShape(Capsule())
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(minHeight: 68)
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

// MARK: - 会话页（阶段 2：媒体收发 + 长按菜单 + 语音 + 查看器）

struct ChatView: View {
    @State private var showLangPicker = false
    @State private var showSearch = false
    @State private var searchText = ""

    private static let langOptions: [(String, String)] = [
        ("自动检测", ""), ("英语", "en"), ("西班牙语", "es"),
        ("葡萄牙语", "pt"), ("法语", "fr"), ("德语", "de")
    ]

    @ViewBuilder
    private func langRow(name: String, code: String) -> some View {
        let key = "chat_lang_\(chatJid)"
        let current = UserDefaults.standard.string(forKey: key) ?? ""
        Button {
            UserDefaults.standard.set(code, forKey: key)
            showLangPicker = false
        } label: {
            HStack {
                Text(name).foregroundStyle(.primary)
                Spacer()
                if current == code {
                    Image(systemName: "checkmark").foregroundStyle(Color(hex: 0x25D366))
                }
            }
        }
    }

    let chatJid: String
    @ObservedObject var core = ProtocolCore.shared
    // 通话浮层/alert 的可见性条件在本视图 body 里 → 必须观察 CallEngine
    @ObservedObject private var callEngine = CallEngine.shared
    @State private var title = ""
    @State private var input = ""
    @State private var msgs: [MessageRow] = []
    @State private var sendError: String?
    // 阶段 2 状态
    @State private var pendingQuote: MessageRow?
    @State private var showAttach = false
    @State private var showPoll = false
    @State private var showLocation = false
    @State private var showEvent = false
    @State private var editing: MessageRow?
    @State private var editText = ""
    @State private var viewerItems: [MessageRow] = []
    @State private var viewerIndex = 0
    @State private var quickLookURL: URL?
    // VoiceRecorder 是 ObservableObject：必须用 StateObject 才会订阅 @Published seconds
    // （之前用 @State——引用类型不触发刷新，录音计时永远显示 0s）
    @StateObject private var voice = VoiceRecorder()
    @State private var recording = false
    @State private var kickedFrom: String?
    // 翻译模式：开=发中文自动译外语；关=直接发原文（「译」按钮切换）
    @AppStorage("translate_enabled") private var translateEnabled = true
    // 未配置任何接口时确认过「按原文发送」→ 本次会话不再重复弹窗
    @State private var rawSendConfirmed = false
    @State private var showNoProviderAlert = false

    var body: some View {
        ZStack {
            // 聊天壁纸垫底（铺满全屏含安全区；SwiftUI background 挂 ScrollView 会随内容裁切）
            ChatWallpaperView().ignoresSafeArea()
            VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(msgs) { m in
                            BubbleView(m: m, receipt: core.receiptStates[m.id] ?? "sent",
                                       chatMsgs: msgs,
                                       onOpenImage: { idx in viewerItems = mediaOf("image"); viewerIndex = idx },
                                       onOpenFile: { quickLookURL = $0 })
                                .id(m.id)
                                .contextMenu { contextMenu(m) }
                        }
                        }
                        .padding(.horizontal, 8).padding(.vertical, 10)
                }
                .onChange(of: msgs.count) { _ in
                    if let last = msgs.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
                .onChange(of: core.inboxTick) { _ in reload() }
            }
            if let q = pendingQuote {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(q.fromMe ? "你" : (title.isEmpty ? "对方" : title))
                            .font(.caption2.bold()).foregroundStyle(.teal)
                        Text(q.text.isEmpty ? "[媒体]" : q.text)
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button { pendingQuote = nil } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12).padding(.vertical, 4)
                .background(Color(uiColor: .secondarySystemBackground))
            }
            // 照搬 Android 输入区：灰胶囊(圆角23) + ＋28dp + 输入 16sp
            // + 发送/麦克风 48dp 白底 + 1dp 淡描边 + 深色图标
            HStack(spacing: 6) {
                Button { showAttach = true } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(.secondary)
                        .frame(width: 28, height: 28)
                }.disabled(recording)
                .accessibilityIdentifier("composer_attach")

                // 圆形「译」按钮：未配置接口=灰色不可用；配置后蓝色=翻译模式开，点击可关
                Button {
                    if hasAnyProvider {
                        translateEnabled.toggle()
                    }
                } label: {
                    Text("译")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(translateButtonColors.icon)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(translateButtonColors.bg))
                }
                .disabled(!hasAnyProvider)
                .accessibilityIdentifier("composer_translate_toggle")
                .disabled(recording)

                TextField(translateEnabled && hasAnyProvider ? "输入中文，发出自动翻译" : "输入消息原文直接发送",
                          text: $input, axis: .vertical)
                    .font(.system(size: 16))
                    .textFieldStyle(.plain)
                    .lineLimit(1...4)
                    .disabled(recording)

                if recording {
                    Text("●\(voice.seconds)s")
                        .font(.system(size: 13).monospaced()).foregroundStyle(.red)
                    roundAction(icon: "paperplane.fill", filled: true) {
                        voice.stop(send: true); recording = false
                    }
                    roundAction(icon: "xmark", filled: false) {
                        voice.stop(send: false); recording = false
                    }
                } else if input.trimmingCharacters(in: .whitespaces).isEmpty {
                    roundAction(icon: "mic.fill", filled: false) {
                        voice.finished = { url, sec in
                            core.sendMediaAsync(chatJid: chatJid, kind: "audio",
                                                localURL: url, ptt: true, seconds: Int64(sec)) { err in
                                sendError = err
                            }
                        }
                        voice.start()
                        recording = true
                    }
                } else {
                    roundAction(icon: "paperplane.fill", filled: false) { sendText() }
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(Capsule().fill(Color.dyn(0xF2F2F7, 0x202C33)))
            .padding(.horizontal, 8).padding(.vertical, 6)
            }
        }
        .alert("提示", isPresented: $showNoProviderAlert) {
            Button("确认，按原文发送") { rawSendConfirmed = true }
            Button("去配置接口", role: .cancel) { }
        } message: {
            Text("目前没有配置接口，将以原文的形式进行发送。")
        }
        .sheet(isPresented: $showLangPicker) { CustomerLanguageSheet(chatJid: chatJid) }
        .sheet(isPresented: $showSearch) { MessageSearchSheet(chatJid: chatJid) }
        // 通话浮层：来电全屏/主叫状态（之前只挂在列表页——会话页内不可见）
        .overlay {
            if callEngine.phase != .idle {
                CallOverlayView(client: core.client) { jid in
                    core.chats().first(where: { $0.jid == jid })?.title
                        ?? core.messages(chatJid: jid).last.map { _ in jid } ?? jid
                }
            }
        }
        .alert("通话", isPresented: .init(
            get: { callEngine.error != nil && callEngine.phase == .idle },
            set: { if !$0 { callEngine.clearError() } })) {
            Button("好", role: .cancel) {}
        } message: { Text(callEngine.error ?? "") }
        .navigationTitle(title.isEmpty ? chatJid : title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !chatJid.contains("@g.us") {
                    // 对齐 Android 头部：📞 语音 / 📹 视频（之前只有语音）
                    Button {
                        CallEngine.shared.wire(client: core.client)
                        CallEngine.shared.startCall(client: core.client, jid: chatJid, video: false)
                    } label: { Image(systemName: "phone") }
                    .accessibilityIdentifier("call_voice")
                    Button {
                        CallEngine.shared.wire(client: core.client)
                        CallEngine.shared.startCall(client: core.client, jid: chatJid, video: true)
                    } label: { Image(systemName: "video") }
                    .accessibilityIdentifier("call_video")
                    // 对齐 Android customerLanguageButton：会话级指定客户语言与引擎
                    // （不用 SF textformat 符号——中文系统会本地化成「甲乙丙」）
                    Button { showLangPicker = true } label: {
                        Text("文").font(.system(size: 16, weight: .semibold))
                    }
                    // 对齐 Android searchButton：会话内消息搜索
                    Button { showSearch = true } label: {
                        Image(systemName: "magnifyingglass")
                    }
                }
                if chatJid.contains("@g.us") {
                    NavigationLink { GroupInfoView(chatJid: chatJid, title: title) } label: {
                        Image(systemName: "info.circle")
                    }.accessibilityIdentifier("chat_info_group")
                } else {
                    NavigationLink { ContactInfoView(chatJid: chatJid, title: title) } label: {
                        Image(systemName: "info.circle")
                    }
                    .accessibilityIdentifier("chat_info")
                }
            }
        }
        .alert("发送失败", isPresented: .init(get: { sendError != nil }, set: { if !$0 { sendError = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(sendError ?? "") }
        // P8：被移出群提示（对齐参考 dialog_kicked）
        .alert("你已被移出群聊", isPresented: .init(get: { kickedFrom != nil }, set: { if !$0 { kickedFrom = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: { Text("群：\(kickedFrom ?? "")\n对方将不再收到你发送的消息") }
        // 编辑自己的文本
        .alert("编辑消息", isPresented: .init(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("新内容", text: $editText)
            Button("保存") {
                if let m = editing, !editText.trimmingCharacters(in: .whitespaces).isEmpty {
                    do {
                        _ = try core.client.editTextMessage(chatJid, msgID: m.id, newText: editText)
                        core.updateLocalEditText(chatJid: chatJid, id: m.id, text: editText)
                    } catch { sendError = error.localizedDescription }
                }
                editing = nil
            }
            Button("取消", role: .cancel) { editing = nil }
        }
        .sheet(isPresented: $showAttach) { attachSheet }
        .sheet(isPresented: $showPoll) { PollDialog(chatJid: chatJid, core: core) }
        .sheet(isPresented: $showLocation) { LocationDialog(chatJid: chatJid, core: core) }
        .sheet(isPresented: $showEvent) { EventDialog(chatJid: chatJid, core: core) }
        .sheet(item: $quickLookURL) { url in QuickLookView(url: url).ignoresSafeArea() }
        .sheet(isPresented: .init(get: { viewerItems.isEmpty == false },
                                  set: { if !$0 { viewerItems = [] } })) {
            ImageViewerView(items: viewerItems, index: viewerIndex)
        }
        .onAppear {
            reload()
            core.markRead(chatJid: chatJid)
            core.bindKicked { group in
                if group == chatJid { kickedFrom = group }
            }
            core.bindReadReceipts { chat, type, ids in
                if chat == chatJid { core.handleReadReceipts(chatJid: chat, type: type, ids: ids) }
            }
        }
    }

    /// 是否配置了任意可用翻译接口（内置密钥或自定义接口）
    private var hasAnyProvider: Bool {
        let c = core.core.credentials
        if !(c.deepLAPIKey ?? "").isEmpty || !(c.googleAPIKey ?? "").isEmpty
            || !(c.llmAPIKey ?? "").isEmpty { return true }
        return CustomProfile.loadAll().contains { !$0.key.isEmpty && !$0.baseUrl.isEmpty && !$0.model.isEmpty }
    }

    private var translateButtonColors: (icon: Color, bg: Color) {
        if !hasAnyProvider {
            return (Color.primary.opacity(0.3), Color.primary.opacity(0.08))   // 灰
        }
        return translateEnabled
            ? (.white, Color(hex: 0x0A7EA4))                                    // 蓝=翻译模式开
            : (.primary, Color.dyn(0xFFFFFF, 0x2A3942))                         // 白=翻译模式关
    }

    private func sendText() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        // 未配置接口 + 翻译模式开着 → 先弹一次确认，确认后本次会话直接发原文
        if translateEnabled && !hasAnyProvider && !rawSendConfirmed {
            showNoProviderAlert = true
            return
        }
        let asRaw = !translateEnabled || !hasAnyProvider
        input = ""
        if let q = pendingQuote {
            pendingQuote = nil
            core.sendReplyAsync(chatJid: chatJid, quote: q, body: text) { err in sendError = err }
        } else if asRaw {
            core.sendRawAsync(chatJid: chatJid, text: text) { err in sendError = err }
        } else {
            core.sendTextAsync(chatJid: chatJid, text: text) { err in sendError = err }
        }
    }

    private func mediaOf(_ kind: String) -> [MessageRow] {
        msgs.filter { $0.mediaKind == kind }
    }

    private var attachSheet: some View {
        AttachPicker(chatJid: chatJid, core: core,
                     onDone: { showAttach = false },
                     onError: { sendError = $0 })
    }

    @ViewBuilder private func contextMenu(_ m: MessageRow) -> some View {
        Button { pendingQuote = m } label: { Label("回复", systemImage: "arrowshape.turn.up.left") }
        if !m.text.isEmpty {
            Button { UIPasteboard.general.string = m.text } label: {
                Label("复制", systemImage: "doc.on.doc") }
        }
        if m.fromMe, m.mediaKind.isEmpty, !m.text.isEmpty {
            Button { editing = m; editText = m.text } label: {
                Label("编辑", systemImage: "pencil") }
            Button(role: .destructive) {
                do {
                    _ = try core.client.revokeMessage(chatJid, msgID: m.id)
                    core.markRevoked(chatJid: chatJid, id: m.id)
                } catch { sendError = error.localizedDescription }
            } label: { Label("撤回", systemImage: "arrow.uturn.backward") }
        }
        if !m.fromMe, !m.text.isEmpty {
            Button { core.retranslate(m) } label: {
                Label("重新翻译", systemImage: "arrow.2.squarepath") }
        }
        if ["image", "video", "document", "audio"].contains(m.mediaKind), !m.fromMe {
            Button {
                DispatchQueue.global(qos: .userInitiated).async {
                    _ = MediaStore.download(client: core.client, m: m)
                    Task { @MainActor in core.inboxTick &+= 1 }
                }
            } label: { Label("下载", systemImage: "arrow.down.circle") }
        }
        Button { core.forwardAsync(chatJid: chatJid, m: m) { err in sendError = err } } label: {
            Label("转发", systemImage: "arrowshape.turn.up.right")
        }
    }

    private func reload() {
        msgs = core.messages(chatJid: chatJid)
        if title.isEmpty {
            title = core.chats().first(where: { $0.jid == chatJid })?.title ?? chatJid
        }
    }
}

// MARK: - 客户语言（对齐 Android dialog_customer_language）
struct CustomerLanguageSheet: View {
    let chatJid: String
    @Environment(\.dismiss) private var dismiss
    @State private var sel: String = ""

    private static let options: [(String, String)] = [
        ("自动检测", ""), ("英语", "en"), ("西班牙语", "es"),
        ("葡萄牙语", "pt"), ("法语", "fr"), ("德语", "de")
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("目标语言（该客户说什么）") {
                    ForEach(Self.options, id: \.1) { opt in
                        row(opt.0, opt.1)
                    }
                }
                Section("翻译引擎") {
                    Text("跟随全局优先级").font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("客户语言")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
            }
            .onAppear { sel = UserDefaults.standard.string(forKey: "chat_lang_\(chatJid)") ?? "" }
        }
    }

    private func row(_ name: String, _ code: String) -> some View {
        Button {
            UserDefaults.standard.set(code, forKey: "chat_lang_\(chatJid)")
            sel = code
            dismiss()
        } label: {
            HStack {
                Text(name).foregroundStyle(.primary)
                Spacer()
                if sel == code {
                    Image(systemName: "checkmark").foregroundStyle(Color(hex: 0x25D366))
                }
            }
        }
    }
}

// MARK: - 会话内搜索（对齐 Android chatSearchBar）
struct MessageSearchSheet: View {
    let chatJid: String
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""

    private var hits: [MessageRow] {
        let all = ProtocolCore.shared.messages(chatJid: chatJid)
        guard !q.isEmpty else { return [] }
        let k = q.lowercased()
        return all.filter { $0.text.lowercased().contains(k) || $0.translated.lowercased().contains(k) }
    }

    var body: some View {
        NavigationStack {
            List(hits) { m in
                VStack(alignment: .leading, spacing: 3) {
                    Text(m.text).font(.system(size: 15)).lineLimit(2)
                    if !m.translated.isEmpty {
                        Text(m.translated).font(.system(size: 13))
                            .foregroundStyle(Color(hex: 0x0A7EA4)).lineLimit(2)
                    }
                }
            }
            .searchable(text: $q, prompt: "搜索消息")
            .navigationTitle("搜索消息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
            }
        }
    }
}

// MARK: - 附件面板 + 选择器（照片/文档）

struct AttachPicker: View {
    let chatJid: String
    @ObservedObject var core: ProtocolCore
    var onDone: () -> Void
    var onError: (String) -> Void
    @State private var photoItem: PhotosPickerItem?
    @State private var videoItem: PhotosPickerItem?
    @State private var stickerItem: PhotosPickerItem?
    @State private var showFiles = false

    var body: some View {
        List {
            PhotosPicker(selection: $photoItem, matching: .images) {
                Label("照片", systemImage: "photo")
            }
            PhotosPicker(selection: $videoItem, matching: .videos) {
                Label("视频", systemImage: "video")
            }
            PhotosPicker(selection: $stickerItem, matching: .images) {
                Label("贴纸（webp/png 图）", systemImage: "face.smiling")
            }
            Button {
                showFiles = true
            } label: { Label("文档", systemImage: "doc") }
            Button {
                onError("相机拍摄请用真机（模拟器无摄像头），可从相册选")
            } label: { Label("相机", systemImage: "camera") }
        }
        .navigationTitle("发送")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: photoItem) { item in
            guard let item else { return }
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                let tmp = MediaStore.dir.appendingPathComponent("photo-\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
                try? data.write(to: tmp)
                core.sendMediaAsync(chatJid: chatJid, kind: "image", localURL: tmp) { err in
                    if let err { onError(err) } else { onDone() }
                }
            }
        }
        .onChange(of: stickerItem) { item in
            guard let item else { return }
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                let tmp = MediaStore.dir.appendingPathComponent("sticker-\(Int(Date().timeIntervalSince1970 * 1000)).webp")
                try? data.write(to: tmp)
                // P9：贴纸发送（协议 SendStickerFromPath；whatsmeow StickerMessage）
                core.sendStickerAsync(chatJid: chatJid, localURL: tmp) { err in
                    if let err { onError(err) } else { onDone() }
                }
            }
        }
        .onChange(of: videoItem) { item in
            guard let item else { return }
            Task {
                // 视频：Data 直搬（CoreTransferable 无 MovieFileRepresentation；大文件后续换 FileRepresentation）
                guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                let dst = MediaStore.dir.appendingPathComponent(
                    "video-\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
                try? data.write(to: dst)
                core.sendMediaAsync(chatJid: chatJid, kind: "video", localURL: dst) { err in
                    if let err { onError(err) } else { onDone() }
                }
            }
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.data],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let src = urls.first {
                // 唯一名防覆盖（同名文档连发会 copy 失败 → 静默发旧文件）
                let tmp = MediaStore.dir.appendingPathComponent(
                    "\(Int(Date().timeIntervalSince1970 * 1000))-\(src.lastPathComponent)")
                try? FileManager.default.copyItem(at: src, to: tmp)
                core.sendMediaAsync(chatJid: chatJid, kind: "document", localURL: tmp) { err in
                    if let err { onError(err) } else { onDone() }
                }
            }
        }
    }
}

// MARK: - 投票 / 位置 / 活动弹层

struct PollDialog: View {
    let chatJid: String
    @ObservedObject var core: ProtocolCore
    @Environment(\.dismiss) private var dismiss
    @State private var question = ""
    @State private var options = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("问题，如 选哪个交期？", text: $question)
                TextField("选项，逗号分隔：下周三,下周五", text: $options)
            }
            .navigationTitle("发起投票")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("发送") {
                        let opts = options.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                            .filter { !$0.isEmpty }
                        guard !question.isEmpty, opts.count >= 2 else { return }
                        let optsCSV = opts.joined(separator: ",")
                        core.sendCardAsync(chatJid: chatJid, kind: "poll", mediaName: question,
                            meta: MediaMeta(options: opts), text: question) { e in
                            core.client.sendPoll(chatJid, question: question,
                                                 optionsCSV: optsCSV, error: &e)
                        }
                        dismiss()
                    }
                }
            }
        }
    }
}

struct LocationDialog: View {
    let chatJid: String
    @ObservedObject var core: ProtocolCore
    @Environment(\.dismiss) private var dismiss
    @State private var lat = ""
    @State private var lng = ""
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("地点名称（可选）", text: $name)
                TextField("纬度，如 19.4326", text: $lat)
                TextField("经度，如 -99.1332", text: $lng)
            }
            .navigationTitle("发送位置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("发送") {
                        guard let la = Double(lat), let lo = Double(lng) else { return }
                        core.sendCardAsync(chatJid: chatJid, kind: "location", mediaName: name,
                            meta: MediaMeta(lat: la, lng: lo, name: name, address: ""), text: name) { e in
                            core.client.sendLocation(chatJid, lat: la, lng: lo,
                                                     name: name, address: "", error: &e)
                        }
                        dismiss()
                    }
                }
            }
        }
    }
}

struct EventDialog: View {
    let chatJid: String
    @ObservedObject var core: ProtocolCore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var desc = ""
    @State private var start = Date().addingTimeInterval(3600)

    var body: some View {
        NavigationStack {
            Form {
                TextField("活动名称", text: $name)
                TextField("描述（可选）", text: $desc)
                DatePicker("开始时间", selection: $start)
            }
            .navigationTitle("活动邀请")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("发送") {
                        guard !name.isEmpty else { return }
                        let startMs = Int64(start.timeIntervalSince1970 * 1000)
                        core.sendCardAsync(chatJid: chatJid, kind: "event", mediaName: name,
                            meta: MediaMeta(name: name, desc: desc, start: startMs), text: name) { e in
                            core.client.sendEvent(chatJid, name: name, description: desc,
                                                  startMs: startMs, endMs: 0,
                                                  locationName: "", joinLink: "", error: &e)
                        }
                        dismiss()
                    }
                }
            }
        }
    }
}

/// 输入区圆钮：48dp，白底 + 1dp 淡描边 + 深色图标（对齐 Android input_circle_black）
private func roundAction(icon: String, filled: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Image(systemName: icon)
            .font(.system(size: 16))
            .foregroundStyle(filled ? .white : .primary)
            .frame(width: 48, height: 48)
            .background(Circle().fill(filled ? Color(hex: 0x25D366) : Color.dyn(0xFFFFFF, 0x2A3942)))
            .overlay(Circle().stroke(Color(hex: 0x14000000), lineWidth: 1))
    }
}

// MARK: - 聊天壁纸（WhatsApp 涂鸦壁纸，与安卓同款资源：wa_wallpaper_doodle[_dark].jpg）

struct ChatWallpaperView: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { geo in
            if let ui = Self.wallpaper(scheme) {
                Image(uiImage: ui)
                    .resizable()
                    .scaledToFill()
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
                    .ignoresSafeArea()
            } else {
                // 资源缺失兜底：暗色近黑 / 亮色白
                Color.dyn(0xFFFFFF, 0x0B141A).ignoresSafeArea()
            }
        }
    }

    static func wallpaper(_ scheme: ColorScheme) -> UIImage? {
        let name = scheme == .dark ? "wa_wallpaper_doodle_dark" : "wa_wallpaper_doodle"
        return UIImage(named: name)
    }
}

// MARK: - 气泡（阶段 2：媒体渲染 + 时间回执）

struct BubbleView: View {
    let m: MessageRow
    /// 回执三态：sent=单灰勾 / delivered=灰双勾 / read=蓝双勾（WhatsApp 语义）
    var receipt: String = "sent"
    @State private var localURL: URL?
    @State private var playing = false
    @State private var player: AVAudioPlayer?
    // P7：链接预览（og 元数据）
    @State private var linkPreview: LinkPreview?
    @State private var thumb: UIImage?
    // 字号/颜色设置统一走 ProtocolCore 的 AppSettings（翻译服务页 applySettings 后即时生效）
    private var settings: AppSettings { ProtocolCore.shared.core.settings }
    let chatMsgs: [MessageRow]
    var onOpenImage: (Int) -> Void
    var onOpenFile: (URL) -> Void

    var body: some View {
        HStack(alignment: .bottom) {
            if m.fromMe { Spacer(minLength: 46) }
            if isSystemLine {
                systemLine
            } else if isCallRecord {
                callRecordBubble
            } else if isMediaBubble {
                mediaBubble
            } else if isDocument {
                documentBubble
            } else if isAudio {
                audioBubble
            } else {
                textBubble
            }
            if !m.fromMe { Spacer(minLength: 46) }
        }
        .task(id: m.id) {
            // 缩略图只读一次盘（原来每次渲染都同步读文件，气泡多时明显卡顿）
            if thumb == nil {
                thumb = await Task.detached(priority: .utility) {
                    MediaStore.thumbnail(m)
                }.value
            }
            // P7：链接预览（有 URL、无媒体、未抓过 → 后台抓 og）
            if m.mediaKind.isEmpty, !m.fromMe, let u = LinkPreviewFetcher.firstURL(m.text) {
                if let cached = ProtocolCore.linkPreviewCache[u] {
                    linkPreview = cached
                } else {
                    let fetched = await Task.detached(priority: .utility) {
                        LinkPreviewFetcher.fetch(u)
                    }.value
                    if let fetched {
                        ProtocolCore.linkPreviewCache[u] = fetched
                        linkPreview = fetched
                    }
                }
            }
            guard ["image", "video", "document", "audio"].contains(m.mediaKind), !m.fromMe else { return }
            // 网络下载不能占主线程：后台下完回主线程刷 UI
            let client = ProtocolCore.shared.client
            let msg = m
            let downloaded = await Task.detached(priority: .utility) {
                MediaStore.download(client: client, m: msg)
            }.value
            if downloaded != nil { localURL = downloaded }
        }
    }

    private var isCardKind: Bool {
        ["poll", "location", "contact", "event", "product"].contains(m.mediaKind)
    }
    /// 撤回/系统行：居中灰字（对齐参考「撤回了一条消息」）
    private var isSystemLine: Bool {
        m.mediaKind.isEmpty && m.text.contains("撤回了一条消息")
    }

    private var systemLine: some View {
        HStack(spacing: 5) {
            if m.fromMe { Spacer(minLength: 0) }
            Image(systemName: "nosign").font(.caption2)
            Text(m.text).font(.caption)
            if !m.fromMe { Spacer(minLength: 0) }
        }
        .foregroundStyle(.secondary)
        .padding(.vertical, 3)
        .frame(maxWidth: .infinity, alignment: m.fromMe ? .trailing : .leading)
        .padding(.horizontal, 12)
    }

    private var mediaBubble: some View {
        VStack(alignment: m.fromMe ? .trailing : .leading, spacing: 3) {
            media
                .overlay(alignment: .bottomTrailing) { timeBadge }
            if !m.text.isEmpty {
                Text(m.text)
                    .font(.system(size: settings.sourceTextSp))
                    .foregroundStyle(.primary)
            }
        }
        .frame(maxWidth: 290, alignment: m.fromMe ? .trailing : .leading)
    }

    private var textBubble: some View {
        textBubbleContent
            .padding(.horizontal, 9).padding(.vertical, 6)
            // 照搬 WaSpec：BUBBLE_SENT #E0FCD6 / BUBBLE_RECEIVED 白 / 圆角 10 / 最大宽 280dp
            .frame(maxWidth: 290, alignment: .leading)
            .background(m.fromMe ? Color.dyn(0xE0FCD6, 0x005C4B)
                        : Color.dyn(0xFFFFFF, 0x1F2C33))
            .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    /// 气泡内引用块（对齐参考：teal 左条 + 引用者 + 引文，深色半透明底）
    private var quoteBlock: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(m.fromMe ? "你" : "对方")
                .font(.caption2.bold()).foregroundStyle(Color(hex: 0x00A884))
            Text(quoteText)
                .font(.caption).foregroundStyle(Color.primary.opacity(0.7))
                .lineLimit(3)
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.14))
        .overlay(alignment: .leading) {
            Rectangle().fill(Color(hex: 0x00A884)).frame(width: 3)
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private var quoteText: String {
        guard let d = m.mediaMeta.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let t = o["replyText"] as? String, !t.isEmpty else { return "" }
        return t
    }

    private var textBubbleContent: some View {
        VStack(alignment: .leading, spacing: 3) {
            if isCardKind {
                // 投票/位置/联系人/事件/产品卡：卡内容 + 右下时间小行（卡本身有宽度，不会撑满）
                media
                trailingRow
            } else {
                if !quoteText.isEmpty {
                    quoteBlock
                }
                if let lp = linkPreview {
                    LinkPreviewCard(lp: lp)
                }
                textLinesWithInlineTime
            }
        }
    }

    /// 时间+回执 Text 段（挂到最后一行行尾；对齐 WhatsApp：时间永远跟最后一行）
    private var timeTickText: Text {
        var t = Text("  " + Self.time(m.ts))
            .font(.system(size: 10)).foregroundColor(Color.primary.opacity(0.45))
        if m.fromMe {
            let color = receipt == "read" ? Color(hex: 0x34B7F1) : Color.primary.opacity(0.4)
            t = t + Text(Image(systemName: "checkmark"))
                .font(.system(size: 9, weight: .bold)).foregroundColor(color)
            if receipt != "sent" {
                t = t + Text(Image(systemName: "checkmark"))
                    .font(.system(size: 9, weight: .bold)).foregroundColor(color)
            }
        }
        return t
    }

    /// 全部文本行；时间+勾内联在最后一行（无 Spacer，气泡严格贴内容）
    @ViewBuilder private var textLinesWithInlineTime: some View {
        let body = Text(m.text)
            .font(.system(size: settings.sourceTextSp)).foregroundColor(.primary)
        if m.fromMe {
            if !m.text.isEmpty { body }
            if !m.original.isEmpty, m.original != m.text {
                // 原文行是最后一行 → 时间挂这
                (Text(m.original).font(.caption).foregroundColor(Color.primary.opacity(0.55))
                 + timeTickText)
            } else if !m.text.isEmpty {
                body + timeTickText
            }
        } else {
            if !m.text.isEmpty { body }
            if !m.translated.isEmpty {
                // 译文行是最后一行 → 时间挂这
                (Text(m.translated)
                  .font(.system(size: settings.translationTextSp, weight: .medium))
                  .foregroundColor(Color(hex: UInt32(settings.translationColorHex)))
                 + timeTickText)
            } else if !m.text.isEmpty {
                body + timeTickText
            }
        }
    }

    /// 卡片类消息的时间行（不撑宽：卡是固定宽度子视图，VStack 宽度由卡决定）
    private var trailingRow: some View {
        HStack(spacing: 3) {
            Text(Self.time(m.ts)).font(.system(size: 10))
                .foregroundStyle(Color.primary.opacity(0.45))
            if m.fromMe {
                Image(systemName: "checkmark").font(.system(size: 9, weight: .bold))
                if receipt != "sent" {
                    Image(systemName: "checkmark").font(.system(size: 9, weight: .bold))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .foregroundStyle(receipt == "read" ? Color(hex: 0x34B7F1) : Color.primary.opacity(0.4))
    }
    /// 图片/视频/贴纸：无气泡底
    private var isMediaBubble: Bool {
        ["image", "video", "sticker"].contains(m.mediaKind)
    }
    private var isDocument: Bool { m.mediaKind == "document" }
    private var isAudio: Bool { m.mediaKind == "audio" }
    private var isCallRecord: Bool { m.mediaKind == "call" }

    /// 通话记录气泡（对齐 WhatsApp：图标+通话类型+时长/未接，未接图标红）
    private var callRecordBubble: some View {
        HStack(alignment: .bottom, spacing: 8) {
            Group {
                if let d = m.mediaMeta.data(using: .utf8),
                   let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                   let kind = o["kind"] as? String, kind == "video" {
                    Image(systemName: "video.fill")
                } else {
                    Image(systemName: "phone.fill")
                }
            }
            .font(.system(size: 17))
            .foregroundStyle(m.text.contains("未接") ? Color(hex: 0xE53935) : Color(hex: 0x25D366))
            VStack(alignment: .leading, spacing: 2) {
                Text(m.text).font(.callout).foregroundStyle(.primary)
            }
            timeRowNoSpacer
        }
        .padding(.horizontal, 9).padding(.vertical, 8)
        .frame(maxWidth: 290, alignment: .leading)
        .background(m.fromMe ? Color.dyn(0xE0FCD6, 0x005C4B)
                    : Color.dyn(0xFFFFFF, 0x1F2C33))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    /// 语音条：气泡内播放行 + 时间（audio 不渲染在 textBubble 分支里的回归修复）
    private var audioBubble: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .bottom, spacing: 6) {
                media
                timeRowNoSpacer
            }
        }
        .padding(.horizontal, 9).padding(.vertical, 8)
        .frame(maxWidth: 290, alignment: .leading)
        .background(m.fromMe ? Color.dyn(0xE0FCD6, 0x005C4B)
                    : Color.dyn(0xFFFFFF, 0x1F2C33))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    /// 无 Spacer 的时间小行（audio/document 卡内右下；宽度由主内容决定）
    private var timeRowNoSpacer: some View {
        HStack(spacing: 3) {
            Text(Self.time(m.ts)).font(.system(size: 10))
                .foregroundStyle(Color.primary.opacity(0.45))
            if m.fromMe {
                Image(systemName: "checkmark").font(.system(size: 9, weight: .bold))
                if receipt != "sent" {
                    Image(systemName: "checkmark").font(.system(size: 9, weight: .bold))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .foregroundStyle(receipt == "read" ? Color(hex: 0x34B7F1) : Color.primary.opacity(0.4))
    }

    /// 文档消息（对齐参考）：中性灰气泡（双方向同色）装文件卡；
    /// 说明文字在卡外下方直接贴壁纸，收件时间随说明行、发件时间+回执在卡内右下
    private var documentBubble: some View {
        VStack(alignment: m.fromMe ? .trailing : .leading, spacing: 3) {
            VStack(alignment: .leading, spacing: 3) {
                media
                    .foregroundStyle(m.mediaMime.contains("pdf") ? Color(hex: 0xE53935) : .orange)
                if m.fromMe { trailingRow }
            }
            .padding(.horizontal, 9).padding(.vertical, 8)
            .frame(maxWidth: 290, alignment: .leading)
            .background(Color.dyn(0xFFFFFF, 0x1F2C33))
            .clipShape(RoundedRectangle(cornerRadius: 16))

            if !m.text.isEmpty {
                // 说明文字在卡外（对齐参考「I need something with stones」）
                Text(m.text)
                    .font(.system(size: settings.sourceTextSp))
                    .foregroundStyle(.primary)
            }
            if !m.fromMe {
                Text(Self.time(m.ts)).font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: 290, alignment: m.fromMe ? .trailing : .leading)
    }
    /// 叠在媒体图右下角的时间徽章（黑 45% 胶囊白字）
    private var timeBadge: some View {
        Text(Self.time(m.ts))
            .font(.system(size: 10))
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Capsule().fill(Color.black.opacity(0.45)))
            .padding(5)
    }
    private var mediaKindNeedsStroke: Bool {
        ["image", "video", "document"].contains(m.mediaKind)
    }

    @ViewBuilder private var media: some View {
        switch m.mediaKind {
        case "image", "sticker":
            Group {
                if let url = localURL ?? (m.fromMe ? URL(string: m.mediaPath).map({ URL(fileURLWithPath: $0.path) }) : nil),
                   let ui = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: ui).resizable().scaledToFill()
                        .frame(maxWidth: 220, maxHeight: 220).clipped()
                } else if let th = MediaStore.thumbnail(m) {
                    Image(uiImage: th).resizable().scaledToFill()
                        .frame(maxWidth: 220, maxHeight: 220).clipped()
                } else {
                    RoundedRectangle(cornerRadius: 8).fill(Color(uiColor: .tertiarySystemFill))
                        .frame(width: 200, height: 120)
                        .overlay(ProgressView())
                }
            }
            .cornerRadius(8)
            .onTapGesture { openViewer() }
        case "video":
            ZStack {
                if let th = MediaStore.thumbnail(m) {
                    Image(uiImage: th).resizable().scaledToFill()
                        .frame(width: 220, height: 140).clipped()
                } else {
                    Rectangle().fill(Color.black.opacity(0.6)).frame(width: 220, height: 140)
                }
                Image(systemName: "play.circle.fill").font(.system(size: 40)).foregroundStyle(.white)
            }
            .cornerRadius(8)
            .onTapGesture {
                if let url = localURL ?? mediaLocalFromMe { onOpenFile(url) }
            }
        case "audio":
            HStack(spacing: 8) {
                Button {
                    togglePlay()
                } label: {
                    Image(systemName: playing ? "pause.circle.fill" : "play.circle.fill")
                        .font(.title).foregroundStyle(.teal)
                }
                // 波形（收件有 waveform 字段；发件本地录制无，画等高占位）
                HStack(alignment: .center, spacing: 2) {
                    let wave = MediaStore.waveform(m)
                    ForEach(0..<max(wave.count, 18), id: \.self) { i in
                        RoundedRectangle(cornerRadius: 1)
                            .fill(Color.teal.opacity(0.6))
                            .frame(width: 2, height: CGFloat(4 + (wave.isEmpty ? 6 : wave[i % wave.count] * 20)))
                    }
                }
                Text(Self.duration(m)).font(.caption2.monospaced()).foregroundStyle(.secondary)
                    .fixedSize()
            }
            .onDisappear { player?.stop(); playing = false }
        case "document":
            Button {
                if let url = localURL ?? mediaLocalFromMe { onOpenFile(url) }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "doc.fill").font(.title2).foregroundStyle(.orange)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.mediaName.isEmpty ? "文档" : m.mediaName)
                            .font(.callout).foregroundStyle(.primary).lineLimit(1)
                        if m.mediaSize > 0 {
                            Text(ByteCountFormatter.string(fromByteCount: m.mediaSize, countStyle: .file))
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        case "poll":
            VStack(alignment: .leading, spacing: 6) {
                Label(m.mediaName.isEmpty ? "投票" : m.mediaName, systemImage: "chart.bar")
                    .font(.callout.bold())
                if let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
                   let opts = meta.options {
                    ForEach(opts, id: \.self) { o in
                        Text("◯  " + o).font(.callout)
                    }
                    Text("投票 · 点按选项").font(.caption2).foregroundStyle(.secondary)
                }
            }
        case "event":
            VStack(alignment: .leading, spacing: 4) {
                Label("📅  " + (m.mediaName.isEmpty ? m.text : m.mediaName), systemImage: "")
                    .font(.callout.bold())
                if let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)) {
                    if let s = meta.start, s > 0 {
                        Text("🕒 " + Self.fullTime(s)).font(.caption).foregroundStyle(.teal)
                    }
                    if let d = meta.desc, !d.isEmpty {
                        Text(d).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        case "location":
            VStack(alignment: .leading, spacing: 4) {
                Label("📍  " + (m.mediaName.isEmpty ? "位置" : m.mediaName), systemImage: "")
                    .font(.callout.bold())
                if let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
                   let addr = meta.address, !addr.isEmpty {
                    Text(addr).font(.caption).foregroundStyle(.secondary)
                }
            }
        case "contact":
            Label("👤  " + (m.mediaName.isEmpty ? "联系人" : m.mediaName), systemImage: "")
                .font(.callout.bold())
        case "product":
            VStack(alignment: .leading, spacing: 4) {
                Label("🏪  " + (m.mediaName.isEmpty ? "商品" : m.mediaName), systemImage: "")
                    .font(.callout.bold())
                if let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
                   let p = meta.price {
                    Text("\(meta.currency ?? "$")\(String(format: "%.2f", p))")
                        .font(.callout.bold()).foregroundStyle(.teal)
                }
            }
        default:
            EmptyView()
        }
    }

    private var mediaLocalFromMe: URL? {
        m.fromMe && !m.mediaPath.isEmpty ? URL(fileURLWithPath: m.mediaPath) : nil
    }

    private func openViewer() {
        let images = chatMsgs.filter { $0.mediaKind == "image" }
        if let idx = images.firstIndex(where: { $0.id == m.id }) {
            onOpenImage(idx)
        }
    }

    private func togglePlay() {
        if playing {
            player?.pause(); playing = false; return
        }
        guard let url = localURL ?? mediaLocalFromMe else { return }
        if player == nil {
            player = try? AVAudioPlayer(contentsOf: url)
            player?.play()
        } else {
            player?.play()
        }
        playing = true
    }

    static func time(_ ms: Int64) -> String {
        let df = DateFormatter(); df.dateFormat = "HH:mm"
        return df.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }

    static func duration(_ m: MessageRow) -> String {
        guard let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
              let s = meta.seconds, s > 0 else { return "" }
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    static func fullTime(_ ms: Int64) -> String {
        let df = DateFormatter()
        df.dateFormat = "M月d日 HH:mm"
        return df.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

// MARK: - 联系人信息（只读）

/// 联络人资料（对齐 WATransChat contact_info：号码行/本地备注/客户备注/媒体分区）
struct ContactInfoView: View {
    let chatJid: String
    let title: String
    @ObservedObject var core = ProtocolCore.shared
    @State private var avatar: UIImage?
    @State private var blocked = false
    @State private var blockError: String?

    // 备注双字段（参考图）：本地备注=称呼（displayName）/ 客户备注=成交意向·偏好·跟进（notes）
    // 均存本机 CustomerProfile（matchKey=jid），与安卓同构
    private let profileStore = UserDefaultsProfileStore()
    @State private var alias = ""
    @State private var notes = ""
    @State private var savedToast = false

    @State private var mediaTab = 0
    private let tabs = ["文件", "图片与视频", "链接", "音乐与音频"]

    /// +手机号（jid 去后缀）
    private var phoneDisplay: String {
        let num = chatJid
            .replacingOccurrences(of: "@s.whatsapp.net", with: "")
            .replacingOccurrences(of: "@lid", with: "")
        return num.hasPrefix("+") ? num : "+" + num
    }

    private var messages: [MessageRow] { core.messages(chatJid: chatJid) }
    private var docMsgs: [MessageRow] { messages.filter { $0.mediaKind == "document" } }
    private var mediaMsgs: [MessageRow] { messages.filter { ["image", "video"].contains($0.mediaKind) } }
    private var audioMsgs: [MessageRow] { messages.filter { $0.mediaKind == "audio" } }
    private var linkURLs: [URL] {
        var seen = Set<String>()
        return messages.compactMap { m in
            guard !m.text.isEmpty, let raw = LinkPreviewFetcher.firstURL(m.text),
                  seen.insert(raw).inserted, let u = URL(string: raw) else { return nil }
            return u
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                // 头部：头像 + 名称 + 号码
                VStack(spacing: 8) {
                    if let avatar {
                        Image(uiImage: avatar).resizable().scaledToFill()
                            .frame(width: 108, height: 108).clipShape(Circle())
                    } else {
                        ZStack {
                            Circle().fill(Color(hex: 0x0A7EA4).opacity(0.15))
                            Text(String((title.isEmpty ? chatJid : title).first ?? "#"))
                                .font(.largeTitle).foregroundStyle(Color(hex: 0x0A7EA4))
                        }
                        .frame(width: 108, height: 108)
                    }
                    Text(title.isEmpty ? chatJid : title)
                        .font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                    Text(phoneDisplay).font(.callout).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))

                // 本地备注（仅保存在 WeFlip）= 称呼
                VStack(alignment: .leading, spacing: 8) {
                    Text("本地备注（仅保存在 WeFlip）").font(.footnote).foregroundStyle(.secondary)
                    TextField("给这位客户起个好认的名字", text: $alias)
                        .textFieldStyle(.roundedBorder)
                    Button {
                        saveProfile()
                    } label: {
                        Text(savedToast ? "已保存" : "保存备注")
                            .frame(maxWidth: .infinity, minHeight: 42)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))

                // 客户备注（仅保存在本机）= 成交意向/偏好/跟进
                VStack(alignment: .leading, spacing: 8) {
                    Text("客户备注（仅保存在本机）").font(.footnote).foregroundStyle(.secondary)
                    TextEditor(text: $notes)
                        .frame(minHeight: 88)
                        .font(.system(size: 14))
                        .scrollContentBackground(.hidden)
                        .background(Color(uiColor: .tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(alignment: .topLeading) {
                            if notes.isEmpty {
                                Text("点此添加成交意向、商品偏好、跟进计划…")
                                    .font(.system(size: 14)).foregroundStyle(.tertiary)
                                    .padding(.horizontal, 6).padding(.vertical, 8)
                                    .allowsHitTesting(false)
                            }
                        }
                    .onSubmit { saveProfile() }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))

                // 媒体、连接和文件
                VStack(alignment: .leading, spacing: 10) {
                    Text("媒体、连接和文件").font(.footnote).foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Array(tabs.enumerated()), id: \.offset) { i, name in
                                Button { mediaTab = i } label: {
                                    Text(name).font(.footnote)
                                        .padding(.horizontal, 12).padding(.vertical, 6)
                                        .background(Capsule().fill(mediaTab == i ? Color(hex: 0x0A7EA4) : Color(uiColor: .tertiarySystemBackground)))
                                        .foregroundStyle(mediaTab == i ? .white : .primary)
                                }
                            }
                        }
                    }
                    mediaSection
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))

                // 操作：拉黑（保留，参考图红色操作行）
                VStack(alignment: .leading, spacing: 8) {
                    Text("操作").font(.footnote).foregroundStyle(.secondary)
                    Button {
                        if let e = BlocklistHelper.setBlocked(client: core.client, jid: chatJid, blocked: !blocked) {
                            blockError = e
                        } else {
                            blocked.toggle()
                        }
                    } label: {
                        Text(blocked ? "解除拉黑" : "拉黑此联系人")
                            .frame(maxWidth: .infinity, minHeight: 42)
                    }
                    .buttonStyle(.bordered)
                    .foregroundStyle(.red)
                    Text(blocked ? "已拉黑：对方消息将不再进入本机" : "拉黑后对方的消息不再进入本机")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .padding(12)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("联络人资料")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if let b = try? core.client.getProfilePicture(chatJid) { avatar = UIImage(data: Data(b)) }
            blocked = BlocklistHelper.isBlocked(client: core.client, jid: chatJid)
            let p = profileStore.profile(matching: chatJid)
            if let p {
                // 称呼默认回显 JID（与参考图一致），客户改过则显示改过的
                alias = p.displayName == chatJid || p.displayName.isEmpty ? chatJid : p.displayName
                notes = p.notes
            } else {
                alias = chatJid
            }
        }
        .alert("拉黑失败", isPresented: .init(get: { blockError != nil }, set: { if !$0 { blockError = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(blockError ?? "") }
    }

    private func saveProfile() {
        var p = profileStore.profile(matching: chatJid)
            ?? CustomerProfile(matchKey: chatJid, displayName: chatJid)
        p.displayName = alias.trimmingCharacters(in: .whitespaces).isEmpty ? chatJid : alias.trimmingCharacters(in: .whitespaces)
        p.notes = notes
        p.updatedAt = Date()
        profileStore.upsert(p)
        savedToast = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { savedToast = false }
    }

    @ViewBuilder private var mediaSection: some View {
        switch mediaTab {
        case 0:
            if docMsgs.isEmpty { emptyHint } else {
                VStack(spacing: 8) {
                    ForEach(docMsgs) { m in
                        HStack(spacing: 10) {
                            Image(systemName: "doc.fill")
                                .foregroundStyle(m.mediaMime.contains("pdf") ? Color(hex: 0xE53935) : .orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(m.mediaName.isEmpty ? "文档" : m.mediaName)
                                    .font(.callout).lineLimit(1)
                                Text(Self.docDate(m.ts)).font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if m.mediaSize > 0 {
                                Text(ByteCountFormatter.string(fromByteCount: m.mediaSize, countStyle: .file))
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        case 1:
            if mediaMsgs.isEmpty { emptyHint } else {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                    ForEach(mediaMsgs) { m in
                        Group {
                            if let th = MediaStore.thumbnail(m) {
                                Image(uiImage: th).resizable().scaledToFill()
                            } else if m.fromMe, !m.mediaPath.isEmpty,
                                      let local = MediaStore.localFile(for: m.id, mime: m.mediaMime) {
                                Image(uiImage: UIImage(contentsOfFile: local.path) ?? UIImage()).resizable().scaledToFill()
                            } else {
                                Rectangle().fill(Color(uiColor: .tertiarySystemFill))
                                    .overlay(ProgressView())
                            }
                        }
                        .frame(height: 104).clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
        case 2:
            if linkURLs.isEmpty { emptyHint } else {
                VStack(spacing: 8) {
                    ForEach(linkURLs) { u in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(u.absoluteString).font(.callout).foregroundStyle(Color(hex: 0x0A7EA4))
                                .lineLimit(1)
                            if let host = u.host { Text(host).font(.caption2).foregroundStyle(.secondary) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        default:
            if audioMsgs.isEmpty { emptyHint } else {
                VStack(spacing: 8) {
                    ForEach(audioMsgs) { m in
                        HStack(spacing: 10) {
                            Image(systemName: "waveform").foregroundStyle(Color(hex: 0x0A7EA4))
                            Text("语音消息").font(.callout)
                            Spacer()
                            Text(BubbleView.duration(m)).font(.caption2.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private var emptyHint: some View {
        Text("暂无内容").font(.caption).foregroundStyle(.tertiary)
            .frame(maxWidth: .infinity).padding(.vertical, 10)
    }

    static func docDate(_ ms: Int64) -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm"
        return df.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

// MARK: - P7 链接预览卡片（对齐参考 view_link_preview：图 120dp + 标题 + 描述 + 域名）

struct LinkPreviewCard: View {
    let lp: LinkPreview

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let imgStr = lp.imageURL, let url = URL(string: imgStr) {
                AsyncImage(url: url) { phase in
                    if let img = phase.image {
                        img.resizable().scaledToFill().frame(height: 110).clipped()
                    } else {
                        Rectangle().fill(Color.teal.opacity(0.12)).frame(height: 110)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(lp.title).font(.callout.weight(.medium)).lineLimit(2)
                if !lp.description.isEmpty {
                    Text(lp.description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                if let host = URL(string: lp.url)?.host {
                    Text(host).font(.caption2).foregroundStyle(.teal)
                }
            }
            .padding(8)
        }
        .background(Color.teal.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - 工具

extension URL: Identifiable {
    public var id: String { absoluteString }
}

extension Color {
    /// 动态明暗色（对齐 Android 昼夜主题）：light=昼，dark=夜（WhatsApp 同款暗板）
    static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { trait in
            let hex = trait.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255,
                           green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }

    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}
