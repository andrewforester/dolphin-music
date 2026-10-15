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
class EditPipelineFailureTest : EditPipelineTestBase() {
    @Test
    fun undoSwapFailureLeavesCurrentUnchanged() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            engine.failSwaps = true
            assertFailsWith<IllegalStateException> { p.undo() }
            assertTrue(store.current.isEmpty())
            assertFalse(p.busy.value)
        }

    @Test
    fun saveFailureFailsEveryDoneSection() =
        runTest {
            val p = pipeline()
            store.saveFailure = true
            p.events.test {
                p.submit(chorus())
                assertEquals(PipelineEvent.SectionRegenerating(1), awaitItem())
                assertEquals(PipelineEvent.SectionDone(1, chorus(), swaps = 1), awaitItem())
                assertEquals(PipelineEvent.SectionRegenerating(3), awaitItem())
                assertEquals(PipelineEvent.SectionDone(3, chorus(), swaps = 2), awaitItem())
                val failed = listOf(awaitItem(), awaitItem()).map { assertIs<PipelineEvent.SectionFailed>(it).index }
                assertEquals(setOf(1, 3), failed.toSet())
                advanceUntilIdle()
                expectNoEvents()
            }
        }

    @Test
    fun saveFailureRollsBackToParent() =
        runTest {
            val p = pipeline()
            store.saveFailure = true
            p.events.test {
                p.submit(verse2("brass"))
                assertEquals(PipelineEvent.SectionRegenerating(2), awaitItem())
                assertEquals(PipelineEvent.SectionDone(2, verse2("brass"), swaps = 1), awaitItem())
                val failed = assertIs<PipelineEvent.SectionFailed>(awaitItem())
                assertEquals(2, failed.index)
                assertTrue("disk full" in failed.reason)
                advanceUntilIdle()
                expectNoEvents()
            }
            assertEquals(2, engine.swaps.size)
            assertSame(master, engine.swaps.last().master)
            assertEquals(listOf(18_400L..31_599L), engine.swaps.last().diffRangesMs)
            store.saveFailure = false
            assertIs<EditResult.Applied>(p.submit(verse2("brass")))
            advanceUntilIdle()
            assertEquals(
                ReferenceChunk("A", TimeRange(17_000, 20_000)),
                store.windowPlans
                    .last()
                    .chunks
                    .first(),
            )
            assertEquals(
                ReferenceChunk("A", TimeRange(30_000, 33_000)),
                store.windowPlans
                    .last()
                    .chunks
                    .last(),
            )
        }

    @Test
    fun cancellationAfterSwapRollsBack() =
        runTest {
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val p = pipeline(scope = scope)
            store.generationDelayMs = 1_000
            p.submit(chorus())
            advanceTimeBy(1_500)
            assertEquals(1, engine.swaps.size)
            assertEquals(listOf("window", "window"), store.log)
            scope.cancel()
            advanceUntilIdle()
            assertEquals(2, engine.swaps.size)
            assertSame(master, engine.swaps.last().master)
            assertEquals(engine.swaps.first().diffRangesMs, engine.swaps.last().diffRangesMs)
            assertEquals(listOf("window", "window"), store.log)
            assertFalse(p.busy.value)
        }

    @Test
    fun editNoLongerApplyingIsReported() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            store.windowAudio = { error("boom") }
            p.events.test {
                p.submit(PlanEdit.EditSection("Verse 2", add = emptyList(), remove = listOf("brass")))
                assertIs<EditResult.Applied>(p.submit(verse2("brass")))
                assertEquals(PipelineEvent.SectionRegenerating(2), awaitItem())
                assertIs<PipelineEvent.SectionFailed>(awaitItem())
                val stale = assertIs<PipelineEvent.SectionFailed>(awaitItem())
                assertEquals(2, stale.index)
                assertTrue("no longer" in stale.reason)
            }
            assertEquals(listOf("window", "save", "window"), store.log)
            assertEquals(EditResult.NoChange, p.submit(verse2("brass")))
        }

    @Test
    fun sourceMapNotCoveringPlanFails() =
        runTest {
            val broken = v1.copy(sourceMap = SourceMap.single("A", TOTAL_MS - 1_000))
            val p = pipeline(broken)
            p.events.test {
                p.submit(chorus())
                assertEquals(1, assertIs<PipelineEvent.SectionFailed>(awaitItem()).index)
                assertEquals(3, assertIs<PipelineEvent.SectionFailed>(awaitItem()).index)
            }
            assertTrue(store.log.isEmpty())
            assertTrue(engine.swaps.isEmpty())
        }

    @Test
    fun adjacentWindowsInPlaybackOrder() =
        runTest {
            val p = pipeline(v1.copy(plan = adjacentPlan))
            p.events.test {
                p.submit(chorus())
                skipItems(4)
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(ReferenceChunk("B", TimeRange(10_000, 13_000)), store.windowPlans[1].chunks.first())
                assertEquals(ReferenceChunk("A", TimeRange(30_000, 33_000)), store.windowPlans[1].chunks.last())
                val expected =
                    SourceMap
                        .single("A", TOTAL_MS)
                        .replace(7_000, 23_000, "B", 0)
                        .replace(17_000, 33_000, "C", 0)
                assertEquals(expected, saved.sourceMap)
                assertEquals(listOf(8_400L..31_599L), saved.diffRangesMs)
            }
            assertEquals(2, engine.swaps.size)
        }

    @Test
    fun adjacentWindowsWrappedOrder() =
        runTest {
            val p = pipeline(v1.copy(plan = adjacentPlan))
            engine.positionMs.value = 15_000
            p.events.test {
                p.submit(chorus())
                assertEquals(PipelineEvent.SectionRegenerating(2), awaitItem())
                skipItems(3)
                val saved = assertIs<PipelineEvent.VersionSaved>(awaitItem()).version
                assertEquals(ReferenceChunk("A", TimeRange(7_000, 10_000)), store.windowPlans[1].chunks.first())
                assertEquals(ReferenceChunk("B", TimeRange(3_000, 6_000)), store.windowPlans[1].chunks.last())
                val expected =
                    SourceMap
                        .single("A", TOTAL_MS)
                        .replace(17_000, 33_000, "B", 0)
                        .replace(7_000, 23_000, "C", 0)
                assertEquals(expected, saved.sourceMap)
                assertEquals(listOf(8_400L..31_599L), saved.diffRangesMs)
            }
            assertEquals(2, engine.swaps.size)
        }

    @Test
    fun onFinishedReportsLandedWhenEverySectionIsSaved() =
        runTest {
            val p = pipeline()
            val landed = mutableListOf<Boolean>()
            p.submit(chorus()) { landed += it }
            p.submit(PlanEdit.SetGlobal(add = listOf("jazz"), remove = emptyList())) { landed += it }
            advanceUntilIdle()
            assertEquals(listOf(true, true), landed)
        }

    @Test
    fun onFinishedReportsFailureWhenNothingLands() =
        runTest {
            val p = pipeline()
            store.windowAudio = { error("boom") }
            val landed = mutableListOf<Boolean>()
            p.submit(chorus()) { landed += it }
            advanceUntilIdle()
            assertEquals(listOf(false), landed)
        }

    @Test
    fun onFinishedReportsFailureForAPartialEdit() =
        runTest {
            val p = pipeline()
            var calls = 0
            store.windowAudio = { if (calls++ == 0) tone(it.totalMs) else error("boom") }
            val landed = mutableListOf<Boolean>()
            p.submit(chorus()) { landed += it }
            advanceUntilIdle()
            assertEquals(listOf(false), landed)
            assertEquals(listOf("window", "window", "save"), store.log)
        }

    @Test
    fun onFinishedReportsFailureWhenSavingFails() =
        runTest {
            val p = pipeline()
            store.saveFailure = true
            val landed = mutableListOf<Boolean>()
            p.submit(verse2("brass")) { landed += it }
            advanceUntilIdle()
            assertEquals(listOf(false), landed)
        }

    @Test
    fun onFinishedReportsFailureForAnEditThatNoLongerApplies() =
        runTest {
            val p = pipeline()
            p.submit(verse2("brass"))
            advanceUntilIdle()
            store.windowAudio = { error("boom") }
            val landed = mutableListOf<Boolean>()
            p.submit(PlanEdit.EditSection("Verse 2", add = emptyList(), remove = listOf("brass"))) { landed += it }
            p.submit(verse2("brass")) { landed += it }
            advanceUntilIdle()
            assertEquals(listOf(false, false), landed)
        }

    @Test
    fun onFinishedReportsFailureWhenTheEditThrows() =
        runTest {
            val errors = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    coroutineContext + SupervisorJob(coroutineContext[Job]) + CoroutineExceptionHandler { _, e -> errors += e },
                )
            val p = pipeline(scope = scope)
            store.saveFailure = true
            engine.failSwapsFrom = 1
            val landed = mutableListOf<Boolean>()
            p.submit(verse2("brass")) { landed += it }
            advanceUntilIdle()
            assertEquals(listOf(false), landed)
            assertEquals(1, errors.size)
            scope.cancel()
        }

    @Test
    fun onFinishedReportsFailureWhenTheEditIsCancelled() =
        runTest {
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val p = pipeline(scope = scope)
            store.generationDelayMs = 1_000
            val landed = mutableListOf<Boolean>()
            p.submit(chorus()) { landed += it }
            advanceTimeBy(500)
            scope.cancel()
            advanceUntilIdle()
            assertEquals(listOf(false), landed)
        }
}
