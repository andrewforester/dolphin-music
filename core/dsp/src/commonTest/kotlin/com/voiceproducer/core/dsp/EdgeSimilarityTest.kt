package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeSimilarityTest {
    private val rate = 24_000
    private val contextMs = 1_000L
    private val fadeMs = 200L

    private class Window(
        val startMs: Long,
        val leadMs: Long,
        val sectionEndMs: Long,
        val tailMs: Long,
    ) {
        val expectedMs: Long get() = leadMs + (sectionEndMs - startMs - leadMs) + tailMs
    }

    private val windows =
        listOf(
            Window(startMs = 0, leadMs = 0, sectionEndMs = 2_000, tailMs = contextMs),
            Window(startMs = 1_000, leadMs = contextMs, sectionEndMs = 4_000, tailMs = contextMs),
        )

    private fun splice(
        w: Window,
        audio: PcmAudio,
        master: PcmAudio,
    ): Splice {
        val fadeFrames = master.msToFrames(fadeMs)
        val startFrame = master.msToFrames(w.startMs)
        val endFrame = startFrame + audio.frames
        val sectionEnd = master.msToFrames(w.sectionEndMs)
        val fadeIn =
            if (w.leadMs == 0L) {
                startFrame
            } else {
                (startFrame + master.msToFrames(w.leadMs) / 2 - fadeFrames / 2).coerceAtLeast(startFrame)
            }
        val fadeOut =
            when {
                w.tailMs == 0L && sectionEnd >= master.frames -> null
                w.tailMs == 0L -> endFrame - fadeFrames
                else -> (sectionEnd + master.msToFrames(w.tailMs) / 2 - fadeFrames / 2).coerceAtMost(endFrame - fadeFrames)
            }
        return Splice(audio, startFrame, fadeIn, fadeOut, fadeFrames.toInt())
    }

    private fun tone(
        frames: Int,
        phase: Double = 0.0,
        channels: Int = 1,
    ): PcmAudio =
        PcmAudio(
            ShortArray(frames * channels) {
                val t = (it / channels).toDouble() / rate
                (sin(2 * PI * 440 * t + phase) * 6_000 + sin(2 * PI * 1_250 * t + 2 * phase) * 3_000)
                    .roundToInt()
                    .toShort()
            },
            rate,
            channels,
        )

    private fun noise(
        frames: Int,
        channels: Int = 1,
    ): PcmAudio {
        val rnd = Random(7)
        return PcmAudio(ShortArray(frames * channels) { rnd.nextInt(-8_000, 8_000).toShort() }, rate, channels)
    }

    private fun similarity(
        master: PcmAudio,
        context: PcmAudio,
        section: Int = 1,
    ): Float {
        val w = windows[section]
        val ch = master.channels
        val start = (w.startMs * rate / 1000).toInt()
        val len = (w.expectedMs * rate / 1000).toInt()
        val window = ShortArray(len * ch) { context.samples[start * ch + it] }
        val splice = splice(w, PcmAudio(window, rate, ch), master)
        return EdgeSimilarity.similarity(master, splice, w.leadMs, w.tailMs, w.sectionEndMs)
    }

    @Test
    fun edgeSimilarityHighForIdenticalContext() {
        val m = tone(6 * rate)
        assertTrue(similarity(m, m) >= 0.99f)
    }

    @Test
    fun edgeSimilarityHighForPhaseShiftedContext() {
        val sim = similarity(tone(6 * rate), tone(6 * rate, phase = PI / 2))
        assertTrue(sim >= 0.9f, "sim=$sim")
    }

    @Test
    fun edgeSimilarityLowForDifferentContent() {
        val sim = similarity(tone(6 * rate), noise(6 * rate))
        assertTrue(sim < 0.5f, "sim=$sim")
    }

    @Test
    fun edgeSimilarityUsesWorseEdge() {
        val m = tone(6 * rate)
        val n = noise(6 * rate)
        val mixed = PcmAudio(ShortArray(m.samples.size) { if (it < 3 * rate) m.samples[it] else n.samples[it] }, rate, 1)
        val sim = similarity(m, mixed)
        assertTrue(sim < 0.5f, "sim=$sim")
    }

    @Test
    fun edgeWithoutContextCountsAsIdentical() {
        val m = tone(6 * rate)
        assertTrue(similarity(m, m, section = 0) >= 0.99f)
        val only = Window(startMs = 0, leadMs = 0, sectionEndMs = 6_000, tailMs = 0)
        val s = splice(only, noise(6 * rate), m)
        assertEquals(1f, EdgeSimilarity.similarity(m, s, only.leadMs, only.tailMs, only.sectionEndMs))
    }

    private fun nearSilence(seed: Int): PcmAudio {
        val rnd = Random(seed)
        return PcmAudio(ShortArray(6 * rate) { rnd.nextInt(-17, 18).toShort() }, rate, 1)
    }

    @Test
    fun edgeSimilarityIsOneWhenBothContextsAreSilent() {
        assertEquals(1f, similarity(nearSilence(1), nearSilence(2)))
        val zero = PcmAudio(ShortArray(6 * rate), rate, 1)
        assertEquals(1f, similarity(zero, zero))
    }

    @Test
    fun edgeSimilarityIsZeroWhenOnlyOneContextIsSilent() {
        val silent = nearSilence(3)
        val m = tone(6 * rate)
        assertEquals(0f, similarity(m, silent))
        assertEquals(0f, similarity(silent, m))
    }

    @Test
    fun edgeSimilarityHandlesStereo() {
        val m = tone(6 * rate, channels = 2)
        assertTrue(similarity(m, m) >= 0.99f)
        assertTrue(similarity(m, noise(6 * rate, channels = 2)) < 0.5f)
    }
}
