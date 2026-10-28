import UIKit
import UniformTypeIdentifiers
import WeiFanCore

/// 分享扩展：在 WhatsApp 里长按对方消息 → 分享 → 微翻 → 直接看到中文译文。
///
/// 这是 iOS 上"收件方向"的主路径。自定义键盘读不到聊天内容，
/// 而分享扩展能合法拿到用户主动分享出来的那一条文本（或语音文件）。
final class ShareViewController: UIViewController {

    private let card = UIView()
    private let originalLabel = UILabel()
    private let translatedLabel = UILabel()
    private let statusLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let copyButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)

    private var originalText: String = ""
    private var translatedText: String = ""

    override func viewDidLoad() {
        super.viewDidLoad()
        buildUI()
        extractSharedContent()
    }

    // MARK: UI

    private func buildUI() {
        view.backgroundColor = UIColor.black.withAlphaComponent(0.35)

        card.backgroundColor = .secondarySystemBackground
        card.layer.cornerRadius = 18
        card.translatesAutoresizingMaskIntoConstraints = false

        let title = UILabel()
        title.text = "微翻"
        title.font = .systemFont(ofSize: 17, weight: .semibold)

        originalLabel.font = .systemFont(ofSize: 13)
        originalLabel.textColor = .secondaryLabel
        originalLabel.numberOfLines = 4

        translatedLabel.font = .systemFont(ofSize: 18)
        translatedLabel.textColor = .label
        translatedLabel.numberOfLines = 0
        translatedLabel.text = "…"

        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 2

        copyButton.setTitle("复制译文", for: .normal)
        copyButton.addTarget(self, action: #selector(didTapCopy), for: .touchUpInside)

        closeButton.setTitle("关闭", for: .normal)
        closeButton.addTarget(self, action: #selector(didTapClose), for: .touchUpInside)

        let buttons = UIStackView(arrangedSubviews: [copyButton, closeButton])
        buttons.axis = .horizontal
        buttons.distribution = .fillEqually
        buttons.spacing = 8

        let stack = UIStackView(arrangedSubviews: [title, originalLabel, translatedLabel, statusLabel, buttons])
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false

        card.addSubview(stack)
        card.addSubview(spinner)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            card.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),

            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -18),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 18),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -18),

            spinner.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: card.centerYAnchor)
        ])
    }

    // MARK: 取内容

    private func extractSharedContent() {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            finish(with: "没有拿到内容")
            return
        }
        let providers = items.compactMap(\.attachments).flatMap { $0 }
        guard !providers.isEmpty else {
            finish(with: "没有拿到内容")
            return
        }

        spinner.startAnimating()

        // 优先文本，其次音频（WhatsApp 语音消息分享过来通常是 m4a/opus）
        if let textProvider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) }) {
            loadText(from: textProvider)
        } else if let audioProvider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.audio.identifier) }) {
            loadAudio(from: audioProvider)
        } else if let urlProvider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.url.identifier) }) {
            urlProvider.loadItem(forTypeIdentifier: UTType.url.identifier) { [weak self] item, _ in
                let text = (item as? URL)?.absoluteString ?? ""
                DispatchQueue.main.async { self?.translateIncoming(text) }
            }
        } else {
            finish(with: "这个内容类型还不支持（目前支持文本与语音）")
        }
    }

    private func loadText(from provider: NSItemProvider) {
        provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) { [weak self] item, error in
            guard let self else { return }
            let text: String
            if let string = item as? String {
                text = string
            } else if let attributed = item as? NSAttributedString {
                text = attributed.string
            } else if let data = item as? Data, let string = String(data: data, encoding: .utf8) {
                text = string
            } else {
                text = ""
            }
            DispatchQueue.main.async {
                guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    self.finish(with: error?.localizedDescription ?? "内容为空")
                    return
                }
                self.translateIncoming(text)
            }
        }
    }

    private func loadAudio(from provider: NSItemProvider) {
        provider.loadFileRepresentation(forTypeIdentifier: UTType.audio.identifier) { [weak self] url, error in
            guard let self else { return }
            guard let url else {
                DispatchQueue.main.async { self.finish(with: error?.localizedDescription ?? "无法读取语音文件") }
                return
            }
            // 系统给的临时 URL 在回调返回后就失效，必须先拷到自己的临时目录
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(url.pathExtension.isEmpty ? "m4a" : url.pathExtension)
            do {
                try FileManager.default.copyItem(at: url, to: destination)
            } catch {
                DispatchQueue.main.async { self.finish(with: "语音文件拷贝失败：\(error.localizedDescription)") }
                return
            }
            DispatchQueue.main.async {
                self.statusLabel.text = "正在转写语音…"
                self.translateVoice(at: destination)
            }
        }
    }

    // MARK: 翻译

    private func translateIncoming(_ text: String) {
        originalText = text
        originalLabel.text = text
        statusLabel.text = "翻译中…"
        spinner.startAnimating()

        let core = TranslationCore.make(credentials: Keychain.credentials())
        Task { @MainActor in
            do {
                let outcome = try await core.translateIncoming(text)
                self.spinner.stopAnimating()
                self.translatedText = outcome.translated
                self.translatedLabel.text = outcome.translated
                self.statusLabel.text = outcome.alreadyChinese
                    ? "原文已是中文"
                    : "\(outcome.sourceLanguage?.displayName ?? "未知语言") → 简体中文 · \(outcome.provider)"
                self.autoCopyIfNeeded()
            } catch {
                self.finish(with: error.localizedDescription)
            }
        }
    }

    private func translateVoice(at url: URL) {
        let core = TranslationCore.make(credentials: Keychain.credentials())
        Task { @MainActor in
            do {
                let outcome = try await core.translateVoice(audioURL: url)
                self.spinner.stopAnimating()
                self.translatedText = outcome.translated
                self.translatedLabel.text = outcome.translated
                self.statusLabel.text = "语音转写 · \(outcome.provider)"
                self.autoCopyIfNeeded()
            } catch {
                self.finish(with: error.localizedDescription)
            }
        }
    }

    private func autoCopyIfNeeded() {
        let settings = UserDefaultsSettingsStore().load()
        if settings.copyToClipboardOnShare, !translatedText.isEmpty {
            UIPasteboard.general.string = translatedText
            statusLabel.text = (statusLabel.text ?? "") + " · 已复制"
        }
    }

    // MARK: 动作

    @objc private func didTapCopy() {
        guard !translatedText.isEmpty else { return }
        UIPasteboard.general.string = translatedText
        copyButton.setTitle("已复制", for: .normal)
    }

    @objc private func didTapClose() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    private func finish(with message: String) {
        spinner.stopAnimating()
        translatedLabel.text = "—"
        statusLabel.text = message
        statusLabel.textColor = .systemRed
    }
}
