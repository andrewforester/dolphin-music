package com.voiceproducer.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.voiceproducer.core.dsp.SmoothedGain
import com.voiceproducer.core.model.DuckTarget
import java.nio.ByteBuffer
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class DuckingAudioProcessor : BaseAudioProcessor() {
    private val lock = Any()
    private var target: DuckTarget? = null

    @Volatile private var gain: SmoothedGain? = null

    fun setTarget(target: DuckTarget) {
        synchronized(lock) {
            this.target = target
            gain?.setTarget(target.db, target.rampMs)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        synchronized(lock) {
            val previous = gain
            val g = SmoothedGain(inputAudioFormat.sampleRate)
            if (previous != null) {
                g.setTarget(previous.currentDb, 0)
                g.nextFrameGain()
            }
            target?.let { g.setTarget(it.db, if (previous != null) it.rampMs else 0) }
            gain = g
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val g = checkNotNull(gain) { "Not configured" }
        val channels = inputAudioFormat.channelCount
        val frameBytes = 2 * channels
        val output = replaceOutputBuffer(remaining - remaining % frameBytes)
        while (inputBuffer.remaining() >= frameBytes) {
            val frameGain = g.nextFrameGain()
            repeat(channels) {
                val scaled = (inputBuffer.short * frameGain).roundToInt()
                output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.flip()
    }
}
