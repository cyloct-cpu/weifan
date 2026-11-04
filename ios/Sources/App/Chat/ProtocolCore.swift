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
        if let jid {
            DispatchQueue.main.async {
                self.onSuccess?(jid)
                ProtocolCore.shared.qrInFlight = false
            }
        }
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
    // 群元数据事件（groupInfo/kicked 等，Go onGroupInfo 转发）
    var onEvent: ((Data) -> Void)?

    func onMessage(_ json: String?) {
        guard let json, let data = json.data(using: .utf8) else { return }
        onMessage?(data)
    }
    func onHistoryDone() {
        onHistoryDoneCb?()
    }
    func onMessageEvent(_ json: String?) {
        guard let json, let data = json.data(using: .utf8) else { return }
        onEvent?(data)
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
        msgHandler.onEvent = { data in
            Task { @MainActor in self.onGroupEvent?(data) }
        }
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
    /// 群事件（kicked 等）出口；ChatView/列表页 bind
    var onGroupEvent: ((Data) -> Void)? = nil
    func bindKicked(_ handler: @escaping (String) -> Void) {
        onGroupEvent = { data in
            KickedAlert.handle(eventJSON: data) { _, group in handler(group) }
        }
    }

    /// 发起二维码配对。回调码经 pairHandler.onCode 抛给 UI（用 CoreImage 现场渲染 QR）。
    /// 注意：gobind 回调来自 Go 线程，UI 状态必须回主线程改。
    var qrInFlight = false
    func startQRPiring(qrSink: @escaping (Data) -> Void) {
        // 配对页 onAppear 会反复触发；Go 侧 PairWithQR 并发重入会双路消费同一 qrChan
        guard !qrInFlight else { return }
        qrInFlight = true
        pairHandler.onQR = qrSink
        DispatchQueue.global(qos: .userInitiated).async { [weak self, client] in
            do {
                _ = try client.pairWithQR()
            } catch {
                Task { @MainActor in
                    self?.pairError = error.localizedDescription
                    self?.qrInFlight = false
                }
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
        // 阶段 2：媒体字段（与 Go messageJSON 一一对应）
        let mediaKind: String?
        let mimeType: String?
        let fileName: String?
        let fileLength: Int64?
        let mediaKey: String?
        let fileSha256: String?
        let fileEncSha256: String?
        let url: String?
        let directPath: String?
        let thumbnail: String?
        let seconds: Int?
        let waveform: String?
    }

    nonisolated private func handleIncoming(data: Data) {
        guard let m = try? JSONDecoder().decode(IncomingMsg.self, from: data) else { return }
        Task { @MainActor in
            // 媒体字段打包进 mediaMeta（下载/缩略图/时长都从这里取）
            var metaJSON = ""
            var mediaKind = m.mediaKind ?? ""
            if mediaKind.isEmpty, m.mimeType?.isEmpty == false { mediaKind = "document" }
            if mediaKind.isEmpty == false {
                let meta = MediaMeta(url: m.url, directPath: m.directPath,
                                     mediaKey: m.mediaKey,
                                     fileSha256: m.fileSha256, fileEncSha256: m.fileEncSha256,
                                     fileLength: m.fileLength, thumbnail: m.thumbnail,
                                     seconds: m.seconds, waveform: m.waveform)
                metaJSON = String(data: (try? JSONEncoder().encode(meta)) ?? Data(), encoding: .utf8) ?? ""
            }
            let row = MessageRow(
                id: m.id, chatJid: m.chatJid, senderJid: m.senderJid, fromMe: m.fromMe,
                text: m.text, original: "", translated: "", translatedLang: "",
                ts: m.timestamp, mediaKind: mediaKind, mediaMime: m.mimeType ?? "",
                mediaName: m.fileName ?? "", mediaSize: m.fileLength ?? 0,
                mediaPath: "", mediaThumb: "", mediaMeta: metaJSON, reaction: "")
            try? db.insertMessage(row)
            try? db.upsertChat(jid: m.chatJid,
                               title: m.pushName ?? "",
                               lastMessage: m.text,
                               lastTs: m.timestamp,
                               incrementUnread: !m.fromMe)
            inboxTick &+= 1
            // 收件自动翻译（媒体消息没有文本可翻；开关在聊天设置，与 Android 同名键）
            let autoOn = UserDefaults.standard.object(forKey: "wa_auto_translate_incoming") as? Bool ?? true
            if autoOn, !m.fromMe, !m.text.isEmpty {
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

    /// 发媒体：协议发送成功后本地落一条（mediaMeta 带本地路径，气泡直接读文件）
    func sendMediaAsync(chatJid: String, kind: String, localURL: URL,
                        caption: String? = nil, ptt: Bool = false, seconds: Int64 = 0,
                        completion: ((String?) -> Void)? = nil) {
        let client = self.client
        Task.detached { [db] in
            do {
                guard let msgId = try MediaStore.sendFile(client: client, chatJid: chatJid, kind: kind,
                                                          localURL: localURL, caption: caption,
                                                          ptt: ptt, seconds: seconds) else {
                    throw NSError(domain: "weifan", code: 4,
                                  userInfo: [NSLocalizedDescriptionKey: "媒体发送返回空"])
                }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                var mime = ""
                var meta = MediaMeta()
                if kind == "image" { mime = localURL.pathExtension.lowercased() == "png" ? "image/png" : "image/jpeg" }
                if kind == "audio" { mime = "audio/mp4"; meta.seconds = Int(seconds) }
                if kind == "document" { mime = "application/octet-stream" }
                meta.name = localURL.lastPathComponent
                let metaJSON = String(data: (try? JSONEncoder().encode(meta)) ?? Data(), encoding: .utf8) ?? ""
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: caption ?? "", original: "", translated: "", translatedLang: "",
                    ts: now, mediaKind: kind, mediaMime: mime,
                    mediaName: localURL.lastPathComponent,
                    mediaSize: Int64((try? Data(contentsOf: localURL))?.count ?? 0),
                    mediaPath: localURL.path, mediaThumb: "", mediaMeta: metaJSON, reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: "[\(kind)]",
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

    nonisolated private func replyMeta(_ quote: MessageRow) -> String {
        let o: [String: Any] = ["replyTo": quote.id, "replyText": quote.text]
        return String(data: (try? JSONSerialization.data(withJSONObject: o)) ?? Data(), encoding: .utf8) ?? ""
    }

    /// 引用回复：quote 参数取自被引消息（sendReply 协议）
    func sendReplyAsync(chatJid: String, quote: MessageRow, body: String,
                        completion: ((String?) -> Void)? = nil) {
        let client = self.client
        Task.detached { [db] in
            do {
                var err: NSError?
                let msgId = client.sendReply(chatJid, quoteID: quote.id,
                                             quoteParticipant: quote.senderJid,
                                             quoteText: quote.text,
                                             body: body, error: &err)
                if let err { throw err }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: body, original: "", translated: "", translatedLang: "",
                    ts: now, mediaKind: "", mediaMime: "", mediaName: "",
                    mediaSize: 0, mediaPath: "", mediaThumb: "",
                    mediaMeta: self.replyMeta(quote),
                    reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: body,
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

    /// P7：链接预览缓存（内存级；og 抓取在 Media.swift LinkPreview）
    static var linkPreviewCache: [String: LinkPreview] = [:]

    /// 转发：文本 sendForwardText；收件媒体 SendForwardMedia（存档元数据重建）；发件媒体重新上传发送
    func forwardAsync(chatJid: String, m: MessageRow, completion: ((String?) -> Void)? = nil) {
        if m.mediaKind.isEmpty {
            sendForwardAsync(chatJid: chatJid, m: m, completion: completion)
            return
        }
        let client = self.client
        Task.detached { [weak self, db] in
            do {
                var err: NSError?
                var msgId: String?
                if m.fromMe, !m.mediaPath.isEmpty {
                    // 发件媒体：本机文件重发（等价转发，无 IsForwarded 标）
                    msgId = try MediaStore.sendFile(client: client, chatJid: chatJid,
                                                    kind: m.mediaKind, localURL: URL(fileURLWithPath: m.mediaPath))
                } else {
                    // 收件媒体：下载元数据重建（SendForwardMedia 原生支持 image/video/audio/document/sticker）
                    guard let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)) else {
                        throw NSError(domain: "weifan", code: 6,
                                      userInfo: [NSLocalizedDescriptionKey: "无媒体元数据，无法转发"])
                    }
                    let params: [String: Any] = [
                        "kind": m.mediaKind, "url": meta.url ?? "", "directPath": meta.directPath ?? "",
                        "mediaKeyB64": meta.mediaKey ?? "", "shaB64": meta.fileSha256 ?? "",
                        "encShaB64": meta.fileEncSha256 ?? "", "mime": m.mediaMime,
                        "fileName": m.mediaName, "fileLength": meta.fileLength ?? 0]
                    let json = String(data: (try? JSONSerialization.data(withJSONObject: params)) ?? Data(), encoding: .utf8) ?? "{}"
                    msgId = client.sendForwardMedia(chatJid, paramsJSON: json, error: &err)
                    if let err { throw err }
                }
                guard let msgId else { return }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: m.text, original: "", translated: "", translatedLang: "",
                    ts: now, mediaKind: m.mediaKind, mediaMime: m.mediaMime,
                    mediaName: m.mediaName, mediaSize: 0, mediaPath: "", mediaThumb: "",
                    mediaMeta: m.mediaMeta, reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: "[转发]",
                                  lastTs: now, incrementUnread: false)
                await MainActor.run {
                    self?.inboxTick &+= 1
                    completion?(nil)
                }
            } catch {
                await MainActor.run { completion?(error.localizedDescription) }
            }
        }
    }

    /// P9：贴纸发送（协议 SendStickerFromPath）+ 本地落库
    func sendStickerAsync(chatJid: String, localURL: URL, completion: ((String?) -> Void)? = nil) {
        let client = self.client
        Task.detached { [weak self, db] in
            do {
                var err: NSError?
                let msgId = client.sendSticker(fromPath: chatJid, path: localURL.path, error: &err)
                if let err { throw err }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: "", original: "", translated: "", translatedLang: "",
                    ts: now, mediaKind: "sticker", mediaMime: "image/webp",
                    mediaName: localURL.lastPathComponent, mediaSize: Int64((try? Data(contentsOf: localURL))?.count ?? 0),
                    mediaPath: localURL.path, mediaThumb: "", mediaMeta: "", reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: "[贴纸]",
                                  lastTs: now, incrementUnread: false)
                await MainActor.run {
                    self?.inboxTick &+= 1
                    completion?(nil)
                }
            } catch {
                await MainActor.run { completion?(error.localizedDescription) }
            }
        }
    }

    /// 转发：文本走 sendForwardText；媒体转发需要 paramsJSON（源消息媒体字段），阶段 2 先支持文本
    func sendForwardAsync(chatJid: String, m: MessageRow, completion: ((String?) -> Void)? = nil) {
        let client = self.client
        Task.detached { [db] in
            do {
                var err: NSError?
                let msgId = client.sendForwardText(chatJid, text: m.text, error: &err)
                if let err { throw err }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: m.text, original: "", translated: "", translatedLang: "",
                    ts: now, mediaKind: "", mediaMime: "", mediaName: "",
                    mediaSize: 0, mediaPath: "", mediaThumb: "", mediaMeta: "", reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: m.text,
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

    /// 编辑自己的文本消息：协议 editTextMessage + 本地同步
    func updateLocalEditText(chatJid: String, id: String, text: String) {
        try? db.exec("UPDATE messages SET text = ?, original = text WHERE chat_jid = ? AND id = ?",
                     args: [text, chatJid, id])
        inboxTick &+= 1
    }

    /// 撤回自己的消息：本地标记（气泡渲染为「已撤回」由 UI 层处理——阶段 2 先置空文本）
    func markRevoked(chatJid: String, id: String) {
        try? db.exec("UPDATE messages SET text = '你撤回了一条消息', media_kind = '', media_meta = '' WHERE chat_jid = ? AND id = ?",
                     args: [chatJid, id])
        inboxTick &+= 1
    }

    /// B6 对应：重新翻译（清空译文 → 重新走收件链）
    func retranslate(_ m: MessageRow) {
        try? db.updateTranslation(chatJid: m.chatJid, id: m.id, translated: "", lang: "")
        translateIncomingAsync(chatJid: m.chatJid, id: m.id, text: m.text)
        inboxTick &+= 1
    }

    /// 投票/位置/活动发送后本地落一条（本人消息即时可见，与 Android 同构）
    func sendCardAsync(chatJid: String, kind: String, mediaName: String,
                       meta: MediaMeta, text: String = "",
                       send: @escaping (inout NSError?) -> String?) {
        let client = self.client
        Task.detached { [weak self, db] in
            do {
                var err: NSError?
                let msgId = send(&err)
                if let err { throw err }
                guard let msgId else { return }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                let metaJSON = String(data: (try? JSONEncoder().encode(meta)) ?? Data(), encoding: .utf8) ?? ""
                try db.insertMessage(MessageRow(
                    id: msgId, chatJid: chatJid, senderJid: "", fromMe: true,
                    text: text, original: "", translated: "", translatedLang: "",
                    ts: now, mediaKind: kind, mediaMime: "",
                    mediaName: mediaName, mediaSize: 0, mediaPath: "", mediaThumb: "",
                    mediaMeta: metaJSON, reaction: ""))
                try db.upsertChat(jid: chatJid, title: "", lastMessage: "[\(kind)]",
                                  lastTs: now, incrementUnread: false)
                await MainActor.run {
                    self?.inboxTick &+= 1
                    if let e = err { self?.pairError = e.localizedDescription }
                }
            } catch {
                await MainActor.run { self?.pairError = error.localizedDescription }
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
