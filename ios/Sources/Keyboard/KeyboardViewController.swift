import UIKit
import WeiFanCore

/// 微翻键盘。
///
/// 核心交互（D-02 的落地形态）：
/// 用户在 WhatsApp 输入框里**用系统拼音键盘**把中文打完 → 点地球键切到微翻 →
/// 微翻读取输入框里已经存在的中文（`documentContextBeforeInput`）→ 点「译」→
/// 删除原文、插入译文 → 点 WhatsApp 自己的绿色发送键。
///
/// 为什么不让用户直接在微翻键盘上打中文：iOS 同时只能显示一个键盘，
/// 要在扩展里打中文就得自己实现拼音输入法，那是一个几万行、要维护词库的独立工程。
/// 借系统的输入法 + 切换键盘读上下文，是唯一低成本的可行路径。
final class KeyboardViewController: UIInputViewController {

    // MARK: 状态

    private var core: TranslationCore?
    private var settings = AppSettings()
    private var profiles: [CustomerProfile] = []
    private var currentProfile: CustomerProfile?
    /// 显式选中的目标语言；nil 表示自动
    private var explicitTarget: LanguageCode?
    /// 上一次替换，用于一键撤销
    private var lastSwap: (original: String, translated: String)?
    /// 风险拦截后等待确认的译文
    private var pending: (original: String, translated: String, target: LanguageCode)?

    // MARK: 视图

    private let chipScroll = UIScrollView()
    private let chipStack = UIStackView()
    private let previewLabel = UILabel()
    private let statusLabel = UILabel()
    private let translateButton = UIButton(type: .system)
    private let undoButton = UIButton(type: .system)
    private let profileButton = UIButton(type: .system)
    private let globeButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)

    // MARK: 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        buildUI()
        reloadSettings()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadSettings()
        refreshPreview()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        refreshPreview()
    }

    private func reloadSettings() {
        settings = UserDefaultsSettingsStore().load()
        profiles = UserDefaultsProfileStore().allProfiles()
        core = TranslationCore.make(credentials: Keychain.credentials())
        rebuildChips()
        updateStatusForPermissions()
    }

    private func updateStatusForPermissions() {
        if !hasFullAccess {
            statusLabel.text = "需要「允许完全访问」才能联网翻译：设置 → 通用 → 键盘 → 微翻"
            statusLabel.textColor = .systemOrange
        } else if statusLabel.text?.hasPrefix("需要") == true {
            statusLabel.text = "输入框里是中文时，点「译」"
            statusLabel.textColor = .secondaryLabel
        }
    }

    // MARK: UI

    private func buildUI() {
        view.backgroundColor = UIColor.secondarySystemBackground

        // 语言芯片
        chipScroll.showsHorizontalScrollIndicator = false
        chipScroll.translatesAutoresizingMaskIntoConstraints = false
        chipStack.axis = .horizontal
        chipStack.spacing = 6
        chipStack.translatesAutoresizingMaskIntoConstraints = false
        chipScroll.addSubview(chipStack)

        // 预览
        previewLabel.font = .systemFont(ofSize: 15)
        previewLabel.numberOfLines = 2
        previewLabel.textColor = .label
        previewLabel.text = " "

        // 状态
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 1

        // 按钮
        configure(translateButton, title: "译", image: "character.bubble.fill", prominent: true)
        translateButton.addTarget(self, action: #selector(didTapTranslate), for: .touchUpInside)

        configure(undoButton, title: "撤销", image: "arrow.uturn.backward", prominent: false)
        undoButton.addTarget(self, action: #selector(didTapUndo), for: .touchUpInside)

        configure(profileButton, title: "客户", image: "person.crop.circle", prominent: false)
        profileButton.addTarget(self, action: #selector(didTapProfile), for: .touchUpInside)

        configure(globeButton, title: "切回", image: "globe", prominent: false)
        globeButton.addTarget(self, action: #selector(didTapGlobe), for: .touchUpInside)

        let actions = UIStackView(arrangedSubviews: [translateButton, undoButton, profileButton, globeButton])
        actions.axis = .horizontal
        actions.distribution = .fillEqually
        actions.spacing = 6
        actions.translatesAutoresizingMaskIntoConstraints = false

        let root = UIStackView(arrangedSubviews: [chipScroll, previewLabel, statusLabel, actions])
        root.axis = .vertical
        root.spacing = 6
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        view.addSubview(spinner)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.hidesWhenStopped = true

        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 10),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -10),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 8),
            root.bottomAnchor.constraint(lessThanOrEqualTo: view.bottomAnchor, constant: -8),

            chipScroll.heightAnchor.constraint(equalToConstant: 34),
            chipStack.leadingAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.leadingAnchor),
            chipStack.trailingAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.trailingAnchor),
            chipStack.topAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.topAnchor),
            chipStack.bottomAnchor.constraint(equalTo: chipScroll.contentLayoutGuide.bottomAnchor),
            chipStack.heightAnchor.constraint(equalTo: chipScroll.frameLayoutGuide.heightAnchor),

            previewLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 40),
            actions.heightAnchor.constraint(equalToConstant: 40),

            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),

            view.heightAnchor.constraint(equalToConstant: 216)
        ])
    }

    private func configure(_ button: UIButton, title: String, image: String, prominent: Bool) {
        var config = UIButton.Configuration.filled()
        config.title = title
        config.image = UIImage(systemName: image)
        config.imagePadding = 4
        config.cornerStyle = .medium
        config.baseBackgroundColor = prominent ? .systemBlue : .tertiarySystemFill
        config.baseForegroundColor = prominent ? .white : .label
        config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 13)
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = .systemFont(ofSize: 14, weight: .semibold)
            return out
        }
        button.configuration = config
    }

    private func rebuildChips() {
        chipStack.arrangedSubviews.forEach { $0.removeFromSuperview() }

        let autoChip = makeChip(title: "自动", selected: explicitTarget == nil)
        autoChip.addAction(UIAction { [weak self] _ in
            self?.explicitTarget = nil
            self?.rebuildChips()
            self?.refreshPreview()
        }, for: .touchUpInside)
        chipStack.addArrangedSubview(autoChip)

        for language in settings.pinnedLanguages {
            let chip = makeChip(title: language.shortLabel, selected: explicitTarget == language)
            chip.addAction(UIAction { [weak self] _ in
                guard let self else { return }
                self.explicitTarget = (self.explicitTarget == language) ? nil : language
                self.rebuildChips()
                self.refreshPreview()
            }, for: .touchUpInside)
            chipStack.addArrangedSubview(chip)
        }
    }

    private func makeChip(title: String, selected: Bool) -> UIButton {
        var config = UIButton.Configuration.filled()
        config.title = title
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 5, leading: 12, bottom: 5, trailing: 12)
        config.baseBackgroundColor = selected ? .systemBlue : .tertiarySystemFill
        config.baseForegroundColor = selected ? .white : .label
        let button = UIButton(configuration: config)
        button.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        return button
    }

    // MARK: 预览与状态

    private func refreshPreview() {
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let trimmed = before.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            previewLabel.text = "输入框是空的：先用系统键盘打中文，再切过来"
            previewLabel.textColor = .secondaryLabel
            return
        }
        let detection = HeuristicLanguageDetector().detect(trimmed)
        let hint: String
        if let detection, detection.language.isChinese {
            hint = "检测：中文"
        } else if let detection {
            hint = "检测：\(detection.language.displayName)（不是中文，可能不需要译）"
        } else {
            hint = "检测：无法判断"
        }
        previewLabel.text = "\(trimmed)\n\(hint) · 目标：\(targetDescription())"
        previewLabel.textColor = .label
    }

    private func targetDescription() -> String {
        if let explicitTarget { return explicitTarget.displayName }
        if let language = currentProfile?.language { return "\(language.displayName)（客户档案）" }
        return "\(settings.defaultTargetLanguage.displayName)（兜底）"
    }

    // MARK: 动作

    @objc private func didTapTranslate() {
        // 已经拦下一条等待确认的译文时，这一下就是"确认"
        if let pending {
            apply(original: pending.original, translated: pending.translated)
            self.pending = nil
            return
        }

        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let trimmed = before.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            flash("输入框里没有文字")
            return
        }
        guard let core else {
            flash("内核未就绪")
            return
        }
        guard hasFullAccess else {
            flash("请先允许完全访问")
            return
        }

        spinner.startAnimating()
        flash("翻译中…")

        Task { @MainActor in
            defer { self.spinner.stopAnimating() }
            do {
                let outcome = try await core.translateForSending(before,
                                                                 explicitTarget: self.explicitTarget,
                                                                 profile: self.currentProfile)
                if outcome.requiresPreview {
                    self.pending = (before, outcome.translated, outcome.target)
                    self.previewLabel.text = "\(outcome.translated)\n⚠️ \(outcome.risk.reasons.map(\.displayName).joined(separator: " · "))，再点一次「译」确认"
                    self.previewLabel.textColor = .systemOrange
                    self.flash("需确认")
                } else {
                    self.apply(original: before, translated: outcome.translated)
                }
            } catch {
                self.flash(error.localizedDescription)
            }
        }
    }

    @objc private func didTapUndo() {
        guard let lastSwap else {
            flash("没有可撤销的操作")
            return
        }
        replace(count: lastSwap.translated.count, with: lastSwap.original)
        self.lastSwap = nil
        flash("已还原中文")
        refreshPreview()
    }

    @objc private func didTapProfile() {
        guard !profiles.isEmpty else {
            flash("还没有客户档案，去 App 里加一个")
            return
        }
        // 依次轮换，够用且不打断输入
        if let current = currentProfile,
           let index = profiles.firstIndex(where: { $0.id == current.id }) {
            currentProfile = profiles[(index + 1) % profiles.count]
        } else {
            currentProfile = profiles.first
        }
        flash("客户：\(currentProfile?.displayName ?? "无")")
        refreshPreview()
    }

    @objc private func didTapGlobe() {
        advanceToNextInputMode()
    }

    // MARK: 文本替换

    private func apply(original: String, translated: String) {
        replace(count: original.count, with: translated)
        lastSwap = (original, translated)
        flash("已替换，点 WhatsApp 的绿色发送键")
        pending = nil
        refreshPreview()
    }

    private func replace(count: Int, with text: String) {
        // 防御：极端情况下 documentContextBeforeInput 可能返回整段文本
        let safeCount = min(count, 2000)
        for _ in 0..<safeCount {
            textDocumentProxy.deleteBackward()
        }
        textDocumentProxy.insertText(text)
    }

    private func flash(_ message: String) {
        if statusLabel.text?.hasPrefix("需要") == true { return }
        statusLabel.text = message
        statusLabel.textColor = .secondaryLabel
    }
}
