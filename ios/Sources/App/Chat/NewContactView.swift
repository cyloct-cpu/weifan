import SwiftUI
import Contacts

// MARK: - 新建联系人（对齐 Android NewContactActivity）
//
// 安卓流程：姓名 + 国家码 + 手机号 → 查是否在 WhatsApp → 存进手机通讯录 → 打开会话
// 这里照做；查号失败不阻塞（存通讯录与开聊仍可用）。

struct NewContactView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var code = "86"
    @State private var phone = ""
    @State private var status = "填完后可保存到手机通讯录并直接开聊"
    @State private var statusColor: Color = .secondary
    @State private var saved = false

    /// 纯数字：国家码 + 手机号（安卓 fullNumber() 同逻辑，区号空则默认 86）
    private var digits: String? {
        let c = code.filter(\.isNumber)
        let p = phone.filter(\.isNumber)
        if p.count < 6 { return nil }
        return (c.isEmpty ? "86" : c) + p
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("姓名，例如：Leon van Dijk", text: $name)
                    HStack(spacing: 8) {
                        // 「+」固定不可编辑，国家码是输入框（对齐安卓 prefixField）
                        Text("+").foregroundStyle(.secondary)
                        TextField("86", text: $code)
                            .keyboardType(.numberPad)
                            .frame(width: 64)
                        Divider().frame(height: 20)
                        TextField("手机号", text: $phone)
                            .keyboardType(.numberPad)
                    }
                } header: {
                    Text("联系人信息")
                } footer: {
                    Text(status).foregroundStyle(statusColor)
                }

                Section {
                    Button("保存到手机通讯录") { saveToContacts() }
                    Button("保存并去发消息") {
                        saveToContacts()
                        openChat()
                    }
                }
            }
            .navigationTitle("新建联系人")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    /// 写系统通讯录（需 NSContactsUsageDescription；拒绝时提示去设置开权限）
    private func saveToContacts() {
        guard let d = digits else {
            status = "号码不完整：请填国家码和至少 6 位手机号"
            statusColor = .red
            return
        }
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else {
            status = "姓名必填"
            statusColor = .red
            return
        }
        let store = CNContactStore()
        store.requestAccess(for: .contacts) { ok, _ in
            guard ok else {
                DispatchQueue.main.async {
                    status = "没有通讯录权限，请到 设置 → 隐私 → 通讯录 打开"
                    statusColor = .red
                }
                return
            }
            let c = CNMutableContact()
            c.givenName = n
            c.phoneNumbers = [CNLabeledValue(label: CNLabelPhoneNumberMobile,
                                             value: CNPhoneNumber(stringValue: "+\(d)"))]
            let req = CNSaveRequest()
            req.add(c, toContainerWithIdentifier: nil)
            do {
                try store.execute(req)
                DispatchQueue.main.async {
                    saved = true
                    status = "已存入手机通讯录：\(n) +\(d)"
                    statusColor = .green
                }
            } catch {
                DispatchQueue.main.async {
                    status = "写入通讯录失败：\(error.localizedDescription)"
                    statusColor = .red
                }
            }
        }
    }

    private func openChat() {
        guard let d = digits else { return }
        let n = name.trimmingCharacters(in: .whitespaces)
        ProtocolCore.shared.openChat(jid: "\(d)@s.whatsapp.net",
                                     title: n.isEmpty ? "+\(d)" : n)
        dismiss()
    }
}

// MARK: - 新建会话（对齐 Android showNewChatDialog：单输入框，号码含国家码）

struct NewChatSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""

    private var digits: String? {
        let d = input.filter(\.isNumber)
        return d.count >= 8 && d.count <= 15 ? d : nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("手机号（含国家码），如 31612345678", text: $input)
                        .keyboardType(.numberPad)
                } footer: {
                    Text("号码要带国家码，不用输「+」。对方在 WhatsApp 上即可直接聊。")
                }
            }
            .navigationTitle("新建会话")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("打开") {
                        guard let d = digits else { return }
                        ProtocolCore.shared.openChat(jid: "\(d)@s.whatsapp.net", title: "+\(d)")
                        dismiss()
                    }
                    .disabled(digits == nil)
                }
            }
        }
    }
}

// MARK: - 新建群聊（对齐 Android showNewGroupDialog：群名 + 成员号码逗号分隔）

struct NewGroupSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var members = ""
    @State private var error = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("群名称（25 字内）", text: $name)
                    TextField("成员手机号，逗号分隔\n如 31612345678,447925169391",
                              text: $members, axis: .vertical)
                        .lineLimit(2...4)
                } footer: {
                    if !error.isEmpty { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("新建群聊")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") { create() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func create() {
        let names = members.split(whereSeparator: { ",， \n".contains($0) })
            .map { $0.filter(\.isNumber) }.filter { $0.count >= 8 }
        var err: NSError?
        // gomobile 真签名：createGroup(name, phonesJSON: String?, error:)
        let json = (try? JSONSerialization.data(withJSONObject: names))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        let jid = ProtocolCore.shared.client.createGroup(name, phonesJSON: json, error: &err)
        if let e = err {
            error = e.localizedDescription
        } else {
            ProtocolCore.shared.openChat(jid: jid, title: name)
            dismiss()
        }
    }
}