import Foundation

// MARK: - 请求与结果

public struct TranslationRequest: Sendable {
    public var text: String
    public var source: LanguageCode?
    public var target: LanguageCode
    public var style: TranslationStyle
    public var route: TranslationRoute

    public init(text: String, source: LanguageCode? = nil, target: LanguageCode,
                style: TranslationStyle = .business, route: TranslationRoute = .fastMT) {
        self.text = text
        self.source = source
        self.target = target
        self.style = style
        self.route = route
    }
}

public struct TranslationResult: Sendable, Equatable {
    public var text: String
    public var provider: String
    public var route: TranslationRoute
    public var detectedSource: LanguageCode?
    public var latency: TimeInterval

    public init(text: String, provider: String, route: TranslationRoute,
                detectedSource: LanguageCode? = nil, latency: TimeInterval = 0) {
        self.text = text
        self.provider = provider
        self.route = route
        self.detectedSource = detectedSource
        self.latency = latency
    }
}

public enum TranslationError: LocalizedError, Sendable {
    case missingCredential(String)
    case badStatus(code: Int, body: String)
    case emptyResult
    case unsupportedLanguage(LanguageCode)
    case transport(String)
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .missingCredential(let key): return "缺少密钥：\(key)"
        case .badStatus(let code, let body): return "服务返回 \(code)：\(body.prefix(120))"
        case .emptyResult: return "翻译结果为空"
        case .unsupportedLanguage(let lang): return "该引擎不支持 \(lang.displayName)"
        case .transport(let message): return "网络错误：\(message)"
        case .cancelled: return "已取消"
        }
    }
}

public protocol TranslationProviding: Sendable {
    var name: String { get }
    func translate(_ request: TranslationRequest) async throws -> TranslationResult
}

public protocol TranscriptionProviding: Sendable {
    var name: String { get }
    /// 传入音频文件（m4a/opus/wav），返回识别文本
    func transcribe(audioURL: URL, languageHint: LanguageCode?) async throws -> String
}

// MARK: - 凭证

/// 凭证不写入 UserDefaults，统一放 Keychain（iOS 端由 App 写入，键盘扩展只需要读）
public struct ProviderCredentials: Sendable {
    public var googleAPIKey: String?
    public var deepLAPIKey: String?
    public var deepLEndpoint: URL
    public var llmAPIKey: String?
    public var llmBaseURL: URL
    public var llmModel: String
    public var openAIAPIKey: String?

    public init(googleAPIKey: String? = nil,
                deepLAPIKey: String? = nil,
                deepLEndpoint: URL = URL(string: "https://api-free.deepl.com/v2/translate")!,
                llmAPIKey: String? = nil,
                llmBaseURL: URL = URL(string: "https://api.deepseek.com/v1")!,
                llmModel: String = "deepseek-chat",
                openAIAPIKey: String? = nil) {
        self.googleAPIKey = googleAPIKey
        self.deepLAPIKey = deepLAPIKey
        self.deepLEndpoint = deepLEndpoint
        self.llmAPIKey = llmAPIKey
        self.llmBaseURL = llmBaseURL
        self.llmModel = llmModel
        self.openAIAPIKey = openAIAPIKey
    }
}

// MARK: - Google Cloud Translation (v2)

public struct GoogleTranslationProvider: TranslationProviding {
    public let name = "Google"
    private let apiKey: String
    private let session: URLSession

    public init(apiKey: String, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.session = session
    }

    public func translate(_ request: TranslationRequest) async throws -> TranslationResult {
        guard !apiKey.isEmpty else { throw TranslationError.missingCredential("Google API Key") }
        var components = URLComponents(string: "https://translation.googleapis.com/language/translate/v2")!
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]

        var body: [String: Any] = [
            "q": [request.text],
            "target": request.target.rawValue,
            "format": "text"
        ]
        if let source = request.source { body["source"] = source.rawValue }

        let started = Date()
        let (data, response) = try await post(components.url!, json: body)
        try Self.validate(response, data: data)

        struct Payload: Decodable {
            struct DataBox: Decodable {
                struct Translation: Decodable { let translatedText: String; let detectedSourceLanguage: String? }
                let translations: [Translation]
            }
            let data: DataBox
        }
        let payload = try JSONDecoder().decode(Payload.self, from: data)
        guard let first = payload.data.translations.first else { throw TranslationError.emptyResult }

        return TranslationResult(
            text: Self.unescapeHTML(first.translatedText),
            provider: name,
            route: .fastMT,
            detectedSource: first.detectedSourceLanguage.flatMap { LanguageCode(rawValue: $0) },
            latency: Date().timeIntervalSince(started)
        )
    }

    private func post(_ url: URL, json: [String: Any]) async throws -> (Data, URLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: json)
        request.timeoutInterval = 8
        do {
            return try await session.data(for: request)
        } catch {
            throw TranslationError.transport(error.localizedDescription)
        }
    }

    static func unescapeHTML(_ text: String) -> String {
        text.replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&lt;", with: "<")
    }
}

// MARK: - DeepL

public struct DeepLTranslationProvider: TranslationProviding {
    public let name = "DeepL"
    private let apiKey: String
    private let endpoint: URL
    private let session: URLSession

    public init(apiKey: String, endpoint: URL, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.endpoint = endpoint
        self.session = session
    }

    public func translate(_ request: TranslationRequest) async throws -> TranslationResult {
        guard !apiKey.isEmpty else { throw TranslationError.missingCredential("DeepL API Key") }
        guard request.target.supportedByDeepL else { throw TranslationError.unsupportedLanguage(request.target) }

        var items: [URLQueryItem] = [
            .init(name: "text", value: request.text),
            .init(name: "target_lang", value: Self.deepLTarget(request.target))
        ]
        if let source = request.source { items.append(.init(name: "source_lang", value: Self.deepLTarget(source))) }

        var comps = URLComponents()
        comps.queryItems = items
        var urlRequest = URLRequest(url: endpoint)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("DeepL-Auth-Key \(apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = comps.percentEncodedQuery?.data(using: .utf8)
        urlRequest.timeoutInterval = 8

        let started = Date()
        let (data, response) = try await session.data(for: urlRequest)
        try Self.validate(response, data: data)

        struct Payload: Decodable {
            struct Item: Decodable { let detectedSourceLanguage: String?; let text: String }
            let translations: [Item]
        }
        let payload = try JSONDecoder().decode(Payload.self, from: data)
        guard let first = payload.translations.first else { throw TranslationError.emptyResult }

        return TranslationResult(
            text: first.text,
            provider: name,
            route: .fastMT,
            detectedSource: first.detectedSourceLanguage.flatMap { LanguageCode(rawValue: $0.lowercased()) },
            latency: Date().timeIntervalSince(started)
        )
    }

    static func deepLTarget(_ language: LanguageCode) -> String {
        switch language {
        case .chineseSimplified: return "ZH-HANS"
        case .chineseTraditional: return "ZH-HANT"
        case .english: return "EN"
        default: return language.rawValue.uppercased()
        }
    }
}

// MARK: - 大模型（OpenAI 兼容：DeepSeek / OpenAI / 自建）

public struct LLMTranslationProvider: TranslationProviding {
    public let name: String
    private let apiKey: String
    private let baseURL: URL
    private let model: String
    private let session: URLSession

    public init(apiKey: String, baseURL: URL, model: String, name: String = "LLM", session: URLSession = .shared) {
        self.apiKey = apiKey
        self.baseURL = baseURL
        self.model = model
        self.name = name
        self.session = session
    }

    public func translate(_ request: TranslationRequest) async throws -> TranslationResult {
        guard !apiKey.isEmpty else { throw TranslationError.missingCredential("大模型 API Key") }

        let url = baseURL.appendingPathComponent("chat/completions")
        var body: [String: Any] = [
            "model": model,
            "temperature": 0.2,
            "messages": [
                ["role": "system", "content": Self.systemPrompt(for: request)],
                ["role": "user", "content": request.text]
            ]
        ]
        // 新版 OpenAI 使用 max_completion_tokens，DeepSeek 兼容 max_tokens
        body["max_tokens"] = 1024

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        urlRequest.timeoutInterval = 20

        let started = Date()
        let (data, response) = try await session.data(for: urlRequest)
        try Self.validate(response, data: data)

        struct Payload: Decodable {
            struct Choice: Decodable { let message: Message }
            struct Message: Decodable { let content: String }
            let choices: [Choice]
        }
        let payload = try JSONDecoder().decode(Payload.self, from: data)
        guard let content = payload.choices.first?.message.content,
              !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TranslationError.emptyResult
        }

        return TranslationResult(
            text: content.trimmingCharacters(in: .whitespacesAndNewlines),
            provider: name,
            route: .languageModel,
            detectedSource: request.source,
            latency: Date().timeIntervalSince(started)
        )
    }

    /// 提示词是四端共用资产，改这里等于四端同时生效（见 docs/02-技术方案.md §5）
    static func systemPrompt(for request: TranslationRequest) -> String {
        let tone: String
        switch request.style {
        case .business: tone = "正式商务语气，简洁专业，不使用缩略语和表情"
        case .casual:   tone = "自然口语，像熟人聊天，可以简短"
        case .literal:  tone = "忠实直译，保留原文结构与数字格式，不润色"
        }
        return """
        你是专业的中英互译引擎，服务于跨境贸易沟通。把用户输入翻译成\(request.target.displayName)（\(request.target.rawValue)）。
        要求：
        1. 只输出译文，不要解释、不要引号、不要加任何前后缀。
        2. 语气：\(tone)。
        3. 文本中的 ⟦数字⟧ 是占位符，必须原样保留在译文中的对应位置，不得翻译、不得改写、不得移动。
        4. 金额、数量、日期、型号必须逐字对应，不许换算、不许四舍五入。
        5. 人名、品牌名、产品型号保留原文，不要音译。
        6. 如果原文已是目标语言，原样返回。
        """
    }
}

// MARK: - Whisper 语音转写

public struct WhisperTranscriber: TranscriptionProviding {
    public let name = "Whisper"
    private let apiKey: String
    private let baseURL: URL
    private let model: String
    private let session: URLSession

    public init(apiKey: String, baseURL: URL = URL(string: "https://api.openai.com/v1")!,
                model: String = "whisper-1", session: URLSession = .shared) {
        self.apiKey = apiKey
        self.baseURL = baseURL
        self.model = model
        self.session = session
    }

    public func transcribe(audioURL: URL, languageHint: LanguageCode?) async throws -> String {
        guard !apiKey.isEmpty else { throw TranslationError.missingCredential("Whisper API Key") }

        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        func append(_ string: String) { body.append(string.data(using: .utf8)!) }

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"model\"\r\n\r\n\(model)\r\n")
        if let hint = languageHint?.whisperHint {
            append("--\(boundary)\r\n")
            append("Content-Disposition: form-data; name=\"language\"\r\n\r\n\(hint)\r\n")
        }
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\n")
        append("Content-Type: audio/m4a\r\n\r\n")
        body.append(try Data(contentsOf: audioURL))
        append("\r\n--\(boundary)--\r\n")

        var request = URLRequest(url: baseURL.appendingPathComponent("audio/transcriptions"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 30

        let (data, response) = try await session.data(for: request)
        try Self.validate(response, data: data)

        struct Payload: Decodable { let text: String }
        return try JSONDecoder().decode(Payload.self, from: data).text
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - 公共校验

enum ProviderSupport {
    static func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw TranslationError.transport("无 HTTP 响应")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw TranslationError.badStatus(code: http.statusCode,
                                             body: String(data: data, encoding: .utf8) ?? "")
        }
    }
}

extension GoogleTranslationProvider {
    static func validate(_ response: URLResponse, data: Data) throws {
        try ProviderSupport.validate(response, data: data)
    }
}

extension DeepLTranslationProvider {
    static func validate(_ response: URLResponse, data: Data) throws {
        try ProviderSupport.validate(response, data: data)
    }
}

extension LLMTranslationProvider {
    static func validate(_ response: URLResponse, data: Data) throws {
        try ProviderSupport.validate(response, data: data)
    }
}

extension WhisperTranscriber {
    static func validate(_ response: URLResponse, data: Data) throws {
        try ProviderSupport.validate(response, data: data)
    }
}
