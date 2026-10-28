import Foundation

/// 微翻支持的语种。原始值使用 BCP-47 风格的语言标签，直接可传给 Google / DeepL / Whisper。
public enum LanguageCode: String, CaseIterable, Codable, Sendable {
    case chineseSimplified  = "zh-Hans"
    case chineseTraditional = "zh-Hant"
    case english            = "en"
    case spanish            = "es"
    case portuguese         = "pt"
    case french             = "fr"
    case german             = "de"
    case italian            = "it"
    case dutch              = "nl"
    case russian            = "ru"
    case arabic             = "ar"
    case turkish            = "tr"
    case vietnamese         = "vi"
    case thai               = "th"
    case indonesian         = "id"
    case malay              = "ms"
    case hindi              = "hi"
    case japanese           = "ja"
    case korean             = "ko"
    case polish             = "pl"
    case hebrew             = "he"
    case filipino           = "fil"

    /// 界面展示名（中文）
    public var displayName: String {
        switch self {
        case .chineseSimplified:  return "简体中文"
        case .chineseTraditional: return "繁体中文"
        case .english:            return "英语"
        case .spanish:            return "西班牙语"
        case .portuguese:         return "葡萄牙语"
        case .french:             return "法语"
        case .german:             return "德语"
        case .italian:            return "意大利语"
        case .dutch:              return "荷兰语"
        case .russian:            return "俄语"
        case .arabic:             return "阿拉伯语"
        case .turkish:            return "土耳其语"
        case .vietnamese:         return "越南语"
        case .thai:               return "泰语"
        case .indonesian:         return "印尼语"
        case .malay:              return "马来语"
        case .hindi:              return "印地语"
        case .japanese:           return "日语"
        case .korean:             return "韩语"
        case .polish:             return "波兰语"
        case .hebrew:             return "希伯来语"
        case .filipino:           return "菲律宾语"
        }
    }

    /// 键盘顶部语言芯片上的短标签
    public var shortLabel: String {
        switch self {
        case .chineseSimplified:  return "中"
        case .chineseTraditional: return "繁"
        case .english:            return "EN"
        case .spanish:            return "ES"
        case .portuguese:         return "PT"
        case .french:             return "FR"
        case .german:             return "DE"
        case .italian:            return "IT"
        case .dutch:              return "NL"
        case .russian:            return "RU"
        case .arabic:             return "AR"
        case .turkish:            return "TR"
        case .vietnamese:         return "VI"
        case .thai:               return "TH"
        case .indonesian:         return "ID"
        case .malay:              return "MS"
        case .hindi:              return "HI"
        case .japanese:           return "JA"
        case .korean:             return "KO"
        case .polish:             return "PL"
        case .hebrew:             return "HE"
        case .filipino:           return "FIL"
        }
    }

    /// 是否是"我要翻译成"的语言（中文不作为发件目标）
    public var isChinese: Bool {
        self == .chineseSimplified || self == .chineseTraditional
    }

    /// 可作为发件目标语言的列表（去掉中文）
    public static var sendTargets: [LanguageCode] {
        allCases.filter { !$0.isChinese }
    }

    /// DeepL 不支持的语言需要回落到 Google / 大模型
    public var supportedByDeepL: Bool {
        switch self {
        case .thai, .malay, .hindi, .hebrew, .filipino, .vietnamese:
            return false
        default:
            return !isChinese || self == .chineseSimplified
        }
    }

    /// Whisper 转写用的语言提示（nil = 自动）
    public var whisperHint: String? {
        isChinese ? "zh" : rawValue.split(separator: "-").first.map(String.init)
    }
}
