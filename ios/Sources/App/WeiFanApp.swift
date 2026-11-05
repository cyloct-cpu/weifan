import SwiftUI
import WeiFanCore

@main
struct WeiFanApp: App {
    @StateObject private var model = AppModel()

    init() {
        // UI 测试：在任何 SwiftUI 视图创建之前把首启标记清掉（@AppStorage 缓存于此之后才建立）
        if ProcessInfo.processInfo.arguments.contains("UITEST_RESET_ONBOARDING") {
            UserDefaults.standard.removeObject(forKey: "onboard_lang_done")
        }
        // UI 测试：强制夜态（截图核对昼夜切换的可读性）
        if ProcessInfo.processInfo.arguments.contains("UITEST_FORCE_DARK") {
            var s = UserDefaultsSettingsStore().load()
            s.themeMode = "dark"
            UserDefaultsSettingsStore().save(s)
        }
        // 调试：清渠道停用表（旧版本开关语义留下的 disabledProviders 残留；
        // 必须走 UserDefaults API——直接改 plist 文件会被 cfprefsd 缓存冲回）
        if ProcessInfo.processInfo.arguments.contains("UITEST_RESET_PROVIDER_STATE") {
            var s = UserDefaultsSettingsStore().load()
            s.disabledProviders = []
            UserDefaultsSettingsStore().save(s)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // 外观：昼夜切换（Android theme_mode 同键；跟随系统 = nil 交给系统）
                .preferredColorScheme(Self.scheme(model.settings.themeMode))
                .environmentObject(model)
                // 翻译服务页等保存设置后立即反映（UserDefaults didChange → 重载）
                .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
                    model.reload()
                }
                .onOpenURL { url in
                    // weifan://share?text=... 供快捷指令或其它 App 唤起
                    guard url.scheme == "weifan",
                          let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
                          let text = items.first(where: { $0.name == "text" })?.value else { return }
                    model.prefill = text
                }
        }
    }

    static func scheme(_ mode: String) -> ColorScheme? {
        switch mode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

final class AppModel: ObservableObject {
    @Published var settings: AppSettings
    @Published var profiles: [CustomerProfile]
    @Published var history: [TranslationRecord]
    @Published var credentials: ProviderCredentials
    @Published var prefill: String = ""

    private let settingsStore = UserDefaultsSettingsStore()
    private let profileStore = UserDefaultsProfileStore()
    private let historyStore = TranslationHistory()

    init() {
        settings = settingsStore.load()
        profiles = profileStore.allProfiles()
        history = historyStore.all()
        credentials = Keychain.credentials()
    }

    func reload() {
        settings = settingsStore.load()
        profiles = profileStore.allProfiles()
        history = historyStore.all()
    }

    func saveSettings() {
        settingsStore.save(settings)
    }

    func saveCredentials() {
        Keychain.save(credentials)
    }

    func upsert(_ profile: CustomerProfile) {
        profileStore.upsert(profile)
        profiles = profileStore.allProfiles()
    }

    func delete(profile: CustomerProfile) {
        profileStore.delete(id: profile.id)
        profiles = profileStore.allProfiles()
    }

    func record(_ record: TranslationRecord) {
        historyStore.append(record)
        history = historyStore.all()
    }

    func makeCore() -> TranslationCore {
        var core = TranslationCore.make(credentials: credentials)
        core.settings = settings
        return core
    }
}

/// 主界面：对齐 Android 版的「会话列表为主」结构。
///
/// 之前是 5 个 TabView（聊天/翻译/客户/术语/设置），与 Android 的两级导航差别很大。
/// Android 是：会话列表 → 会话（两级），其余功能从列表右上角菜单进。
/// 这里改成同构：RootView 只放会话栈，翻译/客户/术语/设置都进 ChatListView 的菜单。
struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        // ChatListView 内部已有 NavigationStack（导航标题/工具栏挂在那里），
        // 外层再包一层会导致内层失效 → 标题与 chips 不渲染（实测全黑无标题）
        // ChatListView 内部依赖外层 NavigationStack 提供渲染上下文，
        // 去掉会导致整页白屏（实测）。标题/chips 的问题改用内层处理。
        NavigationStack { ChatRootView() }
    }
}
