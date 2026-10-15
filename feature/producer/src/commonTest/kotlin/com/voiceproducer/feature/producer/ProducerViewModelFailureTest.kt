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
class ProducerViewModelFailureTest : ProducerViewModelTestBase() {
    @Test
    fun saveFailureMarksEveryDoneSectionFailed() =
        runTest {
            val twoChoruses = CompositionPlan(listOf(gen("Chorus 1", 10_000), gen("Verse", 10_000), gen("Chorus 2", 10_000)))
            library.put(version(1, null, twoChoruses))
            library.saveFailure = IllegalStateException("disk full")
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            engine.swapsApplied.value = 3
            advanceUntilIdle()
            val sections = vm.state.value.sections
            assertEquals(listOf(SectionStatus.Failed, SectionStatus.Idle, SectionStatus.Failed), sections.map { it.status })
            assertTrue(sections.all { it.version == 1 && it.lastTag == null })
        }

    @Test
    fun bargeInTranscriptWhileSpeakingIsNotThinking() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Speaking
            runCurrent()
            agent.events.emit(AgentEvent.UserTranscript("stop"))
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(AgentActivity.Listening, vm.state.value.agentActivity)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun agentTextEndsThinking() =
        runTest {
            val vm = viewModel()
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.events.emit(AgentEvent.UserTranscript("add drums"))
            runCurrent()
            assertEquals(AgentActivity.Thinking, vm.state.value.agentActivity)
            agent.events.emit(AgentEvent.AgentText("Sure."))
            runCurrent()
            assertEquals(AgentActivity.Listening, vm.state.value.agentActivity)
            agent.state.value = AgentState.Disconnected
            advanceUntilIdle()
        }

    @Test
    fun newTrackResetsQueuedSectionsAndVersions() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            engine.swapsApplied.value = 1
            advanceUntilIdle()
            vm.edit(PlanEdit.EditSection("Chorus", listOf("strings"), emptyList()))
            advanceUntilIdle()
            assertEquals(
                SectionStatus.Queued to 2,
                vm.state.value.sections[2]
                    .let { it.status to it.version },
            )

            library.createGate.complete(Unit)
            vm.composeTrack("new", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Idle && it.version == 1 && it.lastTag == null },
            )
            engine.swapsApplied.value = 2
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sections
                    .all { it.status == SectionStatus.Idle && it.version == 1 },
            )

            vm.edit(chorus())
            advanceUntilIdle()
            engine.swapsApplied.value = 1
            advanceUntilIdle()
            assertEquals(
                2 to "brass",
                vm.state.value.sections[2]
                    .let { it.version to it.lastTag },
            )
        }

    @Test
    fun saveFailureAfterSwapsAppliedRevertsSectionVersions() =
        runTest {
            val twoChoruses = CompositionPlan(listOf(gen("Chorus 1", 10_000), gen("Verse", 10_000), gen("Chorus 2", 10_000)))
            library.put(version(1, null, twoChoruses))
            library.saveFailure = IllegalStateException("disk full")
            library.saveGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            engine.swapsApplied.value = 2
            advanceUntilIdle()
            assertEquals(
                listOf(2, 1, 2),
                vm.state.value.sections
                    .map { it.version },
            )
            library.saveGate!!.complete(Unit)
            advanceUntilIdle()
            val sections = vm.state.value.sections
            assertEquals(listOf(SectionStatus.Failed, SectionStatus.Idle, SectionStatus.Failed), sections.map { it.status })
            assertTrue(sections.all { it.version == 1 && it.lastTag == null })
        }

    @Test
    fun failureAfterSavedEditKeepsItsAppliedVersion() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.edit(chorus())
            advanceUntilIdle()
            engine.swapsApplied.value = 1
            advanceUntilIdle()
            library.windowFailure = IllegalStateException("boom")
            vm.edit(PlanEdit.EditSection("Chorus", listOf("strings"), emptyList()))
            advanceUntilIdle()
            assertEquals(
                Triple(SectionStatus.Failed, 2, "brass"),
                vm.state.value.sections[2]
                    .let { Triple(it.status, it.version, it.lastTag) },
            )
        }

    private fun TestScope.connected(position: Long = 0): ProducerViewModel {
        library.put(v1)
        val vm = viewModel()
        advanceUntilIdle()
        engine.positionMs.value = position
        agent.state.value = AgentState.Listening
        runCurrent()
        agent.playheads.clear()
        return vm
    }

    private fun TestScope.endingSession(body: () -> Unit) {
        try {
            body()
        } finally {
            agent.state.value = AgentState.Disconnected
            advanceTimeBy(60)
        }
    }

    @Test
    fun playheadSentOnConnect() =
        runTest {
            endingSession {
                library.put(v1)
                viewModel()
                advanceUntilIdle()
                engine.positionMs.value = 42_900
                runCurrent()
                assertEquals(emptyList(), agent.playheads)
                agent.state.value = AgentState.Listening
                runCurrent()
                assertEquals(listOf("[system] Producer is at 0:42 \u00B7 Outro (section 4 of 4)"), agent.playheads)
                agent.state.value = AgentState.Speaking
                runCurrent()
                assertEquals(1, agent.playheads.size)
            }
        }

    @Test
    fun playheadSentAgainOnNextConnect() =
        runTest {
            endingSession {
                connected(position = 5_000)
                agent.state.value = AgentState.Disconnected
                runCurrent()
                agent.state.value = AgentState.Listening
                runCurrent()
                assertEquals(listOf("[system] Producer is at 0:05 \u00B7 Intro (section 1 of 4)"), agent.playheads)
            }
        }

    @Test
    fun playheadSentWhenPlaybackEntersAnotherSection() =
        runTest {
            endingSession {
                connected(position = 1_000)
                engine.positionMs.value = 7_950
                runCurrent()
                assertEquals(emptyList(), agent.playheads)
                engine.positionMs.value = 8_050
                runCurrent()
                assertEquals(listOf("[system] Producer is at 0:08 \u00B7 Verse (section 2 of 4)"), agent.playheads)
                engine.positionMs.value = 0
                runCurrent()
                assertEquals("[system] Producer is at 0:00 \u00B7 Intro (section 1 of 4)", agent.playheads.last())
                assertEquals(2, agent.playheads.size)
            }
        }

    @Test
    fun playheadSentOnSeekWithinSection() =
        runTest {
            endingSession {
                val vm = connected(position = 1_000)
                vm.onIntent(ProducerIntent.SeekToMs(3_000))
                runCurrent()
                assertEquals(listOf("[system] Producer is at 0:03 \u00B7 Intro (section 1 of 4)"), agent.playheads)
                vm.onIntent(ProducerIntent.SeekToSection(2))
                runCurrent()
                assertEquals("[system] Producer is at 0:28 \u00B7 Chorus (section 3 of 4)", agent.playheads.last())
            }
        }

    @Test
    fun playheadSentOnPlayAndPause() =
        runTest {
            endingSession {
                val vm = connected(position = 30_000)
                engine.isPlaying.value = false
                runCurrent()
                agent.playheads.clear()
                vm.onIntent(ProducerIntent.TogglePlay)
                runCurrent()
                vm.onIntent(ProducerIntent.TogglePlay)
                runCurrent()
                assertEquals(List(2) { "[system] Producer is at 0:30 \u00B7 Chorus (section 3 of 4)" }, agent.playheads)
            }
        }

    @Test
    fun playheadNotSentWhileDisconnected() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            engine.positionMs.value = 9_000
            runCurrent()
            vm.onIntent(ProducerIntent.SeekToMs(30_000))
            vm.onIntent(ProducerIntent.TogglePlay)
            runCurrent()
            assertEquals(emptyList(), agent.playheads)
        }

    @Test
    fun playheadNotSentWithoutPlan() =
        runTest {
            endingSession {
                viewModel()
                advanceUntilIdle()
                agent.state.value = AgentState.Listening
                engine.positionMs.value = 9_000
                runCurrent()
                assertEquals(emptyList(), agent.playheads)
            }
        }

    @Test
    fun restoresTheCurrentTracksConversationOnStart() =
        runTest {
            library.put(v1)
            history.append(7, user("make it darker"))
            history.append(3, user("another track"))
            history.append(7, reply("On it."))
            history.append(7, storedTool("Changing Chorus — brass", "EditSection", "Done", "Chorus", "brass"))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf(
                    TranscriptItem.User(0, "make it darker"),
                    TranscriptItem.Agent(0, "On it."),
                    line(ToolKind.EditSection, "Chorus", "brass", ToolStatus.Done),
                ),
                vm.lines(),
            )
            assertEquals("Mic off · Tap to continue", idleMicLabel(vm.state.value.transcript))
        }

    @Test
    fun restoredAndNewLinesHaveUniqueIds() =
        runTest {
            library.put(v1)
            repeat(3) { history.append(7, user("old $it")) }
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.UserTranscript("new"))
            runCurrent()
            val ids =
                vm.state.value.transcript
                    .map { it.id }
            assertEquals(4, ids.size)
            assertEquals(4, ids.toSet().size)
        }

    @Test
    fun aToolLineLeftRunningIsRestoredAsFailed() =
        runTest {
            library.put(v1)
            history.append(7, storedTool("Changing the whole track", "SetGlobal", "Running"))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(listOf(line(ToolKind.SetGlobal, status = ToolStatus.Failed)), vm.lines())
        }

    @Test
    fun withoutATrackThePendingConversationIsRestored() =
        runTest {
            history.append(null, user("a lofi beat please"))
            history.append(7, user("an older track's line"))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(listOf(TranscriptItem.User(0, "a lofi beat please")), vm.lines())
        }

    @Test
    fun finalLinesArePersistedInPlaceButNotTheLiveBubble() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.TentativeTranscript("add br"))
            agent.events.emit(AgentEvent.UserTranscript("add brass to the chorus"))
            agent.events.emit(AgentEvent.AgentText("Sure,"))
            agent.events.emit(AgentEvent.AgentText("Sure, adding brass."))
            runCurrent()
            vm.edit(PlanEdit.EditSection("chorus", listOf("brass"), emptyList()))
            advanceUntilIdle()
            assertEquals(
                listOf(
                    user("add brass to the chorus"),
                    reply("Sure, adding brass."),
                    storedTool("Changing Chorus — brass", "EditSection", "Done", "Chorus", "brass"),
                ),
                history.of(7),
            )
            assertEquals(3, history.rows.size)
        }

    @Test
    fun linesBeforeTheFirstTrackMoveToItOnceComposed() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.UserTranscript("a lofi beat"))
            runCurrent()
            vm.composeTrack("lofi", instrumental = false, lengthMs = 60_000)
            runCurrent()
            assertEquals(listOf(user("a lofi beat"), storedTool("Writing a new track", "Compose", "Running")), history.of(null))
            library.createGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(history.of(null).isEmpty())
            assertEquals(listOf(user("a lofi beat"), storedTool("Writing a new track", "Compose", "Done")), history.of(7))
            agent.events.emit(AgentEvent.UserTranscript("nice"))
            advanceUntilIdle()
            assertEquals(user("nice"), history.of(7).last())
        }

    @Test
    fun newTrackClearsTheTranscriptAndStartsAFreshHistory() =
        runTest {
            val vm = vmWithPlayingTrack()
            agent.events.emit(AgentEvent.UserTranscript("old talk"))
            runCurrent()
            vm.onIntent(ProducerIntent.NewTrack)
            advanceUntilIdle()
            assertTrue(
                vm.state.value.transcript
                    .isEmpty(),
            )
            assertEquals("Mic off · Tap to talk", idleMicLabel(vm.state.value.transcript))
            agent.events.emit(AgentEvent.UserTranscript("next idea"))
            advanceUntilIdle()
            assertEquals(listOf(user("old talk")), history.of(7))
            assertEquals(listOf(user("next idea")), history.of(null))
        }

    @Test
    fun earlierConversationIsSentOnceWhenTheSessionConnects() =
        runTest {
            library.put(v1)
            history.append(7, user("make it  darker"))
            history.append(7, reply("On it."))
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(listOf<String?>("Let's continue."), agent.firstMessages)
            assertTrue(agent.contexts.isEmpty())
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.state.value = AgentState.Speaking
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            val earlier = "$earlierHeading\nProducer: make it darker\nComposer: On it."
            assertEquals(listOf(earlier), agent.contexts)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.events.emit(AgentEvent.AgentText("Bye."))
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.state.value = AgentState.Speaking
            runCurrent()
            assertEquals(listOf(earlier, "$earlier\nComposer: Bye."), agent.contexts)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceTimeBy(60)
        }

    @Test
    fun withoutHistoryTheAgentGreetsAsConfiguredAndGetsNoEarlierConversation() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            assertEquals(listOf<String?>(null), agent.firstMessages)
            assertTrue(agent.contexts.isEmpty())
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceTimeBy(60)
        }

    @Test
    fun aUserStopAndAServerEndBothLeaveTheSameIdleStateWithoutError() =
        runTest {
            library.put(v1)
            val vm = viewModel()
            advanceUntilIdle()

            fun assertIdle() {
                val s = vm.state.value
                assertEquals(AgentActivity.Idle, s.agentActivity)
                assertNull(s.agent.error)
                assertEquals("Mic off · Tap to continue", idleMicLabel(s.transcript))
            }
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.state.value = AgentState.Listening
            agent.events.emit(AgentEvent.AgentText("Hello, I'm your composer."))
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceTimeBy(60)
            assertIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.state.value = AgentState.Disconnected
            runCurrent()
            advanceTimeBy(60)
            assertIdle()
        }

    @Test
    fun anEditInFlightFinishesAndIsStoredAfterTheSessionEnds() =
        runTest {
            library.put(v1)
            library.windowGate = CompletableDeferred()
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            vm.edit(chorus())
            runCurrent()
            agent.state.value = AgentState.Disconnected
            runCurrent()
            library.windowGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(ToolStatus.Done, vm.lastTool().status)
            assertEquals(
                SectionStatus.Queued,
                vm.state.value.sections[2]
                    .status,
            )
            assertEquals("Done", history.of(7).last().toolStatus)
        }

    @Test
    fun aLineAddedBeforeTheRestoreFinishesLandsOnTheExistingTrack() =
        runTest {
            library.put(v1)
            library.latestGate = CompletableDeferred()
            viewModel()
            runCurrent()
            agent.events.emit(AgentEvent.UserTranscript("right at startup"))
            runCurrent()
            library.latestGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(user("right at startup")), history.of(7))
            assertTrue(history.of(null).isEmpty())
        }

    @Test
    fun aComposeFinishingBeforeTheHistoryRestoreKeepsItsTrack() =
        runTest {
            library.put(version(1, null).copy(trackId = 3))
            history.append(3, user("old track talk"))
            history.recentGate = CompletableDeferred()
            library.createGate.complete(Unit)
            val vm = viewModel()
            runCurrent()
            vm.composeTrack("lofi", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            history.recentGate!!.complete(Unit)
            advanceUntilIdle()
            agent.events.emit(AgentEvent.UserTranscript("about the new track"))
            advanceUntilIdle()
            assertEquals(user("about the new track"), history.of(7).last())
            assertEquals(user("old track talk"), history.of(3).first())
            assertFalse(user("about the new track") in history.of(3))
            assertFalse(vm.lines().contains(TranscriptItem.User(0, "old track talk")))
        }

    @Test
    fun aReplyMergedIntoARestoredLineUpdatesItsStoredRow() =
        runTest {
            library.put(v1)
            history.append(7, reply("Sure,"))
            val vm = viewModel()
            advanceUntilIdle()
            agent.events.emit(AgentEvent.AgentText("Sure, adding brass."))
            advanceUntilIdle()
            assertEquals(listOf(TranscriptItem.Agent(0, "Sure, adding brass.")), vm.lines())
            assertEquals(listOf(reply("Sure, adding brass.")), history.of(7))
            val next = viewModel()
            advanceUntilIdle()
            assertEquals(listOf(TranscriptItem.Agent(0, "Sure, adding brass.")), next.lines())
        }

    @Test
    fun aComposeFinishingBeforeTheRestoreKeepsTheEngineOnTheNewTrack() =
        runTest {
            library.put(v1)
            library.latestGate = CompletableDeferred()
            library.createGate.complete(Unit)
            val vm = viewModel()
            runCurrent()
            vm.composeTrack("lofi", instrumental = false, lengthMs = 60_000)
            advanceUntilIdle()
            library.latestGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("load", "play"), engine.calls)
        }

    @Test
    fun aMasterThatFailsToLoadStillRestoresItsTracksConversation() =
        runTest {
            library.put(v1)
            history.append(7, user("make it darker"))
            library.loadFailure = IllegalStateException("file missing")
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(TrackStatus.Error("file missing"), vm.state.value.track)
            assertEquals(listOf<TranscriptItem>(TranscriptItem.User(0, "make it darker")), vm.lines())
            agent.events.emit(AgentEvent.UserTranscript("still there?"))
            advanceUntilIdle()
            assertEquals(listOf(user("make it darker"), user("still there?")), history.of(7))
        }
}
