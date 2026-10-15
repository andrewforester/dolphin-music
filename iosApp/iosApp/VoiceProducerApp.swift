import ComposeApp
import SwiftUI

@main
struct VoiceProducerApp: App {
    init() {
        AudioSessionSetup.configure()
        let info = Bundle.main.infoDictionary ?? [:]
        func value(_ key: String) -> String { info[key] as? String ?? "" }
        KoinIosKt.doInitKoin(
            config: IosConfig(proxyBaseUrl: value("PROXY_BASE_URL")),
            bridge: ElevenLabsAgentBridge(),
            keychain: Keychain()
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
