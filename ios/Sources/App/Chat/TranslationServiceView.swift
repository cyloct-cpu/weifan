// 微翻 iOS —— 翻译服务设置页（逐段对齐 Android 翻译服务页，docs/20 §7）
//
// Android 页面结构（MainActivity 翻译服务）：
//   说明行 → ● 翻译服务（网格+开关+自动优选+添加接口）→ 接口延时
//   → ● 译文显示与 AI 提示词（提示词/颜色/原文字号/译文字号/历史消息翻译接口）
//   → ● 发件 → ● 额度保护 → ● 外观：昼夜切换 → ● 术语 → 保存 → ● 试译
//
// 设置存取走 WeiFanCore AppSettings（新增字段带 decodeIfPresent，旧数据安全），
// 与 Android 同名键语义一致：prompt_tpl/trans_color/src_sp/trans_sp/history_engine/theme_mode。
// 网格开关落 disabledProviders（TranslationCore.makeProvider 路由时跳过，已接入）。

import SwiftUI
import UIKit
import WeiFanCore

// MARK: - 服务商卡片数据（与 Android 网格一一对应）

/// 单个密钥字段（对齐 Android Field(id/label/hint/secret/optional)）
struct ProviderField: Identifiable {
    let id: String
    let label: String
    let hint: String
    var secret: Bool = false
    var optional: Bool = false
}

struct ProviderCard: Identifiable {
    let id: String
    let name: String
    let desc: String
    let implemented: Bool
    /// 密钥字段规格（与 Android Providers.DEFS 逐字一致）
    var fields: [ProviderField] = []
    /// deepl/google 走 Keychain（WeiFanCore 引擎在用）；其余先落 UserDefaults cred_*
    var usesKeychain: Bool = false
    /// 是否有密钥录入界面
    var hasKeyEntry: Bool { usesKeychain }
}

// MARK: - 自定义接口档案（与 Android customs_json 同构）

struct CustomProfile: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var key: String
    var baseUrl: String
    var model: String

    static func loadAll() -> [CustomProfile] {
        guard let raw = UserDefaults.standard.string(forKey: "customs_json"),
              let data = raw.data(using: .utf8),
              let arr = try? JSONDecoder().decode([CustomProfile].self, from: data) else { return [] }
        return arr
    }

    static func saveAll(_ profiles: [CustomProfile]) {
        if let data = try? JSONEncoder().encode(profiles) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8) ?? "[]", forKey: "customs_json")
        }
    }

    static func byId(_ id: String) -> CustomProfile? {
        loadAll().first { $0.id == id }
    }
}

// MARK: - 自定义 OpenAI 兼容引擎（与 Android customRun 同构）

enum CustomLLMEngine {
    static func translate(text: String, target: String, profile: CustomProfile,
                          prompt: String?) async throws -> String {
        let base = profile.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: base + "/chat/completions") else {
            throw NSError(domain: "weifan.llm", code: 0,
                          userInfo: [NSLocalizedDescriptionKey: "Base URL 无效"])
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 30
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer " + profile.key, forHTTPHeaderField: "Authorization")
        let sys = prompt?.isEmpty == false
            ? prompt!
            : "你是专业的跨境贸易翻译引擎。把用户输入翻译成\(target)。只输出译文，不要解释。"
        let body: [String: Any] = [
            "model": profile.model,
            "messages": [["role": "system", "content": sys],
                         ["role": "user", "content": text]],
            "temperature": 0.3
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "weifan.llm", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "接口返回 \((resp as? HTTPURLResponse)?.statusCode ?? -1)"])
        }
        let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let choices = o?["choices"] as? [[String: Any]],
              let msg = choices.first?["message"] as? [String: Any],
              let content = msg["content"] as? String else {
            throw NSError(domain: "weifan.llm", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "响应缺少 choices"])
        }
        return content.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// 渠道凭据存取：键名与 Android 完全一致（cred_<providerId>_<fieldId>），
/// 引擎下一轮接入后直接读同一份；deepl/google 例外走 Keychain（WeiFanCore 在用）。
enum ProviderCredStore {
    static func value(_ provider: String, _ field: String) -> String {
        UserDefaults.standard.string(forKey: "cred_\(provider)_\(field)") ?? ""
    }
    static func set(_ provider: String, _ field: String, _ v: String) {
        UserDefaults.standard.set(v, forKey: "cred_\(provider)_\(field)")
    }
    static func isConfigured(_ card: ProviderCard) -> Bool {
        card.fields.filter { !$0.optional }.allSatisfy { !value(card.id, $0.id).isEmpty }
    }
}

// MARK: - 页面

struct TranslationServiceView: View {
    @ObservedObject var core = ProtocolCore.shared

    // 调试预览：PREVIEW_TRANSLATION_ALL 直接展开下半段（模拟器截图对比用）
    @State private var expanded: Set<String> =
        ProcessInfo.processInfo.arguments.contains("PREVIEW_TRANSLATION_ALL")
        ? ["发件", "额度", "外观", "术语", "试译"]
        : (ProcessInfo.processInfo.arguments.contains("PREVIEW_TRANSLATION_DISPLAY")
           ? ["显示"] : ["服务", "显示"])

    @State private var customs: [CustomProfile] = []
    @State private var editingNew = false
    @State private var editingProfile: CustomProfile?

    @State private var prompt = ""
    @State private var showColor = false
    @State private var showHistoryPicker = false
    @State private var showTemplates = false

    @State private var glossaryText = ""

    @State private var testText = ""
    @State private var testResult = ""
    @State private var testing = false

    @State private var latencyText = "未测试"
    @State private var latencyTesting = false

    // 渠道密钥录入（deepl/google）：点未配置卡片的开关时弹出
    @State private var keyDialog: ProviderCard?

    private let settingsStore = UserDefaultsSettingsStore()

    // 字段规格逐字对齐 Android Providers.DEFS（id/label/hint/secret/optional）
    private let providers: [ProviderCard] = [
        ProviderCard(id: "deepl", name: "DeepL API Free",
                     desc: "每月 50 万字符免费（免费版 key 以 :fx 结尾）", implemented: true,
                     fields: [ProviderField(id: "key", label: "API Key", hint: "xxxxxxxx-xxxx-…:fx", secret: true)],
                     usesKeychain: true),
        ProviderCard(id: "google", name: "Google Cloud Translation",
                     desc: "每月 50 万字符免费额度", implemented: true,
                     fields: [ProviderField(id: "key", label: "API Key", hint: "AIza…", secret: true)],
                     usesKeychain: true),
        ProviderCard(id: "azure", name: "Azure 微软翻译",
                     desc: "F0 每月 200 万字符免费", implemented: false,
                     fields: [ProviderField(id: "key", label: "订阅密钥", hint: "32 位十六进制", secret: true),
                              ProviderField(id: "region", label: "区域（选填）", hint: "eastasia", optional: true)]),
        ProviderCard(id: "baidu", name: "百度翻译开放平台",
                     desc: "通用版 5 万/月（高级版 100 万）", implemented: false,
                     fields: [ProviderField(id: "appid", label: "App ID", hint: "2024……"),
                              ProviderField(id: "secret", label: "Secret Key", hint: "……", secret: true)]),
        ProviderCard(id: "tencent", name: "腾讯云机器翻译 TMT",
                     desc: "个人实名后每月约 500 万字符", implemented: false,
                     fields: [ProviderField(id: "secretId", label: "SecretId", hint: "AKID……"),
                              ProviderField(id: "secretKey", label: "SecretKey", hint: "……", secret: true),
                              ProviderField(id: "region", label: "地域（选填）", hint: "ap-guangzhou", optional: true)]),
        ProviderCard(id: "volcano", name: "火山引擎翻译",
                     desc: "新用户约 100 万字符免费额度", implemented: false,
                     fields: [ProviderField(id: "accessKeyId", label: "AccessKey ID", hint: "AKLT……"),
                              ProviderField(id: "secretKey", label: "Secret Key", hint: "……", secret: true)]),
        ProviderCard(id: "aliyun", name: "阿里云翻译",
                     desc: "个人版每月 100 万字符免费", implemented: false,
                     fields: [ProviderField(id: "accessKeyId", label: "AccessKey ID", hint: "LTAI……"),
                              ProviderField(id: "accessKeySecret", label: "AccessKey Secret", hint: "……", secret: true)]),
        ProviderCard(id: "volcanollm", name: "火山引擎大模型翻译",
                     desc: "大模型翻译，32 种语言", implemented: false,
                     fields: [ProviderField(id: "key", label: "API Key", hint: "火山控制台获取的 API Key", secret: true)]),
        ProviderCard(id: "yandex", name: "Yandex Translate",
                     desc: "按字符计费（新号有试用金）", implemented: false,
                     fields: [ProviderField(id: "key", label: "API Key", hint: "AQVN…", secret: true),
                              ProviderField(id: "folderId", label: "Folder ID", hint: "b1g……")]),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("自己去大厂申请密钥填进来，额度用完自动换下一家。自定义 API 接任何 OpenAI 兼容接口。")
                    .font(.footnote).foregroundStyle(.secondary)

                providerSection
                latencyRow
                displaySection
                sendSection
                quotaSection
                glossarySection
                saveButton
                testSection
            }
            .padding(14)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("翻译服务")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: loadAll)
        .onReceive(NotificationCenter.default.publisher(for: .weifanCredsChanged)) { _ in
            // cred_* 落 UserDefaults 后刷新网格状态（configured 为计算属性，需要一次渲染失效）
            settings = settingsStore.load()
        }
        .sheet(isPresented: $editingNew) {
            CustomProfileEditor(profile: CustomProfile(id: UUID().uuidString, name: "", key: "",
                                                       baseUrl: "", model: "")) { saved in
                customs.append(saved)
                CustomProfile.saveAll(customs)
                editingNew = false
            }
        }
        .sheet(item: $editingProfile) { p in
            CustomProfileEditor(profile: p) { saved in
                if let i = customs.firstIndex(where: { $0.id == p.id }) {
                    customs[i] = saved
                } else {
                    customs.append(saved)
                }
                CustomProfile.saveAll(customs)
                editingProfile = nil
            }
        }
        .sheet(isPresented: $showColor) { colorPickerSheet }
        .sheet(isPresented: $showHistoryPicker) {
            HistoryEnginePicker(customs: customs, selection: $settings.historyEngine,
                                onPick: { _ in persist() })
        }
        .sheet(isPresented: $showTemplates) {
            PromptTemplatePicker { tpl in
                prompt = tpl
                persist()
                showTemplates = false
            }
        }
        .sheet(item: $keyDialog) { card in
            if card.usesKeychain {
                ProviderKeyDialog(card: card) {
                    creds = core.core.credentials   // 保存后刷新 configured 状态
                }
            } else {
                ProviderFieldsDialog(card: card)
            }
        }
    }

    // MARK: 段头（● 圆点 + 可折叠，对齐安卓段头样式）

    private func sectionHeader(_ title: String, _ key: String) -> some View {
        Button {
            withAnimation { toggleExpanded(key) }
        } label: {
            HStack(spacing: 6) {
                Circle().fill(Color(hex: 0x0A7EA4)).frame(width: 8, height: 8)
                Text(title).font(.callout.bold()).foregroundStyle(.primary)
                Spacer()
                Image(systemName: expanded.contains(key) ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private func toggleExpanded(_ key: String) {
        if expanded.contains(key) { expanded.remove(key) } else { expanded.insert(key) }
    }

    // MARK: ● 翻译服务

    private var providerSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("翻译服务", "服务")
            if expanded.contains("服务") {
                Text("翻译服务（网格顺序 = 优先级：左→右、上→下）")
                    .font(.footnote).foregroundStyle(.secondary)

                // 三档服务选择（对齐 Android refreshServiceSpinner）：即时生效
                Menu {
                    Button {
                        settings.providerSelection = "auto"
                        persist()
                    } label: {
                        Label("自动（按语种优选）",
                              systemImage: settings.providerSelection == "auto" ? "checkmark" : "")
                    }
                    Button {
                        settings.providerSelection = "priority"
                        persist()
                    } label: {
                        Label("按我的排序（网格拖动）",
                              systemImage: settings.providerSelection == "priority" ? "checkmark" : "")
                    }
                    ForEach(channels.filter { $0.configured && ($0.card?.usesKeychain == true || $0.custom != nil) }) { ch in
                        Button {
                            settings.providerSelection = ch.id
                            persist()
                        } label: {
                            Label("\(ch.label)（只用这家）",
                                  systemImage: settings.providerSelection == ch.id ? "checkmark" : "")
                        }
                    }
                } label: {
                    HStack {
                        Text(selectionLabel).font(.callout)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(12).frame(maxWidth: .infinity)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }

                Text("长按卡片拖到另一颗上 = 插到它前面；开关关掉 = 停用")
                    .font(.caption2).foregroundStyle(.secondary)

                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                    ForEach(channels) { ch in
                        channelCard(ch)
                            .onDrag {
                                dragging = ch.id
                                return NSItemProvider(object: ch.id as NSString)
                            }
                    }
                    // 放置目标：网格末尾（拖到「添加接口」上 = 挪到最后）
                    Button { editingNew = true } label: {
                        VStack(spacing: 6) {
                            Image(systemName: "plus").font(.title3)
                            Text("添加接口").font(.caption)
                        }
                        .frame(maxWidth: .infinity, minHeight: 74)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .onDrop(of: [.text], delegate: ChannelDropDelegate(view: self, targetId: "__end__"))
                }
                .onDrop(of: [.text], delegate: ChannelDropDelegate(view: self, targetId: nil))
            }
        }
    }

    // MARK: 渠道模型（对齐 Android Channel：free + custom 混排，顺序 = 优先级）

    struct Channel: Identifiable {
        let id: String
        let label: String
        let note: String
        let configured: Bool
        let card: ProviderCard?
        let custom: CustomProfile?
    }

    /// 凭据快照（网格 configured 状态用；Keychain 保存后 refreshCreds 刷新）
    @State private var creds = ProviderCredentials()
    @State private var dragging: String?

    private var channels: [Channel] {
        var free: [Channel] = providers.map { card in
            Channel(id: card.id, label: card.name, note: card.desc,
                    configured: isConfigured(card), card: card, custom: nil)
        }
        free.append(contentsOf: customs.map { c in
            let complete = !c.key.isEmpty && !c.baseUrl.isEmpty && !c.model.isEmpty
            return Channel(id: "custom:\(c.id)", label: c.name.isEmpty ? c.model : c.name,
                           note: complete ? c.baseUrl : "点开填写 key + 接口地址",
                           configured: complete, card: nil, custom: c)
        })
        let byId = Dictionary(uniqueKeysWithValues: free.map { ($0.id, $0) })
        var order = settings.providerOrder.filter { byId[$0] != nil }
        for ch in free where !order.contains(ch.id) { order.append(ch.id) }
        return order.compactMap { byId[$0] }
    }

    private var selectionLabel: String {
        switch settings.providerSelection {
        case "auto": return "自动（按语种优选）"
        case "priority": return "按我的排序（网格拖动）"
        default:
            return channels.first { $0.id == settings.providerSelection }?.label
                .appending("（只用这家）") ?? "自动（按语种优选）"
        }
    }

    private func isConfigured(_ card: ProviderCard) -> Bool {
        if card.usesKeychain {
            switch card.id {
            case "deepl": return !(creds.deepLAPIKey ?? "").isEmpty
            case "google": return !(creds.googleAPIKey ?? "").isEmpty
            default: return false
            }
        }
        return ProviderCredStore.isConfigured(card)
    }

    /// 单颗渠道卡（对齐 Android channelCapsule：开关=启用，点卡=配密钥，长按=拖）
    private func channelCard(_ ch: Channel) -> some View {
        let off = settings.disabledProviders.contains(ch.id)
        let active = ch.configured && !off
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(ch.label).font(.callout.bold()).lineLimit(1)
                Spacer()
                if ch.configured {
                    Toggle("", isOn: Binding(
                        get: { !off },
                        set: { on in setProvider(ch.id, on: on) }))
                        .labelsHidden()
                } else {
                    // 没配密钥：开关默认关，点开关/点卡片都去填密钥
                    Toggle("", isOn: .constant(false))
                        .labelsHidden()
                        .onTapGesture { if ch.card != nil { keyDialog = ch.card } }
                }
            }
            Text(ch.note).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            if let card = ch.card {
                // 九家统一状态提示：已就绪=青色，待办=橙色
                let ready = card.implemented && ch.configured
                let status = card.implemented
                    ? (ch.configured ? "已就绪 · 参与自动优选" : "未配置 · 点开填密钥")
                    : (ch.configured ? "密钥已就绪 · 引擎接入后自动生效" : "Swift 引擎下一轮接入")
                Text(status).font(.caption2).foregroundStyle(ready ? Color.teal : .orange)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12)
            .stroke(active ? Color(hex: 0x0A7EA4) : .clear, lineWidth: 1.5))
        .opacity(off ? 0.45 : 1)
        .onTapGesture {
            if let c = ch.custom { editingProfile = c }
            else if let card = ch.card {
                // 已配且是 Keychain 引擎 → 不用再配；其余一律可点开填/改密钥
                if card.hasKeyEntry && ch.configured { return }
                keyDialog = card
            }
        }
        .contextMenu {
            if ch.custom != nil {
                Button("编辑") { editingProfile = ch.custom }
                Button("删除", role: .destructive) {
                    customs.removeAll { $0.id == ch.custom?.id }
                    CustomProfile.saveAll(customs)
                }
            }
        }
        .onDrop(of: [.text], delegate: ChannelDropDelegate(view: self, targetId: ch.id))
    }

    /// 拖动排序：松手落到目标卡上 = 插到它前面（对齐 Android ACTION_DROP）
    struct ChannelDropDelegate: DropDelegate {
        let view: TranslationServiceView
        let targetId: String?   // nil = 整网格兜底（不重排）

        func dropEntered(info: DropInfo) {
            guard let targetId, let dragging = view.dragging, dragging != targetId else { return }
            var order = view.channels.map { $0.id }
            guard let from = order.firstIndex(of: dragging),
                  let to = order.firstIndex(of: targetId) else { return }
            order.remove(at: from)
            order.insert(dragging, at: to)
            view.settings.providerOrder = order
            view.persist()
        }

        func performDrop(info: DropInfo) -> Bool {
            view.dragging = nil
            return true
        }

        func dropUpdated(info: DropInfo) -> DropProposal? {
            DropProposal(operation: .move)
        }
    }

    // MARK: 接口延时

    private var latencyRow: some View {
        Button {
            measureLatency()
        } label: {
            HStack {
                Text("接口延时（填好密钥后可测，选最快的一家）").font(.footnote)
                Spacer()
                if latencyTesting { ProgressView().scaleEffect(0.7) }
                else { Text(latencyText).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .buttonStyle(.plain)
    }

    private func measureLatency() {
        latencyTesting = true
        DispatchQueue.global(qos: .utility).async {
            var parts: [String] = []
            let sem = DispatchSemaphore(value: 0)
            let group = DispatchGroup()
            let lock = NSLock()
            func probe(_ name: String, _ host: String?) {
                guard let host, !host.isEmpty else { return }
                group.enter()
                DispatchQueue.global().async {
                    let r = LatencyTester.measure(name, host)
                    lock.lock(); parts.append(r); lock.unlock()
                    group.leave()
                }
            }
            probe("deepl", "api-free.deepl.com")
            let llmBase = core.core.credentials.llmBaseURL
            probe("llm", llmBase.host)
            group.wait()
            DispatchQueue.main.async {
                latencyText = parts.filter { !$0.isEmpty }.sorted().joined(separator: " · ")
                latencyTesting = false
            }
        }
    }

    // MARK: ● 译文显示与 AI 提示词

    private var displaySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("译文显示与 AI 提示词", "显示")
            if expanded.contains("显示") {
                Text("自定义大模型的翻译风格，以及译文在聊天里的颜色与原文字号。")
                    .font(.footnote).foregroundStyle(.secondary)

                Text("AI 翻译提示词（仅影响大模型类接口）")
                    .font(.footnote).foregroundStyle(.secondary)
                TextEditor(text: $prompt)
                    .frame(minHeight: 150)
                    .font(.system(size: 13))
                    .scrollContentBackground(.hidden)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                HStack(spacing: 10) {
                    Button {
                        persist()
                    } label: {
                        Text("保存")
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("默认") { prompt = ""; persist() }
                        .buttonStyle(.bordered)
                    Button("模板") { showTemplates = true }
                        .buttonStyle(.bordered)
                }
                Text("占位：{TO} = 目标语言，{TONE} = 语气")
                    .font(.caption2).foregroundStyle(.secondary)

                Button { showColor = true } label: {
                    HStack {
                        Text("译文颜色（聊天里译文那一行的字色）").font(.callout)
                        Spacer()
                        Circle().fill(Color(hex: UInt32(settings.translationColorHex))).frame(width: 22, height: 22)
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)

                HStack {
                    Text("原文字号").font(.callout)
                    Spacer()
                    Stepper("\(Int(settings.sourceTextSp)) sp", value: sizeBinding(\.sourceTextSp), in: 10...24, step: 1)
                        .fixedSize()
                }
                HStack {
                    Text("译文字号").font(.callout)
                    Spacer()
                    Stepper("\(Int(settings.translationTextSp)) sp", value: sizeBinding(\.translationTextSp), in: 10...24, step: 1)
                        .fixedSize()
                }

                Button { showHistoryPicker = true } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("历史消息翻译接口").font(.callout)
                            Text("仅用于聊天窗口内历史消息的自动翻译，不影响输入框翻译。")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(settings.historyEngine.isEmpty ? "跟随全局"
                             : (CustomProfile.byId(settings.historyEngine)?.name ?? "已指定"))
                            .font(.caption).foregroundStyle(.secondary)
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var colorPickerSheet: some View {
        NavigationStack {
            Form {
                ColorPicker("译文颜色", selection: colorBinding, supportsOpacity: false)
                Button("恢复默认青蓝") {
                    settings.translationColorHex = 0x0A7EA4
                    persist()
                }
            }
            .navigationTitle("译文颜色")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        persist()
                        showColor = false
                    }
                }
            }
        }
    }

    // MARK: ● 发件 / 额度 / 外观 / 术语

    private var sendSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("发件：打完中文怎么出去", "发件")
            if expanded.contains("发件") {
                Text("触发词命中后自动翻译并原地替换，不发送。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("iOS 客户端形态：输入框右侧的「开/关」即翻译开关——开着时点发送自动译成对方语言；关掉则原样发送。")
                    .font(.footnote)
            }
        }
    }

    private var quotaSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("额度保护", "额度")
            if expanded.contains("额度") {
                Text("免费额度有上限，超了平台可能会开始计费。没设每日上限，到线当天自动换下一家。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("iOS 端按字符计数的服务商额度保护与安卓同构，随引擎接入逐家生效。")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private var glossarySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("术语：不要乱译的词", "术语")
            if expanded.contains("术语") {
                Text("一行一个，命中后原样保留。支持「原词=固定译法」。")
                    .font(.footnote).foregroundStyle(.secondary)
                TextEditor(text: $glossaryText)
                    .frame(minHeight: 110)
                    .font(.system(size: 13))
                    .scrollContentBackground(.hidden)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                Text("例：\n轻舟 Pro\n定金=deposit")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private var saveButton: some View {
        Button {
            persist()
        } label: {
            Text("保存")
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
    }

    // MARK: ● 试译

    private var testSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("试译：不需要 WhatsApp", "试译")
            if expanded.contains("试译") {
                Text("验证密钥与网络是否可用。")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("输入要试译的文本", text: $testText)
                    .textFieldStyle(.roundedBorder)
                Button {
                    testing = true
                    Task {
                        defer { testing = false }
                        testResult = await runTestTranslate()
                    }
                } label: {
                    HStack {
                        Text("试译")
                        if testing { ProgressView().scaleEffect(0.7) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 38)
                }
                .buttonStyle(.bordered)
                .disabled(testText.isEmpty || testing)
                if !testResult.isEmpty {
                    Text(testResult).font(.callout)
                        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }

    // MARK: 设置存取（AppSettings，AppGroup suite 与聊天翻译路径同源）

    @State private var settings = AppSettings()

    private func loadAll() {
        settings = settingsStore.load()
        prompt = settings.promptTemplate
        glossaryText = glossaryLines(settings.glossary)
        customs = CustomProfile.loadAll()
        creds = core.core.credentials
    }

    private func persist() {
        settings.promptTemplate = prompt
        settings.glossary = parseGlossary(glossaryText)
        settingsStore.save(settings)
        core.applySettings(settings)
    }

    private func setProvider(_ id: String, on: Bool) {
        var off = settings.disabledProviders
        if on { off.remove(id) } else { off.insert(id) }
        settings.disabledProviders = off
        persist()
    }

    private func sizeBinding(_ kp: WritableKeyPath<AppSettings, Double>) -> Binding<Double> {
        Binding(get: { settings[keyPath: kp] },
                set: { settings[keyPath: kp] = $0; persist() })
    }

    private var colorBinding: Binding<Color> {
        Binding(
            get: { Color(hex: UInt32(settings.translationColorHex)) },
            set: { new in
                let ui = UIColor(new)
                var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
                ui.getRed(&r, green: &g, blue: &b, alpha: &a)
                settings.translationColorHex = (Int(r * 255) << 16) | (Int(g * 255) << 8) | Int(b * 255)
                persist()
            })
    }

    private func glossaryLines(_ entries: [GlossaryEntry]) -> String {
        entries.map { e in
            if let fixed = e.translations["zh-Hans"] ?? e.translations["en"] {
                return "\(e.term)=\(fixed)"
            }
            return e.term
        }.joined(separator: "\n")
    }

    private func parseGlossary(_ text: String) -> [GlossaryEntry] {
        text.split(separator: "\n").map { line in
            let parts = line.split(separator: "=", maxSplits: 1)
            let term = parts[0].trimmingCharacters(in: .whitespaces)
            if parts.count > 1 {
                let fixed = parts[1].trimmingCharacters(in: .whitespaces)
                return GlossaryEntry(term: term, policy: .fixed,
                                     translations: ["zh-Hans": fixed])
            }
            return GlossaryEntry(term: term)
        }.filter { !$0.term.isEmpty }
    }

    private func runTestTranslate() async -> String {
        let text = testText
        if !settings.historyEngine.isEmpty,
           let prof = CustomProfile.byId(settings.historyEngine.replacingOccurrences(of: "custom:", with: "")) {
            do {
                return try await CustomLLMEngine.translate(text: text, target: "中文", profile: prof,
                                                           prompt: settings.promptTemplate.isEmpty ? nil : settings.promptTemplate)
            } catch {
                return "失败：\(error.localizedDescription)"
            }
        }
        do {
            let out = try await core.core.translateIncoming(text)
            return out.alreadyChinese ? "原文已是中文，无需翻译" : out.translated
        } catch {
            return "失败：\(error.localizedDescription)"
        }
    }
}


// MARK: - 提示词模板（对齐 Android showPromptTemplates：四选一）

struct PromptTemplatePicker: View {
    var onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    private static let businessPrompt = [
        "你是专业的中外互译引擎，服务于跨境贸易沟通。把用户输入翻译成{TO}。",
        "要求：",
        "1. 只输出译文，不要解释、不要引号、不要加任何前后缀。",
        "2. 语气：正式商务语气，简洁专业，不使用缩略语和表情。",
        "3. 文本中的 ⟦数字⟧ 是占位符，必须原样保留在译文中的对应位置，不得翻译、不得改写、不得移动。",
        "4. 金额、数量、日期、型号必须逐字对应，不许换算、不许四舍五入。",
        "5. 人名、品牌名、产品型号保留原文，不要音译。",
        "6. 如果原文已是目标语言，原样返回。",
    ].joined(separator: "\n")

    private static let literalPrompt = [
        "你是专业的中外互译引擎，服务于跨境贸易沟通。把用户输入翻译成{TO}。",
        "要求：",
        "1. 只输出译文，不要解释、不要引号、不要加任何前后缀。",
        "2. 语气：忠实直译，保留原文结构与数字格式，不润色。",
        "3. 文本中的 ⟦数字⟧ 是占位符，必须原样保留在译文中的对应位置，不得翻译、不得改写、不得移动。",
        "4. 金额、数量、日期、型号必须逐字对应，不许换算、不许四舍五入。",
        "5. 人名、品牌名、产品型号保留原文，不要音译。",
        "6. 如果原文已是目标语言，原样返回。",
    ].joined(separator: "\n")

    private static let techPrompt = [
        "你是技术文档翻译引擎。把输入翻译成{TO}。",
        "要求：",
        "1. 型号、参数、单位、公差、材质代码逐字保留，不翻译不换算。",
        "2. 表格与列表结构原样输出，行数与顺序不变。",
        "3. 只输出译文，不加解释。",
    ].joined(separator: "\n")

    private static let casualPrompt =
        "你是外贸聊天翻译。把输入翻译成{TO}。{TONE}。短句为主，不加礼貌后缀，不补充原文没有的信息。只输出译文。"

    private let templates: [(String, String)] = [
        ("跨境询盘回复（默认风格）", businessPrompt),
        ("技术规格 / 参数表", techPrompt),
        ("口语短句（节拍聊天）", casualPrompt),
        ("严格直译（合同报价）", literalPrompt),
    ]

    var body: some View {
        NavigationStack {
            List {
                ForEach(templates, id: \.0) { name, text in
                    Button {
                        onPick(text)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(name).font(.callout.bold()).foregroundStyle(.primary)
                            Text(text.replacingOccurrences(of: "\n", with: " "))
                                .font(.caption2).foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                }
            }
            .navigationTitle("选择提示词模板")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            }
        }
    }
}

// MARK: - 渠道密钥录入（对齐 Android openProviderDialog：deepL/google 直连 Keychain）

struct ProviderKeyDialog: View {
    let card: ProviderCard
    var onSaved: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var keyValue = ""
    @State private var endpoint = "https://api-free.deepl.com/v2/translate"
    @State private var saved = false

    var body: some View {
        NavigationStack {
            Form {
                Section(card.name + " 密钥") {
                    SecureField(card.id == "deepl" ? "xxxxxxxx-xxxx-…:fx" : "AIza…", text: $keyValue)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    if card.id == "deepl" {
                        TextField("接口地址", text: $endpoint)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                }
                Section {
                    Text("密钥保存在 Keychain，不写入任何会提交的文件。免费额度：" + card.desc)
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("配置密钥")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saved ? "已保存" : "保存") {
                        var c = Keychain.credentials()
                        let v = keyValue.trimmingCharacters(in: .whitespaces)
                        if card.id == "deepl" {
                            c.deepLAPIKey = v.isEmpty ? nil : v
                            if let u = URL(string: endpoint.trimmingCharacters(in: .whitespaces)) { c.deepLEndpoint = u }
                        } else if card.id == "google" {
                            c.googleAPIKey = v.isEmpty ? nil : v
                        }
                        Keychain.save(c)
                        saved = true
                        onSaved()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { dismiss() }
                    }
                    .disabled(keyValue.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                let c = Keychain.credentials()
                if card.id == "deepl" {
                    keyValue = c.deepLAPIKey ?? ""
                    endpoint = c.deepLEndpoint.absoluteString
                } else if card.id == "google" {
                    keyValue = c.googleAPIKey ?? ""
                }
            }
        }
    }
}


// MARK: - 通用渠道密钥录入（七家占位引擎；字段规格对齐 Android，键名 cred_<id>_<field>）

struct ProviderFieldsDialog: View {
    let card: ProviderCard
    @Environment(\.dismiss) private var dismiss

    @State private var values: [String: String] = [:]
    @State private var saved = false

    var body: some View {
        NavigationStack {
            Form {
                Section(card.name + " 密钥") {
                    ForEach(card.fields) { f in
                        if f.secret {
                            SecureField(f.hint, text: binding(f))
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        } else {
                            TextField("\(f.label)（\(f.hint)）", text: binding(f))
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        }
                    }
                }
                Section {
                    Text("密钥保存在本机（键名与安卓版一致），翻译引擎接入后自动生效，不用重新填。免费额度：" + card.desc)
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("配置密钥")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saved ? "已保存" : "保存") {
                        for f in card.fields {
                            ProviderCredStore.set(card.id, f.id,
                                                  (values[f.id] ?? "").trimmingCharacters(in: .whitespaces))
                        }
                        saved = true
                        NotificationCenter.default.post(name: .weifanCredsChanged, object: nil)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { dismiss() }
                    }
                    .disabled(!card.fields.filter { !$0.optional }.allSatisfy { !(values[$0.id] ?? "").isEmpty })
                }
            }
            .onAppear {
                for f in card.fields { values[f.id] = ProviderCredStore.value(card.id, f.id) }
            }
        }
    }

    private func binding(_ f: ProviderField) -> Binding<String> {
        Binding(get: { values[f.id] ?? "" },
                set: { values[f.id] = $0 })
    }
}

extension Notification.Name {
    static let weifanCredsChanged = Notification.Name("weifan.creds.changed")
}

// MARK: - 自定义接口编辑器

struct CustomProfileEditor: View {
    @State var profile: CustomProfile
    var onSave: (CustomProfile) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("名称（如 Agnes）", text: $profile.name)
                SecureField("API Key", text: $profile.key)
                TextField("Base URL（https://apihub.agnes-ai.com/v1）", text: $profile.baseUrl)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField("模型名（如 agnes-3.0-flash）", text: $profile.model)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            .navigationTitle("自定义接口")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        var p = profile
                        if p.name.isEmpty { p.name = p.model }
                        onSave(p)
                        dismiss()
                    }
                    .disabled(profile.key.isEmpty || profile.baseUrl.isEmpty || profile.model.isEmpty)
                }
            }
        }
    }
}

// MARK: - 历史引擎选择

struct HistoryEnginePicker: View {
    let customs: [CustomProfile]
    @Binding var selection: String
    var onPick: ((String) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button {
                    selection = ""
                    onPick?("")
                    dismiss()
                } label: {
                    HStack {
                        Text("跟随全局")
                        Spacer()
                        if selection.isEmpty { Image(systemName: "checkmark").foregroundStyle(.teal) }
                    }
                }
                ForEach(customs) { p in
                    Button {
                        selection = "custom:\(p.id)"
                        onPick?("custom:\(p.id)")
                        dismiss()
                    } label: {
                        HStack {
                            Text(p.name)
                            Spacer()
                            if selection == "custom:\(p.id)" {
                                Image(systemName: "checkmark").foregroundStyle(.teal)
                            }
                        }
                    }
                }
            }
            .navigationTitle("历史消息翻译接口")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - 接口延时探测

enum LatencyTester {
    static func measure(_ name: String, _ host: String) -> String {
        let t0 = Date()
        for _ in 0..<2 {
            if let err = tcpPing(host) { return "\(name) 失败(\(err))" }
        }
        let ms = Int(Date().timeIntervalSince(t0) * 1000 / 2)
        return "\(name) \(ms)ms"
    }

    static func tcpPing(_ host: String) -> String? {
        var err: String?
        let sem = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: URL(string: "https://\(host)")!) { _, resp, e in
            if let e { err = e.localizedDescription.prefix(12).description }
            sem.signal()
        }
        task.resume()
        _ = sem.wait(timeout: .now() + 6)
        if Date().timeIntervalSince1970 > 0 && task.state == .running { task.cancel() }
        return err
    }
}
