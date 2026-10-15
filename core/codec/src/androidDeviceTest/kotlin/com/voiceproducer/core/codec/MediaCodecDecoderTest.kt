package com.voiceproducer.core.codec

import androidx.test.platform.app.InstrumentationRegistry
import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MediaCodecDecoderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val decoder = MediaCodecDecoder(context.cacheDir)

    @Test(expected = CancellationException::class)
    fun cancelledDecodeStopsBetweenBuffers() {
        val mp3 =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("tone_440_1s.mp3")
                .use { it.readBytes() }
        decoder.decodeCompressed(mp3, Job().apply { cancel() })
    }

    @Test
    fun decodesMp3Tone() =
        runTest {
            val mp3 =
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open("tone_440_1s.mp3")
                    .use { it.readBytes() }

            val audio = decoder.decode(mp3, AudioFormat.Compressed("audio/mpeg"))

            val frames = audio.samples.size / audio.channels
            val durationMs = frames * 1000.0 / audio.sampleRate
            assertTrue("duration was $durationMs ms", abs(durationMs - 1000.0) <= 60.0)
            val hz = dominantHz(audio)
            assertTrue("frequency was $hz Hz", abs(hz - 440.0) <= 10.0)
        }

    @Test
    fun passesThroughPcm() =
        runTest {
            val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234)
            val bytes = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, s ->
                bytes[i * 2] = s.toInt().toByte()
                bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
            }

            val audio = decoder.decode(bytes, AudioFormat.Pcm(sampleRate = 24000, channels = 2))

            assertEquals(24000, audio.sampleRate)
            assertEquals(2, audio.channels)
            assertTrue(samples.contentEquals(audio.samples))
        }

    private fun dominantHz(audio: PcmAudio): Double {
        val ch = audio.channels
        val frames = audio.samples.size / ch
        val skip = audio.sampleRate / 10
        var crossings = 0
        for (i in skip + 1 until frames - skip) {
            val prev = audio.samples[(i - 1) * ch]
            val cur = audio.samples[i * ch]
            if (prev < 0 && cur >= 0) crossings++
        }
        val seconds = (frames - 2 * skip - 1).toDouble() / audio.sampleRate
        return crossings / seconds
    }
}
