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

struct ProviderCard: Identifiable {
    let id: String
    let name: String
    let desc: String
    let implemented: Bool
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

    @State private var themeMode = "system"

    @State private var glossaryText = ""

    @State private var testText = ""
    @State private var testResult = ""
    @State private var testing = false

    @State private var latencyText = "未测试"
    @State private var latencyTesting = false

    private let settingsStore = UserDefaultsSettingsStore()

    private let providers: [ProviderCard] = [
        ProviderCard(id: "deepl", name: "DeepL API Free",
                     desc: "每月 50 万字符免费（免费版 key 以 :fx 结尾）", implemented: true),
        ProviderCard(id: "google", name: "Google Cloud Translation",
                     desc: "每月 50 万字符免费额度", implemented: true),
        ProviderCard(id: "azure", name: "Azure 微软翻译",
                     desc: "F0 每月 200 万字符免费", implemented: false),
        ProviderCard(id: "baidu", name: "百度翻译开放平台",
                     desc: "通用版 5 万/月（高级版 100 万）", implemented: false),
        ProviderCard(id: "tencent", name: "腾讯云机器翻译 TMT",
                     desc: "个人实名后每月约 500 万字符", implemented: false),
        ProviderCard(id: "volcano", name: "火山引擎翻译",
                     desc: "新用户约 100 万字符免费额度", implemented: false),
        ProviderCard(id: "aliyun", name: "阿里云翻译",
                     desc: "个人版每月 100 万字符免费", implemented: false),
        ProviderCard(id: "volcanollm", name: "火山引擎大模型翻译",
                     desc: "大模型翻译，32 种语言", implemented: false),
        ProviderCard(id: "yandex", name: "Yandex Translate",
                     desc: "按字符计费（新号有试用金）", implemented: false),
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
                appearanceSection
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

                Button {
                    settings.disabledProviders = []
                    persist()
                } label: {
                    HStack {
                        Text("自动（按语种优选）").font(.callout)
                        Spacer()
                        if settings.disabledProviders.isEmpty {
                            Image(systemName: "checkmark").font(.caption).foregroundStyle(.teal)
                        }
                    }
                    .padding(12).frame(maxWidth: .infinity)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)

                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                    builtinCard(providers[0])
                    builtinCard(providers[1])
                    ForEach(providers.dropFirst(2)) { p in
                        unimplementedCard(p)
                    }
                    ForEach(customs) { p in
                        customCard(p)
                    }
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
                }
            }
        }
    }

    private func builtinCard(_ p: ProviderCard) -> some View {
        let enabled = !settings.disabledProviders.contains(p.id)
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(p.name).font(.callout.bold()).lineLimit(1)
                Spacer()
                Toggle("", isOn: Binding(
                    get: { enabled },
                    set: { on in setProvider(p.id, on: on) }))
                    .labelsHidden()
            }
            Text(p.desc).font(.caption2).foregroundStyle(.secondary)
            if !p.implemented {
                Text("Swift 引擎下一轮接入").font(.caption2).foregroundStyle(.orange)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12)
            .stroke(enabled ? Color.teal : .clear, lineWidth: 1.5))
    }

    private func unimplementedCard(_ p: ProviderCard) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(p.name).font(.callout.bold()).lineLimit(1)
                Spacer()
                Toggle("", isOn: .constant(false)).labelsHidden().disabled(true)
            }
            Text(p.desc).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
        .opacity(0.55)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func customCard(_ p: CustomProfile) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(p.name.isEmpty ? p.model : p.name).font(.callout.bold()).lineLimit(1)
                Spacer()
            }
            Text(p.baseUrl).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            Text(p.model).font(.caption2).foregroundStyle(.teal)
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.teal, lineWidth: 1.5))
        .contextMenu {
            Button("编辑") { editingProfile = p }
            Button("删除", role: .destructive) {
                customs.removeAll { $0.id == p.id }
                CustomProfile.saveAll(customs)
            }
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
                    Button("模板") {
                        prompt = Self.promptTemplateText
                        persist()
                    }
                    .buttonStyle(.bordered)
                }
                Text("占位：(TO) = 目标语言，(TONE) = 语气")
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

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("外观：昼夜切换", "外观")
            if expanded.contains("外观") {
                Text("昼是浅色，夜是暗色调。跟随系统会跟着手机的深浅色走。")
                    .font(.footnote).foregroundStyle(.secondary)
                Picker("外观", selection: themeBinding) {
                    Text("跟随系统").tag("system")
                    Text("昼").tag("light")
                    Text("夜").tag("dark")
                }
                .pickerStyle(.segmented)
                Text("自定夜间时段与安卓一样，列下一轮接入。")
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
        themeMode = settings.themeMode
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

    private var themeBinding: Binding<String> {
        Binding(get: { settings.themeMode },
                set: { settings.themeMode = $0; persist() })
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

extension TranslationServiceView {
    static let promptTemplateText = "你是专业的外贸翻译引擎，服务于跨境贸易沟通。把用户输入翻译成(TO)。\n要求：\n1. 只输出译文，不要解释、不要引号、不要加任何前后缀。\n2. 语气：正式商务语气，简洁专业，不使用缩略语和表情。\n3. 文本中的『数字』是占位符，必须原样保留在译文中的对应位置，不得翻译、不得改写、不得移除。\n4. 金额、数量、日期、型号必须逐字对应，不许换算、不许分开。\n5. 人名、品牌名、产品型号保留原文，不要音译。\n6. 如果原文已是目标语言，原样返回。"
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
