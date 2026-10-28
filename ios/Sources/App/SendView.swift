import SwiftUI
import WeiFanCore

struct SendView: View {
    @EnvironmentObject private var model: AppModel

    @State private var input: String = ""
    @State private var target: LanguageCode?
    @State private var outcome: SendOutcome?
    @State private var errorText: String?
    @State private var isWorking = false
    @FocusState private var focused: Bool

    var body: some View {
        Form {
            Section {
                Text("在 WhatsApp 里怎么用")
                    .font(.headline)
                StepRow(index: 1, text: "用系统拼音键盘在 WhatsApp 输入框把中文打完，先不要发")
                StepRow(index: 2, text: "点地球键切到「微翻」键盘，看清目标语言后点「译」")
                StepRow(index: 3, text: "译文会直接替换掉输入框里的中文，点 WhatsApp 的绿色发送键即可")
                Text("不需要在微翻键盘上打中文——iOS 只允许一个键盘显示，中文仍然用系统拼音输入。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("试译") {
                TextEditor(text: $input)
                    .frame(minHeight: 90)
                    .focused($focused)
                    .overlay(alignment: .topLeading) {
                        if input.isEmpty {
                            Text("输入中文，例如：这批货下周能发出吗？")
                                .foregroundStyle(.tertiary)
                                .padding(.top, 8)
                                .allowsHitTesting(false)
                        }
                    }

                Picker("目标语言", selection: $target) {
                    Text("自动（默认英语）").tag(LanguageCode?.none)
                    ForEach(LanguageCode.sendTargets, id: \.self) { language in
                        Text("\(language.displayName) \(language.shortLabel)").tag(LanguageCode?.some(language))
                    }
                }

                Button {
                    translate()
                } label: {
                    HStack {
                        if isWorking { ProgressView().padding(.trailing, 4) }
                        Text(isWorking ? "翻译中…" : "翻译")
                    }
                }
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isWorking)
            }

            if let outcome {
                Section("译文") {
                    Text(outcome.translated)
                        .textSelection(.enabled)
                    HStack {
                        Text("\(outcome.target.displayName) · \(outcome.provider)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(String(format: "%.2fs", outcome.latency))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if !outcome.risk.reasons.isEmpty {
                        Label(outcome.risk.reasons.map(\.displayName).joined(separator: " · "),
                              systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                    Button("复制译文") {
                        UIPasteboard.general.string = outcome.translated
                    }
                }
            }

            if let errorText {
                Section {
                    Text(errorText).foregroundStyle(.red).font(.footnote)
                }
            }

            Section("最近翻译") {
                if model.history.isEmpty {
                    Text("还没有记录").foregroundStyle(.secondary)
                } else {
                    ForEach(model.history.prefix(6)) { record in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(record.original).font(.subheadline)
                            Text(record.translated).font(.callout).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("微翻")
        .onAppear {
            if !model.prefill.isEmpty {
                input = model.prefill
                model.prefill = ""
            }
        }
    }

    private func translate() {
        focused = false
        isWorking = true
        errorText = nil
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let core = model.makeCore()
        let picked = target
        Task {
            do {
                let result = try await core.translateForSending(text, explicitTarget: picked)
                await MainActor.run {
                    outcome = result
                    isWorking = false
                    model.record(TranslationRecord(direction: .send,
                                                   original: text,
                                                   translated: result.translated,
                                                   sourceLanguage: "zh-Hans",
                                                   targetLanguage: result.target.rawValue,
                                                   provider: result.provider))
                }
            } catch {
                await MainActor.run {
                    errorText = error.localizedDescription
                    isWorking = false
                }
            }
        }
    }
}

private struct StepRow: View {
    let index: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text("\(index)")
                .font(.caption.bold())
                .frame(width: 18, height: 18)
                .background(Circle().fill(Color.accentColor.opacity(0.15)))
            Text(text).font(.subheadline)
        }
    }
}
