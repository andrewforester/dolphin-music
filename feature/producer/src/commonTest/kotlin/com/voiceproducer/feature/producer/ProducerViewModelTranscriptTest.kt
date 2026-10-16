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
class ProducerViewModelTranscriptTest : ProducerViewModelTestBase() {
    @Test
    fun transcriptsAndControlsReachUi() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            vm.state.test {
                skipItems(1)
                agent.events.emit(AgentEvent.UserTranscript("make it louder"))
                assertEquals("make it louder", awaitItem().agent.lastUserText)
                agent.events.emit(AgentEvent.AgentText("Sure."))
                assertEquals("Sure.", awaitItem().agent.lastAgentText)
                vm.onIntent(ProducerIntent.ToggleMic)
                assertTrue(awaitItem().agent.micMuted)
                vm.onIntent(ProducerIntent.MicPermission(true))
                assertTrue(awaitItem().micPermission)
            }
            assertEquals(true, agent.micMuted)
            vm.onIntent(ProducerIntent.SendText("  "))
            vm.onIntent(ProducerIntent.SendText("add drums"))
            assertEquals(listOf("add drums"), agent.userTexts)
            assertNull(vm.state.value.title)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun replyCarriesTheTimingOfItsOwnSpeech() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Speaking
            runCurrent()
            val hello = AgentEvent.SpeechChunk(7, "Hello", listOf(0, 80, 160, 240, 320), 400)
            val there = AgentEvent.SpeechChunk(7, " there", listOf(0, 40, 80, 120, 160, 200), 300)
            agent.events.emit(hello)
            agent.events.emit(AgentEvent.AgentText("Hello there", eventId = 7))
            runCurrent()
            val replyId =
                vm.state.value.transcript
                    .last()
                    .id
            assertEquals(AgentSpeech(replyId, SpeechTimeline() + hello), vm.state.value.agentSpeech)
            agent.events.emit(AgentEvent.SpeechChunk(9, "Other", listOf(0, 1, 2, 3, 4), 50))
            agent.events.emit(there)
            runCurrent()
            assertEquals(AgentSpeech(replyId, SpeechTimeline() + hello + there), vm.state.value.agentSpeech)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun speechTimingEndsWithTheNextUserTurnOrAReplyWithoutTiming() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Speaking
            runCurrent()
            agent.events.emit(AgentEvent.SpeechChunk(7, "Hi", listOf(0, 80), 160))
            agent.events.emit(AgentEvent.AgentText("Hi", eventId = 7))
            runCurrent()
            assertEquals(
                vm.state.value.transcript
                    .last()
                    .id,
                vm.state.value.agentSpeech
                    ?.itemId,
            )
            agent.events.emit(AgentEvent.UserTranscript("louder"))
            runCurrent()
            assertNull(vm.state.value.agentSpeech)
            agent.events.emit(AgentEvent.AgentText("Sure."))
            runCurrent()
            assertNull(vm.state.value.agentSpeech)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun transcriptRecordsUserAgentAndToolLines() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.TentativeTranscript("add dr"))
            agent.events.emit(AgentEvent.UserTranscript("add brass to the chorus"))
            agent.events.emit(AgentEvent.AgentText("Sure,"))
            agent.events.emit(AgentEvent.AgentText("Sure, adding brass."))
            runCurrent()
            vm.edit(PlanEdit.EditSection("chorus", listOf("brass"), emptyList()))
            vm.edit(PlanEdit.EditSection("bridge", listOf("x"), listOf("y")))
            advanceUntilIdle()
            vm.undo()
            advanceUntilIdle()
            assertEquals(
                listOf(
                    TranscriptItem.User(0, "add brass to the chorus"),
                    TranscriptItem.Agent(0, "Sure, adding brass."),
                    line(ToolKind.EditSection, "Chorus", "brass", ToolStatus.Done),
                    line(ToolKind.EditSection, "bridge", "x", ToolStatus.Failed),
                    line(ToolKind.Undo, status = ToolStatus.Done),
                ),
                vm.lines(),
            )
        }

    @Test
    fun transcriptIdsAreUniqueAndStayWhenALineUpdates() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.UserTranscript("brass"))
            agent.events.emit(AgentEvent.AgentText("Sure,"))
            runCurrent()
            val agentId =
                vm.state.value.transcript
                    .last()
                    .id
            agent.events.emit(AgentEvent.AgentText("Sure, adding it."))
            runCurrent()
            assertEquals(
                agentId,
                vm.state.value.transcript
                    .last()
                    .id,
            )
            vm.edit(chorus())
            val running =
                vm.state.value.transcript
                    .last() as TranscriptItem.Tool
            assertEquals(ToolStatus.Running, running.status)
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            val done =
                vm.state.value.transcript
                    .last() as TranscriptItem.Tool
            assertEquals(running.id to ToolStatus.Done, done.id to done.status)
            val ids =
                vm.state.value.transcript
                    .map { it.id }
            assertEquals(ids.distinct(), ids)
        }

    @Test
    fun composeLineRunsThenIsDone() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("melancholic night drive", instrumental = true, lengthMs = 60_000)
            assertEquals(listOf(line(ToolKind.Compose, status = ToolStatus.Running)), vm.lines())
            library.createGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(line(ToolKind.Compose, status = ToolStatus.Done)), vm.lines())
        }

    @Test
    fun composeLineFailsWhenGenerationFails() =
        runTest {
            library.createFailure = IllegalStateException("rate limited")
            library.createGate.complete(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("lofi", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            assertEquals(line(ToolKind.Compose, status = ToolStatus.Failed), vm.lastTool())
        }

    @Test
    fun composeLineFailsWhenRefused() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.composeTrack("a", instrumental = false, lengthMs = 60_000)
            assertEquals(false, vm.composeTrack("b", instrumental = false, lengthMs = 60_000).ok)
            assertEquals(
                listOf(line(ToolKind.Compose, status = ToolStatus.Running), line(ToolKind.Compose, status = ToolStatus.Failed)),
                vm.lines(),
            )
            library.createGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun editLineRunsUntilTheEditIsSaved() =
        runTest {
            library.put(v1)
            library.saveGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(PlanEdit.EditSection("Verse", listOf("heavier drums", "male baritone"), listOf("vocals")))
            advanceUntilIdle()
            assertEquals(line(ToolKind.EditSection, "Verse", "heavier drums, male baritone", ToolStatus.Running), vm.lastTool())
            library.saveGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(ToolStatus.Done, vm.lastTool().status)
        }

    @Test
    fun globalEditLineIsDoneWhenSaved() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(PlanEdit.SetGlobal(add = emptyList(), remove = listOf("vocals")))
            advanceUntilIdle()
            assertEquals(line(ToolKind.SetGlobal, null, "no vocals", ToolStatus.Done), vm.lastTool())
        }

    @Test
    fun editLineFailsWhenGenerationFails() =
        runTest {
            library.put(v1)
            library.windowFailure = IllegalStateException("boom")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(ToolStatus.Failed, vm.lastTool().status)
        }

    @Test
    fun editLineFailsWhenThePromptIsRejected() =
        runTest {
            library.put(v1)
            library.windowFailure = ProxyError.BadPrompt("copyrighted_material_detected", null)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(ToolStatus.Failed, vm.lastTool().status)
        }

    @Test
    fun editLineFailsWhenSavingFails() =
        runTest {
            library.put(v1)
            library.saveFailure = IllegalStateException("disk full")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(ToolStatus.Failed, vm.lastTool().status)
        }

    @Test
    fun partlyFailedEditLineFails() =
        runTest {
            val twoChoruses = CompositionPlan(listOf(gen("Chorus 1", 10_000), gen("Verse", 10_000), gen("Chorus 2", 10_000)))
            library.put(version(1, null, twoChoruses))
            library.failWindowsFrom = 1
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(line(ToolKind.EditSection, "Chorus 1, Chorus 2", "brass", ToolStatus.Failed), vm.lastTool())
        }

    @Test
    fun editLineFailsWhenRejectedAtOnce() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            assertEquals(line(ToolKind.EditSection, "Chorus", "brass", ToolStatus.Failed), vm.lastTool())
            library.put(v1)
            val vm2 = viewModel()
            advanceUntilIdle()
            vm2.edit(PlanEdit.EditSection("Chorus", emptyList(), emptyList()))
            assertEquals(line(ToolKind.EditSection, "Chorus", null, ToolStatus.Failed), vm2.lastTool())
            vm2.edit(PlanEdit.EditSection("Bridge", listOf("x"), emptyList()))
            assertEquals(line(ToolKind.EditSection, "Bridge", "x", ToolStatus.Failed), vm2.lastTool())
            vm2.composeTrack("a", instrumental = false, lengthMs = 60_000)
            vm2.edit(chorus())
            assertEquals(ToolStatus.Failed, vm2.lastTool().status)
            library.createGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun undoLineFailsWithNothingToUndo() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.undo()
            assertEquals(line(ToolKind.Undo, status = ToolStatus.Failed), vm.lastTool())
        }

    @Test
    fun undoLineWhileEditRunsIsDoneAfterTheRevert() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            vm.undo()
            assertEquals(ToolStatus.Running, vm.lastTool().status)
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(ToolStatus.Done, ToolStatus.Done),
                vm.state.value.transcript
                    .filterIsInstance<TranscriptItem.Tool>()
                    .map { it.status },
            )
        }

    @Test
    fun undoLineWhileFailingEditRunsFails() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            library.windowFailure = IllegalStateException("boom")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            vm.undo()
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(ToolStatus.Failed, ToolStatus.Failed),
                vm.state.value.transcript
                    .filterIsInstance<TranscriptItem.Tool>()
                    .map { it.status },
            )
        }

    @Test
    fun undoButtonAddsNoLine() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.Undo)
            advanceUntilIdle()
            assertFalse(vm.state.value.canUndo)
            assertEquals(1, vm.state.value.transcript.size)
        }

    @Test
    fun liveUserTextFollowsTentativeTranscriptUntilTheFinalOne() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.TentativeTranscript("make the"))
            runCurrent()
            assertEquals("make the", vm.state.value.liveUserText)
            assertTrue(
                vm.state.value.transcript
                    .isEmpty(),
            )
            agent.events.emit(AgentEvent.TentativeTranscript("make the chorus"))
            runCurrent()
            assertEquals("make the chorus", vm.state.value.liveUserText)
            agent.events.emit(AgentEvent.UserTranscript("make the chorus heavier"))
            runCurrent()
            assertNull(vm.state.value.liveUserText)
            assertEquals(listOf<TranscriptItem>(TranscriptItem.User(0, "make the chorus heavier")), vm.lines())
        }

    @Test
    fun liveUserTextClearedWhenTheSessionEnds() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            agent.state.value = AgentState.Listening
            agent.events.emit(AgentEvent.TentativeTranscript("make"))
            runCurrent()
            assertEquals("make", vm.state.value.liveUserText)
            agent.state.value = AgentState.Disconnected
            runCurrent()
            assertNull(vm.state.value.liveUserText)
            advanceTimeBy(60)
        }

    @Test
    fun liveUserTextClearedWhenTheAgentReplies() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            agent.state.value = AgentState.Listening
            agent.events.emit(AgentEvent.TentativeTranscript("make"))
            runCurrent()
            agent.events.emit(AgentEvent.AgentText("Sure."))
            runCurrent()
            assertNull(vm.state.value.liveUserText)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun liveUserTextClearedWhenTheAgentStartsSpeaking() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            agent.state.value = AgentState.Listening
            agent.events.emit(AgentEvent.TentativeTranscript("make"))
            runCurrent()
            agent.state.value = AgentState.Speaking
            runCurrent()
            assertNull(vm.state.value.liveUserText)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }

    @Test
    fun liveUserTextClearedOnNewTrack() =
        runTest {
            val vm = vmWithPlayingTrack()
            agent.events.emit(AgentEvent.TentativeTranscript("start"))
            runCurrent()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertNull(vm.state.value.liveUserText)
        }

    @Test
    fun transcriptKeepsLastThirtyItems() =
        runTest {
            val vm = viewModel()
            runCurrent()
            repeat(32) {
                agent.events.emit(AgentEvent.UserTranscript("u$it"))
                runCurrent()
            }
            val t = vm.state.value.transcript
            assertEquals(30, t.size)
            assertEquals("u2", (t.first() as TranscriptItem.User).text)
        }
}
