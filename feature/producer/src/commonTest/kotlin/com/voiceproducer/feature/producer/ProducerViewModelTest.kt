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
import com.voiceproducer.core.model.ApiLogEntry
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
import com.voiceproducer.core.testing.FakeApiLogFeed
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
class ProducerViewModelTest : ProducerViewModelTestBase() {
    @Test
    fun toggleApiLogFlipsFlagAndExposesEntries() =
        runTest {
            val log = FakeApiLogFeed()
            val vm = viewModel(apiLog = log)
            advanceUntilIdle()
            assertFalse(vm.state.value.showApiLog)
            vm.onIntent(ProducerIntent.ToggleApiLog)
            assertTrue(vm.state.value.showApiLog)
            log.entries.value = listOf(ApiLogEntry(1, 0, "music-plan", "{}"))
            assertEquals(1, vm.apiLog.value.size)
            vm.onIntent(ProducerIntent.ToggleApiLog)
            assertFalse(vm.state.value.showApiLog)
        }

    @Test
    fun startsEmptyWithoutTrack() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(TrackStatus.Empty, s.track)
            assertTrue(s.sections.isEmpty())
            assertFalse(s.canUndo)
            assertFalse(s.micPermission)
            assertEquals(AgentUi(AgentState.Disconnected, null, null, micMuted = false), s.agent)
            assertTrue(engine.calls.isEmpty())
        }

    @Test
    fun startDeletesOrphanMastersOnceAndIgnoresAFailure() =
        runTest {
            library.put(v1)
            library.orphanSweepFailure = IllegalStateException("disk")
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(1, library.orphanSweeps)
            assertEquals(TrackStatus.Ready, vm.state.value.track)
        }

    @Test
    fun restoresLatestTrackOnStart() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(TrackStatus.Ready, s.track)
            assertEquals("Track 7", s.title)
            assertEquals(listOf("Intro", "Verse", "Chorus", "Outro"), s.sections.map { it.name })
            assertTrue(s.sections.all { it.status == SectionStatus.Idle })
            assertEquals(master, engine.loaded)
            assertEquals(listOf("load"), engine.calls)
            assertEquals(50_000L, s.durationMs)
            assertFalse(s.isPlaying)
        }

    @Test
    fun positionFollowsEngineWithoutRebuildingTheUiState() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val before = vm.state.value
            engine.positionMs.value = 1234
            advanceUntilIdle()
            assertEquals(1234L, vm.positionMs.value)
            assertSame(before, vm.state.value)
        }

    @Test
    fun seekToSectionSeeksEngine() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.SeekToSection(2))
            vm.onIntent(ProducerIntent.SeekToSection(9))
            assertEquals("seekTo(28000)", engine.calls.last())
            assertEquals(1, engine.calls.count { it.startsWith("seekTo") })
        }

    @Test
    fun togglePlayPausesAndResumes() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.TogglePlay)
            advanceUntilIdle()
            assertTrue(vm.state.value.isPlaying)
            vm.onIntent(ProducerIntent.TogglePlay)
            advanceUntilIdle()
            assertFalse(vm.state.value.isPlaying)
            assertEquals(listOf("load", "play", "pause"), engine.calls)
        }

    @Test
    fun regeneratingEventMarksSection() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            val result = vm.edit(chorus())
            assertEquals(ToolResult(true, "ok: regenerating Chorus (1 occurrence)"), result)
            advanceUntilIdle()
            assertEquals(
                listOf(SectionStatus.Idle, SectionStatus.Idle, SectionStatus.Regenerating, SectionStatus.Idle),
                vm.state.value.sections
                    .map { it.status },
            )
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Queued,
                vm.state.value.sections[2]
                    .status,
            )
            engine.swapsApplied.value = 1
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Idle,
                vm.state.value.sections[2]
                    .status,
            )
        }

    @Test
    fun versionSavedPushesContextToAgent() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            val saved = library.versions.getValue(2)
            assertEquals(listOf(AgentNotices.planUpdated(saved.plan)), agent.contexts)
            assertTrue(vm.state.value.canUndo)
            assertEquals(listOf("swap"), engine.calls.filter { it == "swap" })
        }

    @Test
    fun finishedEditJumpsToTheEditedSectionOnce() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(listOf("seekTo(28000)"), seeks())
            assertTrue(engine.calls.none { it == "play" || it == "pause" })
            assertFalse(vm.state.value.isPlaying)
        }

    @Test
    fun finishedEditWhilePlayingJumpsAndKeepsPlaying() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.TogglePlay)
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(listOf("seekTo(28000)"), seeks())
            assertTrue(engine.calls.none { it == "pause" })
            assertTrue(vm.state.value.isPlaying)
        }

    @Test
    fun partlyFailedEditJumpsOnceToTheSectionThatWasDone() =
        runTest {
            val twoChoruses = CompositionPlan(listOf(gen("Chorus 1", 10_000), gen("Verse", 10_000), gen("Chorus 2", 10_000)))
            library.put(version(1, null, twoChoruses))
            library.failWindowsFrom = 1
            val vm = viewModel()
            advanceUntilIdle()
            engine.positionMs.value = 15_000
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(
                listOf(SectionStatus.Failed, SectionStatus.Idle, SectionStatus.Queued),
                vm.state.value.sections
                    .map { it.status },
            )
            assertEquals(listOf("seekTo(20000)"), seeks())
        }

    @Test
    fun failedEditDoesNotJump() =
        runTest {
            library.put(v1)
            library.windowFailure = IllegalStateException("boom")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Failed,
                vm.state.value.sections[2]
                    .status,
            )
            assertEquals(emptyList(), seeks())
        }

    @Test
    fun aFailedSectionPutsANoticeInTheTranscriptWithoutASession() =
        runTest {
            library.put(v1)
            library.windowFailure = ProxyError.QuotaExceeded(137, 325)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            val text = "Couldn't regenerate Chorus: Not enough ElevenLabs credits: 137 left, 325 needed"
            assertEquals(
                text,
                (
                    vm.state.value.transcript
                        .last() as TranscriptItem.Notice
                ).text,
            )
            assertEquals(StoredMessage(MessageRole.Notice, text), history.of(7).last())
        }

    @Test
    fun sectionsFailingForTheSameReasonShareOneNotice() =
        runTest {
            library.put(v1)
            library.fullFailure = ProxyError.QuotaExceeded(null, null)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(PlanEdit.SetGlobal(add = listOf("lofi"), remove = emptyList()))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Failed },
            )
            val notices =
                vm.state.value.transcript
                    .filterIsInstance<TranscriptItem.Notice>()
            assertEquals(
                listOf("Couldn't regenerate Intro, Verse, Chorus and Outro: Not enough ElevenLabs credits"),
                notices.map { it.text },
            )
            assertEquals(1, history.of(7).count { it.role == MessageRole.Notice })
        }

    @Test
    fun rolledBackEditDoesNotJump() =
        runTest {
            library.put(v1)
            library.saveFailure = IllegalStateException("disk full")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Failed,
                vm.state.value.sections[2]
                    .status,
            )
            assertEquals(listOf("swap", "swap"), engine.calls.filter { it == "swap" })
            assertEquals(emptyList(), seeks())
        }

    @Test
    fun finishedGlobalEditJumpsToTheStart() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            engine.positionMs.value = 30_000
            vm.edit(PlanEdit.SetGlobal(add = listOf("lofi"), remove = emptyList()))
            advanceUntilIdle()
            assertEquals(listOf("seekTo(0)"), seeks())
        }

    @Test
    fun undoJumpsToTheRestoredSection() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            engine.calls.clear()
            engine.positionMs.value = 45_000
            assertEquals(ToolResult(true, "ok: reverted"), vm.undo())
            advanceUntilIdle()
            assertEquals(listOf("seekTo(28000)"), seeks())
        }

    @Test
    fun undoRevertsAndPushesContext() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            agent.contexts.clear()
            assertEquals(ToolResult(true, "ok: reverted"), vm.undo())
            advanceUntilIdle()
            assertEquals(listOf(AgentNotices.reverted(v1.plan)), agent.contexts)
            assertFalse(vm.state.value.canUndo)
            assertEquals(ToolResult(false, "nothing to undo"), vm.undo())
        }

    @Test
    fun undoWhileEditRunsRepliesAtOnceAndRevertsAfterIt() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(ToolResult(true, "ok: will revert after the current edit finishes"), vm.undo())
            assertTrue(agent.contexts.isEmpty())
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    AgentNotices.planUpdated(library.versions.getValue(2).plan),
                    AgentNotices.reverted(v1.plan),
                ),
                agent.contexts,
            )
            assertEquals(1L, library.latestId)
            assertFalse(vm.state.value.canUndo)
        }

    @Test
    fun undoWhileFailingEditRunsReportsNothingToUndo() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            library.windowFailure = IllegalStateException("boom")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(ToolResult(true, "ok: will revert after the current edit finishes"), vm.undo())
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("[system] Regenerating Chorus failed: boom", "[system] Nothing to undo"), agent.contexts)
            assertEquals(
                SectionStatus.Failed,
                vm.state.value.sections[2]
                    .status,
            )
        }

    @Test
    fun undoClearsFailedSections() =
        runTest {
            library.put(v1, current = false)
            library.put(version(2, parent = 1))
            library.windowFailure = IllegalStateException("boom")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Failed,
                vm.state.value.sections[2]
                    .status,
            )
            assertEquals(ToolResult(true, "ok: reverted"), vm.undo())
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Idle },
            )
        }

    @Test
    fun composeWhileEditRunsIsRefused() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            assertEquals(
                ToolResult(false, "an edit is still in progress; try again shortly"),
                vm.composeTrack("new", instrumental = false, lengthMs = 60_000),
            )
            assertTrue(library.created.isEmpty())
            assertEquals(TrackStatus.Ready, vm.state.value.track)
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun concurrentEditAndComposeDoNotBothRun() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            val edit = async { vm.edit(chorus()) }
            val compose = async { vm.composeTrack("new", instrumental = false, lengthMs = 60_000) }
            runCurrent()
            assertTrue(edit.await().ok)
            assertFalse(compose.await().ok)
            assertTrue(library.created.isEmpty())
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
        }
}
