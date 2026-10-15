package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MixerTest {
    private val rate = 24_000

    private fun sine(
        freq: Double,
        frames: Int,
        sampleRate: Int = rate,
    ): PcmAudio =
        PcmAudio(
            ShortArray(frames) { (sin(2 * PI * freq * it / sampleRate) * 10_000).roundToInt().toShort() },
            sampleRate,
            1,
        )

    @Test
    fun crossfadeGainsEndpoints() {
        assertEquals(1f, Mixer.crossfadeGains(0f).first, 1e-6f)
        assertEquals(0f, Mixer.crossfadeGains(0f).second, 1e-6f)
        assertEquals(0f, Mixer.crossfadeGains(1f).first, 1e-6f)
        assertEquals(1f, Mixer.crossfadeGains(1f).second, 1e-6f)
        for (t in listOf(0.1f, 0.25f, 0.5f, 0.9f)) {
            val (o, n) = Mixer.crossfadeGains(t)
            assertEquals(1f, o * o + n * n, 1e-6f, "t=$t")
        }
        val half = sqrt(0.5f)
        assertEquals(half, Mixer.crossfadeGains(0.5f).first, 1e-6f)
        assertEquals(half, Mixer.crossfadeGains(0.5f).second, 1e-6f)
    }

    @Test
    fun bakeKeepsMasterOutsideDiff() {
        val master = sine(440.0, 24_000)
        val splice =
            Splice(
                sine(660.0, 12_000),
                startFrame = 6_000,
                fadeInStartFrame = 7_000,
                fadeOutStartFrame = 15_000,
                fadeFrames = 480,
            )
        val out = Mixer.bake(master, splice)
        val diff = splice.diffRange(master.frames)
        assertEquals(master.frames, out.frames)
        for (i in 0 until master.frames.toInt()) {
            if (i.toLong() !in diff) assertEquals(master.samples[i], out.samples[i], "frame $i")
        }
    }

    @Test
    fun bakeUsesNewInsideBody() {
        val master = sine(440.0, 24_000)
        val new = sine(660.0, 12_000)
        val splice = Splice(new, 6_000, 7_000, 15_000, 480)
        val out = Mixer.bake(master, splice)
        for (i in (7_000 + 480) until 15_000) {
            assertEquals(new.samples[i - 6_000], out.samples[i], "frame $i")
        }
    }

    @Test
    fun bakeIsContinuousAtSeams() {
        val master = sine(440.0, 24_000)
        val new = sine(440.0, 12_000)
        val aligned = PcmAudio(ShortArray(12_000) { master.samples[it + 6_000] }, rate, 1)
        val splice = Splice(aligned, 6_000, 7_000, 15_000, 480)
        val out = Mixer.bake(master, splice)

        fun maxDelta(
            a: ShortArray,
            from: Int,
            to: Int,
        ): Int = (from + 1 until to).maxOf { abs(a[it] - a[it - 1]) }
        val pure = maxDelta(master.samples, 0, master.samples.size)
        val limit = pure * sqrt(2.0) * 1.05
        assertTrue(maxDelta(out.samples, 6_990, 7_490) <= limit, "fade-in seam")
        assertTrue(maxDelta(out.samples, 14_990, 15_490) <= limit, "fade-out seam")
        assertEquals(new.frames, aligned.frames)
    }

    @Test
    fun bakeKeepsRmsForUncorrelatedSignals() {
        val rnd = Random(1234)

        fun noise(n: Int) = PcmAudio(ShortArray(n) { (rnd.nextDouble(-1.0, 1.0) * 10_000).roundToInt().toShort() }, rate, 1)
        val master = noise(24_000)
        val new = noise(14_000)
        val fade = 4_800
        val out = Mixer.bake(master, Splice(new, 6_000, 7_000, 15_000, fade))

        fun rms(
            a: ShortArray,
            from: Int,
            to: Int,
        ): Double = sqrt((from until to).sumOf { a[it].toDouble() * a[it] } / (to - from))
        val db = 20 * log10(rms(out.samples, 7_000, 7_000 + fade) / rms(master.samples, 7_000, 7_000 + fade))
        assertTrue(abs(db) <= 0.5, "RMS deviates by $db dB")
    }

    @Test
    fun bakeWithoutFadeOutRunsToEnd() {
        val master = sine(440.0, 24_000)
        val new = sine(660.0, 18_000)
        val splice = Splice(new, 6_000, 7_000, null, 480)
        val out = Mixer.bake(master, splice)
        assertEquals(7_000L until 24_000L, splice.diffRange(master.frames))
        for (i in 7_480 until 24_000) assertEquals(new.samples[i - 6_000], out.samples[i])
        assertContentEquals(master.samples.copyOfRange(0, 7_000), out.samples.copyOfRange(0, 7_000))
    }

    @Test
    fun bakeRejectsFormatMismatch() {
        val master = sine(440.0, 24_000)
        val new = sine(660.0, 12_000, sampleRate = 48_000)
        assertFailsWith<IllegalArgumentException> {
            Mixer.bake(master, Splice(new, 6_000, 7_000, 15_000, 480))
        }
    }

    @Test
    fun bakeRejectsMaterialThatEndsTooEarly() {
        val master = sine(440.0, 24_000)
        assertFailsWith<IllegalArgumentException> {
            Mixer.bake(master, Splice(sine(660.0, 5_000), 6_000, 7_000, 15_000, 480))
        }
    }

    @Test
    fun bakeRejectsBadGeometry() {
        val master = sine(440.0, 24_000)
        val new = sine(660.0, 12_000)
        assertFailsWith<IllegalArgumentException> { Mixer.bake(master, Splice(new, 6_000, 5_000, 15_000, 480)) }
        assertFailsWith<IllegalArgumentException> { Mixer.bake(master, Splice(new, 6_000, 7_000, 7_100, 480)) }
        assertFailsWith<IllegalArgumentException> { Mixer.bake(master, Splice(new, 6_000, 7_000, 15_000, 0)) }
        assertFailsWith<IllegalArgumentException> { Mixer.bake(master, Splice(new, -100, -50, 15_000, 480)) }
    }

    @Test
    fun bakeSaturatesAndSharesGainAcrossChannels() {
        val loud = PcmAudio(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE), 24_000, 2)
        val out = Mixer.bake(loud, Splice(loud, 0, 0, null, 2))
        val (a, b) = out.samples[0] to out.samples[1]
        assertEquals(Short.MAX_VALUE, a)
        assertEquals(Short.MIN_VALUE, b)
    }
}
