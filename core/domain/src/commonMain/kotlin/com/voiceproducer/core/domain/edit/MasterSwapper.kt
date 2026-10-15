package com.voiceproducer.core.domain.edit

import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.domain.window.WindowLengthMismatch
import com.voiceproducer.core.domain.window.WindowPlanner
import com.voiceproducer.core.dsp.EdgeSimilarity
import com.voiceproducer.core.dsp.Mixer
import com.voiceproducer.core.dsp.Splice
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.abs

private const val LENGTH_TOLERANCE_MS = 100L

internal class Progress(
    plan: CompositionPlan,
    var sourceMap: SourceMap,
) {
    val chunks = plan.chunks.toMutableList()
    val diffs = mutableListOf<LongRange>()
    val done = mutableListOf<Int>()
}

internal class MasterSwapper(
    private val store: TrackStore,
    private val engine: AudioEngine,
    private val planner: WindowPlanner,
    private val edgeThreshold: Float,
    private val cpuDispatcher: CoroutineDispatcher,
) {
    @Volatile lateinit var master: PcmAudio

    @Volatile var swapsQueued = 0

    suspend fun swapWindow(
        newPlan: CompositionPlan,
        index: Int,
        progress: Progress,
    ): Boolean {
        val request = planner.request(newPlan, progress.sourceMap, index)
        val (songId, audio) = store.composeWindow(request.plan)
        val current = master
        val baked =
            withContext(cpuDispatcher) {
                val splice = planner.splice(request, audio, current)
                if (EdgeSimilarity.similarity(current, splice, request.leadMs, request.tailMs, request.section.endMs) <
                    edgeThreshold
                ) {
                    return@withContext null
                }
                Mixer.bake(current, splice) to EditPipeline.toMs(splice.diffRange(current.frames), current.sampleRate)
            } ?: return false
        val (next, diff) = baked
        queueSwap(next, listOf(diff))
        master = next
        progress.diffs += diff
        val windowEndMs = request.windowStartMs + request.expectedMs
        progress.sourceMap = progress.sourceMap.replace(request.windowStartMs, windowEndMs, songId, 0)
        return true
    }

    suspend fun swapFull(
        newPlan: CompositionPlan,
        progress: Progress,
    ) {
        val (songId, raw) =
            store.composeFull(
                CompositionPlan(newPlan.chunks.map { if (it is GenerationChunk) it.copy(contextAdherence = null) else it }),
            )
        val current = master
        require(raw.sameFormatAs(current)) { "Format mismatch: regenerated song and master must match" }
        val rate = current.sampleRate
        val position = engine.positionMs.value
        val boundary = SectionIndex.sections(newPlan).firstOrNull { it.startMs - planner.fadeMs > position }
        val prepared =
            withContext(cpuDispatcher) {
                val full = fit(raw, current.frames)
                if (boundary == null) {
                    FullSwap(full, null, EditPipeline.toMs(0 until full.frames, rate))
                } else {
                    val fadeFrames = current.msToFrames(planner.fadeMs)
                    val boundaryFrame = current.msToFrames(boundary.startMs)
                    val splice = Splice(full, 0, boundaryFrame - fadeFrames, null, fadeFrames.toInt())
                    FullSwap(
                        full,
                        Mixer.bake(current, splice) to EditPipeline.toMs(splice.diffRange(current.frames), rate),
                        EditPipeline.toMs(0 until boundaryFrame, rate),
                    )
                }
            }
        val full = prepared.full
        if (prepared.transition == null) {
            queueSwap(full, listOf(prepared.head))
            progress.diffs += prepared.head
        } else {
            val (transitional, tail) = prepared.transition
            queueSwap(transitional, listOf(tail))
            queueSwap(full, listOf(prepared.head))
            progress.diffs += listOf(tail, prepared.head)
        }
        master = full
        progress.sourceMap = SourceMap.single(songId, newPlan.totalMs)
    }

    fun rollBack(
        parentMaster: PcmAudio,
        diffs: List<LongRange>,
    ) {
        queueSwap(parentMaster, EditPipeline.merge(diffs))
        master = parentMaster
    }

    fun queueSwap(
        next: PcmAudio,
        diffRangesMs: List<LongRange>,
    ) {
        engine.swapMaster(next, diffRangesMs)
        swapsQueued++
    }
}

private class FullSwap(
    val full: PcmAudio,
    val transition: Pair<PcmAudio, LongRange>?,
    val head: LongRange,
)

private fun fit(
    audio: PcmAudio,
    frames: Long,
): PcmAudio {
    if (audio.frames == frames) return audio
    if (abs(audio.framesToMs(audio.frames - frames)) > LENGTH_TOLERANCE_MS) {
        throw WindowLengthMismatch(audio.framesToMs(frames), audio.framesToMs(audio.frames))
    }
    return PcmAudio(audio.samples.copyOf((frames * audio.channels).toInt()), audio.sampleRate, audio.channels)
}
