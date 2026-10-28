import Foundation

// MARK: - 客户档案（见 docs/00-决策记录.md D-09）

public struct CustomerProfile: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    /// 用于匹配的键：联系人名称/手机号，键盘扩展里拿不到通讯录，只能靠用户手动选或从分享内容里推断
    public var matchKey: String
    public var displayName: String
    /// 该客户的惯用语言（检测不到时的第二兜底，优先级高于全局默认）
    public var language: LanguageCode?
    public var style: TranslationStyle
    /// 禁忌/注意事项，只做提示，不参与翻译
    public var notes: String
    /// 该客户专属的"不翻译"词（产品型号、人名）
    public var extraGlossary: [GlossaryEntry]
    public var updatedAt: Date

    public init(id: UUID = UUID(), matchKey: String, displayName: String,
                language: LanguageCode? = nil, style: TranslationStyle = .business,
                notes: String = "", extraGlossary: [GlossaryEntry] = [], updatedAt: Date = Date()) {
        self.id = id
        self.matchKey = matchKey
        self.displayName = displayName
        self.language = language
        self.style = style
        self.notes = notes
        self.extraGlossary = extraGlossary
        self.updatedAt = updatedAt
    }
}

public protocol ProfileStoring: Sendable {
    func profile(matching key: String) -> CustomerProfile?
    func allProfiles() -> [CustomerProfile]
    func upsert(_ profile: CustomerProfile)
    func delete(id: UUID)
}

// MARK: - 设置

public struct AppSettings: Codable, Equatable, Sendable {
    /// 兜底目标语言。检测不出对方语言时用它（D-07：兜底英语）
    public var defaultTargetLanguage: LanguageCode = .english
    /// 是否允许"点译即插"，不弹预览
    public var allowsDirectSend: Bool = true
    /// 直发模式下仍然拦截的原因
    public var hardBlockReasons: Set<RiskReason> = [.containsMoney]
    /// 长句阈值
    public var longSentenceThreshold: Int = 60
    /// 语言判断置信度低于此值时强制预览
    public var previewOnLowConfidence: Bool = true
    /// 默认语气
    public var defaultStyle: TranslationStyle = .business
    /// 键盘顶部芯片展示哪些语言（顺序即展示顺序）
    public var pinnedLanguages: [LanguageCode] = [.english, .spanish, .portuguese, .french, .arabic, .russian]
    /// 全局"不翻译"词
    public var glossary: [GlossaryEntry] = []
    /// 是否把译文同时写入剪贴板（分享扩展用）
    public var copyToClipboardOnShare: Bool = true

    public init() {}

    // 手写解码，保证新增字段在旧数据上不会解码失败
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        defaultTargetLanguage = try c.decodeIfPresent(LanguageCode.self, forKey: .defaultTargetLanguage) ?? d.defaultTargetLanguage
        allowsDirectSend = try c.decodeIfPresent(Bool.self, forKey: .allowsDirectSend) ?? d.allowsDirectSend
        hardBlockReasons = try c.decodeIfPresent(Set<RiskReason>.self, forKey: .hardBlockReasons) ?? d.hardBlockReasons
        longSentenceThreshold = try c.decodeIfPresent(Int.self, forKey: .longSentenceThreshold) ?? d.longSentenceThreshold
        previewOnLowConfidence = try c.decodeIfPresent(Bool.self, forKey: .previewOnLowConfidence) ?? d.previewOnLowConfidence
        defaultStyle = try c.decodeIfPresent(TranslationStyle.self, forKey: .defaultStyle) ?? d.defaultStyle
        pinnedLanguages = try c.decodeIfPresent([LanguageCode].self, forKey: .pinnedLanguages) ?? d.pinnedLanguages
        glossary = try c.decodeIfPresent([GlossaryEntry].self, forKey: .glossary) ?? d.glossary
        copyToClipboardOnShare = try c.decodeIfPresent(Bool.self, forKey: .copyToClipboardOnShare) ?? d.copyToClipboardOnShare
    }
}

// MARK: - 存储实现

/// 基于 UserDefaults(suiteName:)，App 与键盘/分享扩展通过 App Group 共享同一个 suite。
public final class UserDefaultsSettingsStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "weifan.settings.v1"

    public init(suiteName: String = AppGroup.identifier) {
        self.defaults = UserDefaults(suiteName: suiteName) ?? .standard
    }

    public func load() -> AppSettings {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            return AppSettings()
        }
        return decoded
    }

    public func save(_ settings: AppSettings) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: key)
    }
}

public final class UserDefaultsProfileStore: ProfileStoring, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "weifan.profiles.v1"
    private let lock = NSLock()

    public init(suiteName: String = AppGroup.identifier) {
        self.defaults = UserDefaults(suiteName: suiteName) ?? .standard
    }

    public func profile(matching matchKey: String) -> CustomerProfile? {
        allProfiles().first { $0.matchKey.caseInsensitiveCompare(matchKey) == .orderedSame }
    }

    public func allProfiles() -> [CustomerProfile] {
        lock.lock(); defer { lock.unlock() }
        guard let data = defaults.data(forKey: key),
              let list = try? JSONDecoder().decode([CustomerProfile].self, from: data) else { return [] }
        return list.sorted { $0.updatedAt > $1.updatedAt }
    }

    public func upsert(_ profile: CustomerProfile) {
        lock.lock(); defer { lock.unlock() }
        var list = allProfilesLocked()
        if let index = list.firstIndex(where: { $0.id == profile.id }) {
            list[index] = profile
        } else {
            list.append(profile)
        }
        persistLocked(list)
    }

    public func delete(id: UUID) {
        lock.lock(); defer { lock.unlock() }
        persistLocked(allProfilesLocked().filter { $0.id != id })
    }

    private func allProfilesLocked() -> [CustomerProfile] {
        guard let data = defaults.data(forKey: key),
              let list = try? JSONDecoder().decode([CustomerProfile].self, from: data) else { return [] }
        return list
    }

    private func persistLocked(_ list: [CustomerProfile]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        defaults.set(data, forKey: key)
    }
}

public enum AppGroup {
    /// 必须与 ios/project.yml 里的 entitlements 保持一致
    public static let identifier = "group.com.weifan.app"
}
