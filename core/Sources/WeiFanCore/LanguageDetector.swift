import Foundation

/// 语言检测结果
public struct LanguageDetection: Equatable, Sendable {
    public let language: LanguageCode
    /// 0...1，越高越可信
    public let confidence: Double
    /// 次优候选，用于"记忆与检测冲突"的判断
    public let runnerUp: LanguageCode?
    /// 检测依据：script（靠文字系统直接判定）/ lexicon（靠停用词打分）
    public let basis: Basis

    public enum Basis: String, Sendable {
        case script   // 阿拉伯字母、西里尔字母这类一眼可判的
        case lexicon  // 拉丁字母，靠常用词打分
    }

    public init(language: LanguageCode, confidence: Double, runnerUp: LanguageCode? = nil, basis: Basis) {
        self.language = language
        self.confidence = confidence
        self.runnerUp = runnerUp
        self.basis = basis
    }
}

public protocol LanguageDetecting: Sendable {
    /// 返回 nil 表示无法判断（内容太短、纯数字、纯表情等）
    func detect(_ text: String, candidates: [LanguageCode]) -> LanguageDetection?
}

/// 纯本地检测器，零网络、零依赖、微秒级。
///
/// 设计取舍（见 docs/00-决策记录.md D-06）：
/// - 不发网络请求，因为键盘扩展里每一次往返都会让"打完中文→点译"的手感崩掉；
/// - 不求 100% 准确，只求"够用来决定目标语言"，判不出来时由上层兜底英语；
/// - 拉丁字母语种之间靠停用词打分，因为纯字符分布法在 es/pt/it 之间基本不可用。
public struct HeuristicLanguageDetector: LanguageDetecting {

    public init() {}

    public func detect(_ text: String, candidates: [LanguageCode] = LanguageCode.sendTargets) -> LanguageDetection? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let byScript = detectByScript(trimmed) {
            // 脚本判定优先，但目标语言列表里没有它就退化成未知
            guard candidates.contains(byScript.language) || byScript.language.isChinese else { return nil }
            return byScript
        }
        return detectLatinByLexicon(trimmed, candidates: candidates)
    }

    // MARK: - 文字系统判定

    private func detectByScript(_ text: String) -> LanguageDetection? {
        var counts: [Script: Int] = [:]
        for scalar in text.unicodeScalars {
            if let script = Script(scalar: scalar) {
                counts[script, default: 0] += 1
            }
        }
        guard let dominant = counts.max(by: { $0.value < $1.value })?.key else { return nil }

        switch dominant {
        case .arabic:
            return LanguageDetection(language: .arabic, confidence: 0.95, basis: .script)
        case .cyrillic:
            return LanguageDetection(language: .russian, confidence: 0.85, basis: .script)
        case .devanagari:
            return LanguageDetection(language: .hindi, confidence: 0.95, basis: .script)
        case .thai:
            return LanguageDetection(language: .thai, confidence: 0.95, basis: .script)
        case .hebrew:
            return LanguageDetection(language: .hebrew, confidence: 0.95, basis: .script)
        case .hangul:
            return LanguageDetection(language: .korean, confidence: 0.95, basis: .script)
        case .kana:
            return LanguageDetection(language: .japanese, confidence: 0.95, basis: .script)
        case .han:
            return LanguageDetection(language: chineseVariant(of: text), confidence: 0.9, basis: .script)
        case .latin, .other:
            return nil
        }
    }

    private func chineseVariant(of text: String) -> LanguageCode {
        let traditionalOnly: Set<Character> = ["這", "個", "們", "麼", "說", "時", "間", "發", "貨", "價", "錢", "單", "還", "讓", "對", "請", "謝"]
        let simplifiedOnly: Set<Character> = ["这", "个", "们", "么", "说", "时", "间", "发", "货", "价", "钱", "单", "还", "让", "对", "请", "谢"]
        var t = 0, s = 0
        for ch in text {
            if traditionalOnly.contains(ch) { t += 1 }
            if simplifiedOnly.contains(ch) { s += 1 }
        }
        return t > s ? .chineseTraditional : .chineseSimplified
    }

    // MARK: - 拉丁字母打分

    private func detectLatinByLexicon(_ text: String, candidates: [LanguageCode]) -> LanguageDetection? {
        let lowered = text.lowercased()
        let tokens = lowered
            .components(separatedBy: CharacterSet.letters.inverted)
            .filter { $0.count >= 1 }
        guard !tokens.isEmpty else { return nil }

        var scores: [LanguageCode: Double] = [:]
        let tokenSet = Set(tokens)

        for language in candidates where latinLexicon[language] != nil {
            guard let lex = latinLexicon[language] else { continue }
            var score = 0.0
            for token in tokenSet {
                if lex.words.contains(token) { score += 1.0 }
            }
            // 变音符号/特殊标点签名，权重比停用词更能区分近亲语言
            for (marker, weight) in lex.markers where lowered.contains(marker) {
                score += weight
            }
            if score > 0 { scores[language] = score }
        }

        guard let best = scores.max(by: { $0.value < $1.value }) else { return nil }
        let total = scores.values.reduce(0, +)
        let second = scores
            .filter { $0.key != best.key }
            .max(by: { $0.value < $1.value })

        // 词太少的短句（"ok" / "thanks"）不足以支撑判断
        if total < 1.0 || tokens.count < 2 { return nil }

        return LanguageDetection(
            language: best.key,
            confidence: min(0.99, best.value / max(total, 1) * 0.9 + 0.1),
            runnerUp: second?.key,
            basis: .lexicon
        )
    }

    // MARK: - 数据

    private enum Script {
        case latin, han, kana, hangul, arabic, cyrillic, devanagari, thai, hebrew, other

        init?(scalar: Unicode.Scalar) {
            switch scalar.value {
            case 0x0041...0x024F, 0x1E00...0x1EFF:
                self = .latin
            case 0x4E00...0x9FFF, 0x3400...0x4DBF, 0xF900...0xFAFF:
                self = .han
            case 0x3040...0x30FF:
                self = .kana
            case 0xAC00...0xD7AF, 0x1100...0x11FF:
                self = .hangul
            case 0x0600...0x06FF, 0x0750...0x077F, 0xFB50...0xFDFF:
                self = .arabic
            case 0x0400...0x04FF:
                self = .cyrillic
            case 0x0900...0x097F:
                self = .devanagari
            case 0x0E00...0x0E7F:
                self = .thai
            case 0x0590...0x05FF:
                self = .hebrew
            default:
                return nil
            }
        }
    }

    private struct Lexicon {
        let words: Set<String>
        let markers: [String: Double]
    }

    private var latinLexicon: [LanguageCode: Lexicon] {
        [
            .english: Lexicon(
                words: ["the", "and", "you", "for", "with", "this", "that", "have", "will", "can", "please", "hello", "thanks", "when", "what", "how", "ship", "order", "price", "yes", "not", "are", "is", "to", "of", "it", "we", "my", "your", "good", "morning", "dear"],
                markers: [:]
            ),
            .spanish: Lexicon(
                words: ["el", "la", "los", "las", "que", "de", "en", "para", "por", "con", "hola", "gracias", "cuando", "puedes", "pedido", "precio", "muy", "pero", "usted", "nosotros", "buenos", "dias", "por favor", "si", "no", "es", "un", "una"],
                markers: ["ñ": 2, "¿": 3, "¡": 3, "ción": 1]
            ),
            .portuguese: Lexicon(
                words: ["o", "a", "os", "as", "que", "de", "em", "para", "por", "com", "olá", "obrigado", "obrigada", "quando", "você", "pedido", "preço", "muito", "mas", "não", "sim", "é", "um", "uma", "enviar", "bom", "dia"],
                markers: ["ã": 2, "õ": 2, "ç": 1, "ção": 2, "não": 2]
            ),
            .french: Lexicon(
                words: ["le", "la", "les", "des", "que", "de", "en", "pour", "par", "avec", "bonjour", "merci", "quand", "vous", "commande", "prix", "très", "mais", "oui", "non", "est", "un", "une", "nous", "pouvez"],
                markers: ["è": 1, "ê": 1, "ç": 1, "œ": 2, "à": 1, "ù": 2, "qu'": 2]
            ),
            .german: Lexicon(
                words: ["der", "die", "das", "und", "sie", "für", "mit", "ist", "nicht", "hallo", "danke", "wann", "können", "bestellung", "preis", "sehr", "aber", "ja", "nein", "wir", "ein", "eine", "bitte", "guten", "tag"],
                markers: ["ß": 3, "ä": 1, "ö": 1, "ü": 1]
            ),
            .italian: Lexicon(
                words: ["il", "lo", "la", "gli", "le", "che", "di", "in", "per", "con", "ciao", "grazie", "quando", "puoi", "ordine", "prezzo", "molto", "ma", "sì", "no", "è", "un", "una", "noi", "buongiorno"],
                markers: ["ì": 2, "ò": 1, "à": 1]
            ),
            .dutch: Lexicon(
                words: ["de", "het", "een", "en", "van", "voor", "met", "dat", "niet", "hallo", "dank", "wanneer", "kunnen", "bestelling", "prijs", "zeer", "maar", "ja", "nee", "wij", "is", "ik", "jij"],
                markers: ["ij": 2, "aa": 1, "oe": 1]
            ),
            .turkish: Lexicon(
                words: ["ve", "bir", "bu", "için", "ile", "merhaba", "teşekkür", "ne zaman", "sipariş", "fiyat", "çok", "ama", "evet", "hayır", "değil", "biz", "siz"],
                markers: ["ğ": 3, "ş": 2, "ı": 2, "ç": 1]
            ),
            .vietnamese: Lexicon(
                words: ["và", "của", "cho", "với", "xin chào", "cảm ơn", "khi nào", "đơn hàng", "giá", "rất", "nhưng", "có", "không", "chúng tôi", "bạn"],
                markers: ["ạ": 2, "ế": 1, "ộ": 2, "đ": 3, "ơ": 1, "ư": 2]
            ),
            .indonesian: Lexicon(
                words: ["dan", "yang", "untuk", "dengan", "ini", "itu", "halo", "terima kasih", "kapan", "pesanan", "harga", "sangat", "tapi", "ya", "tidak", "kami", "anda"],
                markers: []
            ),
            .malay: Lexicon(
                words: ["dan", "yang", "untuk", "dengan", "ini", "itu", "hai", "terima kasih", "bila", "pesanan", "harga", "sangat", "tetapi", "ya", "tidak", "kami", "awak"],
                markers: []
            ),
            .polish: Lexicon(
                words: ["i", "w", "na", "do", "z", "że", "nie", "jest", "cześć", "dziękuję", "kiedy", "zamówienie", "cena", "bardzo", "ale", "tak", "my", "wy"],
                markers: ["ł": 2, "ą": 2, "ę": 2, "ż": 2, "ś": 1, "ć": 1]
            ),
            .filipino: Lexicon(
                words: ["ang", "ng", "sa", "na", "at", "kumusta", "salamat", "kailan", "order", "presyo", "sobrang", "pero", "oo", "hindi", "kami", "ikaw"],
                markers: []
            )
        ]
    }
}
