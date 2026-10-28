import SwiftUI
import WeiFanCore

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    @State private var googleKey = ""
    @State private var deepLKey = ""
    @State private var deepLEndpoint = ""
    @State private var llmKey = ""
    @State private var llmBaseURL = ""
    @State private var llmModel = ""
    @State private var loaded = false
    @State private var savedToast = false

    var body: some View {
        Form {
            Section("翻译行为") {
                Picker("兜底目标语言", selection: $model.settings.defaultTargetLanguage) {
                    ForEach(LanguageCode.sendTargets, id: \.self) { language in
                        Text(language.displayName).tag(language)
                    }
                }
                Toggle("点译即替换（不弹预览）", isOn: $model.settings.allowsDirectSend)
                Toggle("语言判断不确定时强制预览", isOn: $model.settings.previewOnLowConfidence)
                Toggle("分享扩展译完自动复制到剪贴板", isOn: $model.settings.copyToClipboardOnShare)

                VStack(alignment: .leading) {
                    Text("长句阈值：\(model.settings.longSentenceThreshold) 字")
                        .font(.callout)
                    Slider(value: Binding(
                        get: { Double(model.settings.longSentenceThreshold) },
                        set: { model.settings.longSentenceThreshold = Int($0) }
                    ), in: 20...200, step: 10)
                    Text("超过这个长度直接走大模型")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .onChange(of: model.settings.allowsDirectSend) { _ in model.saveSettings() }
            .onChange(of: model.settings.previewOnLowConfidence) { _ in model.saveSettings() }
            .onChange(of: model.settings.copyToClipboardOnShare) { _ in model.saveSettings() }
            .onChange(of: model.settings.defaultTargetLanguage) { _ in model.saveSettings() }
            .onChange(of: model.settings.longSentenceThreshold) { _ in model.saveSettings() }

            Section("键盘语言芯片") {
                ForEach(LanguageCode.sendTargets, id: \.self) { language in
                    if model.settings.pinnedLanguages.contains(language) {
                        HStack {
                            Text(language.displayName)
                            Spacer()
                            Text(language.shortLabel).foregroundStyle(.secondary)
                        }
                    }
                }
                Button("恢复默认（EN / ES / PT / FR / AR / RU）") {
                    model.settings.pinnedLanguages = [.english, .spanish, .portuguese, .french, .arabic, .russian]
                    model.saveSettings()
                }
            }

            Section {
                SecureField("Google Cloud Translation API Key", text: $googleKey)
                SecureField("DeepL API Key（中→欧语质量更稳）", text: $deepLKey)
                TextField("DeepL 端点", text: $deepLEndpoint)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("快速通道（机翻）")
            } footer: {
                Text("短句、无数字的消息走这里，延迟通常 200–400ms。")
            }

            Section {
                SecureField("大模型 API Key（DeepSeek / OpenAI / 自建）", text: $llmKey)
                TextField("Base URL", text: $llmBaseURL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField("模型名，如 deepseek-chat", text: $llmModel)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("大模型通道")
            } footer: {
                Text("长句、含金额/数字、商务语气走这里。密钥保存在 Keychain，键盘扩展通过 App Group 读取。")
            }

            Section {
                Button(savedToast ? "已保存" : "保存密钥") {
                    var credentials = model.credentials
                    credentials.googleAPIKey = googleKey.isEmpty ? nil : googleKey
                    credentials.deepLAPIKey = deepLKey.isEmpty ? nil : deepLKey
                    credentials.deepLEndpoint = URL(string: deepLEndpoint) ?? credentials.deepLEndpoint
                    credentials.llmAPIKey = llmKey.isEmpty ? nil : llmKey
                    credentials.llmBaseURL = URL(string: llmBaseURL) ?? credentials.llmBaseURL
                    credentials.llmModel = llmModel.isEmpty ? "deepseek-chat" : llmModel
                    model.credentials = credentials
                    model.saveCredentials()
                    savedToast = true
                }
            }

            Section("关于") {
                LabeledContent("版本", value: "0.1.0 (MVP)")
                LabeledContent("App Group", value: AppGroup.identifier)
                Text("所有翻译内容会发送到你配置的第三方翻译服务；本地只保留最近 200 条记录。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("设置")
        .onAppear(perform: loadOnce)
    }

    private func loadOnce() {
        guard !loaded else { return }
        googleKey = model.credentials.googleAPIKey ?? ""
        deepLKey = model.credentials.deepLAPIKey ?? ""
        deepLEndpoint = model.credentials.deepLEndpoint.absoluteString
        llmKey = model.credentials.llmAPIKey ?? ""
        llmBaseURL = model.credentials.llmBaseURL.absoluteString
        llmModel = model.credentials.llmModel
        loaded = true
    }
}
