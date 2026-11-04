// 微翻 iOS —— 协议层桥 + 全局聊天状态（阶段 1）
//
// 职责（对齐 Android ChatEngine）：
//  1. 持有 MobileWeifanClient（gomobile 单实例）
//  2. 挂 gobind 回调（配对/连接/消息/回执），消息落 GRDB
//  3. 收件自动翻译（走 WeiFanCore TranslationCore —— 与 Android 同一套规格）
//  4. 发送：直译直发（原文存 original，译文上协议）

import Foundation
import SwiftUI
import WeiFanCore

/// gobind 回调都要求继承 proxy 基类——直接用 ObjC 协议名子类化
final class PairHandler: NSObject, MobilePairListenerProtocol {
    var onCode: ((String) -> Void)?          // 配对码（手机号输入用）
    var onQR: ((Data) -> Void)?              // Go 渲染好的二维码 PNG
    var onError: ((String) -> Void)?
    var onSuccess: ((String) -> Void)?

    func onPairCode(_ code: String?) {
        if let code { DispatchQueue.main.async { self.onCode?(code) } }
    }
    // QR 事件走这两个（mobile.go qrChan 循环）：原始串 + 渲染好的 PNG
    func onQRCode(_ code: String?) {
    }
    func onQRImage(_ png: Data?) {
        if let png { DispatchQueue.main.async { self.onQR?(png) } }
    }
    func onPairError(_ message: String?) {
        let msg = message ?? "配对失败"
        DispatchQueue.main.async { self.onError?(msg) }
    }
    func onPairSuccess(_ jid: String?) {
        if let jid { DispatchQueue.main.async { self.onSuccess?(jid) } }
    }
}

final class ConnectionHandler: NSObject, MobileConnectionListenerProtocol {
    var onChange: ((Bool, String?) -> Void)?
    func onConnected() { onChange?(true, nil) }
    func onDisconnected(_ reason: String?) { onChange?(false, reason) }
}

final class MessageHandler: NSObject, MobileMessageListenerProtocol {
    var onMessage: ((Data) -> Void)?
    var onHistoryDoneCb: (() -> Void)?

    func onMessage(_ json: String?) {
        guard let json, let data = json.data(using: .utf8) else { return }
        onMessage?(data)
    }
    func onHistoryDone() {
        onHistoryDoneCb?()
    }
}

final class ReceiptHandler: NSObject, MobileReceiptListenerProtocol {
    var onReceipt: ((String, String, [String]) -> Void)?
    func onReceipt(_ json: String?) {
        guard let json,
              let d = json.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let chatJid = o["chatJid"] as? String,
              let type = o["type"] as? String,
              let ids = o["ids"] as? [String] else { return }
        onReceipt?(chatJid, type, ids)
    }
}

@MainActor
final class ProtocolCore: ObservableObject {
    static let shared = ProtocolCore()

    let client: MobileWeifanClient
    let db: ChatDatabase
    let core: TranslationCore

    @Published var paired: Bool
    @Published var connected: Bool = false
    /// 收到新消息的会话 jid 集合（UI 刷新用，Counter 模式避免 Diffable 抖动）
    @Published var inboxTick: Int = 0

    private let pairHandler = PairHandler()
    private let connHandler = ConnectionHandler()
    private let msgHandler = MessageHandler()
    private let receiptHandler = ReceiptHandler()

    private init() {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("protocol").path
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let dbPath = dir + "/chats.db"

        // 协议客户端（失败直接带错误页——阶段 1 用 fatalError 级别提示）
        // 代理：与 Android 同名配置 wa_proxy（设置页后续接入，模拟器验证用 defaults write 写入）
        // 国内网络必须走代理才能连上 WhatsApp 服务器（mobile.go NewClient 注释同）
        let proxy = UserDefaults.standard.string(forKey: "wa_proxy") ?? ""
        var err: NSError?
        guard let c = MobileNewClient(dir, proxy, &err) else {
            fatalError("协议层初始化失败: \(err?.localizedDescription ?? "未知")")
        }
        self.client = c

        do {
            db = try ChatDatabase(path: dbPath)
        } catch {
            fatalError("本地库初始化失败: \(error)")
        }

        // 翻译管线：与翻译工具形态共用一套 WeiFanCore 装配（凭据来自钥匙串）
        core = TranslationCore.make(credentials: Keychain.credentials())

        // UI 测试开关：launch argument 模拟已配对态（真实配对需要手机扫码）
        if ProcessInfo.processInfo.arguments.contains("UITEST_FAKE_PAIRED") {
            paired = true
        } else {
            paired = client.isPaired()
        }

        // ---- 挂回调 ----
        pairHandler.onCode = { _ in }   // 配对码由 PairingView 直接持有 handler 使用
        pairHandler.onError = { [weak self] msg in
            Task { @MainActor in self?.pairError = msg }
        }
        pairHandler.onSuccess = { [weak self] _ in
            Task { @MainActor in
                self?.paired = true
                self?.connectAsync()
            }
        }
        connHandler.onChange = { [weak self] ok, _ in
            Task { @MainActor in self?.connected = ok }
        }
        msgHandler.onMessage = { [weak self] data in
            self?.handleIncoming(data: data)
        }
        receiptHandler.onReceipt = { [weak self] chatJid, type, ids in
            Task { @MainActor in self?.onReadReceipts? (chatJid, type, ids) }
        }

        client.setPairListener(pairHandler)
        client.setConnectionListener(connHandler)
        client.setMessageListener(msgHandler)
        client.setReceiptListener(receiptHandler)
    }

    /// 阶段 1：已配对就静默重连
    func connectAsync() {
        guard paired else { return }
        DispatchQueue.global(qos: .userInitiated).async { [client] in
            // 失败由 ConnectionListener 的 onDisconnected 反馈
            _ = try? client.connect()
        }
    }

    // MARK: - 配对

    @Published var pairError: String? = nil

    /// 发起二维码配对。回调码经 pairHandler.onCode 抛给 UI（用 CoreImage 现场渲染 QR）。
    /// 注意：gobind 回调来自 Go 线程，UI 状态必须回主线程改。
    func startQRPiring(qrSink: @escaping (Data) -> Void) {
        pairHandler.onQR = qrSink
        DispatchQueue.global(qos: .userInitiated).async { [client] in
            do {
                let r = try client.pairWithQR()
            } catch {
                Task { @MainActor in self.pairError = error.localizedDescription }
            }
        }
    }

    // MARK: - 收件

    private struct IncomingMsg: Decodable {
        let chatJid: String
        let senderJid: String
        let fromMe: Bool
        let id: String
        let text: String
        let pushName: String?
        let timestamp: Int64
        let history: Bool?
    }

    nonisolated private func handleIncoming(data: Data) {
        guard let m = try? JSONDecoder().decode(IncomingMsg.self, from: data) else { return }
        Task { @MainActor in
            let row = MessageRow(
                id: m.id, chatJid: m.chatJid, senderJid: m.senderJid, fromMe: m.fromMe,
                text: m.text, original: "", translated: "", translatedLang: "",
                ts: m.timestamp, mediaKind: "", mediaMime: "", mediaName: "",
                mediaSize: 0, mediaPath: "", mediaThumb: "", mediaMeta: "", reaction: "")
            try? db.insertMessage(row)
            try? db.upsertChat(jid: m.chatJid,
                               title: m.pushName ?? "",
                               lastMessage: m.text,
                               lastTs: m.timestamp,
                               incrementUnread: !m.fromMe)
            inboxTick &+= 1
            // 收件自动翻译（历史消息随 historyEngine 语义：阶段 1 全部走默认链）
            if !m.fromMe, !m.text.isEmpty {
                translateIncomingAsync(chatJid: m.chatJid, id: m.id, text: m.text)
            }
        }
    }

    /// 收件：对方语言 → 中文（WeiFanCore，与 Android Engine.translateIncoming 同规格）
    func translateIncomingAsync(chatJid: String, id: String, text: String) {
        Task.detached { [core, db] in
            guard let out = try? await core.translateIncoming(text) else { return }
            guard !out.alreadyChinese, !out.translated.isEmpty else { return }
            try? db.updateTranslation(chatJid: chatJid, id: id,
                                      translated: out.translated,
                                      lang: out.sourceLanguage?.rawValue ?? "")
            await MainActor.run { self.inboxTick &+= 1 }
        }
    }

    /// 发件：中文 → 对方语言；译文上协议，原文存 original
    func sendTextAsync(chatJid: String, text: String,
                       completion: ((String?) -> Void)? = nil) {
        Task.detached { [core, db, client] in
            do {
                let out = try await core.translateForSending(text, contextSample: nil)
                var sendErr: NSError?
                let msgId = client.sendMessage(chatJid, text: out.translated, error: &sendErr)
                if let sendErr { throw sendErr }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: out.translated, original: text, translated: "", translatedLang: "",
                    ts: now, mediaKind: "", mediaMime: "", mediaName: "",
                    mediaSize: 0, mediaPath: "", mediaThumb: "", mediaMeta: "", reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: out.translated,
                                  lastTs: now, incrementUnread: false)
                await MainActor.run {
                    self.inboxTick &+= 1
                    completion?(nil)
                }
            } catch {
                await MainActor.run { completion?(error.localizedDescription) }
            }
        }
    }

    // MARK: - 回执（蓝色 ✓✓）

    @Published var readReceipts: [String: Bool] = [:]
    var onReadReceipts: ((String, String, [String]) -> Void)? = nil

    func bindReadReceipts(_ handler: @escaping (String, String, [String]) -> Void) {
        onReadReceipts = handler
        receiptHandler.onReceipt = { chatJid, type, ids in
            handler(chatJid, type, ids)
        }
    }

    func handleReadReceipts(chatJid: String, type: String, ids: [String]) {
        guard type == "read" else { return }
        for id in ids { readReceipts[id] = true }
    }

    // MARK: - 消息查询（给 View 用）

    func chats() -> [ChatRow] { (try? db.chats()) ?? [] }
    func messages(chatJid: String) -> [MessageRow] { (try? db.messages(chatJid: chatJid)) ?? [] }
    func markRead(chatJid: String) { try? db.markChatRead(jid: chatJid); inboxTick &+= 1 }
}
