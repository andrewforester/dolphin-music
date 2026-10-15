package com.voiceproducer.feature.producer

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.AgentToolHandler
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.domain.repository.TrackLibrary
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProducerViewModelAgentTest : ProducerViewModelTestBase() {
    @Test
    fun composeTrackRepliesAtOnceAndPlaysWhenReady() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val result = vm.composeTrack("lofi beat", instrumental = true, lengthMs = 60_000)
            assertEquals(ToolResult(true, "ok: generating, about 15 seconds"), result)
            advanceUntilIdle()
            assertEquals(TrackStatus.Generating, vm.state.value.track)
            assertFalse(vm.composeTrack("again", false, 60_000).ok)
            assertFalse(vm.edit(chorus()).ok)

            library.createGate.complete(Unit)
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(TrackStatus.Ready, s.track)
            assertEquals(listOf("Intro", "Verse", "Chorus", "Outro"), s.sections.map { it.name })
            assertEquals(listOf("load", "play"), engine.calls)
            assertEquals(listOf(Triple("lofi beat", true, 60_000L)), library.created)
            assertEquals(listOf(AgentNotices.trackReady(plan)), agent.contexts)
            assertTrue(vm.edit(chorus()).ok)
        }

    @Test
    fun composeBadPromptShowsHumanErrorAndTellsAgentWhatToDo() =
        runTest {
            library.createFailure = rejected
            library.createGate.complete(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("Paul Mauriat style", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            assertEquals(TrackStatus.Error("Prompt rejected: it names artists or copyrighted material"), vm.state.value.track)
            assertTrue(agent.contexts.isEmpty())
            val notice = agent.userTexts.single()
            assertTrue(notice.contains("FAILED"), notice)
            assertTrue(notice.contains("no track is coming"), notice)
            assertTrue(notice.contains("copyrighted"), notice)
            assertTrue(notice.contains("without any artist or song names"), notice)
            assertTrue(notice.contains("lush orchestra with piano"), notice)
            assertTrue(notice.contains("call compose_track again only after the user agrees"), notice)
        }

    @Test
    fun composeBadPromptWithoutSuggestionOmitsIt() =
        runTest {
            library.createFailure = ProxyError.BadPrompt(null, null)
            library.createGate.complete(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("x", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            val notice = agent.userTexts.single()
            assertFalse(notice.contains("Suggested prompt"), notice)
            assertTrue(notice.contains("FAILED"))
        }

    @Test
    fun editBadPromptTellsAgentWhichSectionAndToRetry() =
        runTest {
            library.put(v1)
            library.windowFailure = rejected
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Failed,
                vm.state.value.sections[2]
                    .status,
            )
            val notice = agent.userTexts.single()
            assertTrue(notice.contains("edit to Chorus FAILED"), notice)
            assertTrue(notice.contains("lush orchestra with piano"), notice)
            assertTrue(notice.contains("repeat the edit with the new wording only after the user agrees"), notice)
            assertFalse(agent.contexts.any { it.contains("Chorus failed") })
        }

    @Test
    fun composeFailureShowsError() =
        runTest {
            library.createFailure = IllegalStateException("rate limited")
            library.createGate.complete(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("lofi beat", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            assertEquals(TrackStatus.Error("rate limited"), vm.state.value.track)
            assertTrue(agent.contexts.isEmpty())
            assertEquals(1, agent.userTexts.size)
            assertTrue(agent.userTexts.single().startsWith("[system] Track generation FAILED"), agent.userTexts.single())
            assertTrue(agent.userTexts.single().contains("rate limited"))
        }

    @Test
    fun toggleAgentStartsWithTokenAndPlan() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(listOf("tok-1" to AgentNotices.plan(plan)), agent.started)
            assertIs<AgentToolHandler>(agent.tools)
            assertEquals(AgentState.Connecting, vm.state.value.agent.state)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(1, agent.stops)
            assertEquals(AgentState.Disconnected, vm.state.value.agent.state)
            advanceUntilIdle()
        }

    @Test
    fun toggleWhileConnectingStopsSession() =
        runTest {
            agent.startGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(AgentState.Connecting, vm.state.value.agent.state)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(1, agent.stops)
            assertEquals(AgentState.Disconnected, vm.state.value.agent.state)
            advanceUntilIdle()
        }

    @Test
    fun toggleWhileFetchingTokenCancelsConnect() =
        runTest {
            val pendingToken = CompletableDeferred<String>()
            token = { pendingToken.await() }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleAgent)
            advanceUntilIdle()
            pendingToken.complete("late")
            advanceUntilIdle()
            assertTrue(agent.started.isEmpty())
            assertEquals(0, agent.stops)

            token = { "tok-3" }
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(listOf("tok-3" to "No track yet."), agent.started)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun connectingShowsAtTheTapWhileTheTokenIsFetched() =
        runTest {
            val pendingToken = CompletableDeferred<String>()
            token = { pendingToken.await() }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            assertEquals(AgentActivity.Connecting, vm.state.value.agentActivity)
            runCurrent()
            assertEquals(AgentActivity.Connecting, vm.state.value.agentActivity)
            pendingToken.complete("tok")
            runCurrent()
            assertEquals(AgentActivity.Connecting, vm.state.value.agentActivity)
            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(AgentActivity.Listening, vm.state.value.agentActivity)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
        }

    @Test
    fun cancellingWhileFetchingTheTokenReturnsToIdle() =
        runTest {
            token = { CompletableDeferred<String>().await() }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleAgent)
            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
            advanceUntilIdle()
            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
        }

    @Test
    fun aFailedTokenReturnsToIdleWithTheError() =
        runTest {
            token = { throw IllegalStateException("offline") }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            assertEquals(AgentActivity.Connecting, vm.state.value.agentActivity)
            advanceUntilIdle()
            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
            assertTrue(
                vm.state.value.agent.error!!
                    .startsWith("Could not connect to the agent"),
            )
        }

    @Test
    fun aSessionRefusedForCreditsSaysSoInsteadOfGoingSilentlyIdle() =
        runTest {
            tokens.refusal = { ProxyError.QuotaExceeded(null, null) }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()

            agent.events.emit(AgentEvent.Refused)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()

            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
            assertEquals("Could not connect to the agent: Not enough ElevenLabs credits", vm.state.value.agent.error)
            assertEquals(listOf("conv_1"), tokens.refusalLookups)
        }

    @Test
    fun aRefusalShowsAtOnceAndKeepsItsGeneralTextWhenTheReasonIsUnknown() =
        runTest {
            val lookup = CompletableDeferred<Exception>()
            tokens.refusal = { lookup.await() }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()

            agent.events.emit(AgentEvent.Refused)
            agent.state.value = AgentState.Disconnected
            runCurrent()
            val unexplained = "Could not connect to the agent: the agent ended the session before it began"
            assertEquals(unexplained, vm.state.value.agent.error)

            lookup.completeExceptionally(IllegalStateException("http 502"))
            advanceUntilIdle()
            assertEquals(unexplained, vm.state.value.agent.error)
        }

    @Test
    fun aLateReasonDoesNotComeBackAfterTheNextTap() =
        runTest {
            val lookup = CompletableDeferred<Exception>()
            tokens.refusal = { lookup.await() }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.events.emit(AgentEvent.Refused)
            agent.state.value = AgentState.Disconnected
            runCurrent()

            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            lookup.complete(ProxyError.QuotaExceeded(null, null))
            runCurrent()

            assertNull(vm.state.value.agent.error)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun startsWithEmptyPlanWithoutTrack() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(listOf("tok-1" to "No track yet."), agent.started)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun tokenFailureKeepsAgentDisconnectedWithError() =
        runTest {
            token = { error("http 500") }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            advanceUntilIdle()
            assertTrue(agent.started.isEmpty())
            assertTrue(agent.contexts.isEmpty())
            assertTrue(engine.ducks.isEmpty())
            val ui = vm.state.value.agent
            assertEquals(AgentState.Disconnected, ui.state)
            assertEquals("Could not connect to the agent: http 500", ui.error)

            token = { "tok-2" }
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertNull(vm.state.value.agent.error)
            assertEquals(listOf("tok-2" to "No track yet."), agent.started)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun agentErrorEventShownInUi() =
        runTest {
            val vm = viewModel()
            runCurrent()
            agent.events.emit(AgentEvent.Error("connection lost"))
            runCurrent()
            assertEquals("connection lost", vm.state.value.agent.error)
        }

    @Test
    fun agentSpeakingDucksEngine() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertTrue(engine.ducks.isEmpty())

            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(DuckTarget(-6f, 400), engine.ducks.last())

            agent.state.value = AgentState.Speaking
            advanceTimeBy(60)
            assertEquals(DuckTarget(-18f, 80), engine.ducks.last())
            assertEquals(AgentState.Speaking, vm.state.value.agent.state)

            agent.state.value = AgentState.Listening
            advanceTimeBy(500)
            assertEquals(DuckTarget(-18f, 80), engine.ducks.last())
            advanceTimeBy(600)
            assertEquals(DuckTarget(-6f, 400), engine.ducks.last())

            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
            assertEquals(DuckTarget(0f, 400), engine.ducks.last())
            val count = engine.ducks.size
            advanceTimeBy(5_000)
            assertEquals(count, engine.ducks.size)
        }

    @Test
    fun userSpeechEventDucksEngine() =
        runTest {
            viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.events.emit(AgentEvent.VadScore(0.9f))
            advanceTimeBy(60)
            assertEquals(DuckTarget(-18f, 80), engine.ducks.last())
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun vadScoreDrivesUserVoiceLevel() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(0f, vm.state.value.userVoiceLevel)
            agent.events.emit(AgentEvent.VadScore(0.9f))
            runCurrent()
            assertEquals(0.9f, vm.state.value.userVoiceLevel)
            agent.events.emit(AgentEvent.VadScore(0.1f))
            runCurrent()
            val falling = vm.state.value.userVoiceLevel
            assertTrue(falling > 0.1f && falling < 0.9f, "decays: $falling")
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
            assertEquals(0f, vm.state.value.userVoiceLevel)
        }

    @Test
    fun userVoiceLevelDropsWhenVadScoresStop() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.events.emit(AgentEvent.VadScore(0.9f))
            runCurrent()
            advanceTimeBy(600)
            assertEquals(0f, vm.state.value.userVoiceLevel)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun mutingSilencesUserVoiceLevel() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.events.emit(AgentEvent.VadScore(0.9f))
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleMic)
            assertEquals(0f, vm.state.value.userVoiceLevel)
            agent.events.emit(AgentEvent.VadScore(0.9f))
            runCurrent()
            assertEquals(0f, vm.state.value.userVoiceLevel)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun muteDoesNotOutliveTheSession() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleMic)
            assertTrue(vm.state.value.agent.micMuted)
            agent.state.value = AgentState.Disconnected
            runCurrent()
            assertFalse(vm.state.value.agent.micMuted, "a new session starts with the mic open")
            agent.state.value = AgentState.Listening
            runCurrent()
            assertFalse(vm.state.value.agent.micMuted)
            agent.events.emit(AgentEvent.VadScore(0.9f))
            runCurrent()
            assertEquals(0.9f, vm.state.value.userVoiceLevel)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun toggleMicIgnoredUnlessConnected() =
        runTest {
            val vm = viewModel()
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleMic)
            agent.state.value = AgentState.Connecting
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleMic)
            assertFalse(vm.state.value.agent.micMuted)
            assertNull(agent.micMuted)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun theSessionStartsWithTheConversationAsItIsWhenTheTokenArrives() =
        runTest {
            val pendingToken = CompletableDeferred<String>()
            token = { pendingToken.await() }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.events.emit(AgentEvent.UserTranscript("make it darker"))
            runCurrent()
            pendingToken.complete("tok-1")
            runCurrent()
            val firstMessages = agent.firstMessages.toList()
            agent.state.value = AgentState.Listening
            runCurrent()
            val contexts = agent.contexts.toList()
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
            assertEquals(listOf<String?>("Let's continue."), firstMessages)
            assertEquals(listOf("$earlierHeading\nProducer: make it darker"), contexts)
        }

    @Test
    fun aFailingAgentStartIsNotReportedAsATokenFailure() {
        val failure = IllegalStateException("sdk down")
        val thrown =
            assertFailsWith<IllegalStateException> {
                runTest {
                    agent.startFailure = failure
                    val vm = viewModel()
                    advanceUntilIdle()
                    vm.onIntent(ProducerIntent.ToggleAgent)
                    advanceUntilIdle()
                    assertNull(vm.state.value.agent.error)
                }
            }
        assertEquals("sdk down", thrown.message)
    }
}
