// 微翻 iOS —— 翻译服务设置页（对齐 Android 翻译服务页结构，docs/20 §2.1）
//
// Android 页面段：翻译服务网格 / 译文显示与 AI 提示词 / 发件 / 额度保护 / 外观 / 术语 / 试译。
// iOS 引擎层（WeiFanCore）目前实现 Google/DeepL/LLM 三家 + 自定义 OpenAI 兼容接口；
// 本页先对齐「结构 + 自定义接口全功能 + 显示设置 + 历史独立引擎（B5）」，
// 六家机翻引擎（百度/腾讯/火山/阿里/Yandex/Azure）的 Swift 签名实现列下一轮（见页内灰显）。
//
// 与 Android 同名键（UserDefaults）：prompt_tpl / trans_color / src_sp / trans_sp /
// history_engine / customs_json —— 跨端语义一致。

import SwiftUI
import UIKit

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

// MARK: - 自定义 OpenAI 兼容引擎（收件/历史翻译用；与 Android customRun 同构）

enum CustomLLMEngine {
    static func translate(text: String, target: String, profile: CustomProfile,
                          prompt: String?) async throws -> String {
        var req = URLRequest(url: URL(string: profile.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/chat/completions")!)
        req.timeoutInterval = 30
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer " + profile.key, forHTTPHeaderField: "Authorization")
        let sys = prompt ?? "你是专业的 Sof?tware 翻译引擎。只输出译文，不要解释。目标语言：\(target)。"
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

    // —— 服务网格状态 ——
    @State private var customs: [CustomProfile] = []
    @State private var editingProfile: CustomProfile?
    @State private var showAddCustom = false

    // —— 译文显示与 AI 提示词 ——
    @AppStorage("prompt_tpl") private var promptTemplate = ""
    @AppStorage("trans_color") private var transColorHex = 0x0A7EA4
    @AppStorage("src_sp") private var sourceSp = 15.0
    @AppStorage("trans_sp") private var transSp = 13.0
    @AppStorage("history_engine") private var historyEngine = ""
    @State private var showColorPicker = false
    @State private var showHistoryPicker = false

    // —— 试译 ——
    @State private var testText = ""
    @State private var testResult = ""
    @State private var testing = false

    private let defaultPrompt = "你是专业的外贸翻译引擎。把用户输入翻译成英文(en)。只输出译文，不要解释。"

    var body: some View {
        Form {
            // ── 翻译服务（网格顺序 = 优先级）：对齐安卓 provider 网格 ──
            Section("翻译服务（网格顺序 = 优先级）") {
                ForEach(customs) { p in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.name).font(.callout.weight(.medium))
                            Text(p.baseUrl).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Text(p.model).font(.caption2).foregroundStyle(.secondary)
                    }
                    .swipeActions {
                        Button("删除", role: .destructive) {
                            customs.removeAll { $0.id == p.id }
                            CustomProfile.saveAll(customs)
                        }
                        Button("编辑") { editingProfile = p }.tint(.blue)
                    }
                }
                .onMove { from, to in
                    customs.move(fromOffsets: from, toOffset: to)
                    CustomProfile.saveAll(customs)
                }
                Button {
                    editingProfile = CustomProfile(id: "custom\(Int(Date().timeIntervalSince1970))",
                                                   name: "", key: "", baseUrl: "", model: "")
                    showAddCustom = true
                } label: {
                    Label("添加自定义接口（OpenAI 兼容，如 Agnes）", systemImage: "plus")
                }
            }
            Section {
                Text("机翻引擎（百度/腾讯/火山/阿里/Yandex/Azure）的 Swift 签名实现下一轮接入；当前自定义 OpenAI 兼容接口已覆盖 Agnes/DeepSeek/硅基流动等。")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            // ── 译文显示与 AI 提示词（对齐安卓同名段） ──
            Section("译文显示与 AI 提示词") {
                NavigationLink("AI 翻译提示词（仅影响大模型类接口）") {
                    PromptEditorView(prompt: $promptTemplate, defaultPrompt: defaultPrompt)
                }
                ColorPicker("译文颜色（聊天里译文那一行的字色）",
                            selection: colorBinding,
                            supportsOpacity: false)
                Stepper("原文字号 \(Int(sourceSp)) sp", value: $sourceSp, in: 10...24, step: 1)
                Stepper("译文字号 \(Int(transSp)) sp", value: $transSp, in: 10...24, step: 1)
                Button {
                    showHistoryPicker = true
                } label: {
                    HStack {
                        Text("历史消息翻译接口")
                        Spacer()
                        Text(historyEngine.isEmpty ? "跟随全局" : historyEngineName)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Text("仅用于聊天窗口内历史消息的自动翻译，不影响输入框翻译。")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            // ── 试译（对齐安卓「试译：不需要 WhatsApp」） ──
            Section("试译：不需要 WhatsApp") {
                TextField("输入要试译的文本", text: $testText)
                Button {
                    testing = true
                    Task {
                        defer { testing = false }
                        do {
                            let prof = CustomProfile.byId(historyEngine.isEmpty ? (customs.first?.id ?? "") : historyEngine)
                            guard let prof else {
                                testResult = "没有可用的自定义接口：先在上方添加并填好 Key"
                                return
                            }
                            testResult = try await CustomLLMEngine.translate(
                                text: testText, target: "中文", profile: prof,
                                prompt: promptTemplate.isEmpty ? nil : promptTemplate)
                        } catch {
                            testResult = "失败：\(error.localizedDescription)"
                        }
                    }
                } label: {
                    HStack {
                        Text("试译")
                        if testing { ProgressView().scaleEffect(0.7) }
                    }
                }
                .disabled(testText.isEmpty || testing)
                if !testResult.isEmpty {
                    Text(testResult).font(.callout).foregroundStyle(.primary)
                }
            }
        }
        .navigationTitle("翻译服务")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { customs = CustomProfile.loadAll() }
        .sheet(isPresented: $showAddCustom) {
            if let p = editingProfile {
                CustomProfileEditor(profile: p) { saved in
                    customs.append(saved)
                    CustomProfile.saveAll(customs)
                    showAddCustom = false
                }
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
        .sheet(isPresented: $showHistoryPicker) {
            HistoryEnginePicker(customs: customs, selection: $historyEngine)
        }
    }

    private var colorBinding: Binding<Color> {
        Binding(
            get: { Color(hex: UInt32(transColorHex)) },
            set: { new in
                // ColorPicker 返回 RGBA 浮点，转回我们存档的 0xRRGGBB
                let ui = UIColor(new)
                var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
                ui.getRed(&r, green: &g, blue: &b, alpha: &a)
                transColorHex = (Int(r * 255) << 16) | (Int(g * 255) << 8) | Int(b * 255)
            })
    }

    private var historyEngineName: String {
        CustomProfile.byId(historyEngine)?.name ?? historyEngine
    }
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
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button { selection = ""; dismiss() } label: {
                    HStack {
                        Text("跟随全局")
                        Spacer()
                        if selection.isEmpty { Image(systemName: "checkmark").foregroundStyle(.teal) }
                    }
                }
                ForEach(customs) { p in
                    Button {
                        selection = "custom:\(p.id)"
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

// MARK: - AI 提示词编辑器（对齐安卓：保存/默认/模板 + 占位说明）

struct PromptEditorView: View {
    @Binding var prompt: String
    let defaultPrompt: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section {
                TextEditor(text: $prompt)
                    .frame(minHeight: 220)
                    .font(.system(size: 14))
                    .autocorrectionDisabled()
            } header: {
                Text("自定义大模型的翻译风格。占位：(TO)=目标语言，(TONE)=语气")
            } footer: {
                Text("留空 = 使用内置默认提示词。")
            }
            Section {
                Button("恢复默认") { prompt = "" }
            }
        }
        .navigationTitle("AI 翻译提示词")
        .navigationBarTitleDisplayMode(.inline)
    }
}
