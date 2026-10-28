import SwiftUI
import WeiFanCore

struct CustomersView: View {
    @EnvironmentObject private var model: AppModel
    @State private var editing: CustomerProfile?

    var body: some View {
        List {
            Section {
                Text("客户档案用来记住“这个客户说哪种语言、什么语气、有哪些不能乱译的词”。键盘里点右上角的人名按钮可以快速套用。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            ForEach(model.profiles) { profile in
                Button {
                    editing = profile
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(profile.displayName).font(.body)
                            Spacer()
                            Text(profile.language?.shortLabel ?? "自动")
                                .font(.caption.bold())
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Capsule().fill(Color.secondary.opacity(0.15)))
                        }
                        Text([profile.matchKey, profile.style.displayName]
                                .filter { !$0.isEmpty }
                                .joined(separator: " · "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                .swipeActions {
                    Button(role: .destructive) {
                        model.delete(profile: profile)
                    } label: { Label("删除", systemImage: "trash") }
                }
            }
        }
        .navigationTitle("客户")
        .toolbar {
            Button {
                editing = CustomerProfile(matchKey: "", displayName: "")
            } label: { Image(systemName: "plus") }
        }
        .sheet(item: $editing) { profile in
            CustomerEditor(profile: profile) { saved in
                model.upsert(saved)
            }
        }
    }
}

private struct CustomerEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: CustomerProfile

    let onSave: (CustomerProfile) -> Void

    init(profile: CustomerProfile, onSave: @escaping (CustomerProfile) -> Void) {
        _draft = State(initialValue: profile)
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("基本信息") {
                    TextField("显示名，如 Juan（智利）", text: $draft.displayName)
                    TextField("匹配键：WhatsApp 里的名字或手机号", text: $draft.matchKey)
                }
                Section("语言与语气") {
                    Picker("惯用语言", selection: $draft.language) {
                        Text("自动检测").tag(LanguageCode?.none)
                        ForEach(LanguageCode.sendTargets, id: \.self) { language in
                            Text(language.displayName).tag(LanguageCode?.some(language))
                        }
                    }
                    Picker("语气", selection: $draft.style) {
                        ForEach(TranslationStyle.allCases, id: \.self) { style in
                            Text(style.displayName).tag(style)
                        }
                    }
                }
                Section("备注（不参与翻译）") {
                    TextField("例如：只接受西语、周五不回复、忌讳谈价格", text: $draft.notes, axis: .vertical)
                        .lineLimit(3...6)
                }
            }
            .navigationTitle(draft.displayName.isEmpty ? "新客户" : draft.displayName)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        draft.updatedAt = Date()
                        if draft.matchKey.isEmpty { draft.matchKey = draft.displayName }
                        onSave(draft)
                        dismiss()
                    }
                    .disabled(draft.displayName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
