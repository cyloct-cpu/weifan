import Foundation

// MARK: - 发件结果

public struct SendOutcome: Sendable {
    public let translated: String
    public let target: LanguageCode
    public let route: TranslationRoute
    public let provider: String
    public let risk: RiskAssessment
    public let glossaryHits: [String]
    public let latency: TimeInterval
    /// 语言是怎么定下来的，用于键盘上给用户一个明确交代
    public let languageSource: LanguageSource

    public enum LanguageSource: String, Sendable {
        case manual      // 用户点了语言芯片
        case detected    // 从输入/收件内容检测到
        case profile     // 客户档案
        case fallback    // 全局默认（英语）
    }

    public var requiresPreview: Bool { risk.shouldPreview }
}

public struct IncomingOutcome: Sendable {
    public let translated: String
    public let sourceLanguage: LanguageCode?
    public let provider: String
    public let alreadyChinese: Bool
    public let latency: TimeInterval
}

// MARK: - 核心门面

/// 所有"翻译判断逻辑"的唯一入口。
///
/// 设计约定（见 docs/02-技术方案.md §3）：
/// - **这个类型不接触任何 UI / UIKit / SwiftUI**，将来搬去安卓或 Rust 时，
///   需要移植的就是这层和它下面的四个纯逻辑组件；
/// - 对外只有 `translateForSending` 与 `translateIncoming` 两个方法，
///   键盘扩展、分享扩展、主 App 的调试页都调它，保证四端行为一致。
public struct TranslationCore: Sendable {
    public var settings: AppSettings
    public var credentials: ProviderCredentials
    public var detector: LanguageDetecting
    public var router: TranslationRouter
    public var riskGate: RiskGate
    public var session: URLSession
    private let transcriberBuilder: (@Sendable (ProviderCredentials) -> TranscriptionProviding?)?

    public init(settings: AppSettings,
                credentials: ProviderCredentials,
                detector: LanguageDetecting = HeuristicLanguageDetector(),
                router: TranslationRouter = TranslationRouter(),
                riskGate: RiskGate = RiskGate(),
                session: URLSession = .shared,
                transcriberBuilder: (@Sendable (ProviderCredentials) -> TranscriptionProviding?)? = nil) {
        self.settings = settings
        self.credentials = credentials
        self.detector = detector
        self.router = router
        self.riskGate = riskGate
        self.session = session
        self.transcriberBuilder = transcriberBuilder
    }

    /// 从设置直接构建（App 与扩展的统一装配入口）
    public static func make(suiteName: String = AppGroup.identifier,
                            credentials: ProviderCredentials) -> TranslationCore {
        let settings = UserDefaultsSettingsStore(suiteName: suiteName).load()
        var gate = RiskGate()
        gate.longSentenceThreshold = settings.longSentenceThreshold
        gate.previewOnLowConfidence = settings.previewOnLowConfidence
        gate.hardBlockReasons = settings.hardBlockReasons
        return TranslationCore(
            settings: settings,
            credentials: credentials,
            router: TranslationRouter(policy: RoutingPolicy(longTextThreshold: settings.longSentenceThreshold,
                                                            businessThreshold: max(20, settings.longSentenceThreshold / 2))),
            riskGate: gate
        )
    }

    // MARK: 发件方向：中文 → 对方语言

    /// - Parameters:
    ///   - text: 用户在 WhatsApp 输入框里已经打好的中文
    ///   - explicitTarget: 用户手动点选的语言芯片（最高优先级）
    ///   - profile: 当前聊天的客户档案（可能为 nil）
    ///   - contextSample: 用于检测对方语言的样本，通常是最近一条对方消息
    public func translateForSending(_ text: String,
                                    explicitTarget: LanguageCode? = nil,
                                    profile: CustomerProfile? = nil,
                                    contextSample: String? = nil) async throws -> SendOutcome {
        let started = Date()

        // 1. 定目标语言：手动 > 检测 > 客户档案 > 全局兜底
        let detection = contextSample.flatMap { detector.detect($0, candidates: LanguageCode.sendTargets) }
        let resolved = resolveTarget(explicit: explicitTarget, detection: detection, profile: profile)

        // 2. 术语保护
        var glossary = Glossary(entries: settings.glossary)
        if let extra = profile?.extraGlossary, !extra.isEmpty {
            glossary.entries.append(contentsOf: extra)
        }
        let protected = glossary.protect(text, target: resolved.language)

        // 3. 路由
        let style = profile?.style ?? settings.defaultStyle
        let decision = router.route(protected.masked, style: style)

        // 4. 翻译
        let provider = try makeProvider(for: decision.route, target: resolved.language)
        let request = TranslationRequest(text: protected.masked,
                                         source: .chineseSimplified,
                                         target: resolved.language,
                                         style: style,
                                         route: decision.route)
        let result = try await provider.translate(request)
        let restored = glossary.restore(result.text, units: protected.units)

        // 5. 风险判定
        let risk = riskGate.assess(text: text,
                                   detection: detection,
                                   glossaryHits: protected.hits,
                                   rememberedLanguage: profile?.language,
                                   userAllowsDirectSend: settings.allowsDirectSend)

        return SendOutcome(translated: restored,
                           target: resolved.language,
                           route: result.route,
                           provider: result.provider,
                           risk: risk,
                           glossaryHits: protected.hits,
                           latency: Date().timeIntervalSince(started),
                           languageSource: resolved.source)
    }

    // MARK: 收件方向：对方语言 → 中文

    public func translateIncoming(_ text: String, profile: CustomerProfile? = nil) async throws -> IncomingOutcome {
        let started = Date()
        let detection = detector.detect(text)

        if let detection, detection.language.isChinese {
            return IncomingOutcome(translated: text, sourceLanguage: detection.language,
                                   provider: "none", alreadyChinese: true,
                                   latency: Date().timeIntervalSince(started))
        }

        let source = detection?.language
        let decision = router.route(text, style: .casual)
        // 收件方向一律翻译成简体中文，与"我自己看得懂"这个唯一目的对齐
        let provider = try makeProvider(for: decision.route, target: .chineseSimplified)
        let request = TranslationRequest(text: text, source: source, target: .chineseSimplified,
                                         style: .casual, route: decision.route)
        let result = try await provider.translate(request)

        return IncomingOutcome(translated: result.text,
                               sourceLanguage: source ?? result.detectedSource,
                               provider: result.provider,
                               alreadyChinese: false,
                               latency: Date().timeIntervalSince(started))
    }

    // MARK: 语音

    public func transcribe(audioURL: URL, languageHint: LanguageCode? = nil) async throws -> String {
        guard let transcriber = transcriberBuilder?(credentials) else {
            throw TranslationError.missingCredential("语音转写服务")
        }
        return try await transcriber.transcribe(audioURL: audioURL, languageHint: languageHint)
    }

    /// 语音 → 转写 → 中文，一步到位
    public func translateVoice(audioURL: URL, languageHint: LanguageCode? = nil) async throws -> IncomingOutcome {
        let started = Date()
        let transcript = try await transcribe(audioURL: audioURL, languageHint: languageHint)
        let outcome = try await translateIncoming(transcript)
        return IncomingOutcome(translated: outcome.translated,
                               sourceLanguage: outcome.sourceLanguage,
                               provider: outcome.provider,
                               alreadyChinese: outcome.alreadyChinese,
                               latency: Date().timeIntervalSince(started))
    }

    // MARK: - 内部

    private func resolveTarget(explicit: LanguageCode?,
                               detection: LanguageDetection?,
                               profile: CustomerProfile?) -> (language: LanguageCode, source: SendOutcome.LanguageSource) {
        if let explicit { return (explicit, .manual) }
        if let detection, detection.confidence >= 0.45 {
            return (detection.language, .detected)
        }
        if let profileLanguage = profile?.language {
            return (profileLanguage, .profile)
        }
        return (settings.defaultTargetLanguage, .fallback)
    }

    private func makeProvider(for route: TranslationRoute,
                              target: LanguageCode) throws -> TranslationProviding {
        switch route {
        case .fastMT:
            // 优先 DeepL（中→欧语质量更稳），不支持的语言回落 Google
            if target.supportedByDeepL, let key = credentials.deepLAPIKey, !key.isEmpty {
                return DeepLTranslationProvider(apiKey: key, endpoint: credentials.deepLEndpoint, session: session)
            }
            if let key = credentials.googleAPIKey, !key.isEmpty {
                return GoogleTranslationProvider(apiKey: key, session: session)
            }
            if let key = credentials.llmAPIKey, !key.isEmpty {
                // 没配机翻就别硬失败，退到大模型
                return LLMTranslationProvider(apiKey: key, baseURL: credentials.llmBaseURL,
                                              model: credentials.llmModel, name: "LLM(兜底)", session: session)
            }
            throw TranslationError.missingCredential("Google / DeepL / 大模型 任一")
        case .languageModel:
            guard let key = credentials.llmAPIKey, !key.isEmpty else {
                if let google = credentials.googleAPIKey, !google.isEmpty {
                    return GoogleTranslationProvider(apiKey: google, session: session)
                }
                throw TranslationError.missingCredential("大模型 API Key")
            }
            return LLMTranslationProvider(apiKey: key, baseURL: credentials.llmBaseURL,
                                          model: credentials.llmModel, name: "LLM", session: session)
        }
    }
}

// MARK: - 历史记录

public struct TranslationRecord: Codable, Identifiable, Equatable, Sendable {
    public enum Direction: String, Codable, Sendable { case send, receive }

    public var id: UUID
    public var direction: Direction
    public var original: String
    public var translated: String
    public var sourceLanguage: String?
    public var targetLanguage: String
    public var provider: String
    public var createdAt: Date

    public init(id: UUID = UUID(), direction: Direction, original: String, translated: String,
                sourceLanguage: String?, targetLanguage: String, provider: String, createdAt: Date = Date()) {
        self.id = id
        self.direction = direction
        self.original = original
        self.translated = translated
        self.sourceLanguage = sourceLanguage
        self.targetLanguage = targetLanguage
        self.provider = provider
        self.createdAt = createdAt
    }
}

/// 历史上限 200 条，只存本地，不上传（D-10：隐私取向）
public final class TranslationHistory: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "weifan.history.v1"
    private let limit: Int
    private let lock = NSLock()

    public init(suiteName: String = AppGroup.identifier, limit: Int = 200) {
        self.defaults = UserDefaults(suiteName: suiteName) ?? .standard
        self.limit = limit
    }

    public func append(_ record: TranslationRecord) {
        lock.lock(); defer { lock.unlock() }
        var list = allLocked()
        list.insert(record, at: 0)
        if list.count > limit { list = Array(list.prefix(limit)) }
        if let data = try? JSONEncoder().encode(list) { defaults.set(data, forKey: key) }
    }

    public func all() -> [TranslationRecord] {
        lock.lock(); defer { lock.unlock() }
        return allLocked()
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: key)
    }

    private func allLocked() -> [TranslationRecord] {
        guard let data = defaults.data(forKey: key),
              let list = try? JSONDecoder().decode([TranslationRecord].self, from: data) else { return [] }
        return list
    }
}
