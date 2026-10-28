import Foundation
import Security
import WeiFanCore

/// 密钥只放 Keychain，不进 UserDefaults、不进 App Group 的 plist。
/// 键盘扩展需要读密钥才能翻译，所以访问组设为与 App 相同的 keychain group（见 entitlements）。
enum Keychain {
    private static let service = "com.weifan.credentials"

    private struct Blob: Codable {
        var google: String = ""
        var deepl: String = ""
        var deeplEndpoint: String = "https://api-free.deepl.com/v2/translate"
        var llm: String = ""
        var llmBaseURL: String = "https://api.deepseek.com/v1"
        var llmModel: String = "deepseek-chat"
        var openai: String = ""
    }

    static func save(_ credentials: ProviderCredentials) {
        let blob = Blob(google: credentials.googleAPIKey ?? "",
                        deepl: credentials.deepLAPIKey ?? "",
                        deeplEndpoint: credentials.deepLEndpoint.absoluteString,
                        llm: credentials.llmAPIKey ?? "",
                        llmBaseURL: credentials.llmBaseURL.absoluteString,
                        llmModel: credentials.llmModel,
                        openai: credentials.openAIAPIKey ?? "")
        guard let data = try? JSONEncoder().encode(blob) else { return }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "providers"
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func credentials() -> ProviderCredentials {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "providers",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let blob = try? JSONDecoder().decode(Blob.self, from: data) else {
            return ProviderCredentials()
        }
        return ProviderCredentials(
            googleAPIKey: blob.google.isEmpty ? nil : blob.google,
            deepLAPIKey: blob.deepl.isEmpty ? nil : blob.deepl,
            deepLEndpoint: URL(string: blob.deeplEndpoint) ?? URL(string: "https://api-free.deepl.com/v2/translate")!,
            llmAPIKey: blob.llm.isEmpty ? nil : blob.llm,
            llmBaseURL: URL(string: blob.llmBaseURL) ?? URL(string: "https://api.deepseek.com/v1")!,
            llmModel: blob.llmModel,
            openAIAPIKey: blob.openai.isEmpty ? nil : blob.openai
        )
    }
}
