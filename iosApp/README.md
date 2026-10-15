# iosApp

SwiftUI host for the shared Compose UI (`composeApp`, framework `ComposeApp`). The Xcode project is generated
by [xcodegen](https://github.com/yonaskolb/XcodeGen) from `project.yml` and is not committed.

## Build

```
scripts/gen-ios-secrets.sh && cd iosApp && xcodegen && open iosApp.xcodeproj
```

- `scripts/gen-ios-secrets.sh` reads `PROXY_BASE_URL` from `local.properties`, else `voiceproducer.proxyBaseUrl`
  from `gradle.properties`, and writes `iosApp/Secrets.xcconfig` (git-ignored). The values reach Swift via `Info.plist`.
- A pre-build script runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`. If Gradle cannot find a JDK
  from Xcode, set `JAVA_HOME` (e.g. Android Studio's bundled JBR) in your shell profile or the scheme.
- The ElevenLabs Swift SDK (`from: 3.4.0`) is resolved through Swift Package Manager.

CLI build for the simulator:

```
cd iosApp && xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' ARCHS=arm64 ONLY_ACTIVE_ARCH=NO build
```

The voice agent is `IosVoiceAgent` (Kotlin) over `ElevenLabsAgentBridge.swift`, which drives the ElevenLabs
Swift SDK; the bridge is passed to Koin in `VoiceProducerApp`, with `Keychain.swift`, which stores the access code in the Keychain. `mainViewController()` (`MainViewController.kt`) asks `AVAudioSession` for
record permission on every agent toggle and re-checks the authorization on every resume. Music plays through
`IosAudioEngine` (`AVAudioEngine`); `AudioSessionSetup.swift` sets the shared `playAndRecord` session at launch
and turns off LiveKit's automatic session configuration.
