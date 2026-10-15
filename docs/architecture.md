# Architecture

Kotlin Multiplatform app for Android and iOS with one Compose Multiplatform UI for composing music with a voice
agent. The user describes a track by voice, the agent composes it with ElevenLabs Music, the track loops, and every
spoken change regenerates only the edited section and swaps it in while the music plays. Modules follow the
Now in Android layout: layers of the Android architecture guide, modules by feature and by technical role, Gradle
configuration in convention plugins. Ports belong to the domain; every dependency arrow points into the domain.

Base package `com.voiceproducer`; a module's package is its Gradle path, `:core:data` → `com.voiceproducer.core.data`.

```
┌──────────── App (Kotlin Multiplatform, Compose Multiplatform) ─────────────┐
│ androidApp / iosApp   entry points, mic permission                         │
│ composeApp            App(), appModules, initKoin, platformModule          │
│ feature/producer      ProducerScreen · ProducerViewModel                   │
│ feature/access        AccessSheet · AccessViewModel                        │
│ core/domain           use cases · EditPipeline · AgentToolHandler ·        │
│                       PlanEditor · WindowPlanner · DuckController · ports  │
│ core/dsp              EdgeSimilarity · Mixer · SwappableMaster ·           │
│                       SmoothedGain · PcmPump                               │
│ core/data             ProxyApi (Ktor) · repositories · AccessRepository ·  │
│                       SecureStore · AudioStore (okio)                      │
│ core/database         Room KMP          core/codec   AudioDecoder          │
│ core/playback         Media3AudioEngine / IosAudioEngine                   │
│ core/agent            ElevenLabsVoiceAgent / IosVoiceAgent                 │
└──────────────┬───────────────────────────────────┬─────────────────────────┘
               │ x-access-code                     │ WebRTC (LiveKit)
      Supabase Edge Functions                ElevenLabs Agent (private)
      music-plan · music-compose ·           client tools → app
      agent-token · agent-conversation · access
               │ xi-api-key (server only)
      ElevenLabs Music API (music_v2_5) / Agents token API
```

## Stack

| What | Used | How to work with it |
|---|---|---|
| Language, targets | Kotlin 2.4, Android + `iosArm64` + `iosSimulatorArm64`; a `jvm()` target in the `core` modules with `kmp.jvmTests=true` | Shared code in `commonMain`; platform code in `androidMain` / `iosMain` |
| UI | Compose Multiplatform 1.12, Material 3 | One screen in Kotlin for both platforms; Swift only hosts `mainViewController()` |
| State | Lifecycle `ViewModel`, `StateFlow`, coroutines | `ProducerViewModel` exposes `StateFlow<ProducerUiState>` and a separate playback-position `StateFlow` |
| DI | Koin | One Koin module per Gradle module (`di/`), all listed in `composeApp` `appModules` |
| Network | Ktor (OkHttp on Android, Darwin on iOS), kotlinx.serialization | `ProxyApi` in `core/data` talks only to the Supabase proxy |
| Persistence | Room KMP with the bundled SQLite driver, okio for audio files | `AppDatabase` in `core/database`; `AudioStore` in `core/data` |
| Audio | Media3 ExoPlayer on Android, `AVAudioEngine` on iOS; MediaCodec / `AVAudioFile` for decoding | Behind the `AudioEngine` and `AudioDecoder` ports |
| Voice agent | ElevenLabs Android SDK 0.12.2 (LiveKit), ElevenLabs Swift SDK | Behind the `VoiceAgent` port; the agent definition lives in `supabase/functions/_shared/agent/` |
| Proxy | Supabase Edge Functions (Deno) | `supabase/functions`; `deno test supabase/functions`; secrets in `supabase/README.md` |
| iOS project | XcodeGen | Edit `iosApp/project.yml`, run `xcodegen generate`; the `.xcodeproj` is not committed |
| Build | Gradle 9, AGP 9, version catalog `gradle/libs.versions.toml`, convention plugins in `build-logic` | Versions only in the catalog; configuration only in convention plugins |
| Tests | kotlin-test, coroutines-test, Turbine, Robolectric, Roborazzi, ComposablePreviewScanner, Konsist, Deno test | See "Testing" |
| Static analysis | ktlint via Spotless, detekt + Compose rules, Android Lint | See "Static analysis" |

## Modules

### `core/model`
Value types without logic dependencies: `CompositionPlan` (with `Chunk`, `TimeRange`, `PlanJson`) and
`ChunkSerializer`, `SourceMap`, `AudioFormat`, `PcmAudio`, `DuckTarget`, `TrackVersion`, `StoredMessage`,
`MessageRole`, `HistoryEntry`, `ApiLogEntry`, `ProxyError`. kotlinx.serialization plugin. Depends on nothing.

### `core/dsp`
Pure audio processing: `WavCodec`, `Mixer`, `Splice`, `SwappableMaster`, `SmoothedGain`, `PcmPump`,
`EdgeSimilarity` (takes the lead, tail and section end in ms). Depends on `core/model`.

### `core/common`
`AppScope` (Koin qualifier), `appScope()` (the app-lifetime scope on `Dispatchers.Default` that logs failing jobs)
and `nowMs()`; `di/commonModule` binds the `AppScope` scope. Depends on nothing.

### `core/domain`

| Package | Contains |
|---|---|
| (root) | `runCatchingExceptions` / `runCatchingIncludingCancellation` (catch failures without swallowing cancellation) and `Throwable.reason()`. |
| `plan/` | `PlanEditor` (with `PlanEdit`, `EditResult`, `Adherence`), `SectionIndex`, `StyleConflicts`. |
| `window/` | `WindowPlanner` (regeneration windows and their splices). |
| `duck/` | `DuckController`. |
| `agent/` | Port `VoiceAgent` with `AgentState`, `AgentEvent`, `ToolHandler`, `ToolResult`; port `DemoSwitch { fun useDemo(on: Boolean) }`; port `AgentTokenSource` (`token()`: the token and its conversation id; `refusal(conversationId)`: why that conversation was refused); `AgentToolHandler` (client tool calls to `ProducerCommands`). |
| `access/` | `ProxyAccess` (`Code(value)`; its `toString` hides the value); ports `AccessStore { val access: StateFlow<ProxyAccess?>; save; clear }` and `AccessCheck { suspend fun check(access) }` (throws `ProxyError.Unauthorized` for a code the proxy does not know); `AccessResult` (`Granted`, `Rejected`, `Failed(reason)`). |
| `playback/` | Port `AudioEngine`. |
| `repository/` | Ports `TrackStore`, `TrackLibrary` (extends `TrackStore`), `ConversationHistory`, `ApiLogFeed { val entries: StateFlow<List<ApiLogEntry>> }`. |
| `edit/` | `EditPipeline` (edit and undo service) with `PipelineEvent`, `MasterSwapper` and `EditQueue`; `EditPipelineFactory.create(scope: CoroutineScope): EditPipeline`. |
| `usecase/` | The use cases below, each a class with one public `operator fun invoke`. |
| `di/` | `domainModule`: factories for the use cases (except `EndAgentSessionUseCase`) and `EditPipelineFactory`. |

| Use case | `invoke` | Does |
|---|---|---|
| `ComposeTrackUseCase(library: TrackLibrary, engine: AudioEngine)` | `suspend (prompt: String, instrumental: Boolean, lengthMs: Long): Pair<TrackVersion, PcmAudio>` | Creates the track and loads its master into the engine. |
| `StartNewTrackUseCase(library: TrackLibrary, engine: AudioEngine, history: ConversationHistory)` | `suspend (enqueueHistoryWrite: (suspend () -> Unit) -> Unit)` | Clears the current track, unloads the engine and hands the pending-conversation clear to `enqueueHistoryWrite`; the ViewModel runs it in its ordered history write queue. |
| `RestoreLatestTrackUseCase(library: TrackLibrary, engine: AudioEngine)` | `suspend (onLatest: suspend (TrackVersion?) -> Unit, shouldLoad: suspend (TrackVersion) -> Boolean): Pair<TrackVersion, PcmAudio>?` | Reads the latest version and reports it to `onLatest` (the ViewModel restores that track's conversation), reads its audio, asks `shouldLoad` (the ViewModel fetches the title and checks that no new track was requested meanwhile) and loads the engine; null without a track or when `shouldLoad` says no. |
| `StartAgentSessionUseCase(tokens: AgentTokenSource, agent: VoiceAgent)` | `suspend (tools: ToolHandler, opening: () -> AgentSessionOpening): Result<Unit>` | Mints a token, then reads `opening` (`AgentSessionOpening(plan: String, firstMessage: String?)`, built from the conversation as it is when the token arrives) and starts the agent session. A token failure is returned as a failed `Result`; a failing start throws. |
| `SubmitAccessCodeUseCase(check: AccessCheck, store: AccessStore)` | `suspend (code: String): AccessResult` | Trims the code; a blank code is `Rejected` without a call. Checks it with the proxy and stores it on success; `ProxyError.Unauthorized` is `Rejected`, any other failure `Failed` with its reason. |
| `EndAgentSessionUseCase(agent: VoiceAgent, appScope: CoroutineScope)` | `()` | Launches `agent.stop()` in the app scope, never in the caller's; bound in `appModules` (`composeApp`) with `get(AppScope)`. |

The guards against overlapping work (`generating`, `clearing`, `submitting`, `pipeline.busy`) stay in the ViewModel.
Depends on `core/model` and `core/dsp`.

### `core/testing`
Public fakes for `commonTest` of other modules: `FakeEngine` (records calls), `SwapCheckingEngine` (checks every swap
against the last queued master; with `Swap`), `FakeLibrary` (with the `put` extension), `FakeStore`, `FakeAgent`,
`FakeHistory`, `FakeCommands`, `FakeApiLogFeed`, `FakeAccessStore`, `FakeAccessCheck` (records the checked access)
and `MarkingDispatcher`. `core/domain`, `core/data`, `feature/producer` and `feature/access` use it. Depends on `core/domain`, `core/model`, kotlinx-coroutines-test and kotlin-test.

### `core/codec`
`AudioDecoder` (decodes compressed audio into PCM16) with `MediaCodecDecoder` (`androidMain`) and `IosAudioDecoder`
(`iosMain`); `di/codecModule` (`expect`/`actual`) binds `AudioDecoder` to the platform decoder. Host tests
(Robolectric) in `androidHostTest`, the instrumented test in `androidDeviceTest`, iOS tests in `iosTest`. Depends on
`core/model` and `core/dsp`.

### `core/database`
Room KMP database: `AppDatabase` (`NAME` is the file name `voiceproducer.db`, version 2), `TrackDao`, `MessageDao`,
the entities, `buildAppDatabase` per platform (`androidMain`, `iosMain`, `jvmMain`) and `databaseModule`
(`expect`/`actual`, provides `AppDatabase`). KSP and the Room Gradle plugin; schemas are exported to
`core/database/schemas`. Depends on nothing.

### `core/data`

| Package | Contains |
|---|---|
| `com.voiceproducer.core.data` | `AudioStore` (okio), `ProxyConfig(baseUrl, initialAccessCode)` |
| `access` | `SecureStore` (read, write, delete strings; `CipherSecureStore` in `androidMain`: AES-GCM with a key in the Android Keystore, values in `SharedPreferences`; on iOS a Keychain bridge from `composeApp`), `AccessRepository` (`internal`, implements `AccessStore` and `ProxyCredentials`; keeps the code in `SecureStore`, falls back to `initialAccessCode` when nothing is stored, forgets it on `rejected()`), `ProxyAccessCheck` (`internal`, implements `AccessCheck` with `ProxyApi.checkAccess`) |
| `remote` | `ProxyApi` (Ktor) for the Supabase proxy (sets the access header from `ProxyCredentials` in one place; a `401` throws `ProxyError.Unauthorized` and calls `rejected()`, except for `checkAccess`), `ProxyCredentials`, `ApiLog` (implements `ApiLogFeed`), `ProxyAgentTokenSource` (`internal`, implements `AgentTokenSource`) |
| `repository` | `TrackRepository` (`internal`, implements `TrackLibrary` and `TrackStore`; delegates the `TrackStore` part to `TrackVersions`, with `MasterFiles` for master files and length fitting and `VersionMapper` for entity to `TrackVersion`), `HistoryRepository` (`internal`, implements `ConversationHistory`) |
| `di` | `dataModule(config: ProxyConfig)`: the `HttpClient` with its timeouts, `AccessRepository` bound to `AccessStore` and `ProxyCredentials`, `ProxyApi`, `ProxyAccessCheck` bound to `AccessCheck`, the DAOs, the repositories and the token source bound to their domain ports, `ApiLog` bound to `ApiLogFeed`. `composeApp`'s `platformModule` provides `HttpClientEngine`, `SecureStore`, `AudioStore` and `ApiLog` (with the platform logger); `databaseModule` provides `AppDatabase`, `codecModule` the `AudioDecoder`. |

Tests: `ProxyApiTest`, `ApiLogTest` and `AccessRepositoryTest` in `commonTest`; `CipherSecureStoreTest` in `androidHostTest` (Robolectric, a software AES key); `TrackRepositoryTest`, `HistoryRepositoryTest` and
`MigrationTest` (opens a version 1 database and migrates it) in `jvmTest`. Depends on `core/domain`, `core/model`,
`core/dsp`, `core/database`, `core/codec` and `core/common`.

### `core/playback`

| Source set | Contains |
|---|---|
| `androidMain` | `Media3AudioEngine` (implements `AudioEngine`; loops the master with ExoPlayer through `MasterDataSource` from a `SwappableMaster`), `MasterDataSource`, `DuckingAudioProcessor` (applies `SmoothedGain` in the Media3 audio path) |
| `iosMain` | `IosAudioEngine` (implements `AudioEngine` with `AVAudioEngine`) with `AudioGraph`, `PcmFeeder` and `SessionObservers` |
| `commonMain` `di` | `playbackModule` (`expect`/`actual`): binds `AudioEngine` to the platform engine as an app-scoped singleton; the Android `actual` builds `Media3AudioEngine` with `androidContext()` |

Host tests (Robolectric) for the Media3 classes in `androidHostTest`, `IosAudioEngineTest` in `iosTest`. Depends on
`core/domain` (`AudioEngine`), `core/dsp` (`SwappableMaster`, `SmoothedGain`, `PcmPump`) and `core/model`; Media3
ExoPlayer on Android.

### `core/agent`

| Source set | Contains |
|---|---|
| `androidMain` | `ElevenLabsVoiceAgent` (implements `VoiceAgent` over the ElevenLabs Android SDK) with `AgentStatePublisher` and `SdkSessions`, `ToolBridge` (client tools to `ToolHandler`), `AudioModeRestorer` |
| `iosMain` | `IosVoiceAgent` (implements `VoiceAgent` over `AgentBridge`), `AgentBridge`, `AgentBridgeListener`, `BridgeStatus` (implemented in Swift by `ElevenLabsAgentBridge` over the ElevenLabs Swift SDK), `ToolParams` |
| `commonMain` | `AgentConfig(demo)`; `demo/` — `DemoVoiceAgent` (a scripted session: no token, no tools), `SwitchableVoiceAgent` (implements `VoiceAgent` and `DemoSwitch`; runs each session on the real or the demo agent), `VoiceAgent.withDemo(demo, scope)`; and `di/agentModule` (`expect`/`actual`): binds `VoiceAgent` as an app-scoped singleton; the Android `actual` builds `ElevenLabsVoiceAgent` with `androidContext()` and `AppScope`; the iOS `actual` builds `IosVoiceAgent` from the `AgentBridge` the app provides; both wrap it with `withDemo` from `AgentConfig` |

Host tests (Robolectric) in `androidHostTest`, iOS tests (`IosVoiceAgentTest`, `ToolParamsTest`, `FakeAgentBridge`) in
`iosTest`. The `ComposeApp` framework exports `core/agent`; Swift sees `AgentBridge`, `AgentBridgeListener` and
`BridgeStatus`. Depends on `core/domain`, `core/model` and `core/common`; the ElevenLabs Android SDK on Android
(`compileOnly` LiveKit).

### `core/designsystem`
`Palette` (design v4 colours), `ProducerTheme` (Material 3 dark scheme over `Palette`, the only `MaterialTheme`),
`DesignKit` (`Modifier.glass`, `PillShape`, `sans(...)` and `mono(...)` text styles, `formatMs`,
`rememberAnimationMillis`), `BrandOrb` (the orb image `drawable/brand_orb.png`, also the app icon and launch image) and `Fonts` (internal). The fonts `hanken_grotesk.ttf` and `jetbrains_mono.ttf` (variable,
OFL; licences in `androidApp/src/main/assets/licenses`) are Compose resources in `src/commonMain/composeResources/font`,
class `com.voiceproducer.core.designsystem.generated.resources.Res`; each family is one
`Font(resource, weight, variationSettings = FontVariation.Settings(FontVariation.weight(w)))` per weight: Hanken
Grotesk 400, 500, 600, 700, 800 and JetBrains Mono 400, 500. Android resources are enabled for the module
(`androidResources.enable`); the fonts ship as Android assets. Built with `template.kmp.compose`. Depends on nothing.

### `feature/producer`
The producer screen. `ProducerViewModel` with `TranscriptLog` (transcript and its stored conversation, including the
notice line of a failed section), `SectionTracker` (section versions and queued swaps), `AgentSession` (agent session
and events), `PlayheadNotices` (the playhead sent to a connected agent on connect, section change, seek and
play/pause) and `DuckPresenter`; `ProducerUiState` / `ProducerIntent`; `ProducerUiMapping`, `TranscriptLines`,
`SessionHistory`, `AgentNotices`, `LoaderProgress`. The ViewModel takes the five use cases, `EditPipelineFactory`,
`TrackLibrary`, `AudioEngine`, `VoiceAgent`, `ConversationHistory`, `ApiLogFeed` and a clock. `di/producerModule`
binds the ViewModel with named arguments and a wall clock (`kotlin.time.Clock`).

| Package / source set | Contains |
|---|---|
| `ui` (`commonMain`) | `ProducerScreen(state, onIntent, positionMs)` (stateless; the background comes in as a parameter, `StaticBackground` by default) and `ProducerScreen(modifier, viewModel = koinViewModel(), onIntent, formatTime, backHandler)` (collects the ViewModel's state, API log and position, closes the API log on Back, draws `ShaderBackground`); components `AgentPanel` (orb home slot and status line, `micLook`, `asksForMicFirst`), `ApiLogPanel`, `Timeline`, `TimelineLoader`, `Transcript`, `PillRows`, `BlockLook`, `NavigationBarsLess`; `Previews.kt` with one `@Preview` per state (section failed, empty, generating, playing, listening and ducked, hearing you, connecting, many pills, no mic permission, errors), drawn by `PreviewScreen` (`PreviewScreen.kt`) |
| `ui/background` (`commonMain`) | `expect fun ShaderBackground(state, positionMs, modifier)`; `ShaderDriver` (per-frame uniform smoothing) writing through `UniformSink`; `FOREST_SHADER` (compiles as AGSL and SkSL); `StaticBackground` (still gradient, test tag `static-background`) |
| `ui/background` (`androidMain`) | `actual ShaderBackground`: `FOREST_SHADER` as an AGSL `RuntimeShader` on API 33+, `StaticBackground` below |
| `ui/background` (`iosMain`) | `actual ShaderBackground`: `FOREST_SHADER` as a Skia `RuntimeEffect`, `StaticBackground` if it does not compile |
| `orb` (`commonMain`) | `OrbMood` and `orbMood(state)`, `orbPulseScale` (size: up to +15 % with the user's voice, ±6 % on 600 ms while the composer speaks), `OrbPhysics` (`OrbArena`, `parkPoint`, `freePoint`, `sticks`, `settle`, `onRelease` with a fling, `inMagnet`, `edgeInset`, `edgeBump`, `stillAttached`) |
| `ui/orb` (`commonMain`) | `OrbLayer` (full-screen layer over `ProducerScreen`: the orb at its home slot, drag, the ✕, parking) with `OrbMotion`; `Orb` (`OrbLook`, `rememberOrbLook`, `StaticOrb`, `ShaderOrb`, `ORB_SHADER`); `LiquidEdge` (the black bump drawn as a path); `expect rememberOrbShader(source)` and `Modifier.excludeFromSystemGestures()` |
| `ui/orb` (`androidMain` / `iosMain`) | `rememberOrbShader`: AGSL `RuntimeShader` on API 33+ (null below) / Skia `RuntimeEffect`; `excludeFromSystemGestures`: `systemGestureExclusion` / no-op |
| `androidHostTest` | Robolectric UI tests: `ProducerScreenTest` and `ProducerScreenTranscriptTest` (over `ProducerScreenTestBase`), `MicLookTest`, `TimelineLoaderTest`, `PillRowsTest`, `ShaderBackgroundFallbackTest` (SDK 32 renders `StaticBackground`); `robolectric.properties` sets SDK 36 |
| `iosTest` | `ForestShaderTest`: the shader compiles with Skia and `ShaderDriver` sets exactly the declared uniforms; `OrbShaderTest`: the orb shader compiles with Skia |

- Timeline: while a finger is down the playhead follows it and the engine is seeked once on release; a tap is press
  and release at the same spot, a gesture taken over by a parent leaves scrub mode unseeked. The released position
  stays until the engine reports it or a short timeout passes.
- A loader that was showing completes to 1 before the real timeline replaces it; the loader stops at `LoaderProgress`'s
  cap until the work is done.
- Transcript: the view follows a growing reply only when the reader was at the end and has not scrolled since;
  replies that arrive with no session count as heard. While `agentConnected`, a reply shows only its first word until
  the agent speaks, then reveals at speaking pace. Restored lines get fresh ids that never clash with new lines.
- The mic mute is allowed only while the agent is Listening or Speaking; it is a no-op while Connecting or
  Disconnected.
- The shader and animations read time and position per frame in the draw phase, without recomposing; the field is
  drawn at at most 1.5 px per CSS px and scaled up.
- `Palette` colours are sRGB values converted from the design's oklch. The AGSL shader needs a real device; previews
  use the static background.
- Only starting a voice session asks for the mic permission (`asksForMicFirst`); ending one never does.
- On iOS a denied microphone answer is reported as `MicPermission(false)` and never needs to stop a session.

The screen follows design v4: an fbm shader background, glass transcript and track cards, section blocks coloured by
kind with version and tag sub-labels (`v2 · heavier`, `regenerating…`, `v2 queued`), tap-to-seek, prev/next, style
chips and the composer orb with activity labels (see "Composer orb"). The presentation tests run in `commonTest` (Android host and iOS
simulator). Built with `template.kmp.feature`, which adds `core/model`, `core/domain`, `core/designsystem`,
lifecycle, navigation, kotlinx.serialization and Koin Compose to `commonMain` and `core/testing` to `commonTest`.
Depends on `core/domain`, `core/model` and `core/designsystem`.

### `feature/access`
The access-code sheet. `AccessViewModel(store: AccessStore, submitCode: SubmitAccessCodeUseCase)` exposes
`StateFlow<AccessUiState>` (`granted`, `sheetOpen`, `code`, `checking`, `error`) and takes `AccessIntent`s
(`CodeChanged`, `Submit`, `Dismiss`); the sheet opens whenever there is no access (at start and after a `401`),
`guard(proceed)` runs `proceed` with access and otherwise opens the sheet. A rejected code keeps the sheet open with
"That code didn't work. Check it and try again."; an unreachable proxy shows "Couldn't check the code: <reason>".
`ui/AccessSheet(state, onIntent, modifier, onOpenGuide)` (stateless) is a pale-teal bottom sheet over a light scrim:
title, a monospace code field (no autocorrect, Done submits), the error line, a dark "Continue" pill (a spinner and
"Checking…" while checking) and "Run your own backend →", which opens `docs/self-hosting.md` on GitHub
(`SELF_HOSTING_URL`); a tap on the scrim or a drag down by 96 dp dismisses it. `ui/AccessSheet(modifier, viewModel)`
collects the ViewModel. `AccessPreviews.kt` has one `@Preview` per state (empty, filled, checking, rejected);
`di/accessModule` binds the ViewModel. Tests: `AccessViewModelTest` in `commonTest`. Built with
`template.kmp.feature`; depends on `core/domain`, `core/model` and `core/designsystem`.

### `composeApp`

| Source set | Contains |
|---|---|
| `commonMain` | `AppConfig(proxy: ProxyConfig, agent: AgentConfig)`; `di/appModules(config)` = `commonModule`, `codecModule`, `playbackModule`, `agentModule`, `databaseModule`, `dataModule(config.proxy)`, `domainModule`, `producerModule`, `accessModule`, `platformModule` and a module binding `AgentConfig` and `EndAgentSessionUseCase` (with `get(AppScope)`); `di/initKoin(config, platform)` starts Koin with the `platform` declaration first, then `appModules`; `platformModule` (`expect`); `App(modifier, onIntent, formatTime, backHandler)` = `ProducerTheme { SplashHost { ProducerScreen(...) with AccessSheet on top } }`; an intent for which `needsAccess(intent, state)` holds (`ToggleAgent` while the agent is disconnected) goes through `AccessViewModel.guard`, which opens the sheet instead when there is no access; Back closes an open sheet; `ui/Splash.kt`: `Splash` (static background, `BrandOrb` with two rings, piano keys drawn on a `Canvas` and the "Dolphin" wordmark) and `SplashHost`, which draws `Splash` over the content for `SPLASH_HOLD_MS` and fades it out |
| `androidMain` | `platformModule`: `ApiLog` with a logcat logger, the OkHttp `HttpClientEngine`, `CipherSecureStore.keystoreBacked` as `SecureStore`, `AudioStore` under `filesDir/audio` |
| `iosMain` | `platformModule`: `ApiLog` with an `NSLog` logger, the Darwin `HttpClientEngine`, `AudioStore` under the Documents directory; `IosConfig(proxyBaseUrl)`, `KeychainBridge` (implemented in Swift by `Keychain`, generic-password items, `WhenUnlockedThisDeviceOnly`) adapted to `SecureStore` by `KeychainSecureStore`, and `initKoin(config: IosConfig, bridge: AgentBridge, keychain: KeychainBridge)` (Swift `KoinIosKt.doInitKoin`); `ui/mainViewController()`: `App()` with the mic-permission flow (on every resume it reports `AVCaptureDevice`'s audio authorization as `MicPermission`; `ToggleAgent` first asks `AVAudioSession` for record permission, reports `MicPermission(granted)` and toggles the agent only if granted) |

Package `com.voiceproducer`. Its iOS target is the static `ComposeApp` framework, which exports `core/agent`.
`IosKoinGraphTest` (`iosTest`) starts the graph with `initKoin` and resolves the ViewModel and every port. Depends on
every `core` module, `feature/producer` and `feature/access`.

### `androidApp`
Package `com.voiceproducer.android`. `App` calls `initKoin(AppConfig(ProxyConfig(BuildConfig.PROXY_BASE_URL,
initialAccessCode = BuildConfig.ACCESS_CODE), AgentConfig(demo = BuildConfig.DEBUG))) { androidLogger(); androidContext(this@App) }`.
`MainActivity` calls `installSplashScreen()` (theme `Theme.VoiceProducer.Starting`: the orb on `splash_background`, then `Theme.VoiceProducer`; the launcher icon `ic_launcher` is the orb on the background gradient, `ic_launcher_round` the orb alone), sets edge-to-edge and `setContent { App(...) }`, passing an intent interceptor (a `ToggleAgent`
without `RECORD_AUDIO` launches the permission request; a grant reports `MicPermission(true)` and starts the agent),
a local-time formatter and the activity `BackHandler`; it re-checks the permission on every resume. Its
`koinViewModel()` and the screen's resolve the same activity-scoped ViewModel. Manifest, resources, release signing,
version from the `versionName` / `versionCode` Gradle properties. Unit tests over the whole app: `KoinGraphTest`
(Robolectric; starts the graph with `initKoin` and `androidContext`, resolves the ViewModel, every port, `AppDatabase`, `AppScope` and every use case) and `PreviewScreenshotTest` (see "Testing"). Holds the module-graph rules
(`moduleGraphAssert`). Depends on `composeApp`, `feature/producer`, `core/domain` and `core/model`.

### `konsist-test`
JVM module with architecture tests over the production sources of every module: import rules per module
(`LayerDependencyTest`) and naming and placement rules (`NamingAndPlacementTest`).

### iOS host: `iosApp`
Not a Gradle module. SwiftUI host generated by XcodeGen from `iosApp/project.yml`. Swift imports `ComposeApp`;
`VoiceProducerApp` calls `KoinIosKt.doInitKoin(config: IosConfig(...), bridge: ElevenLabsAgentBridge())`,
`ContentView` shows `MainViewControllerKt.mainViewController()`. `Assets.xcassets` holds `AppIcon`, and `LaunchOrb` with `LaunchBackground` for `UILaunchScreen`. A pre-build script runs
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`. `scripts/gen-ios-secrets.sh` writes the git-ignored
`iosApp/Secrets.xcconfig` from `local.properties`; build steps are in `iosApp/README.md`.

### Convention plugins: `build-logic`
Included build.

| Plugin | Applied to | Adds |
|---|---|---|
| `template.kmp.library` | every shared module | Android + iOS targets (+ `jvm()` when `kmp.jvmTests=true`), namespace from the path, coroutines, Koin, test libraries, Robolectric JVM flags for host tests |
| `template.kmp.compose` | `core/designsystem`, `composeApp` | the library plugin + Compose Multiplatform |
| `template.kmp.feature` | `feature/*` | the Compose plugin + domain, model, design system, ViewModel, navigation, serialization |
| `template.android.application` | `androidApp` | SDK levels, Compose, Android Lint, Robolectric settings |
| `template.detekt` | all of the above, `konsist-test` | detekt with `config/detekt/detekt.yml` and the Compose rules |

A module's build file applies one plugin and lists its dependencies.

### Proxy and agent definition: `supabase/`, `agent/`
Deno Edge Functions `music-plan`, `music-compose`, `agent-token` and `agent-conversation` in `supabase/functions`, the only place that
holds ElevenLabs keys. The voice agent (prompt, client-tool schemas, LLM) is described in
`supabase/functions/_shared/agent/` and synced to the live agent by `agent-token`; `agent/` holds the creation script.

## Dependency rule

```
androidApp ──► composeApp ──► feature/producer, feature/access ──► core/designsystem
                    │                │
                    │                ▼
                    │           core/domain ──► core/dsp ──► core/model
                    │                ▲
                    ├──► core/data ──┤──► core/database, core/codec, core/common
                    ├──► core/playback ──┤
                    └──► core/agent ─────┘──► core/common
```

Every module may also use `core/model`; `core/testing` depends on `core/domain` and serves `commonTest` only.

| Rule | Checked by |
|---|---|
| `core/model` imports no other project module or package | `moduleGraphAssert` (`:core:model -X> :.*`), Konsist `modelImportsNoProjectPackage` |
| `core/dsp` depends on `core/model` only | `moduleGraphAssert` (`:core:dsp -X> :(?!core:model$).*`), Konsist `dspImportsOnlyModel` |
| `core/common` depends on no project module | `moduleGraphAssert` (`:core:common -X> :.*`) |
| `core/designsystem` depends on no project module | `moduleGraphAssert` (`:core:designsystem -X> :.*`) |
| `core/domain` depends on `core/model` and `core/dsp` only; its sources import only `core.domain`, `core.model` and `core.dsp` project packages | `moduleGraphAssert` (`:core:domain -X> :(?!core:(model\|dsp)$).*`), Konsist `domainImportsOnlyModelAndDsp` |
| `core/codec` depends on `core/model` and `core/dsp` only; its sources import only `core.codec`, `core.model` and `core.dsp` project packages | `moduleGraphAssert` (`:core:codec -X> :(?!core:(model\|dsp)$).*`), Konsist `codecImportsOnlyModelAndDsp` |
| `core/database` depends on no project module except `core/model` | `moduleGraphAssert` (`:core:database -X> :(?!core:model$).*`) |
| `core/playback` depends on `core/domain`, `core/dsp` and `core/model` only; its sources import only `core.playback`, `core.domain`, `core.dsp` and `core.model` project packages | `moduleGraphAssert` (`:core:playback -X> :(?!core:(domain\|dsp\|model)$).*`), Konsist `playbackImportsOnlyDomainDspAndModel` |
| `core/data` depends on `core/domain`, `core/model`, `core/dsp`, `core/database`, `core/codec` and `core/common` only | `moduleGraphAssert` (`:core:data -X> :(?!core:(domain\|model\|dsp\|database\|codec\|common)$).*`) |
| `core/agent` depends on `core/domain`, `core/model` and `core/common` only; its sources import only `core.agent`, `core.domain`, `core.model` and `core.common` project packages | `moduleGraphAssert` (`:core:agent -X> :(?!core:(domain\|model\|common)$).*`), Konsist `agentImportsOnlyDomainModelAndCommon` |
| Feature modules do not depend on `core/data`, `core/database`, `core/playback`, `core/codec`, `core/agent` or `core/common`, nor on another feature; their sources import none of those packages | `moduleGraphAssert` (`:feature:.* -X> :core:(data\|database\|playback\|codec\|agent\|common)`, `:feature:.* -X> :feature:.*`), Konsist `featuresDoNotImportOtherFeaturesOrData` |
| `commonMain` sources import no `android.*` or `platform.*` package | Konsist `commonCodeDoesNotImportPlatformApis` |
| `*UseCase` classes live in `core.domain.usecase` with one `invoke` operator; `*Repository` interfaces live in `core.domain.repository` | Konsist `useCasesLiveInDomainAndExposeOneInvokeOperator`, `repositoryInterfacesLiveInDomain` |
| Implementations of `TrackLibrary`, `TrackStore`, `ConversationHistory`, `AgentTokenSource` and `ApiLogFeed` live in `core.data` and are `internal` (`ApiLog` is public, see "Exceptions") | Konsist `portImplementationsLiveInDataAndAreInternal` |
| Classes extending `ViewModel` end in `ViewModel` and live in `com.voiceproducer.feature..` | Konsist `viewModelsLiveInFeaturesAndAreNamedSo` |
| Every constructor dependency has a Koin binding | `KoinGraphTest` (Android), `IosKoinGraphTest` (iOS) |

`moduleGraphAssert` is configured in `androidApp/build.gradle.kts` over the `api`, `implementation`, `commonMainApi`,
`commonMainImplementation`, `androidMainApi`, `androidMainImplementation`, `iosMainApi` and `iosMainImplementation`
configurations; `./gradlew :androidApp:assertModuleGraph` and `:konsist-test:test` are part of `verify`.

## Platform code

The port lives in `commonMain` (in `core/domain` for the ports features and use cases see, in the platform module
itself otherwise), the implementations in the module's `androidMain` and `iosMain`, and the module's Koin module binds
the right one. `expect`/`actual` is used for those Koin modules and for `ShaderBackground`.

| Capability | Module | `commonMain` | `androidMain` | `iosMain` |
|---|---|---|---|---|
| Playback | `core/playback` | `AudioEngine` (in `core/domain`), `playbackModule` | `Media3AudioEngine` (ExoPlayer, `MasterDataSource`, `DuckingAudioProcessor`) | `IosAudioEngine` (`AVAudioEngine`) |
| Decoding | `core/codec` | `AudioDecoder`, `codecModule` | `MediaCodecDecoder` | `IosAudioDecoder` (`AVAudioFile`) |
| Voice agent | `core/agent` | `VoiceAgent` (in `core/domain`), `agentModule` | `ElevenLabsVoiceAgent` (ElevenLabs Android SDK) | `IosVoiceAgent` over `AgentBridge` (Swift SDK) |
| Database | `core/database` | `AppDatabase`, `databaseModule` | `buildAppDatabase` under `filesDir` | `buildAppDatabase` under the Documents directory |
| HTTP engine, files, logging | `composeApp` | `platformModule` | OkHttp, `filesDir/audio`, logcat | Darwin, Documents, `NSLog` |
| Shader background | `feature/producer` | `ShaderBackground` | AGSL `RuntimeShader` (API 33+) | Skia `RuntimeEffect` |
| Orb shader | `feature/producer` | `rememberOrbShader`, `excludeFromSystemGestures` | AGSL `RuntimeShader` (API 33+), `systemGestureExclusion` | Skia `RuntimeEffect`, no-op |
| Signal processing | `core/dsp` | all of it | — | — |

Manifest components go into the module's `androidMain` manifest; the merged manifest removes LiveKit's
`ScreenCaptureService`.

## Composer orb

- The composer is an orb: an AGSL / SkSL sphere of `Palette` colours. `OrbMood` sets its colours, flow speed, saturation and brightness: Off is a calm teal, Connecting swirls fast in the full palette, Listening is teal and cream, Hearing you warm amber, Speaking bright teal, Thinking mixed, Muted desaturated. Mood changes fade over 300 ms.
- At rest the orb sits in its 96 dp home slot under the track controls, with the status line under it; the slot and the line have a fixed height. A tap starts or ends the session.
- During a session the orb can be dragged; the screen is not dimmed. A ✕ rises at the bottom while dragging; the orb is pulled into it only after it has once left the ✕ zone (the home slot lies inside it), and a drop there ends the session at once.
- Released away from the side edges the orb stays where it is; a fling carries it on for 0.12 s of its speed and stops inside the screen. Released (or flung) within 24 dp of a side edge it sticks: its centre goes 0.3 radius past the edge, so two thirds of it show; a 48 × 160 dp grip along the edge, excluded from system gestures, drags it out, and it stays there across layout changes. When the session ends the orb springs home.
- `LiquidEdge` draws a solid black bump out of the edge, a bell profile 4.4 radii long. It reaches out as the orb nears the edge, holds the parked orb with a rim of 0.4 radius, thins into a neck as the orb is pulled out, and past 1.7 radii flattens into a long strip and sinks back.
- `ProducerScreen` takes the orb renderer as a parameter (`StaticOrb` by default, used by previews and tests); the connected screen passes `ShaderOrb`.
- Debug Android builds set `AgentConfig.demo`: the agent is a `SwitchableVoiceAgent` and a "Demo" pill starts a scripted session (connect, greeting, user turns with voice level and transcript, thinking, replies). Its lines are shown but not stored, and leave the transcript when it ends.

## Editing a section

- The agent changes the `CompositionPlan` through narrow tools; `PlanEditor` applies them. Section names match by
  full name first, else by base name without a trailing number ("chorus" hits "Chorus 1" and "Chorus 2"); styles are
  trimmed and de-duplicated; a style in both lists is removed. Plan edits never change durations. Section offsets
  are the cumulative `duration_ms` of the chunks.
  Added styles go to the front, in the given order. `StyleConflicts`
  moves existing negatives that contradict an added positive and adds the canonical negative only then.
- `EditPipeline.submit` applies the edit to the plan and returns at once; generation runs in the pipeline's scope.
  Edits and undo run one at a time (a `Mutex`); others wait in `EditQueue`.
- A section edit regenerates a window per changed section, the next upcoming occurrence first. `WindowPlanner` builds
  the window plan `[reference: 3 s before] + [generation chunk] + [reference: 3 s after]`; the generation chunk keeps
  the `context_adherence` the agent chose (`high` or `medium`) and uses `high` when it has none. The proxy sends it to
  `/v1/music` with `model_id` `music_v2_5` and `store_for_inpainting=true`. `SourceMap` records which `song_id` and
  offset back each timeline range; later windows reference the latest audio; after a bake the whole window range
  maps to the new song.
- `EdgeSimilarity` compares the window's reference regions with the master they replace: log10 Goertzel energies in
  24 bands, Pearson correlation per edge, the lower edge counts; a region below −60 dBFS RMS counts as silent. At or
  above 0.80 the window is baked; below it, the rest of the edit regenerates the whole song. A global edit
  (`set_global`) always regenerates the whole song.
- The proxy asks for `pcm_44100`, `pcm_48000`, `pcm_24000`, `pcm_22050`, `mp3_44100_128` in that order and returns
  the first the account accepts; the app decodes any of them to PCM16 with `AudioDecoder`. Every master and window
  is fitted to its planned length: a difference up to 100 ms is padded or trimmed, a larger one is an error.
- After the swaps the pipeline saves a version. If saving fails or the edit is cancelled after a swap, the parent
  master is queued again (under `NonCancellable`).
- Versions are stored in Room with atomic insert-and-set-current transactions and relative audio file names, for
  undo and for restoring the latest track at start. The master WAV is written under a unique name before the
  transaction and deleted if the transaction fails; the commit runs `NonCancellable`. At start, master files that no
  version references and that are older than 10 minutes are deleted.
- A failed edit rolls back every section of the edit, and a re-applied edit starts from the committed plan.

## Playback and swapping

- `Mixer.bake` writes a window into a new master PCM with 200 ms equal-power crossfades (gains `cos`, `sin` of the
  fade position), centred in the reference context regions.
- On Android, ExoPlayer loops (`REPEAT_MODE_ONE`) the PCM that `MasterDataSource` serves from a `SwappableMaster`;
  on iOS, `PcmFeeder` feeds `AVAudioEngine` from a `PcmPump` over the same `SwappableMaster`.
- `SwappableMaster` queues swaps in FIFO order. The head applies at the start of a read that neither starts inside
  nor overlaps any of its diff ranges; every discontinuous (re)open (a new loop pass, a seek) applies all queued
  swaps. Updates are compare-and-set; a read never blocks a swap request.
- A whole-song regeneration crossfades in at the next section boundary after the playhead, then the new master
  replaces what lies before that boundary; without a boundary ahead it replaces the master at once.
- Undo and full replacement use the same swap. Two PCM masters are in memory during a swap.
- Eleven Music re-renders reference chunks instead of copying them; the rendered context is phase-decorrelated
  from the master.
- Swaps keep the master's format and length; the `MasterDataSource` header and the `PcmPump` format never change. A swap
  applies only when the new audio reaches the engine: a rejected swap leaves the stored state unchanged, and the
  engine never stays ahead of the stored current version.
- `Media3AudioEngine` buffers a short read-ahead; a reopen buffers for a moment and the UI keeps showing playing.
- `DuckingAudioProcessor` applies the gain sample-accurately. A reconfigured stream continues from the gain the
  previous stream ended on; a first stream starts at the duck level.
- Playing the last section: a swap lands at the next loop start. "Next" on the last section parks at the end, paused.

## Ducking

- `DuckController` sets the music level from the agent state and events: 0 dB without a session, −6 dB while a
  session is active, −18 dB while the agent speaks or the user's VAD score is above 0.5 or a tentative transcript
  arrives, held for 1 s after the last speech signal. Ramps are 80 ms into −18 dB and 400 ms otherwise.
- `DuckPresenter` in the ViewModel applies the target every 50 ms while a session is active.
- On Android, `DuckingAudioProcessor` applies `SmoothedGain` inside the Media3 audio path and the AudioTrack buffer
  targets about 100 ms (`TARGET_PCM_BUFFER_US = 100_000`), set with
  `setAudioOutputProvider(AudioTrackAudioOutputProvider…)`, not the deprecated buffer-size-provider setters.
  On iOS, `PcmPump` applies the gain.

## Voice agent

- The agent runs on the LLM `claude-sonnet-5-5`. It sees a compact text plan as the `plan` dynamic variable
  (sections, times, styles, avoid tags; no lyrics).
- Client tools: `compose_track(prompt, instrumental, length_s)`, `edit_section(name, add_styles, remove_styles,
  context_adherence)` with `context_adherence` `high` (continuity with the neighbours) or `medium` (more freedom;
  also the value for a missing or invalid one), `set_global(add, remove)`, `undo_edit()`. `AgentToolHandler` maps
  them to `ProducerCommands`.
- Agent tool calls time out after about 10 s. Tool replies are immediate ("generating…", "will revert after the
  current edit"); the app reports completion and the updated plan through `sendContextualUpdate`. The ViewModel
  also sends the playhead on connect, section change, seek and play/pause.
- `StartAgentSessionUseCase` mints a conversation token through `agent-token` and starts the session; the
  conversation id comes from the token's LiveKit room (`room_agent_..._conv_...`).
- A session ElevenLabs refuses (exhausted credits, for one) connects, then the agent leaves the room: the SDKs report
  only an agent-initiated disconnect (`DisconnectionDetails.Agent`, `DisconnectionReason.agent`). The adapters turn an
  agent that leaves before it said anything into `AgentEvent.Refused`. The ViewModel shows "Could not connect to the
  agent" at once, then `ExplainAgentRefusalUseCase` reads the reason ElevenLabs recorded on the conversation through
  `agent-conversation`; exhausted credits come back as `ProxyError.QuotaExceeded`.
  `EndAgentSessionUseCase` stops it: the ViewModel calls it when the user toggles the agent off and in `onCleared`;
  both stops run in `AppScope`, which logs a failing stop instead of crashing.
- `ElevenLabsVoiceAgent` serializes start and stop with a `Mutex` and a generation counter (a stop during connect
  ends the session; callbacks from older generations are ignored), derives its state from status and mode
  (`onModeChange`), runs tools on `Dispatchers.Default` and SDK start and stop on `Dispatchers.IO`.
- The agents SDK takes audio focus and switches to `MODE_IN_COMMUNICATION` on connect; ExoPlayer is built with
  `handleAudioFocus=false`. The SDK's cleanup resets `MODE_NORMAL`, then about 0.6 to 0.9 s later LiveKit restores
  its saved mode; after every session end `AudioModeRestorer` restores `MODE_NORMAL` (only its own leftover mode,
  never during a call or a live session).
- The mic permission is requested on the first agent tap.
- A new session starts with the mic open; a mute made while the status is already Connected but `startSession` has not
  returned is applied when it does. The agent mode comes from the `onModeChange` callback, not the SDK's `mode`
  `StateFlow`; errors surface only as events, state changes only through status and disconnect callbacks. A session
  ended by the server or the network also runs `AudioModeRestorer`.
- On connect the app sends the conversation so far as a context update and the agent greets or continues (the agent
  allows this override); passive notices (plan, playhead, timeline cleared) are context updates the agent must not
  speak on, a responding message is sent when it must tell the user.
- `IosVoiceAgent` and `ElevenLabsAgentBridge` chain lifecycle operations under a fresh session id per `start`/`stop`;
  a session is current only while its id is the newest, a newer `start`/`stop` cancels a connect in flight, and
  callbacks of stale sessions are dropped. Only the first error of a failed start is forwarded (the SDK may follow a
  room failure with `.localNetworkPermissionRequired`). The bridge's closures hold the session weakly and an ended
  session drops its conversation. A tool call reaching a stale session still gets a reply; if the conversation is nil
  or released at that moment the reply is dropped.
- A Kotlin `String` passed as an `NSLog` vararg is not an `NSString`; the iOS logger escapes it into the format.

## Threading

- The ViewModel, `EditPipeline` and the audio engines run on Main. The ViewModel creates its `EditPipeline` on
  `viewModelScope` through `EditPipelineFactory`; splice, bake, edge similarity and normalisation run on
  `Dispatchers.Default`, the engine swap calls stay on Main.
- Everything below them is main-safe: `ProxyApi` (body read, JSON formatting, logging) and the decoders on
  `Dispatchers.IO` (decoders check cancellation between buffers), `AudioStore` and Room on `Dispatchers.IO`.
- `AppScope` (`SupervisorJob` on `Dispatchers.Default`, never cancelled) runs `agent.stop()` for both the toggle
  stop and the stop after `onCleared`; a failing job is logged.
- The playback loader and feed threads talk to Main only through the lock-free `SwappableMaster` and `SmoothedGain`
  and `StateFlow`s.
- The playback position is a separate `StateFlow` polled every 50 ms, not part of `ProducerUiState`; only the track
  card reads it in composition, the shader reads it per frame outside composition.
- Engine and voice agent are app-scoped Koin singletons; the ViewModel owns `EditPipeline` and `DuckController`.
- All mutable ViewModel state is touched on Main only; agent tool calls arrive on other threads and hop to Main.
  `Media3AudioEngine` and `IosAudioEngine` are used from Main.
- `SwappableMaster.requestSwap` and `read` may run on different threads; `SmoothedGain.setTarget` may be called from
  any thread and `nextFrameGain` from one audio thread; `ApiLog` changes are single atomic updates. `PcmPump.fill` and
  `seek` run on one consumer thread: on iOS `PcmFeeder`'s serial feed queue, to which AVFoundation buffer completions
  hop; every stop of the player node (`stop`, `seek`, `start`) bumps a generation on that queue, which makes
  completions of flushed buffers schedule nothing; Main uses `dispatch_sync` onto the queue for those short stops.
- `EditQueue` is guarded by the `EditPipeline` plan lock. A Room transaction holds no I/O: the master is written
  before it. Room's defaults: 30 s to acquire a connection, SQLite busy_timeout 3 s; the app sets neither.
- `SdkSessions` runs session teardown under `NonCancellable`, as `IosVoiceAgent.stop` does around its lock wait
  (bounded by the bridge's stop timeout); a cancelled caller still ends the session and closes the mic.

## Secrets

- ElevenLabs keys live only in the Supabase Edge Functions: the Music key and a separate agents-scoped key. The
  agent is private; the app gets a conversation token from `agent-token`.
- The app authenticates to the proxy with an access code the person enters in `AccessSheet`, sent in the
  `x-access-code` header. The proxy compares it in constant time with every code in its `ACCESS_CODES` secret; a code
  is revoked by removing it there. No secret ships in the app; the proxy has no per-user authentication, App Check or
  rate limit. `ProxyAccess` has one kind, `Code`; a user's own ElevenLabs key or a login session becomes another kind,
  with its header set in `ProxyApi`'s `authorize`.
- The code is stored by `SecureStore` (Android Keystore, iOS Keychain) and dropped on a `401`, which opens the sheet.
- The API log never records headers, the access code or agent tokens, audio bytes, or error bodies and parser messages
  of `agent-token` responses; a failing logger never turns a finished call into a failure.
- The Ktor client installs `HttpTimeout`; `ProxyApi`'s per-request compose timeout takes effect through it, and the
  explicit timeouts keep engine defaults (OkHttp: 10 s read) from cutting off a long compose response.
- The Android build reads `PROXY_BASE_URL` from `local.properties` or the environment, else the
  `voiceproducer.proxyBaseUrl` Gradle property in `gradle.properties`, into `BuildConfig`. Debug builds also read an
  optional `ACCESS_CODE` from `local.properties` as the code offered until one is stored; release builds have none.
  `scripts/gen-ios-secrets.sh` writes `PROXY_BASE_URL` (same sources) to the git-ignored `iosApp/Secrets.xcconfig`,
  which reaches Swift through `Info.plist`. `local.properties`, `.env` and `Secrets.xcconfig` are git-ignored.

## Testing

| What | Where | Runs on | Command |
|---|---|---|---|
| Domain logic, edit pipeline, use cases | `core/domain` `commonTest`, fakes from `core/testing` | JVM, Android host, iOS simulator | `:core:domain:jvmTest`, `testAndroidHostTest`, `iosSimulatorArm64Test` |
| Repositories, proxy client, migrations | `core/data` `commonTest` and `jvmTest` | JVM, Android host, iOS simulator | `:core:data:jvmTest` |
| ViewModel and presentation | `feature/producer` `commonTest` | Android host, iOS simulator | `:feature:producer:testAndroidHostTest` |
| Compose UI | `feature/producer` `androidHostTest` | JVM, Robolectric | `:feature:producer:testAndroidHostTest` |
| Platform adapters | `androidHostTest` (Robolectric), `iosTest`, `androidDeviceTest` of `core/codec`, `core/playback`, `core/agent` | JVM, iOS simulator, device | `testAndroidHostTest`, `iosSimulatorArm64Test` |
| Every `@Preview` as a screenshot | `androidApp` `PreviewScreenshotTest` | JVM, Robolectric native graphics | `verifyRoborazziDebug`, `recordRoborazziDebug` |
| Koin graph | `androidApp` `KoinGraphTest`, `composeApp` `IosKoinGraphTest` | JVM, iOS simulator | `:androidApp:testDebugUnitTest`, `iosSimulatorArm64Test` |
| Architecture | `konsist-test` | JVM | `:konsist-test:test` |
| Proxy | `supabase/functions/**/*_test.ts` | Deno | `deno test supabase/functions` |

- Fakes come from `core/testing`; no mocking library.
- `PreviewScreenshotTest` (Roborazzi, Robolectric native graphics, `w392dp-h820dp-xhdpi`) finds every `@Preview`
  under `com.voiceproducer` with ComposablePreviewScanner (`scanPackageTrees("com.voiceproducer")`, private previews
  included), renders it in a compose test rule and compares it with its golden in
  `androidApp/src/test/screenshots/<declaring class>.<method>.png`. `./gradlew :androidApp:recordRoborazziDebug`
  re-records the goldens, `:androidApp:verifyRoborazziDebug` (part of `verify`) compares.
- `scripts/contact_sheet.py <out.png> [images...]` (Pillow) puts screenshots on one sheet, by default every golden in
  `androidApp/src/test/screenshots/`.
- `docs/device-checklist.md` lists what is checked on a device by hand.

## Static analysis

- **ktlint** `1.8.0` through Spotless over every `.kt` and `.kts`, with the Compose ktlint rules; settings in the root
  `build.gradle.kts` and `.editorconfig`. `./gradlew spotlessApply` formats, `spotlessCheck` verifies.
- **detekt** (`dev.detekt` with the Compose rules, applied by `template.detekt` to every module);
  `config/detekt/detekt.yml` on top of the defaults, no baseline. `./gradlew detekt` runs every module,
  `:<module>:detekt` one; reports in `<module>/build/reports/detekt/`.
- **Android Lint** from `androidApp` over every module, warnings are errors; `config/lint/lint.xml`, no baseline.
  `./gradlew :androidApp:lintDebug`; reports in `androidApp/build/reports/lint-results-debug.*`.
- `./gradlew verify` runs `spotlessCheck`, `detekt` of every module, `:androidApp:lintDebug`,
  `:androidApp:assertModuleGraph`, `:konsist-test:test`, the Android host tests of every KMP module, the `jvmTest` of
  modules with `kmp.jvmTests=true`, `:androidApp:testDebugUnitTest` and `:androidApp:verifyRoborazziDebug`.
- Media3 classes marked `@UnstableApi` are used under `@OptIn(UnstableApi::class)` together with
  `@androidx.annotation.OptIn(markerClass = [UnstableApi::class])`.
- Composable parameters that hold collections use `kotlinx.collections.immutable.ImmutableList`.

## Delivery

### Branching

| Branch | Rule |
|---|---|
| `main` | Always green and releasable. Changes land through PRs, squash-merged. |
| `feat/*`, `fix/*`, `chore/*`, `docs/*`, `refactor/*` | Short-lived. Unfinished work hides behind a feature flag. |
| `release/<major>.<minor>` | Cut from `main` at code freeze. Only fixes, made on `main` first and cherry-picked. |
| tag `v<major>.<minor>.<patch>` | Set on a release branch. A hotfix is a fix on `main`, a cherry-pick, a patch tag. |

### CI

| Trigger | Workflow | What runs |
|---|---|---|
| Every push to a PR, the merge queue, `main` | `ci.yml` | `Changes` → `Gates` (Ubuntu, `./gradlew verify`), `iOS` (macOS, simulator tests and app build) and `Supabase` (`deno test supabase/functions`) → `CI status` |
| Weekday nights, by hand | `nightly.yml` | R8 release APK and bundle, release lint, iOS release build |

- **Path filters.** On a PR, `Gates` is skipped when only Markdown, `docs/`, `supabase/` or `agent/` changed;
  `iOS` runs only when `core/`, `feature/`, `composeApp/`, `iosApp/`, the build or `ci.yml` changed; `Supabase`
  runs only when `supabase/`, `agent/` or `ci.yml` changed. `main`, the merge queue and manual runs run every job.
- **Required check.** `CI status` is the required check; it passes when the other jobs passed or were skipped.
- **Branch protection.** The required `CI status` check and rulesets need GitHub Pro or a public repository; on a
  private Free repository they are unavailable.
- **Build cache.** `setup-gradle` saves the Gradle home with its build cache on `main`; PRs restore it read-only.
- **Screenshots.** On a PR from this repository a failed comparison makes CI re-record the goldens, commit them to
  the branch and fail.
- **Reports.** Every `Gates` run uploads the `reports` artifact: detekt, lint, test results, screenshot comparisons.
- **Secrets.** `GRADLE_ENCRYPTION_KEY` encrypts the cached configuration cache; the workflows pass it to
  `setup-gradle` and run without it. No workflow uses another secret: the Android build takes the proxy URL from
  `gradle.properties` and builds with no access code; the iOS jobs write `iosApp/Secrets.xcconfig` with an empty
  URL. No workflow publishes a build: the nightly artifacts are unsigned, carry no access code, and expire after 7
  days.
- **Release signing.** `androidApp` signs the `release` build type when `signing.storeFile`, `signing.storePassword`,
  `signing.keyAlias` and `signing.keyPassword` (from `local.properties`) or `ANDROID_KEYSTORE_PATH`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` (environment) are all set; without
  them the release APK stays unsigned. The keystore and its passwords are never committed.
- **Release version.** `androidApp` takes `versionName` and `versionCode` from the Gradle properties of the same
  name (defaults `0.1.0` and `1`).
- **CI Gradle settings** are in `.github/ci-gradle.properties`, copied to `~/.gradle/gradle.properties` by each job.
- **PR description** follows `.github/pull_request_template.md`.

## Exceptions

Places that depart from the rules above or from the template; everything else follows them.

- The ViewModel calls playback control (`play`, `pause`, `seekTo`, `setDuck`, `positionMs`) on `AudioEngine`,
  conversation history reads and writes on `ConversationHistory`, the API log on `ApiLogFeed`, and track titles and
  the orphan-master sweep on `TrackLibrary` directly, without use cases.
- `ApiLog` (implements `ApiLogFeed`) is public: `composeApp`'s `platformModule` constructs it with the platform logger.
- `core/model` uses kotlinx.serialization: `CompositionPlan` (with `ChunkSerializer`) and `SourceMap` are
  `@Serializable`.
- `core/domain` imports Koin in `di/` to bind its use cases.
- `core/model`, `core/dsp`, `core/common`, `core/codec`, `core/testing`, `core/domain`, `core/database` and
  `core/data` set `kmp.jvmTests=true`: each has a `jvm()` target and a `jvmTest` task, and `verify` runs every one.
  `core/domain`, `core/database` and `core/data` have `jvmTest` sources. The `jvm` actuals of `core/database`'s
  `databaseModule` and of `core/codec`'s `codecModule` are empty; there is no decoder on the `jvm` target.
- There is one screen: `composeApp`'s `App()` shows the producer screen directly, without a `NavHost`, and
  `feature/producer` has no `navigation/ProducerRoute`.
- On iOS, `mainViewController()` re-checks the microphone authorization on every resume, as `MainActivity` does on
  Android.
- detekt is `2.0.0-alpha.6`; Material 3 is `1.12.0-alpha03`, updated together with Compose Multiplatform.
- `config/lint/lint.xml` ignores the Lint issues `GradleDependency`, `NewerVersionAvailable`,
  `AndroidGradlePluginVersion` and `MissingApplicationIcon`, and `OldTargetApi` in `gradle/libs.versions.toml`:
  `targetSdk` stays 36 while `compileSdk` is 37.
- `PreviewScreenshotTest` compares with a change threshold of 1 % (`RoborazziOptions.CompareOptions(changeThreshold
  = 0.01f)`), runs at the device qualifier `w392dp-h820dp-xhdpi` and captures through a compose test rule.
- Robolectric runs with `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` and
  `--add-opens=java.base/java.io=ALL-UNNAMED`, set by `template.android.application` and, for the Android host tests
  of KMP modules, by `template.kmp.library`. JDK 25 (local) needs them; on JDK 21 (CI) they are harmless. The
  Application class under Robolectric is plain, set in `robolectric.properties`.
- `iosApp`'s `Info.plist` sets `CADisableMinimumFrameDurationOnPhone` to true.
- The iOS app builds for arm64 simulators only (`iosSimulatorArm64`; `xcodebuild ... ARCHS=arm64`); there is no
  x86_64 simulator target.
- `Media3AudioEngine` builds ExoPlayer with `handleAudioFocus=false`; a click at agent connect is expected.
- `androidApp` declares `compileOnly(libs.livekit.android)`; the merged manifest removes LiveKit's
  `ScreenCaptureService`, its `CAMERA` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` permissions and marks the camera
  feature not required (screen sharing and camera are unused).
- `settings.gradle.kts` adds JitPack for `audioswitch`, which LiveKit (via `elevenlabs-android`) needs.
- Production code carries three `@Suppress`: `"DEPRECATION"` on `toggleAgentWithMic` in `composeApp`'s
  `MainViewController` (`AVAudioSession.requestRecordPermission`; its replacement `AVAudioApplication` needs iOS 17,
  the deployment target is 16), `"DEPRECATION"` on `AudioModeRestorer.readCallState` (`TelephonyManager.callState`,
  read below API 31 only, where it needs no permission; `TelephonyCallback` needs `READ_PHONE_STATE`) and
  `"KotlinNoActualForExpect"` on `AppDatabaseConstructor` (Room's KSP processor generates its actuals).
