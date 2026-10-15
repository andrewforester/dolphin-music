package com.voiceproducer.feature.producer

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.AgentToken
import com.voiceproducer.core.domain.agent.DemoSwitch
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.edit.EditPipelineFactory
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.ApiLogFeed
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.domain.usecase.ComposeTrackUseCase
import com.voiceproducer.core.domain.usecase.EndAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.ExplainAgentRefusalUseCase
import com.voiceproducer.core.domain.usecase.RestoreLatestTrackUseCase
import com.voiceproducer.core.domain.usecase.StartAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.StartNewTrackUseCase
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.HistoryEntry
import com.voiceproducer.core.model.MessageRole
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.ProxyError
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.StoredMessage
import com.voiceproducer.core.model.TrackVersion
import com.voiceproducer.core.testing.FakeAgent
import com.voiceproducer.core.testing.FakeAgentTokens
import com.voiceproducer.core.testing.FakeApiLogFeed
import com.voiceproducer.core.testing.FakeEngine
import com.voiceproducer.core.testing.FakeHistory
import com.voiceproducer.core.testing.FakeLibrary
import com.voiceproducer.core.testing.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
abstract class ProducerViewModelTestBase {
    internal companion object {
        const val RATE = 1000
    }

    protected fun tone(ms: Long) =
        PcmAudio(
            ShortArray((ms * RATE / 1000).toInt()) { (8000 * sin(2 * PI * 200 * it / RATE)).toInt().toShort() },
            RATE,
            1,
        )

    protected fun gen(
        name: String,
        ms: Long,
    ) = GenerationChunk(text = "[$name]\nla la", durationMs = ms)

    protected val plan =
        CompositionPlan(
            listOf(gen("Intro", 8_000), gen("Verse", 20_000), gen("Chorus", 12_000), gen("Outro", 10_000)),
        )

    protected val master = tone(plan.totalMs)

    protected fun version(
        id: Long,
        parent: Long?,
        plan: CompositionPlan = this.plan,
    ) = TrackVersion(
        trackId = 7,
        versionId = id,
        number = id.toInt(),
        parentVersionId = parent,
        plan = plan,
        sourceMap = SourceMap.single("A", plan.totalMs),
        audioPath = "v$id.wav",
        editedSection = null,
        diffRangesMs = emptyList(),
    )

    protected val v1 = version(1, null)

    protected val library = FakeLibrary(master, composed = { version(100, null) }, render = { tone(it.totalMs) })

    protected val history = FakeHistory()

    protected val engine = FakeEngine()

    protected val agent = FakeAgent()

    protected var token: suspend () -> String = { "tok-1" }

    protected val tokens = FakeAgentTokens { AgentToken(token(), "conv_1") }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    protected fun TestScope.viewModel(
        apiLog: ApiLogFeed = FakeApiLogFeed(),
        demo: DemoSwitch? = null,
    ): ProducerViewModel =
        ProducerViewModel(
            ComposeTrackUseCase(library, engine),
            StartNewTrackUseCase(library, engine, history),
            RestoreLatestTrackUseCase(library, engine),
            StartAgentSessionUseCase(tokens, agent),
            ExplainAgentRefusalUseCase(tokens),
            EndAgentSessionUseCase(agent, appScope = backgroundScope),
            EditPipelineFactory(library, engine, cpuDispatcher = StandardTestDispatcher(testScheduler)),
            library,
            engine,
            agent,
            history,
            apiLog = apiLog,
            clock = { testScheduler.currentTime },
            demo = demo,
        )

    protected fun chorus() = PlanEdit.EditSection("Chorus", add = listOf("brass"), remove = emptyList())

    protected fun seeks() = engine.calls.filter { it.startsWith("seekTo") }

    protected fun TestScope.vmWithPlayingTrack(): ProducerViewModel {
        library.put(v1)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(ProducerIntent.TogglePlay)
        advanceUntilIdle()
        return vm
    }

    protected val rejected = ProxyError.BadPrompt("copyrighted_material_detected", "lush orchestra with piano")

    protected fun line(
        kind: ToolKind,
        section: String? = null,
        detail: String? = null,
        status: ToolStatus,
    ) = TranscriptItem.Tool(0, kind, section, detail, status)

    protected fun ProducerViewModel.lines(): List<TranscriptItem> =
        state.value.transcript.map {
            when (it) {
                is TranscriptItem.User -> it.copy(id = 0)
                is TranscriptItem.Agent -> it.copy(id = 0)
                is TranscriptItem.Tool -> it.copy(id = 0)
                is TranscriptItem.Notice -> it.copy(id = 0)
            }
        }

    protected fun ProducerViewModel.lastTool(): TranscriptItem.Tool =
        state.value.transcript
            .filterIsInstance<TranscriptItem.Tool>()
            .last()
            .copy(id = 0)

    protected fun user(text: String) = StoredMessage(MessageRole.User, text)

    protected fun reply(text: String) = StoredMessage(MessageRole.Agent, text)

    protected fun storedTool(
        label: String,
        kind: String,
        status: String,
        section: String? = null,
        detail: String? = null,
    ) = StoredMessage(MessageRole.Tool, label, kind, status, section, detail)

    protected val earlierHeading = "[system] Earlier conversation on this track, oldest first:"
}
