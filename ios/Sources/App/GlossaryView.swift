import SwiftUI
import WeiFanCore

/// 术语表：只做"别乱译"（D-08）。默认策略是原样保留，
/// 需要固定译法时再补一条"中文 → 目标语言"的映射。
struct GlossaryView: View {
    @EnvironmentObject private var model: AppModel
    @State private var term: String = ""
    @State private var policy: GlossaryEntry.Policy = .keepOriginal
    @State private var fixedTarget: LanguageCode = .english
    @State private var fixedText: String = ""

    var body: some View {
        Form {
            Section {
                Text("命中的词会用占位符保护起来，翻译完再换回来，避免「轻舟 Pro」被译成 Light Boat Pro。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("新增词条") {
                TextField("原文，例如：轻舟 Pro / Chan / 定金", text: $term)
                Picker("策略", selection: $policy) {
                    Text("原样保留").tag(GlossaryEntry.Policy.keepOriginal)
                    Text("固定译法").tag(GlossaryEntry.Policy.fixed)
                }
                .pickerStyle(.segmented)

                if policy == .fixed {
                    Picker("目标语言", selection: $fixedTarget) {
                        ForEach(LanguageCode.sendTargets, id: \.self) { language in
                            Text(language.displayName).tag(language)
                        }
                    }
                    TextField("固定译法", text: $fixedText)
                }

                Button("添加") {
                    let trimmed = term.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    let entry = GlossaryEntry(term: trimmed,
                                              policy: policy,
                                              translations: policy == .fixed ? [fixedTarget.rawValue: fixedText] : [:])
                    model.settings.glossary.append(entry)
                    model.saveSettings()
                    term = ""; fixedText = ""
                }
                .disabled(term.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            Section("已有词条（\(model.settings.glossary.count)）") {
                if model.settings.glossary.isEmpty {
                    Text("还没有词条").foregroundStyle(.secondary)
                }
                ForEach(model.settings.glossary) { entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.term)
                            Text(subtitle(for: entry))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { entry.isEnabled },
                            set: { newValue in
                                if let index = model.settings.glossary.firstIndex(where: { $0.id == entry.id }) {
                                    model.settings.glossary[index].isEnabled = newValue
                                    model.saveSettings()
                                }
                            }
                        ))
                        .labelsHidden()
                    }
                }
                .onDelete { offsets in
                    model.settings.glossary.remove(atOffsets: offsets)
                    model.saveSettings()
                }
            }
        }
        .navigationTitle("术语")
    }

    private func subtitle(for entry: GlossaryEntry) -> String {
        switch entry.policy {
        case .keepOriginal:
            return "原样保留"
        case .fixed:
            let pairs = entry.translations.map { "\($0.key) → \($0.value)" }.sorted()
            return pairs.isEmpty ? "固定译法（未填写）" : pairs.joined(separator: "，")
        }
    }
}
