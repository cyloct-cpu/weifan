import Foundation

public enum TranslationStyle: String, Codable, Sendable, CaseIterable {
    case business   // 商务：正式、无缩写、保留敬语
    case casual     // 日常：口语、简短
    case literal    // 直译：优先信息准确，用于单据类

    public var displayName: String {
        switch self {
        case .business: return "商务"
        case .casual:   return "日常"
        case .literal:  return "直译"
        }
    }
}

/// 走哪条翻译通道
public enum TranslationRoute: String, Sendable, Equatable {
    /// 专用机翻（Google / DeepL）：快、便宜
    case fastMT
    /// 大模型：慢、贵，但懂上下文、能控语气
    case languageModel
}

public struct RouteDecision: Sendable, Equatable {
    public let route: TranslationRoute
    public let reasons: [String]
}

/// 混合路由策略（见 docs/00-决策记录.md D-05）
///
/// 核心判断：**字符数少 + 没有数字 = 机翻足够**；只要出现金额、数量、日期、长句，
/// 就直接上大模型，因为这类错误代价最高。
public struct RoutingPolicy: Sendable {
    /// 超过这个字符数走大模型（中文按字符算，约 60 字的句子机翻已开始丢主语）
    public var longTextThreshold: Int = 60
    /// 商务语气下更早升级到大模型
    public var businessThreshold: Int = 30
    /// 是否"只要含数字就升级"
    public var escalateOnNumbers: Bool = true

    public init(longTextThreshold: Int = 60, businessThreshold: Int = 30, escalateOnNumbers: Bool = true) {
        self.longTextThreshold = longTextThreshold
        self.businessThreshold = businessThreshold
        self.escalateOnNumbers = escalateOnNumbers
    }
}

public struct TranslationRouter: Sendable {
    public var policy: RoutingPolicy

    public init(policy: RoutingPolicy = RoutingPolicy()) {
        self.policy = policy
    }

    public func route(_ text: String, style: TranslationStyle = .business) -> RouteDecision {
        let count = text.count
        var reasons: [String] = []

        if count > policy.longTextThreshold {
            reasons.append("长度 \(count) > \(policy.longTextThreshold)")
            return RouteDecision(route: .languageModel, reasons: reasons)
        }

        if policy.escalateOnNumbers && TextSignals.containsNumber(text) {
            reasons.append("含数字/金额，机翻易出错")
            return RouteDecision(route: .languageModel, reasons: reasons)
        }

        if style == .business && count > policy.businessThreshold {
            reasons.append("商务长句")
            return RouteDecision(route: .languageModel, reasons: reasons)
        }

        if style == .literal {
            reasons.append("单据直译，交给大模型保持结构")
            return RouteDecision(route: .languageModel, reasons: reasons)
        }

        reasons.append("短句无数字，机翻足够")
        return RouteDecision(route: .fastMT, reasons: reasons)
    }
}

public enum RiskReason: String, Sendable, Codable, CaseIterable {
    case containsMoney
    case containsNumber
    case containsDate
    case glossaryHit
    case longSentence
    case lowDetectionConfidence
    case switchedLanguage

    public var displayName: String {
        switch self {
        case .containsMoney:          return "含金额"
        case .containsNumber:         return "含数字"
        case .containsDate:           return "含日期"
        case .glossaryHit:            return "命中专有名词"
        case .longSentence:           return "长句"
        case .lowDetectionConfidence: return "语言判断不确定"
        case .switchedLanguage:       return "对方语言可能与上次不同"
        }
    }
}

public struct RiskAssessment: Sendable, Equatable {
    public let reasons: [RiskReason]
    public var shouldPreview: Bool { !reasons.isEmpty }
}

/// 文本特征识别（纯正则，无网络）
public enum TextSignals {
    private static let numberRegex = try! NSRegularExpression(pattern: "\\d", options: [])
    private static let moneyRegex = try! NSRegularExpression(
        pattern: "(\\d[\\d,\\.]*\\s*(usd|rmb|cny|eur|dollars?|yuan|元|美元|欧元|美金|万|千|k\\b|pcs|件|箱|吨))|([\\$￥€£]\\s*\\d)",
        options: [.caseInsensitive]
    )
    private static let dateRegex = try! NSRegularExpression(
        pattern: "(\\d{1,4}[-/年\\.]\\d{1,2}[-/月\\.]\\d{1,2})|(\\d{1,2}\\s*(月|日|号))|(next\\s+(week|month|monday|tuesday|wednesday|thursday|friday))|(下\\s*(周|月|星期))",
        options: [.caseInsensitive]
    )

    public static func containsNumber(_ text: String) -> Bool {
        numberRegex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    public static func containsMoney(_ text: String) -> Bool {
        moneyRegex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    public static func containsDate(_ text: String) -> Bool {
        dateRegex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }
}

/// 决定"直发"还是"先给用户看一眼"（见 docs/00-决策记录.md D-03）
public struct RiskGate: Sendable {
    public var longSentenceThreshold: Int
    public var previewOnLowConfidence: Bool
    /// 用户选择了"全自动直发"时，仍然拦下这些原因
    public var hardBlockReasons: Set<RiskReason>

    public init(longSentenceThreshold: Int = 60,
                previewOnLowConfidence: Bool = true,
                hardBlockReasons: Set<RiskReason> = [.containsMoney]) {
        self.longSentenceThreshold = longSentenceThreshold
        self.previewOnLowConfidence = previewOnLowConfidence
        self.hardBlockReasons = hardBlockReasons
    }

    public func assess(text: String,
                       detection: LanguageDetection?,
                       glossaryHits: [String],
                       rememberedLanguage: LanguageCode?,
                       userAllowsDirectSend: Bool) -> RiskAssessment {
        var reasons: [RiskReason] = []

        if TextSignals.containsMoney(text) { reasons.append(.containsMoney) }
        else if TextSignals.containsNumber(text) { reasons.append(.containsNumber) }
        if TextSignals.containsDate(text) { reasons.append(.containsDate) }
        if !glossaryHits.isEmpty { reasons.append(.glossaryHit) }
        if text.count > longSentenceThreshold { reasons.append(.longSentence) }

        if previewOnLowConfidence, let detection, detection.confidence < 0.45 {
            reasons.append(.lowDetectionConfidence)
        }
        if let rememberedLanguage, let detection,
           detection.language != rememberedLanguage, detection.confidence > 0.6 {
            reasons.append(.switchedLanguage)
        }

        if userAllowsDirectSend {
            // 直发模式下只保留硬拦截原因，其余仅记录
            reasons = reasons.filter { hardBlockReasons.contains($0) }
        }
        return RiskAssessment(reasons: reasons)
    }
}
