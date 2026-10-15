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
class ProducerViewModelSectionsTest : ProducerViewModelTestBase() {
    @Test
    fun sectionsCarryKindAndStartAtVersionOne() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            val sections = vm.state.value.sections
            assertEquals(
                listOf(SectionKind.Intro, SectionKind.Verse, SectionKind.Chorus, SectionKind.Outro),
                sections.map { it.kind },
            )
            assertTrue(sections.all { it.version == 1 && it.lastTag == null })
        }

    @Test
    fun sectionVersionBumpsWhenSwapIsAppliedAndUndoRestoresIt() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            val queued = vm.state.value.sections[2]
            assertEquals(SectionStatus.Queued, queued.status)
            assertEquals(1, queued.version)
            engine.swapsApplied.value = 1
            advanceUntilIdle()
            assertEquals(
                SectionUi(2, "Chorus", 28_000, 40_000, SectionStatus.Idle, SectionKind.Chorus, 2, "brass"),
                vm.state.value.sections[2],
            )

            vm.edit(PlanEdit.EditSection("Chorus", add = listOf("strings", "harp"), remove = emptyList()))
            advanceUntilIdle()
            engine.swapsApplied.value = 2
            advanceUntilIdle()
            assertEquals(
                3 to "strings",
                vm.state.value.sections[2]
                    .let { it.version to it.lastTag },
            )
            assertEquals(
                1,
                vm.state.value.sections[1]
                    .version,
            )

            vm.undo()
            advanceUntilIdle()
            assertEquals(
                2 to "brass",
                vm.state.value.sections[2]
                    .let { it.version to it.lastTag },
            )
            vm.undo()
            advanceUntilIdle()
            assertEquals(
                1 to null,
                vm.state.value.sections[2]
                    .let { it.version to it.lastTag },
            )
        }

    @Test
    fun fullRegenerationWaitsForBothSwaps() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(PlanEdit.SetGlobal(add = listOf("jazz"), remove = emptyList()))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Queued },
            )
            engine.swapsApplied.value = 1
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Queued },
            )
            engine.swapsApplied.value = 2
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Idle && it.version == 2 && it.lastTag == "jazz" },
            )
        }

    @Test
    fun undoBeforeSwapDropsQueuedSection() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            vm.undo()
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Idle to 1,
                vm.state.value.sections[2]
                    .let { it.status to it.version },
            )
            engine.swapsApplied.value = 2
            advanceUntilIdle()
            assertEquals(
                1,
                vm.state.value.sections[2]
                    .version,
            )
        }

    @Test
    fun chipsFollowTheCurrentPlan() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(
                vm.state.value.chips
                    .isEmpty(),
            )
            vm.edit(PlanEdit.SetGlobal(add = listOf("Jazz"), remove = listOf("rock")))
            advanceUntilIdle()
            assertEquals(listOf(StyleChip("Jazz")), vm.state.value.chips)
            vm.undo()
            advanceUntilIdle()
            assertTrue(
                vm.state.value.chips
                    .isEmpty(),
            )
        }

    @Test
    fun musicLevelFollowsDuckTarget() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertEquals(1f to false, vm.state.value.let { it.musicLevel to it.ducked })
            agent.state.value = AgentState.Listening
            runCurrent()
            assertTrue(abs(vm.state.value.musicLevel - 0.501f) < 0.001f)
            assertTrue(vm.state.value.ducked)
            agent.state.value = AgentState.Speaking
            advanceTimeBy(60)
            assertTrue(abs(vm.state.value.musicLevel - 0.126f) < 0.001f)
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(1_100)
            assertEquals(1f to false, vm.state.value.let { it.musicLevel to it.ducked })
        }

    @Test
    fun agentActivityShowsThinkingAfterUserTurn() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
            agent.state.value = AgentState.Connecting
            runCurrent()
            assertEquals(AgentActivity.Connecting, vm.state.value.agentActivity)
            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(AgentActivity.Listening, vm.state.value.agentActivity)
            agent.events.emit(AgentEvent.UserTranscript("add drums"))
            runCurrent()
            assertEquals(AgentActivity.Thinking, vm.state.value.agentActivity)
            assertEquals(AgentState.Listening, vm.state.value.agent.state)
            agent.state.value = AgentState.Speaking
            runCurrent()
            assertEquals(AgentActivity.Speaking, vm.state.value.agentActivity)
            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(AgentActivity.Listening, vm.state.value.agentActivity)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
            assertEquals(AgentActivity.Idle, vm.state.value.agentActivity)
        }

    @Test
    fun seekToMsIsClamped() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.SeekToMs(-5))
            vm.onIntent(ProducerIntent.SeekToMs(12_345))
            vm.onIntent(ProducerIntent.SeekToMs(60_000))
            assertEquals(listOf("seekTo(0)", "seekTo(12345)", "seekTo(49999)"), engine.calls.filter { it.startsWith("seekTo") })
        }

    @Test
    fun prevAndNextSectionSeek() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()

            fun at(
                ms: Long,
                intent: ProducerIntent,
            ): String {
                engine.positionMs.value = ms
                vm.onIntent(intent)
                return engine.calls.last()
            }
            assertEquals("seekTo(8000)", at(29_000, ProducerIntent.PrevSection))
            assertEquals("seekTo(28000)", at(31_000, ProducerIntent.PrevSection))
            assertEquals("seekTo(0)", at(500, ProducerIntent.PrevSection))
            assertEquals("seekTo(40000)", at(31_000, ProducerIntent.NextSection))
        }

    @Test
    fun nextOnLastSectionPausesAtTheEnd() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            engine.play()
            engine.positionMs.value = 45_000
            vm.onIntent(ProducerIntent.NextSection)
            assertEquals(listOf("pause", "seekTo(49999)"), engine.calls.takeLast(2))
            advanceUntilIdle()
            assertEquals(false, vm.state.value.isPlaying)
        }
}
