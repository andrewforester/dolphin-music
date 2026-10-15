package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavCodecTest {
    @Test
    fun wavRoundTrip() {
        val rate = 24000
        val samples =
            ShortArray(rate * 2) { i ->
                val frame = i / 2
                (sin(2 * PI * 440.0 * frame / rate) * 20000).roundToInt().toShort()
            }
        val audio = PcmAudio(samples, rate, 2)

        val decoded = WavCodec.decode(WavCodec.encode(audio))

        assertContentEquals(audio.samples, decoded.samples)
        assertEquals(rate, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(audio, decoded)
    }

    @Test
    fun wavHeaderFields() {
        val bytes = WavCodec.encode(PcmAudio(shortArrayOf(1, 2, 3, 4), 24000, 2))

        assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals(2, (bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8))
        val rate =
            (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
        assertEquals(24000, rate)
        assertEquals(44 + 8, bytes.size)
    }

    @Test
    fun pcmLeParsing() {
        val audio = WavCodec.pcm16LeToAudio(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F), 24000, 1)
        assertContentEquals(shortArrayOf(1, 32767), audio.samples)
    }

    @Test
    fun decodeRejectsNonPcm16() {
        val bytes = WavCodec.encode(PcmAudio(shortArrayOf(1, 2), 24000, 1))
        bytes[34] = 8
        assertFailsWith<IllegalArgumentException> { WavCodec.decode(bytes) }
        val notWav = ByteArray(44)
        assertFailsWith<IllegalArgumentException> { WavCodec.decode(notWav) }
    }

    @Test
    fun msFrameConversion() {
        val audio = PcmAudio(ShortArray(0), 24000, 1)
        assertEquals(36000L, audio.msToFrames(1500))
        assertEquals(1500L, audio.framesToMs(36000))
        assertEquals(36000L, PcmAudio(ShortArray(72000), 24000, 2).frames)
    }
}
