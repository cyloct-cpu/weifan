// 微翻 iOS —— 聊天栈页面（阶段 1 最小闭环）
//
// 四页：语言选择（首启）→ 配对页 → 会话列表 → 会话页；联系人信息只读。
// 布局规格对齐 Android 端与参考（docs/17 阶段 1 验收）。

import SwiftUI
import CoreImage.CIFilterBuiltins
import AVFoundation
import PhotosUI

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
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink { ChatSettingsView() } label: { Image(systemName: "gearshape") }
                }
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

// MARK: - 会话页（阶段 2：媒体收发 + 长按菜单 + 语音 + 查看器）

struct ChatView: View {
    let chatJid: String
    @ObservedObject var core = ProtocolCore.shared
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
    @State private var voice = VoiceRecorder()
    @State private var recording = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(msgs) { m in
                            BubbleView(m: m, read: core.readReceipts[m.id] == true,
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
            HStack(spacing: 8) {
                Button { showAttach = true } label: { Image(systemName: "plus.circle.fill") }
                    .font(.title2).foregroundStyle(.teal)
                    .disabled(recording)
                TextField("输入中文，发出自动翻译", text: $input, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                    .disabled(recording)
                // U3 对应：空输入尾部是麦克风，有字切发送（Android 同构；iOS 版为点按开/关录音）
                if recording {
                    Text("●\(voice.seconds)s").font(.callout.monospaced()).foregroundStyle(.red)
                    Button("发送") { voice.stop(send: true); recording = false }.buttonStyle(.borderedProminent)
                    Button("取消", role: .cancel) { voice.stop(send: false); recording = false }
                } else if input.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button {
                        voice.finished = { url, sec in
                            core.sendMediaAsync(chatJid: chatJid, kind: "audio",
                                                localURL: url, ptt: true, seconds: Int64(sec)) { err in
                                sendError = err
                            }
                        }
                        voice.start()
                        recording = true
                    } label: { Image(systemName: "mic.fill").frame(width: 40, height: 40) }
                        .buttonStyle(.borderedProminent)
                } else {
                    Button {
                        sendText()
                    } label: {
                        Image(systemName: "paperplane.fill").frame(width: 40, height: 40)
                    }
                    .buttonStyle(.borderedProminent)
                }
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
            core.bindReadReceipts { chat, type, ids in
                if chat == chatJid { core.handleReadReceipts(chatJid: chat, type: type, ids: ids) }
            }
        }
    }

    private func sendText() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        input = ""
        if let q = pendingQuote {
            pendingQuote = nil
            core.sendReplyAsync(chatJid: chatJid, quote: q, body: text) { err in sendError = err }
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
        Button { core.sendForwardAsync(chatJid: chatJid, m: m) { err in sendError = err } } label: {
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

// MARK: - 附件面板 + 选择器（照片/文档）

struct AttachPicker: View {
    let chatJid: String
    @ObservedObject var core: ProtocolCore
    var onDone: () -> Void
    var onError: (String) -> Void
    @State private var photoItem: PhotosPickerItem?
    @State private var showFiles = false

    var body: some View {
        List {
            PhotosPicker(selection: $photoItem, matching: .images) {
                Label("照片或视频", systemImage: "photo.on.rectangle")
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

// MARK: - 气泡（阶段 2：媒体渲染 + 时间回执）

struct BubbleView: View {
    let m: MessageRow
    let read: Bool
    let chatMsgs: [MessageRow]
    var onOpenImage: (Int) -> Void
    var onOpenFile: (URL) -> Void

    @State private var localURL: URL?
    @State private var playing = false
    @State private var player: AVAudioPlayer?

    var body: some View {
        HStack {
            if m.fromMe { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 3) {
                media
                if !m.text.isEmpty, !isCardKind {
                    Text(m.text).foregroundStyle(.primary)
                }
                if !m.fromMe, !m.translated.isEmpty {
                    Text(m.translated)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color(hex: 0x0A7EA4))
                }
                if m.fromMe, !m.original.isEmpty, m.original != m.text {
                    Text(m.original).font(.caption).foregroundStyle(.secondary)
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
            .overlay(RoundedRectangle(cornerRadius: 10)
                .stroke(mediaKindNeedsStroke ? Color.primary.opacity(0.08) : .clear, lineWidth: 1))
            if !m.fromMe { Spacer(minLength: 48) }
        }
        .task(id: m.id) {
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

extension URL: Identifiable {
    public var id: String { absoluteString }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}
