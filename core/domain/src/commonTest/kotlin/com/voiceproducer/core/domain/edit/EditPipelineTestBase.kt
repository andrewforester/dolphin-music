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
import com.voiceproducer.core.testing.FakeStore
import com.voiceproducer.core.testing.SwapCheckingEngine
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
abstract class EditPipelineTestBase {
    internal companion object {
        const val RATE = 1000
        const val TOTAL_MS = 40_000L
    }

    protected fun tone(ms: Long) =
        PcmAudio(
            ShortArray((ms * RATE / 1000).toInt()) { (8000 * sin(2 * PI * 200 * it / RATE)).toInt().toShort() },
            RATE,
            1,
        )

    protected fun silence(ms: Long) = PcmAudio(ShortArray((ms * RATE / 1000).toInt()), RATE, 1)

    protected fun gen(name: String) = GenerationChunk(text = "[$name]\nla la", durationMs = 10_000)

    protected val plan = CompositionPlan(listOf(gen("Verse 1"), gen("Chorus 1"), gen("Verse 2"), gen("Chorus 2")))

    protected val master = tone(TOTAL_MS)

    protected val v1 =
        TrackVersion(
            trackId = 7,
            versionId = 1,
            number = 1,
            parentVersionId = null,
            plan = plan,
            sourceMap = SourceMap.single("A", TOTAL_MS),
            audioPath = "v1.wav",
            editedSection = null,
            diffRangesMs = emptyList(),
        )

    protected val store = FakeStore(v1, master) { tone(it.totalMs) }

    protected val engine = SwapCheckingEngine(TOTAL_MS)

    protected fun TestScope.pipeline(
        version: TrackVersion = v1,
        scope: CoroutineScope = this,
        cpu: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): EditPipeline {
        store.put(version, master)
        engine.queued = master
        return EditPipeline(store, engine, WindowPlanner(), scope = scope, cpuDispatcher = cpu)
            .also { it.init(version, master) }
    }

    protected class RecordingDispatcher(
        private val inner: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var executed = 0

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = inner.dispatch(
            context,
            Runnable {
                executed++
                block.run()
            },
        )
    }

    protected fun chorus(add: String = "brass") = PlanEdit.EditSection("Chorus", add = listOf(add), remove = emptyList())

    protected fun verse2(add: String) = PlanEdit.EditSection("Verse 2", add = listOf(add), remove = emptyList())

    protected val adjacentPlan = CompositionPlan(listOf(gen("Verse 1"), gen("Chorus 1"), gen("Chorus 2"), gen("Outro")))
}
