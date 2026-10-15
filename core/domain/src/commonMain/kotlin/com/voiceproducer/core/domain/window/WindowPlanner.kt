package com.voiceproducer.core.domain.window

import com.voiceproducer.core.domain.plan.Section
import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.dsp.Splice
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.SourceMap
import kotlin.math.abs

data class WindowRequest(
    val plan: CompositionPlan,
    val section: Section,
    val windowStartMs: Long,
    val leadMs: Long,
    val tailMs: Long,
) {
    val expectedMs: Long get() = leadMs + (section.endMs - section.startMs) + tailMs
}

class WindowLengthMismatch(
    val expectedMs: Long,
    val actualMs: Long,
) : Exception("Window audio is $actualMs ms, expected $expectedMs ms")

enum class FadePlacement { CenteredInContext, AtSectionBoundary }

class WindowPlanner(
    private val contextMs: Long = 3000,
    val fadeMs: Long = 200,
    private val placement: FadePlacement = FadePlacement.CenteredInContext,
) {
    fun order(
        changed: List<Int>,
        sections: List<Section>,
        positionMs: Long,
    ): List<Int> {
        require(changed.all { it in sections.indices }) { "Unknown section index in $changed" }
        val (upcoming, passed) = changed.partition { sections[it].startMs >= positionMs }
        return upcoming.sortedBy { sections[it].startMs } + passed.sortedBy { sections[it].startMs }
    }

    fun request(
        newPlan: CompositionPlan,
        sourceMap: SourceMap,
        sectionIndex: Int,
    ): WindowRequest {
        val sections = SectionIndex.sections(newPlan)
        require(sectionIndex in sections.indices) { "No section $sectionIndex" }
        val section = sections[sectionIndex]
        val chunk = newPlan.chunks[sectionIndex]
        require(chunk is GenerationChunk) { "Section $sectionIndex is not a generation chunk" }
        val totalMs = newPlan.totalMs
        require(sourceMap.segments.lastOrNull()?.timelineEndMs == totalMs) { "Source map does not cover the plan" }

        val lead =
            contextRefs(sourceMap, section.startMs - minOf(contextMs, section.startMs), section.startMs)
                .let { refs -> refs.drop(refs.indexOfLast { it.durationMs < MIN_REF_MS } + 1) }
        val tail =
            contextRefs(sourceMap, section.endMs, section.endMs + minOf(contextMs, totalMs - section.endMs))
                .let { refs -> refs.takeWhile { it.durationMs >= MIN_REF_MS } }
        val leadMs = lead.sumOf { it.durationMs }
        val tailMs = tail.sumOf { it.durationMs }
        val plan = CompositionPlan(lead + chunk.copy(contextAdherence = chunk.contextAdherence ?: "high") + tail)
        return WindowRequest(plan, section, section.startMs - leadMs, leadMs, tailMs)
    }

    private fun contextRefs(
        sourceMap: SourceMap,
        fromMs: Long,
        toMs: Long,
    ): List<ReferenceChunk> = if (toMs - fromMs < MIN_REF_MS) emptyList() else sourceMap.refs(fromMs, toMs)

    fun splice(
        request: WindowRequest,
        audio: PcmAudio,
        master: PcmAudio,
    ): Splice {
        require(audio.sameFormatAs(master)) { "Format mismatch: window audio and master must match" }
        val actualMs = audio.framesToMs(audio.frames)
        if (abs(actualMs - request.expectedMs) > LENGTH_TOLERANCE_MS) {
            throw WindowLengthMismatch(request.expectedMs, actualMs)
        }
        val fitted = fit(audio, master.msToFrames(request.expectedMs))
        val fadeFrames = master.msToFrames(fadeMs)
        val startFrame = master.msToFrames(request.windowStartMs)
        val endFrame = startFrame + fitted.frames
        val sectionStart = master.msToFrames(request.section.startMs)
        val sectionEnd = master.msToFrames(request.section.endMs)

        val fadeIn =
            when {
                request.leadMs == 0L -> {
                    startFrame
                }

                placement == FadePlacement.CenteredInContext -> {
                    startFrame + master.msToFrames(request.leadMs) / 2 - fadeFrames / 2
                }

                else -> {
                    sectionStart - fadeFrames / 2
                }
            }.coerceAtLeast(startFrame)
        val fadeOut =
            when {
                request.tailMs == 0L && sectionEnd >= master.frames -> {
                    null
                }

                request.tailMs == 0L -> {
                    endFrame - fadeFrames
                }

                placement == FadePlacement.CenteredInContext -> {
                    sectionEnd + master.msToFrames(request.tailMs) / 2 - fadeFrames / 2
                }

                else -> {
                    sectionEnd - fadeFrames / 2
                }
            }?.coerceAtMost(endFrame - fadeFrames)
        return Splice(fitted, startFrame, fadeIn, fadeOut, fadeFrames.toInt())
    }

    private fun fit(
        audio: PcmAudio,
        frames: Long,
    ): PcmAudio {
        if (audio.frames == frames) return audio
        return PcmAudio(audio.samples.copyOf((frames * audio.channels).toInt()), audio.sampleRate, audio.channels)
    }

    private companion object {
        const val MIN_REF_MS = 50L
        const val LENGTH_TOLERANCE_MS = 100L
    }
}
