package com.voiceproducer.core.domain.edit

import app.cash.turbine.test
import com.voiceproducer.core.domain.plan.EditResult
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.domain.window.WindowPlanner
import com.voiceproducer.core.dsp.SwappableMaster
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TimeRange
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class EditPipelineTest : EditPipelineTestBase() {
    @Test
    fun editRegeneratesNextOccurrenceFirst() =
        runTest {
            val p = pipeline()
            engine.positionMs.value = 30_000
            p.events.test {
                assertIs<EditResult.Applied>(p.submit(chorus()))
                assertEquals(PipelineEvent.SectionRegenerating(3), awaitItem())
                assertEquals(PipelineEvent.SectionDone(3, chorus(), swaps = 1), awaitItem())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                assertEquals(PipelineEvent.SectionDone(1, chorus(), swaps = 2), awaitItem())
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(2, saved.versionId)
                assertEquals(listOf("brass"), (saved.plan.chunks[1] as GenerationChunk).positiveStyles)
                assertEquals(listOf("brass"), (saved.plan.chunks[3] as GenerationChunk).positiveStyles)
            }
        }

    @Test
    fun afterResetUndoReturnsFalseAndSubmitIsRefusedWithoutTouchingTheStore() =
        runTest {
            val p = pipeline()
            p.reset()
            assertFalse(p.undo())
            assertFailsWith<IllegalStateException> { p.submit(chorus()) }
            advanceUntilIdle()
            assertTrue(store.log.isEmpty())
            assertTrue(store.current.isEmpty())
            assertTrue(engine.swaps.isEmpty())
            assertFalse(p.busy.value)
        }

    @Test
    fun windowEditRunsCpuWorkOnTheInjectedDispatcher() =
        runTest {
            val cpu = RecordingDispatcher(StandardTestDispatcher(testScheduler))
            val p = pipeline(cpu = cpu)
            p.submit(verse2("brass"))
            advanceUntilIdle()
            assertEquals(1, engine.swaps.size)
            assertTrue(cpu.executed > 0, "splice/bake did not run on the injected dispatcher")
        }

    @Test
    fun fullRegenerationRunsCpuWorkOnTheInjectedDispatcher() =
        runTest {
            val cpu = RecordingDispatcher(StandardTestDispatcher(testScheduler))
            val p = pipeline(cpu = cpu)
            store.fullAudio = { tone(TOTAL_MS) }
            p.submit(PlanEdit.SetGlobal(add = listOf("jazz"), remove = emptyList()))
            advanceUntilIdle()
            assertEquals(2, engine.swaps.size)
            assertTrue(cpu.executed > 0, "fit/bake did not run on the injected dispatcher")
        }

    @Test
    fun editSwapsMasterWithDiffRange() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            assertEquals(1, engine.swaps.size)
            assertEquals(listOf(18_400L..31_599L), engine.swaps[0].diffRangesMs)
            assertEquals(master.frames, engine.swaps[0].master.frames)
            assertEquals(listOf("window", "save"), store.log)
        }

    @Test
    fun savedVersionMapsWholeWindowToNewSong() =
        runTest {
            val p = pipeline()
            p.events.test {
                p.submit(verse2("brass"))
                skipItems(2)
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(
                    SourceMap(SourceMap.single("A", TOTAL_MS).replace(17_000, 33_000, "B", 0).segments),
                    saved.sourceMap,
                )
                assertEquals(listOf(18_400L..31_599L), saved.diffRangesMs)
                assertEquals("Verse 2", saved.editedSection)
                assertSame(engine.swaps.last().master, store.loadAudio(saved))
            }
        }

    @Test
    fun secondEditReferencesPreviousWindowSong() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            p.submit(verse2("strings"))
            advanceUntilIdle()
            assertEquals(2, store.windowPlans.size)
            val second = store.windowPlans[1].chunks
            assertEquals(ReferenceChunk("B", TimeRange(0, 3_000)), second.first())
            assertEquals(ReferenceChunk("B", TimeRange(13_000, 16_000)), second.last())
            assertEquals(listOf("strings", "brass"), (second[1] as GenerationChunk).positiveStyles)
        }

    @Test
    fun failureKeepsMasterAndReports() =
        runTest {
            val p = pipeline()
            store.windowAudio = { error("boom") }
            p.events.test {
                p.submit(chorus())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                val failed = assertIs<PipelineEvent.SectionFailed>(awaitItem())
                assertEquals(1, failed.index)
                assertTrue("boom" in failed.reason)
                advanceUntilIdle()
                expectNoEvents()
            }
            assertTrue(engine.swaps.isEmpty())
            assertEquals(listOf("window"), store.log)
        }

    @Test
    fun failureAfterSuccessSavesWhatWasDone() =
        runTest {
            val p = pipeline()
            var calls = 0
            store.windowAudio = { if (calls++ == 0) tone(it.totalMs) else error("boom") }
            p.events.test {
                p.submit(chorus())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                assertEquals(PipelineEvent.SectionDone(1, chorus(), swaps = 1), awaitItem())
                assertEquals(PipelineEvent.SectionRegenerating(3), awaitItem())
                assertIs<PipelineEvent.SectionFailed>(awaitItem())
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(listOf("brass"), (saved.plan.chunks[1] as GenerationChunk).positiveStyles)
                assertEquals(emptyList(), (saved.plan.chunks[3] as GenerationChunk).positiveStyles)
            }
            assertEquals(1, engine.swaps.size)
        }

    @Test
    fun lowEdgeSimilarityFallsBackToFull() =
        runTest {
            val p = pipeline()
            store.windowAudio = { silence(it.totalMs) }
            p.events.test {
                p.submit(chorus())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                assertEquals(PipelineEvent.SectionRegenerating(3), awaitItem())
                assertEquals(PipelineEvent.SectionDone(1, chorus(), swaps = 2), awaitItem())
                assertEquals(PipelineEvent.SectionDone(3, chorus(), swaps = 2), awaitItem())
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(SourceMap.single("C", TOTAL_MS), saved.sourceMap)
                assertEquals(listOf(0L..TOTAL_MS - 1), saved.diffRangesMs)
            }
            assertEquals(listOf("window", "full", "save"), store.log)
            val full = store.fullPlans.single()
            assertEquals(listOf("brass"), (full.chunks[1] as GenerationChunk).positiveStyles)
            assertEquals(listOf("brass"), (full.chunks[3] as GenerationChunk).positiveStyles)
            assertEquals(2, engine.swaps.size)
            assertEquals(listOf<String?>(null, null, null, null), full.chunks.map { (it as GenerationChunk).contextAdherence })
            assertEquals(
                "medium",
                (
                    store.windowPlans
                        .single()
                        .chunks
                        .single { it is GenerationChunk } as GenerationChunk
                ).contextAdherence,
            )
        }

    @Test
    fun setGlobalSwapsTwice() =
        runTest {
            val p = pipeline()
            val full = tone(TOTAL_MS)
            store.fullAudio = { full }
            engine.positionMs.value = 5_000
            p.events.test {
                val global = PlanEdit.SetGlobal(add = listOf("jazz"), remove = emptyList())
                assertIs<EditResult.Applied>(p.submit(global))
                listOf(1, 2, 3, 0).forEach { assertEquals(PipelineEvent.SectionRegenerating(it), awaitItem()) }
                listOf(1, 2, 3, 0).forEach { assertEquals(PipelineEvent.SectionDone(it, global, swaps = 2), awaitItem()) }
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(SourceMap.single("B", TOTAL_MS), saved.sourceMap)
                assertEquals(null, saved.editedSection)
            }
            assertEquals(listOf("full", "save"), store.log)
            assertEquals(2, engine.swaps.size)
            val transitional = engine.swaps[0]
            assertEquals(listOf(9_800L..TOTAL_MS - 1), transitional.diffRangesMs)
            assertEquals(master.samples[9_000], transitional.master.samples[9_000])
            assertEquals(full.samples[20_000], transitional.master.samples[20_000])
            assertSame(full, engine.swaps[1].master)
            assertEquals(listOf(0L..9_999L), engine.swaps[1].diffRangesMs)
        }

    @Test
    fun setGlobalInLastSectionSwapsOnceAtLoopStart() =
        runTest {
            val p = pipeline()
            engine.positionMs.value = 35_000
            p.submit(PlanEdit.SetGlobal(add = listOf("jazz"), remove = emptyList()))
            advanceUntilIdle()
            assertEquals(1, engine.swaps.size)
            assertEquals(listOf(0L..TOTAL_MS - 1), engine.swaps[0].diffRangesMs)
        }

    @Test
    fun fullWithWrongLengthFails() =
        runTest {
            val p = pipeline()
            store.fullAudio = { tone(TOTAL_MS - 500) }
            p.events.test {
                p.submit(PlanEdit.SetGlobal(add = listOf("jazz"), remove = emptyList()))
                skipItems(4)
                (0..3).forEach { assertEquals(it, assertIs<PipelineEvent.SectionFailed>(awaitItem()).index) }
                advanceUntilIdle()
                expectNoEvents()
            }
            assertTrue(engine.swaps.isEmpty())
        }

    @Test
    fun unknownSectionStartsNoGeneration() =
        runTest {
            val p = pipeline()
            p.events.test {
                val r = p.submit(PlanEdit.EditSection("Bridge", listOf("brass"), emptyList()))
                assertIs<EditResult.UnknownSection>(r)
                assertEquals(EditResult.NoChange, p.submit(PlanEdit.EditSection("Chorus", emptyList(), emptyList())))
                advanceUntilIdle()
                expectNoEvents()
            }
            assertTrue(store.log.isEmpty())
            assertFalse(p.busy.value)
        }

    @Test
    fun undoSwapsToParent() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            val diff = engine.swaps.single().diffRangesMs
            p.events.test {
                assertTrue(p.undo())
                assertEquals(PipelineEvent.VersionRestored(v1), awaitItem())
            }
            assertEquals(2, engine.swaps.size)
            assertSame(master, engine.swaps[1].master)
            assertEquals(diff, engine.swaps[1].diffRangesMs)
            assertEquals(listOf(7L to 1L), store.current)
            p.submit(verse2("strings"))
            advanceUntilIdle()
            assertEquals(listOf("strings"), (store.windowPlans.last().chunks[1] as GenerationChunk).positiveStyles)
            assertEquals(
                ReferenceChunk("A", TimeRange(17_000, 20_000)),
                store.windowPlans
                    .last()
                    .chunks
                    .first(),
            )
        }

    @Test
    fun undoWithoutParentReturnsFalse() =
        runTest {
            val p = pipeline()
            assertFalse(p.undo())
            assertTrue(engine.swaps.isEmpty())
            assertTrue(store.current.isEmpty())
        }

    @Test
    fun undoWaitsForRunningEdit() =
        runTest {
            val p = pipeline()
            store.generationDelayMs = 1_000
            p.submit(verse2("brass"))
            runCurrent()
            assertTrue(p.busy.value)
            val undone = async { p.undo() }
            advanceUntilIdle()
            assertTrue(undone.await())
            assertEquals(listOf("window", "save"), store.log)
            assertEquals(2, engine.swaps.size)
            assertSame(master, engine.swaps[1].master)
            assertFalse(p.busy.value)
        }

    @Test
    fun editsAreSerialized() =
        runTest {
            val p = pipeline()
            store.generationDelayMs = 1_000
            p.events.test {
                p.submit(verse2("brass"))
                p.submit(chorus())
                runCurrent()
                assertTrue(p.busy.value)
                assertEquals(PipelineEvent.SectionRegenerating(2), awaitItem())
                assertEquals(PipelineEvent.SectionDone(2, verse2("brass"), swaps = 1), awaitItem())
                assertIs<PipelineEvent.VersionSaved>(awaitItem())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            advanceUntilIdle()
            assertEquals(listOf("window", "save", "window", "window", "save"), store.log)
            assertFalse(p.busy.value)
        }

    @Test
    fun sectionDoneCountsEverySwapSinceInit() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            assertTrue(p.undo())
            p.events.test {
                p.submit(chorus())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                assertEquals(PipelineEvent.SectionDone(1, chorus(), swaps = 3), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            advanceUntilIdle()
            assertEquals(4, engine.swaps.size)
        }

    @Test
    fun undoIsBusyWhileRunning() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            assertFalse(p.busy.value)
            store.loadDelayMs = 1_000
            val undone = async { p.undo() }
            runCurrent()
            assertTrue(p.busy.value)
            advanceUntilIdle()
            assertTrue(undone.await())
            assertFalse(p.busy.value)
        }
}
