// 微翻 iOS —— 群管理 + 拉黑 + 被踢弹层 + 待办通知（对齐参考 docs/20 P2/P3/P8）

import Foundation
import SwiftUI
import UserNotifications

// MARK: - 群管理页（对齐参考 GroupInfoView / activity_group_info）

struct GroupMember: Identifiable {
    let jid: String
    let name: String
    let isAdmin: Bool
    var id: String { jid }
}

struct GroupInfoView: View {
    let chatJid: String
    let title: String
    @ObservedObject var core = ProtocolCore.shared
    @State private var members: [GroupMember] = []
    @State private var groupName = ""
    @State private var topic = ""
    @State private var inviteLink = ""
    @State private var addPhone = ""
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        Form {
            Section("群名称") {
                TextField("群名称", text: $groupName)
                Button("保存群名") { run { try core.client.setGroupName(chatJid, name: groupName) } }
                    .disabled(groupName.isEmpty)
            }
            Section("群公告") {
                TextField("群公告（SetGroupTopic）", text: $topic)
                Button("保存公告") { run { try core.client.setGroupTopic(chatJid, topic: topic) } }
            }
            Section("邀请链接") {
                if inviteLink.isEmpty {
                    Button("生成/获取邀请链接") {
                        run { var e: NSError?; inviteLink = core.client.getGroupInviteLink(chatJid, reset: false, error: &e); if let e { throw e } }
                    }
                } else {
                    Text(inviteLink).font(.caption.monospaced())
                        .onTapGesture { UIPasteboard.general.string = inviteLink }
                    Text("点击复制 · 长按重置可在下方操作").font(.caption2).foregroundStyle(.secondary)
                    Button("重置链接", role: .destructive) {
                        run { var e: NSError?; inviteLink = core.client.getGroupInviteLink(chatJid, reset: true, error: &e); if let e { throw e } }
                    }
                }
            }
            Section("成员 \(members.count)") {
                ForEach(members) { mem in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(mem.name).font(.callout)
                            Text(mem.jid).font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if mem.isAdmin {
                            Text("管理员").font(.caption2).foregroundStyle(.teal)
                        }
                    }
                    .swipeActions {
                        Button("移除") {
                            run { try core.client.removeGroupParticipants(chatJid, phonesJSON: "[\"\(mem.jid)\"]") }
                        }.tint(.red)
                        Button(mem.isAdmin ? "降级" : "升管理员") {
                            run {
                                if mem.isAdmin {
                                    try core.client.demoteGroupParticipants(chatJid, phonesJSON: "[\"\(mem.jid)\"]")
                                } else {
                                    try core.client.promoteGroupParticipants(chatJid, phonesJSON: "[\"\(mem.jid)\"]")
                                }
                            }
                        }.tint(.orange)
                    }
                }
            }
            Section("添加成员") {
                TextField("手机号（多个用逗号）", text: $addPhone)
                    .keyboardType(.phonePad)
                Button("添加") {
                    let phones = addPhone.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                    guard !phones.isEmpty else { return }
                    run { try core.client.addGroupParticipants(chatJid, phonesJSON: jsonArr(phones)) }
                    addPhone = ""
                }
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.footnote) } }
        }
        .navigationTitle(title.isEmpty ? "群信息" : title)
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
    }

    private func reload() {
        DispatchQueue.global(qos: .userInitiated).async {
            var err: NSError?
            let raw = core.client.getGroupInfo(chatJid, error: &err)
            DispatchQueue.main.async {
                loading = false
                guard err == nil,
                      let o = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
                    error = err?.localizedDescription ?? "群信息获取失败"
                    return
                }
                groupName = o["name"] as? String ?? ""
                topic = o["topic"] as? String ?? ""
                var out: [GroupMember] = []
                if let arr = o["participants"] as? [[String: Any]] {
                    for p in arr {
                        let jid = (p["jid"] as? String) ?? (p["phone"] as? String) ?? ""
                        out.append(GroupMember(jid: jid,
                                               name: (p["name"] as? String) ?? jid,
                                               isAdmin: (p["isAdmin"] as? Bool) ?? (p["is_admin"] as? Bool) ?? false))
                    }
                }
                members = out
            }
        }
    }

    private func run(_ block: @escaping () throws -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try block()
                DispatchQueue.main.async { reload() }
            } catch {
                DispatchQueue.main.async { self.error = error.localizedDescription }
            }
        }
    }

    private func jsonArr(_ items: [String]) -> String {
        let data = try? JSONSerialization.data(withJSONObject: items)
        return String(data: data ?? Data("[]".utf8), encoding: .utf8) ?? "[]"
    }
}

// MARK: - 拉黑（对齐参考 blockContact/unblockContact；入口：联系人信息页）

struct BlocklistHelper {
    /// 查询某 jid 是否已被拉黑（getBlocklistJSON → ["blocklist":[jid...]]）
    static func isBlocked(client: MobileWeifanClient, jid: String) -> Bool {
        var err: NSError?
        let raw: String = client.getBlocklistJSON(&err)
        guard err == nil,
              let o = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
              let list = (o["blocklist"] ?? o["blocklistJSON"]) as? [String] else { return false }
        return list.contains(jid)
    }

    static func setBlocked(client: MobileWeifanClient, jid: String, blocked: Bool) -> String? {
        var err: NSError?
        let raw = client.updateBlocklist(jid, action: blocked ? "block" : "unblock", error: &err)
        if let err { return err.localizedDescription }
        _ = raw
        return nil
    }
}

// MARK: - 被踢出群弹层（dialog_kicked 对应；协议 onMessageEvent → groupInfo.kicked）

enum KickedAlert {
    /// ChatView/ChatList 监听 onMessageEvent，kicked 时弹
    static func handle(eventJSON: Data, present: @escaping (String, String) -> Void) {
        guard let o = try? JSONSerialization.jsonObject(with: eventJSON) as? [String: Any],
              o["type"] as? String == "groupInfo",
              (o["kicked"] as? Bool) == true,
              let group = o["group"] as? String else { return }
        present("你已被移出群聊", group)
    }
}

// MARK: - 待办本地通知（UNUserNotificationCenter；Android TodoNotifications 的 iOS 对应）

enum TodoNotifications {
    static func ensureAuthorized(_ cb: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in
            cb(ok)
        }
    }

    static func schedule(todoId: Int64, content: String, dueTs: Int64) {
        guard Double(dueTs) > Date().timeIntervalSince1970 * 1000 else { return }
        let content2 = UNMutableNotificationContent()
        content2.title = "待办到期"
        content2.body = content
        content2.sound = .default
        let ti = TimeInterval(dueTs) / 1000 - Date().timeIntervalSince1970
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(ti, 1.0), repeats: false)
        let req = UNNotificationRequest(identifier: "todo-\(todoId)", content: content2, trigger: trigger)
        UNUserNotificationCenter.current().add(req)
    }

    static func cancel(todoId: Int64) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["todo-\(todoId)"])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["todo-\(todoId)"])
    }
}
