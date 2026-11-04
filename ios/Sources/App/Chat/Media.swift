// 微翻 iOS —— 媒体收发与查看（阶段 2）
//
// 收件：onMessage JSON 里的媒体字段存进 mediaMeta（JSON），缩略图立即上屏，原文件按需下载落盘。
// 发件：PhotosPicker/fileImporter/AVAudioRecorder 产出本地文件 → 协议层 sendXxxFromPath。
// 查看：图片全屏左右滑（TabView）、视频 AVPlayer、文档 QuickLook。

import Foundation
import AVFoundation
import SwiftUI
import QuickLook

// MARK: - 媒体元数据（与 Go messageJSON 的媒体字段一一对应）

struct MediaMeta: Codable {
    var url: String?
    var directPath: String?
    var mediaKey: String?
    var fileSha256: String?
    var fileEncSha256: String?
    var fileLength: Int64?
    var thumbnail: String?   // base64 JPEG
    var seconds: Int?
    var waveform: String?    // base64
    var lat: Double?
    var lng: Double?
    var name: String?
    var address: String?
    var desc: String?
    var start: Int64?
    var end: Int64?
    var link: String?
    var options: [String]?
    var vcard: String?
    var currency: String?
    var price: Double?
}

enum MediaStore {
    static var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Media", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func localFile(for msgId: String, mime: String) -> URL? {
        let ext: String
        switch mime {
        case "image/jpeg": ext = "jpg"
        case "image/png": ext = "png"
        case "image/webp": ext = "webp"
        case "video/mp4": ext = "mp4"
        case "audio/ogg; codecs=opus", "audio/ogg": ext = "ogg"
        case "audio/mp4", "audio/aac": ext = "m4a"
        default: ext = "bin"
        }
        let url = dir.appendingPathComponent("\(msgId).\(ext)")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// 按需下载：成功落盘后返回 URL（幂等，已存在直接回）
    static func download(client: MobileWeifanClient, m: MessageRow) -> URL? {
        if let u = localFile(for: m.id, mime: m.mediaMime) { return u }
        guard let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
              m.mediaKind != "" else { return nil }
        let data: Data?
        do {
            data = try client.downloadMedia(m.mediaKind, url: meta.url ?? "",
                                            directPath: meta.directPath ?? "",
                                            mediaKeyB64: meta.mediaKey ?? "",
                                            shaB64: meta.fileSha256 ?? "",
                                            encShaB64: meta.fileEncSha256 ?? "",
                                            fileLength: meta.fileLength ?? 0)
        } catch { data = nil }
        guard let data, data.count > 0 else { return nil }
        let mime = m.mediaMime
        let ext: String = mime.contains("jpeg") ? "jpg" : mime.contains("png") ? "png"
            : mime.contains("webp") ? "webp" : mime.contains("mp4") ? "mp4"
            : mime.contains("ogg") ? "ogg" : mime.contains("m4a") || mime.contains("aac") ? "m4a" : "bin"
        let url = dir.appendingPathComponent("\(m.id).\(ext)")
        try? data.write(to: url)
        return url
    }

    static func thumbnail(_ m: MessageRow) -> UIImage? {
        guard let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
              let b64 = meta.thumbnail, let data = Data(base64Encoded: b64) else { return nil }
        return UIImage(data: data)
    }

    static func waveform(_ m: MessageRow) -> [Float] {
        guard let meta = try? JSONDecoder().decode(MediaMeta.self, from: Data(m.mediaMeta.utf8)),
              let b64 = meta.waveform, let data = Data(base64Encoded: b64) else { return [] }
        return data.map { Float($0) / 255.0 }
    }

    /// 发件：把本地文件按 proto 字段推上去（图片/文档/语音共用）
    static func sendFile(client: MobileWeifanClient, chatJid: String,
                         kind: String, localURL: URL,
                         caption: String? = nil, ptt: Bool = false, seconds: Int64 = 0) throws -> String? {
        var err: NSError?
        switch kind {
        case "image":
            return client.sendImage(fromPath: chatJid, path: localURL.path, caption: caption ?? "", error: &err)
        case "document":
            return client.sendDocument(fromPath: chatJid, path: localURL.path,
                                               fileName: localURL.lastPathComponent,
                                               caption: caption ?? "", error: &err)
        case "audio":
            return client.sendAudio(fromPath: chatJid, path: localURL.path, ptt: ptt,
                                            seconds: seconds, error: &err)
        default: return nil
        }
    }
}

// MARK: - 语音录制（AVAudioRecorder，m4a/AAC；参考 Android ptt 四件套的 iOS 对应）

final class VoiceRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published var recording = false
    @Published var seconds = 0
    private var recorder: AVAudioRecorder?
    private var ticker: Timer?
    // 录音目标文件在 begin 时固化；stop 里不能重新计算（时间戳变了会指到不存在的文件）
    private var recordingURL = MediaStore.dir.appendingPathComponent("voice-0.m4a")
    var finished: ((URL, Int) -> Void)?

    func start() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default)
        try? session.setActive(true)
        session.requestRecordPermission { [weak self] ok in
            guard ok else { return }
            DispatchQueue.main.async { self?.begin() }
        }
    }

    private func begin() {
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
        ]
        recordingURL = MediaStore.dir.appendingPathComponent("voice-\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        let r = try? AVAudioRecorder(url: recordingURL, settings: settings)
        guard let r else { return }
        recorder = r
        r.record()
        recording = true
        seconds = 0
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.seconds += 1
        }
    }

    /// stop(send=true) → 回调文件；stop(send=false) → 删文件（上滑取消）
    func stop(send: Bool) {
        guard let r = recorder else { return }
        let dur = seconds
        r.stop()
        ticker?.invalidate(); ticker = nil
        recording = false
        recorder = nil
        let f = recordingURL
        if send, dur >= 1 {
            finished?(f, dur)
        } else {
            try? FileManager.default.removeItem(at: f)
        }
    }
}

// MARK: - 全屏查看器

/// 图片查看器：同一会话的全部图片左右滑（对齐 Android openMediaViewer）
struct ImageViewerView: View {
    let items: [MessageRow]
    @State var index: Int
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, m in
                    if let url = MediaStore.localFile(for: m.id, mime: m.mediaMime),
                       let ui = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: ui)
                            .resizable().scaledToFit()
                            .tag(i)
                    } else if let th = MediaStore.thumbnail(m) {
                        Image(uiImage: th).resizable().scaledToFit().tag(i)
                    }
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .background(Color.black)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                        .foregroundStyle(.white)
                }
            }
            .preferredColorScheme(.dark)
        }
    }
}

/// 文档/视频 QuickLook（视频走 AVPlayer 也可，QuickLook 统一省一套 UI）
struct QuickLookView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> QLPreviewController {
        let c = QLPreviewController()
        c.dataSource = context.coordinator
        return c
    }
    func updateUIViewController(_ c: QLPreviewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}
