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

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var ticker: Timer?
    private var wired = false

    func wire(client: MobileWeifanClient) {
        guard !wired else { return }
        wired = true
        let handler = CallEventHandler()
        handler.onIncoming = { [weak self] from, cid, video in
            Task { @MainActor in
                guard let self else { return }
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
                // phase: Go 侧 callState 枚举（ ringing/accepted/established/ended…）
                if phase == 4 || phase == 5 || phase == 6 {  // ended 系
                    self.teardown()
                } else if phase == 2 || phase == 3 {          // 接通
                    if self.phase != .active { self.activate() }
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

    func reject(client: MobileWeifanClient) {
        do { _ = try client.rejectCall(peerJid, callID: callID) } catch { }
        teardown()
    }

    func hangup(client: MobileWeifanClient) {
        do { _ = try client.hangupCall() } catch { }
        teardown()
    }

    func setMuted(_ m: Bool, client: MobileWeifanClient) {
        // 静音 = 停止向协议喂数据（isVoiceProcessingEnabled 只读，不能开关）
        muted = m
    }

    // MARK: 内部

    private func activate() {
        phase = .active
        seconds = 0
        startMic()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.seconds += 1 }
        }
    }

    func teardown() {
        ticker?.invalidate(); ticker = nil
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

// MARK: - 通话 UI（来电全屏 + 通话中）

struct CallOverlayView: View {
    @ObservedObject var engine = CallEngine.shared
    let client: MobileWeifanClient
    let peerTitle: (String) -> String

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 28) {
                Text(peerTitle(engine.peerJid))
                    .font(.title2).foregroundStyle(.white)
                switch engine.phase {
                case .idle: EmptyView()
                case .incoming:
                    Text("来电 · \(engine.isVideo ? "视频" : "语音")").foregroundStyle(.secondary)
                    HStack(spacing: 60) {
                        Button { engine.reject(client: client) } label: {
                            Image(systemName: "phone.down.fill").font(.title)
                                .frame(width: 80, height: 80).background(.red).clipShape(Circle())
                        }
                        Button { engine.accept(client: client) } label: {
                            Image(systemName: "phone.fill").font(.title)
                                .frame(width: 80, height: 80).background(.green).clipShape(Circle())
                        }
                    }
                case .outgoing:
                    Text("呼叫中…").foregroundStyle(.secondary)
                    hangupButton
                case .active:
                    Text(Self.dur(engine.seconds)).font(.title3.monospaced()).foregroundStyle(.secondary)
                    HStack(spacing: 24) {
                        Button { engine.setMuted(!engine.muted, client: client) } label: {
                            Image(systemName: engine.muted ? "mic.slash.fill" : "mic.fill")
                                .frame(width: 64, height: 64)
                                .background(engine.muted ? Color.red.opacity(0.8) : Color.white.opacity(0.2))
                                .clipShape(Circle())
                        }
                        hangupButton
                        Button { engine.speakerOn.toggle() } label: {
                            Image(systemName: engine.speakerOn ? "speaker.wave.3.fill" : "speaker.fill")
                                .frame(width: 64, height: 64)
                                .background(Color.white.opacity(0.2)).clipShape(Circle())
                        }
                    }
                }
                if let e = engine.error { Text(e).font(.footnote).foregroundStyle(.red) }
            }
        }
    }

    private var hangupButton: some View {
        Button { engine.hangup(client: client) } label: {
            Image(systemName: "phone.down.fill").font(.title)
                .frame(width: 80, height: 80).background(.red).clipShape(Circle())
        }
    }

    static func dur(_ s: Int) -> String {
        String(format: "%02d:%02d", s / 60, s % 60)
    }
}
