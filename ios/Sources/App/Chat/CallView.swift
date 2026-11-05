// 微翻 iOS —— 语音通话（基础版；对齐参考 activity_call 的核心面）
//
// 协议层 meowcaller 已在 iOS framework（startCall/answer/reject/hangup/feedMicPcm +
// onIncomingCall/onCallState/onAudioFrame）。本文件补 UI 与音频引擎：
//   麦克风：AVAudioEngine inputTap 16k mono Int16 → feedMicPcm
//   对端：  onAudioFrame PCM → AVAudioPlayerNode 播放
//   来电：  onIncomingCall → 全屏来电视图（接听/拒接；CallKit 后续接）
// 注意：后台通话需要 UIBackgroundModes audio（project.yml 已加）。

import AVFoundation
import SwiftUI

// MARK: - 通话状态机

@MainActor
final class CallEngine: NSObject, ObservableObject {
    static let shared = CallEngine()

    enum Phase { case idle, outgoing, incoming, active }
    @Published var phase: Phase = .idle
    @Published var peerJid = ""
    @Published var callID = ""
    @Published var isVideo = false
    @Published var seconds = 0
    @Published var muted = false
    @Published var speakerOn = true
    @Published var error: String?
    /// meowcaller 原始相位（1=拨出 2=响铃 3=连接中 4=Active 5=结束）——诊断「卡信令还是卡媒体」
    /// 本端是否进过通话（区分「未接通」与「通话 N 秒」）
    private var wasActive = false
    /// 来电还是拨出
    private var isIncoming = false
    @Published var phaseRaw = 0
    /// 实时音频统计（上行/下行帧）——下行 0 = RTP 没通（网络/UDP/代理问题，非 App 代码）
    @Published var statsLine = "" 

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var ticker: Timer?
    private var statsTimer: Timer?
    private var wired = false

    func wire(client: MobileWeifanClient) {
        guard !wired else { return }
        wired = true
        let handler = CallEventHandler()
        handler.onIncoming = { [weak self] from, cid, video in
            Task { @MainActor in
                guard let self else { return }
                self.isIncoming = true
                self.wasActive = false
                guard self.phase == .idle else {
                    // 通话中又来一通：直接拒接第二通（单路）
                    do { _ = try client.rejectCall(from, callID: cid) } catch { }
                    return
                }
                self.peerJid = from
                self.callID = cid ?? ""
                self.isVideo = video
                self.phase = .incoming
            }
        }
        handler.onState = { [weak self] phase in
            Task { @MainActor in
                guard let self else { return }
                self.phaseRaw = phase
                // meowcaller.CallPhase（session.go iota）：
                // 0=Idle 1=Calling 2=Ringing 3=Connecting 4=Active 5=Ended 6=WaitingRoom
                // （之前的映射把 Ringing 当接通、把 Active 当结束——对方没响铃就进通话计时、
                //   真接通反而立即拆线，是「对方收不到邀请」的表象根源之一）
                switch phase {
                case 4:                       // Active：真正接通才进通话界面/开麦
                    if self.phase != .active { self.activate() }
                case 5:                       // Ended
                    self.teardown()
                default:                      // 1/2/3/6：拨出/响铃/连接中——维持当前状态
                    break
                }
            }
        }
        handler.onTerminated = { [weak self] _, _ in
            Task { @MainActor in self?.teardown() }
        }
        handler.onFrame = { [weak self] pcm in
            self?.playPeerPCM(pcm)
        }
        client.setCallListener(handler)
        client.setAudioListener(handler)
    }

    /// 主叫。未连接 WhatsApp 直接给出明确错误（之前静默失败，用户以为按钮坏了）
    func startCall(client: MobileWeifanClient, jid: String, video: Bool) {
        guard ProtocolCore.shared.connected else {
            error = "未连接 WhatsApp，无法发起通话。请回到会话列表确认顶部横条已消失（已连上）再试。"
            return
        }
        peerJid = jid
        isVideo = video
        isIncoming = false
        wasActive = false
        phase = .outgoing
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var err: NSError?
            let cid = client.startCall(jid, video: video, error: &err)
            DispatchQueue.main.async {
                self?.callID = cid ?? ""
                if let err {
                    self?.error = "通话发起失败：\(err.localizedDescription)"
                    self?.teardown()
                }
            }
        }
    }

    func accept(client: MobileWeifanClient) {
        do {
            _ = try client.answerCall()
        } catch {
            self.error = error.localizedDescription; teardown(); return
        }
        activate()
    }

    func setMuted(_ m: Bool, client: MobileWeifanClient) {
        // 静音 = 停止向协议喂数据（isVoiceProcessingEnabled 只读，不能开关）
        muted = m
    }

    // MARK: 内部

    private func activate() {
        phase = .active
        wasActive = true
        seconds = 0
        startStatsPolling()
        startMic()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.seconds += 1 }
        }
    }

    /// 每 2s 拉一次 CallStats：上行帧在涨=我方在发；下行 0=对方音频没进来
    private func startStatsPolling() {
        statsTimer?.invalidate()
        let client = ProtocolCore.shared.client
        statsTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            let raw = client.callStats()
            guard let d = raw.data(using: .utf8),
                  let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                  let tx = o["txFrames"] as? Int, let rx = o["rxFrames"] as? Int else { return }
            DispatchQueue.main.async {
                self?.statsLine = "上行 \(tx) 帧 · 下行 \(rx) 帧"
            }
        }
    }

    /// 拒接（来电）：写「未接来电」记录
    func reject(client: MobileWeifanClient) {
        do { _ = try client.rejectCall(peerJid, callID: callID) } catch { }
        wasActive = false
        teardown()
    }

    func hangup(client: MobileWeifanClient) {
        do { _ = try client.hangupCall() } catch { }
        teardown()
    }

    /// 通话落库（对齐 WhatsApp：会话里留通话记录）
    private func recordCallLog() {
        guard !peerJid.isEmpty else { return }
        let dir: String
        let label: String
        if isIncoming {
            if wasActive {
                dir = "in"
                label = "语音通话（来电）"
            } else {
                dir = "missed"
                label = "未接来电"
            }
        } else {
            dir = "out"
            label = wasActive ? "语音通话（拨出）" : "语音通话（未接通）"
        }
        var kind = "voice"
        var labelText = label
        if isVideo {
            kind = "video"
            labelText = label.replacingOccurrences(of: "语音", with: "视频")
        }
        let meta: [String: Any] = ["dir": dir, "kind": kind,
                                   "duration": wasActive ? seconds : 0]
        let row = MessageRow(
            id: "call-\(callID.isEmpty ? UUID().uuidString : callID)",
            chatJid: peerJid, senderJid: "", fromMe: !isIncoming,
            text: wasActive ? "\(labelText) · 时长 \(CallOverlayView.dur(seconds))" : labelText,
            original: "", translated: "", translatedLang: "",
            ts: Int64(Date().timeIntervalSince1970 * 1000),
            mediaKind: "call", mediaMime: "", mediaName: "", mediaSize: 0,
            mediaPath: "", mediaThumb: "",
            mediaMeta: String(data: (try? JSONSerialization.data(withJSONObject: meta)) ?? Data(), encoding: .utf8) ?? "",
            reaction: "")
        let db = ProtocolCore.shared.db
        let chatJid = peerJid
        DispatchQueue.global(qos: .utility).async {
            try? db.insertMessage(row)
            try? db.upsertChat(jid: chatJid, title: "", lastMessage: row.text,
                               lastTs: row.ts, incrementUnread: false)
        }
        peerJid = ""; callID = ""
    }

    func teardown() {
        ticker?.invalidate(); ticker = nil
        statsTimer?.invalidate(); statsTimer = nil
        statsLine = ""
        phaseRaw = 0
        recordCallLog()
        audioEngine?.stop()
        audioEngine = nil
        playerNode?.stop(); playerNode = nil
        phase = .idle
        peerJid = ""; callID = ""; seconds = 0; muted = false
        // error 保留：由界面 alert 展示后手动清除
    }

    func clearError() { error = nil }

    private func startMic() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try? session.setPreferredSampleRate(16000)
        try? session.setPreferredIOBufferDuration(0.02)
        try? session.setActive(true)
        try? session.overrideOutputAudioPort(speakerOn ? .speaker : .none)

        let engine = AVAudioEngine()
        audioEngine = engine
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        let client = ProtocolCore.shared.client
        unowned let weakSelf = self
        engine.inputNode.installTap(onBus: 0, bufferSize: 960, format: format) { buffer, _ in
            // 静音时停止喂数据（对端就听不到）
            if weakSelf.muted { return }
            guard let pcm = Self.toPCM16kMono(buffer) else { return }
            client.feedMicPcm(pcm)
        }
        engine.prepare()
        do { try engine.start() } catch {
            self.error = "麦克风启动失败: \(error.localizedDescription)"
        }
    }

    private func playPeerPCM(_ data: Data) {
        // 对端 16k mono Int16 PCM → 播放节点
        if playerNode == nil {
            let engine = audioEngine ?? AVAudioEngine()
            if audioEngine == nil { audioEngine = engine }
            let node = AVAudioPlayerNode()
            engine.attach(node)
            let fmt = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000,
                                    channels: 1, interleaved: true)
            engine.connect(node, to: engine.mainMixerNode, format: fmt)
            try? engine.start()
            node.play()
            playerNode = node
        }
        guard let node = playerNode,
              let fmt = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000,
                                      channels: 1, interleaved: true) else { return }
        guard let buf = AVAudioPCMBuffer(pcmFormat: fmt,
                                         frameCapacity: AVAudioFrameCount(data.count / 2)) else { return }
        buf.frameLength = AVAudioFrameCount(data.count / 2)
        data.withUnsafeBytes { raw in
            // int16ChannelData 是二级指针：pointee 才是缓冲区本体
            if let dst = buf.int16ChannelData?.pointee {
                dst.update(from: raw.bindMemory(to: Int16.self).baseAddress!,
                           count: data.count / 2)
            }
        }
        node.scheduleBuffer(buf)
    }

    /// 任意输入格式 → 16k mono Int16 Data（浮点转整型，重采样交给会话首选采样率）
    static func toPCM16kMono(_ buffer: AVAudioPCMBuffer) -> Data? {
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return nil }
        if let i16 = buffer.int16ChannelData {
            return Data(bytes: i16, count: frames * 2)
        }
        guard let f32 = buffer.floatChannelData else { return nil }
        var out = Data(capacity: frames * 2)
        for i in 0..<frames {
            var v = f32.pointee[i]
            v = max(-1, min(1, v))
            var s = Int16(v * 32767).littleEndian
            withUnsafeBytes(of: &s) { out.append(contentsOf: $0) }
        }
        return out
    }
}

/// gobind 回调（NSObject + Protocol，注意走协议形态）
final class CallEventHandler: NSObject, MobileCallListenerProtocol, MobileAudioListenerProtocol {
    var onIncoming: ((String, String?, Bool) -> Void)?
    var onState: ((Int) -> Void)?
    var onTerminated: ((String?, String?) -> Void)?
    var onFrame: ((Data) -> Void)?

    func onIncomingCall(_ from: String?, callID: String?, video: Bool, timestampMs: Int64) {
        if let from { onIncoming?(from, callID, video) }
    }
    func onCallState(_ phase: Int) { onState?(phase) }
    func onCallTerminated(_ callID: String?, reason: String?) { onTerminated?(callID, reason) }
    func onAudioFrame(_ data: Data?) { if let data { onFrame?(data) } }
}

// MARK: - 通话 UI（来电全屏 + 通话中；亮清晰版，状态/统计实时）

struct CallOverlayView: View {
    @ObservedObject var engine = CallEngine.shared
    let client: MobileWeifanClient
    let peerTitle: (String) -> String

    var body: some View {
        ZStack {
            // 深灰渐变底（纯黑太黑看不清）
            LinearGradient(colors: [Color(hex: 0x24343C), Color(hex: 0x121A1F)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: 30) {
                VStack(spacing: 10) {
                    ZStack {
                        Circle().fill(Color.white.opacity(0.14))
                            .frame(width: 96, height: 96)
                        Text(String(peerTitle(engine.peerJid).first ?? "#"))
                            .font(.system(size: 40, weight: .semibold)).foregroundStyle(.white)
                    }
                    Text(peerTitle(engine.peerJid))
                        .font(.title2.bold()).foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                    Text(engine.isVideo ? "视频通话" : "语音通话")
                        .font(.footnote).foregroundStyle(.white.opacity(0.55))
                }

                switch engine.phase {
                case .idle:
                    EmptyView()
                case .incoming:
                    Text("来电 · \(engine.isVideo ? "视频" : "语音")")
                        .font(.title3).foregroundStyle(.white.opacity(0.85))
                    HStack(spacing: 64) {
                        callButton(icon: "phone.down.fill", label: "拒接", color: .red) {
                            engine.reject(client: client)
                        }
                        callButton(icon: "phone.fill", label: "接听", color: .green) {
                            engine.accept(client: client)
                        }
                    }
                case .outgoing:
                    Text(statusText).font(.title3).foregroundStyle(.white.opacity(0.85))
                    if !engine.statsLine.isEmpty {
                        Text(engine.statsLine).font(.caption.monospaced())
                            .foregroundStyle(.white.opacity(0.5))
                    }
                    callButton(icon: "phone.down.fill", label: "挂断", color: .red) {
                        engine.hangup(client: client)
                    }
                case .active:
                    Text(CallOverlayView.dur(engine.seconds))
                        .font(.system(size: 44, weight: .light, design: .monospaced))
                        .foregroundStyle(.white)
                    if !engine.statsLine.isEmpty {
                        Text(engine.statsLine).font(.caption.monospaced())
                            .foregroundStyle(.white.opacity(0.5))
                    }
                    HStack(spacing: 40) {
                        callButton(icon: engine.muted ? "mic.slash.fill" : "mic.fill",
                                   label: engine.muted ? "已静音" : "静音",
                                   color: engine.muted ? .red : Color.white.opacity(0.18),
                                   iconColor: engine.muted ? .white : .white) {
                            engine.setMuted(!engine.muted, client: client)
                        }
                        callButton(icon: "phone.down.fill", label: "挂断", color: .red) {
                            engine.hangup(client: client)
                        }
                        callButton(icon: engine.speakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                                   label: "扬声器",
                                   color: Color.white.opacity(0.18)) {
                            engine.speakerOn.toggle()
                        }
                    }
                }
                if let e = engine.error {
                    Text(e).font(.footnote).foregroundStyle(.yellow.opacity(0.9))
                        .multilineTextAlignment(.center).padding(.horizontal, 24)
                }
            }
        }
    }

    /// 拨出中的细分状态（meowcaller 相位）：卡在哪一眼可见
    private var statusText: String {
        switch engine.phaseRaw {
        case 2: return "正在响铃，等待对方接听…"
        case 3: return "接通中，建立媒体通道…"
        default: return "呼叫中…"
        }
    }

    private func callButton(icon: String, label: String, color: Color,
                            iconColor: Color = .white, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(iconColor)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(color))
            }
            Text(label).font(.caption).foregroundStyle(.white.opacity(0.75))
        }
    }

    static func dur(_ s: Int) -> String {
        String(format: "%02d:%02d", s / 60, s % 60)
    }
}
