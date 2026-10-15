@file:OptIn(ExperimentalForeignApi::class)

package com.voiceproducer.core.playback

import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.PcmAudio
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioFormat
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class IosAudioEngineTest {
    private val engine = IosAudioEngine.offline(AVAudioFormat(standardFormatWithSampleRate = RATE.toDouble(), channels = 2u))

    @AfterTest
    fun tearDown() = engine.release()

    @Test
    fun `construction and load do not build the audio graph until play`() {
        assertFalse(engine.isGraphCreated)
        engine.load(tone(seconds = 2.0))
        engine.seekTo(500)
        assertFalse(engine.isGraphCreated)
        engine.play()
        assertTrue(engine.isGraphCreated)
    }

    @Test
    fun `load publishes duration and zero position`() {
        engine.load(tone(seconds = 2.0))
        assertEquals(2000, engine.durationMs.value)
        assertEquals(0, engine.positionMs.value)
        assertFalse(engine.isPlaying.value)
    }

    @Test
    fun `play advances position`() {
        engine.load(tone(seconds = 2.0))
        engine.play()
        render(ms = 400)
        spinMainRunLoop(seconds = 0.15)
        assertTrue(engine.isPlaying.value)
        val position = engine.positionMs.value
        assertTrue(position in 200..600, "position $position")
    }

    @Ignore
    @Test
    fun `play advances position on the audio output in real time`() {
        val live = IosAudioEngine()
        try {
            live.load(tone(seconds = 2.0))
            live.play()
            spinMainRunLoop(seconds = 0.4)
            assertTrue(live.isPlaying.value)
            assertTrue(live.positionMs.value in 200..600, "position ${live.positionMs.value}")
        } finally {
            live.release()
        }
    }

    @Test
    fun `seek while paused publishes position`() {
        engine.load(tone(seconds = 2.0))
        engine.seekTo(1500)
        assertEquals(1500, engine.positionMs.value)
    }

    @Test
    fun `swap counts after pass start`() {
        engine.load(tone(seconds = 2.0))
        engine.swapMaster(tone(seconds = 2.0, hz = 880.0), listOf(0L..1999L))
        engine.seekTo(0)
        assertEquals(1, engine.swapsApplied.value)
    }

    @Test
    fun `unload resets state`() {
        engine.load(tone(seconds = 2.0))
        engine.play()
        render(ms = 100)
        engine.unload()
        assertFalse(engine.isPlaying.value)
        assertEquals(0, engine.durationMs.value)
        assertEquals(0, engine.positionMs.value)
        engine.play()
        spinMainRunLoop(seconds = 0.1)
        assertFalse(engine.isPlaying.value)
        assertEquals(0, engine.positionMs.value)
    }

    @Test
    fun `output is the master looping without gaps`() {
        val master = ramp(frames = 6000)
        engine.load(master)
        engine.play()
        assertMatches(master, startFrame = 0, render(frames = 15_000))
    }

    @Test
    fun `a whole-master swap takes over at the loop start without a gap`() {
        val a = ramp(frames = 10_000)
        val b = ramp(frames = 10_000, offset = 7)
        engine.load(a)
        engine.play()
        engine.swapMaster(b, listOf(0L..a.framesToMs(a.frames) - 1))
        val out = render(frames = 20_000)
        assertMatches(a, startFrame = 0, out.copyOfRange(0, 20_000))
        assertMatches(b, startFrame = 0, out.copyOfRange(20_000, 40_000))
        spinMainRunLoop(seconds = 0.1)
        assertEquals(1, engine.swapsApplied.value)
    }

    @Test
    fun `pause and play resume from the paused frame`() {
        val master = ramp(frames = 20_000)
        engine.load(master)
        engine.play()
        render(frames = 4096)
        engine.pause()
        assertFalse(engine.isPlaying.value)
        assertEquals(master.framesToMs(4096), engine.positionMs.value)
        engine.play()
        assertMatches(master, startFrame = 4096, render(frames = 4096))
    }

    @Test
    fun `duck set before load applies to the new master`() {
        val master = ramp(frames = 6000)
        engine.setDuck(DuckTarget(db = -20f, rampMs = 0))
        engine.load(master)
        engine.play()
        val out = render(frames = 4096)
        out.forEachIndexed { i, v ->
            val expected = master.samples[i] * 0.1f / 32768f
            if (abs(v - expected) > 2f / 32768f) fail("frame $i: $v != $expected")
        }
    }

    private fun render(
        frames: Int = 0,
        ms: Int = 0,
    ): FloatArray {
        val total = if (ms > 0) ms * RATE / 1000 else frames
        val out = FloatArray(total * 2)
        var done = 0
        while (done < total) {
            val n = minOf(CHUNK, total - done)
            engine.renderOffline(n).copyInto(out, done * 2)
            done += n
            NSThread.sleepForTimeInterval(0.002)
        }
        return out
    }

    private fun assertMatches(
        master: PcmAudio,
        startFrame: Int,
        out: FloatArray,
    ) {
        val samples = master.samples.size
        out.forEachIndexed { i, v ->
            val expected = master.samples[(startFrame * 2 + i) % samples] / 32768f
            if (abs(v - expected) > 1e-6f) fail("sample $i: $v != $expected")
        }
    }

    private fun tone(
        seconds: Double,
        hz: Double = 440.0,
    ): PcmAudio {
        val samples = ShortArray((seconds * RATE).toInt()) { i -> (sin(2 * PI * hz * i / RATE) * 8000).toInt().toShort() }
        return PcmAudio(samples, RATE, channels = 1)
    }

    private fun ramp(
        frames: Int,
        offset: Int = 0,
    ): PcmAudio = PcmAudio(ShortArray(frames * 2) { i -> ((i * 5 + offset) % 20_000 + 1000).toShort() }, RATE, channels = 2)

    private fun spinMainRunLoop(seconds: Double) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private companion object {
        const val RATE = 44_100
        const val CHUNK = 512
    }
}
