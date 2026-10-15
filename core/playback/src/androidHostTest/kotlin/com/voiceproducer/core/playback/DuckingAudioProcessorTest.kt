package com.voiceproducer.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.voiceproducer.core.model.DuckTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DuckingAudioProcessorTest {
    private val format = AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT)

    private fun process(
        processor: DuckingAudioProcessor,
        samples: ShortArray,
    ): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output
        return ShortArray(output.remaining() / 2) { output.short }
    }

    private fun configured(): DuckingAudioProcessor =
        DuckingAudioProcessor().apply {
            configure(format)
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

    @Test
    fun passesThroughAtUnityGain() {
        val p = configured()
        assertEquals(listOf<Short>(100, -100, 32_767, -32_768), process(p, shortArrayOf(100, -100, 32_767, -32_768)).toList())
    }

    @Test
    fun appliesTheSameGainToEveryChannelOfAFrame() {
        val p = configured()
        p.setTarget(DuckTarget(db = -6.0206f, rampMs = 0))
        assertEquals(listOf<Short>(5_000, -5_000, 5_000, -5_000), process(p, shortArrayOf(10_000, -10_000, 10_000, -10_000)).toList())
    }

    @Test
    fun rampsPerFrame() {
        val p = configured()
        p.setTarget(DuckTarget(db = -200f, rampMs = 4))
        val out = process(p, ShortArray(10) { 8_000 })
        assertEquals(listOf<Short>(6_000, 6_000, 4_000, 4_000, 2_000, 2_000, 0, 0, 0, 0), out.toList())
    }

    @Test
    fun targetSetBeforeConfigureIsApplied() {
        val p = DuckingAudioProcessor()
        p.setTarget(DuckTarget(db = -6.0206f, rampMs = 300))
        p.configure(format)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(listOf<Short>(5_000, 5_000), process(p, shortArrayOf(10_000, 10_000)).toList())
    }

    @Test
    fun clampsBoostedSamples() {
        val p = configured()
        p.setTarget(DuckTarget(db = 6.0206f, rampMs = 0))
        assertEquals(listOf<Short>(32_767, -32_768), process(p, shortArrayOf(30_000, -30_000)).toList())
    }

    @Test
    fun duckLevelSurvivesFlush() {
        val p = configured()
        p.setTarget(DuckTarget(db = -6.0206f, rampMs = 0))
        assertEquals(listOf<Short>(5_000, 5_000), process(p, shortArrayOf(10_000, 10_000)).toList())
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(listOf<Short>(5_000, 5_000), process(p, shortArrayOf(10_000, 10_000)).toList())
    }

    @Test
    fun reconfigureContinuesFromTheCurrentGain() {
        val p = configured()
        p.setTarget(DuckTarget(db = -200f, rampMs = 4))
        assertEquals(listOf<Short>(6_000, 6_000, 4_000, 4_000), process(p, ShortArray(4) { 8_000 }).toList())
        p.configure(AudioProcessor.AudioFormat(2_000, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val out = process(p, ShortArray(4) { 8_000 })
        assertTrue("first frames after reconfigure: ${out.toList()}", out[0] in 3_000..4_000 && out[2] < out[0])
    }

    @Test
    fun nonPcm16IsLeftInactive() {
        val p = DuckingAudioProcessor()
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)),
        )
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(p.isActive)
    }
}
