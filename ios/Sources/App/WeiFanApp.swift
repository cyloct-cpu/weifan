import SwiftUI
import WeiFanCore

@main
struct WeiFanApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onOpenURL { url in
                    // weifan://share?text=... 供快捷指令或其它 App 唤起
                    guard url.scheme == "weifan",
                          let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
                          let text = items.first(where: { $0.name == "text" })?.value else { return }
                    model.prefill = text
                }
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

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            NavigationStack { SendView() }
                .tabItem { Label("翻译", systemImage: "character.bubble") }
            NavigationStack { CustomersView() }
                .tabItem { Label("客户", systemImage: "person.2") }
            NavigationStack { GlossaryView() }
                .tabItem { Label("术语", systemImage: "textformat.abc") }
            NavigationStack { SettingsView() }
                .tabItem { Label("设置", systemImage: "gearshape") }
        }
    }
}
