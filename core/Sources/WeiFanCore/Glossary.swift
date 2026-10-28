import Foundation

/// 一条"不要乱译"的词条。
public struct GlossaryEntry: Codable, Equatable, Sendable, Identifiable {
    public enum Policy: String, Codable, Sendable {
        /// 原样保留，绝不翻译（品牌名、型号、"轻舟 Pro"）
        case keepOriginal
        /// 固定译法（"定金" → deposit），按目标语言分别指定
        case fixed
    }

    public var id: UUID
    public var term: String
    public var policy: Policy
    /// 固定译法表，key 为 LanguageCode.rawValue
    public var translations: [String: String]
    public var isEnabled: Bool

    public init(id: UUID = UUID(), term: String, policy: Policy = .keepOriginal,
                translations: [String: String] = [:], isEnabled: Bool = true) {
        self.id = id
        self.term = term
        self.policy = policy
        self.translations = translations
        self.isEnabled = isEnabled
    }

    public func translation(for language: LanguageCode) -> String? {
        translations[language.rawValue]
    }
}

/// 受保护的文本：命中的词被替换成占位符，翻译完成后再还原。
public struct ProtectedText: Equatable, Sendable {
    /// 已经替换好占位符、可以直接送去翻译的文本
    public let masked: String
    /// 占位符 → 原始词（或固定译法）
    public let units: [String: String]
    /// 命中的词条原文，用于风险提示
    public let hits: [String]

    public var isEmpty: Bool { masked.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// 术语保护器。
///
/// 为什么需要它：机器翻译一定会把"轻舟 Pro"译成 "Light Boat Pro"、把客户名 "Chan" 译成 "陈"。
/// 做法是把命中的词先替换成 `⟦0⟧` 这类占位符，翻译完再换回来。
///
/// **已知风险**（必须实测，见 docs/04-待验证清单.md V-03）：
/// 部分引擎会吞掉或改写占位符（DeepL 偶尔会加空格、Google 偶尔会翻译方括号内容）。
/// 因此还原阶段使用宽松正则，并且还原失败时保留占位符原文以便用户察觉，
/// 而不是静默返回一句带 `⟦0⟧` 的错译。
public struct Glossary: Sendable {
    public var entries: [GlossaryEntry]

    public init(entries: [GlossaryEntry] = []) {
        self.entries = entries
    }

    /// 按长度倒序匹配，保证"轻舟 Pro Max"先于"轻舟"命中
    private var activeEntries: [GlossaryEntry] {
        entries
            .filter { $0.isEnabled && !$0.term.trimmingCharacters(in: .whitespaces).isEmpty }
            .sorted { $0.term.count > $1.term.count }
    }

    public func protect(_ text: String, target: LanguageCode) -> ProtectedText {
        var masked = text
        var units: [String: String] = [:]
        var hits: [String] = []
        var index = 0

        for entry in activeEntries where masked.contains(entry.term) {
            let replacement: String
            switch entry.policy {
            case .keepOriginal:
                replacement = entry.term
            case .fixed:
                replacement = entry.translation(for: target) ?? entry.term
            }
            let token = Self.token(index)
            // 只替换第一次出现之外的都替换，避免逐字扫字符串
            masked = masked.replacingOccurrences(of: entry.term, with: token)
            units[token] = replacement
            hits.append(entry.term)
            index += 1
        }

        return ProtectedText(masked: masked, units: units, hits: hits)
    }

    public func restore(_ translated: String, units: [String: String]) -> String {
        var result = translated
        // 宽松匹配：容忍引擎在方括号内外插入空格、换行、全角转换
        guard let regex = try? NSRegularExpression(pattern: "[⟦\\[【]\\s*\\$?\\{?\\s*(\\d+)\\s*\\}?\\s*[⟧\\]】]", options: []) else {
            return result
        }
        let ns = result as NSString
        let matches = regex.matches(in: result, options: [], range: NSRange(location: 0, length: ns.length))
        // 从后往前替换，避免 range 位移
        for match in matches.reversed() {
            guard match.numberOfRanges > 1 else { continue }
            let digits = ns.substring(with: match.range(at: 1))
            guard let token = units.keys.first(where: { $0.contains(Self.innerIndex(digits)) }) ?? units.keys.first(where: { Self.digits(of: $0) == digits }),
                  let value = units[token] else { continue }
            result = (result as NSString).replacingCharacters(in: match.range, with: value)
        }
        return result
    }

    /// 文本里出现了术语命中，用于决定是否强制走预览
    public func hits(in text: String) -> [String] {
        activeEntries.filter { text.contains($0.term) }.map(\.term)
    }

    private static func token(_ index: Int) -> String { "⟦\(index)⟧" }
    private static func innerIndex(_ digits: String) -> String { "⟦\(digits)⟧" }
    private static func digits(of token: String) -> String {
        token.filter(\.isNumber)
    }
}
