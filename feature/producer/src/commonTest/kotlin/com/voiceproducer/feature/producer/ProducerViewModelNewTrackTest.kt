package com.voiceproducer.feature.producer

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProducerViewModelNewTrackTest : ProducerViewModelTestBase() {
    @Test
    fun newTrackClearsStateAndEngineAndPersists() =
        runTest {
            val vm = vmWithPlayingTrack()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(TrackStatus.Empty, s.track)
            assertNull(s.title)
            assertTrue(s.sections.isEmpty())
            assertFalse(s.canUndo)
            assertTrue(s.chips.isEmpty())
            assertFalse(s.isPlaying)
            assertEquals(0L, s.durationMs)
            assertNull(engine.loaded)
            assertEquals("unload", engine.calls.last())
            assertNull(library.latestId)
            assertTrue(s.transcript.isEmpty())
            assertEquals(ToolResult(false, "there is no track yet; call compose_track first"), vm.edit(chorus()))
        }

    @Test
    fun freshViewModelAfterNewTrackShowsEmptyState() =
        runTest {
            val vm = vmWithPlayingTrack()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            val fresh = viewModel()
            advanceUntilIdle()
            assertEquals(TrackStatus.Empty, fresh.state.value.track)
            assertTrue(
                fresh.state.value.sections
                    .isEmpty(),
            )
        }

    @Test
    fun composeTrackWorksAfterNewTrack() =
        runTest {
            val vm = vmWithPlayingTrack()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            library.createGate.complete(Unit)
            assertTrue(vm.composeTrack("synthwave", instrumental = false, lengthMs = 60_000).ok)
            advanceUntilIdle()
            assertEquals(TrackStatus.Ready, vm.state.value.track)
            assertEquals(4, vm.state.value.sections.size)
            assertEquals(master, engine.loaded)
        }

    @Test
    fun newTrackIsIgnoredWhileGenerating() =
        runTest {
            val vm = vmWithPlayingTrack()
            vm.composeTrack("synthwave", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            assertEquals(TrackStatus.Generating, vm.state.value.track)
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertEquals(TrackStatus.Generating, vm.state.value.track)
            assertEquals(4, vm.state.value.sections.size)
            assertEquals(1, library.created.size)
            assertFalse("unload" in engine.calls)
            library.createGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun newTrackIsIgnoredWhileAnEditRuns() =
        runTest {
            library.windowGate = CompletableDeferred()
            val vm = vmWithPlayingTrack()
            vm.edit(chorus())
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertEquals(TrackStatus.Ready, vm.state.value.track)
            assertEquals(4, vm.state.value.sections.size)
            assertFalse("unload" in engine.calls)
            assertEquals(1L, library.latestId)
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun undoWhileNewTrackIsBeingSavedIsRefused() =
        runTest {
            library.put(v1, current = false)
            library.put(version(2, parent = 1))
            library.clearGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.NewTrack)
            runCurrent()
            assertEquals(
                ToolResult(false, "the timeline is being cleared; try again shortly"),
                vm.undo(),
            )
            vm.onIntent(ProducerIntent.Undo)
            runCurrent()
            library.clearGate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(library.setCurrentCalls.isEmpty())
            assertNull(library.latestId)
            assertEquals(TrackStatus.Empty, vm.state.value.track)
            assertTrue(
                vm.state.value.sections
                    .isEmpty(),
            )
            assertEquals(listOf("load", "unload"), engine.calls)
        }

    @Test
    fun newTrackWithNoTrackDoesNothing() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertTrue(
                vm.state.value.transcript
                    .isEmpty(),
            )
            assertTrue(engine.calls.isEmpty())
        }

    @Test
    fun newTrackTellsAConnectedAgentPassively() =
        runTest {
            val vm = vmWithPlayingTrack()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            vm.onIntent(ProducerIntent.NewTrack)
            runCurrent()
            assertEquals(
                "[system] The producer cleared the timeline to start a new track. The previous track is saved but no " +
                    "longer loaded; the current plan is empty. Wait for the producer's brief.",
                agent.contexts.last(),
            )
            assertTrue(agent.userTexts.isEmpty())
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun newTrackWithoutAgentSendsNothingAndLaterSessionStartsWithEmptyPlan() =
        runTest {
            val vm = vmWithPlayingTrack()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertTrue(agent.contexts.isEmpty())
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals("No track yet.", agent.started.single().second)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun failedPersistenceLeavesTheTrackInPlace() =
        runTest {
            val vm = vmWithPlayingTrack()
            library.clearFailure = IllegalStateException("db closed")
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertEquals(TrackStatus.Ready, vm.state.value.track)
            assertEquals(4, vm.state.value.sections.size)
            assertEquals("Could not start a new track: db closed", vm.state.value.agent.error)
            assertFalse("unload" in engine.calls)
        }

    @Test
    fun titleFailureAfterComposeLeavesTitleNull() =
        runTest {
            library.titleFailure = IllegalStateException("db closed")
            library.createGate.complete(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("lofi beat", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            assertEquals(TrackStatus.Ready, vm.state.value.track)
            assertNull(vm.state.value.title)
        }

    @Test
    fun clearingStopsAgentAndPausesEngine() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            ViewModelStore().apply { put("producer", vm) }.clear()
            runCurrent()
            assertEquals(1, agent.stops)
            assertEquals("pause", engine.calls.last())
        }

    @Test
    fun undoDisabledWithoutParent() =
        runTest {
            library.put(v1, current = false)
            library.put(version(2, parent = 1))
            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(vm.state.value.canUndo)
            vm.onIntent(ProducerIntent.Undo)
            advanceUntilIdle()
            assertFalse(vm.state.value.canUndo)
            assertEquals(1L, library.latestId)
        }

    @Test
    fun unknownSectionListsAvailable() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                ToolResult(false, "unknown section 'bridge'; available: Intro, Verse, Chorus, Outro"),
                vm.edit(PlanEdit.EditSection("bridge", listOf("brass"), emptyList())),
            )
            assertEquals(ToolResult(true, "no change needed"), vm.edit(PlanEdit.SetGlobal(emptyList(), emptyList())))
        }

    @Test
    fun editWithoutTrackFails() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            assertFalse(vm.edit(chorus()).ok)
            assertEquals(ToolResult(false, "nothing to undo"), vm.undo())
        }

    @Test
    fun theNewTrackClearsThePendingConversationOnlyAfterTheQueuedWrites() =
        runTest {
            history.append(null, user("said before the track"))
            history.recentGate = CompletableDeferred()
            library.createGate.complete(Unit)
            val vm = viewModel()
            runCurrent()
            vm.composeTrack("lofi", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertEquals(TrackStatus.Empty, vm.state.value.track)
            history.recentGate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(user("said before the track") in history.of(7))
            assertTrue(history.of(null).isEmpty())
        }
}
