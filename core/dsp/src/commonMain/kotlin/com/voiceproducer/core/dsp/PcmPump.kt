package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.DuckTarget
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

class PcmPump(
    private val master: SwappableMaster,
) {
    val sampleRate: Int = master.current.sampleRate
    val channels: Int = master.current.channels
    val frames: Long = master.current.frames

    @Volatile
    var cursor: Long = 0
        private set

    private val gain = SmoothedGain(sampleRate).also { it.setTarget(0f, 0) }
    private var scratch = ShortArray(0)

    fun fill(
        out: ShortArray,
        frames: Int,
    ) {
        require(frames >= 0 && out.size >= frames * channels) { "out too small for $frames frames" }
        if (this.frames <= 0) {
            out.fill(0, 0, frames * channels)
            return
        }
        if (scratch.size < frames * channels) scratch = ShortArray(frames * channels)
        var filled = 0
        while (filled < frames) {
            if (cursor >= this.frames) wrap()
            val read = master.read(cursor, frames - filled, scratch)
            if (read == 0) {
                wrap()
                continue
            }
            applyGain(scratch, read, out, filled)
            cursor += read
            filled += read
        }
        if (cursor >= this.frames) wrap()
    }

    fun seek(frame: Long) {
        cursor = frame.coerceIn(0, frames)
        if (cursor >= frames) cursor = 0
        master.applyAllQueued()
    }

    fun setDuck(target: DuckTarget) = gain.setTarget(target.db, target.rampMs)

    private fun wrap() {
        cursor = 0
        master.applyAllQueued()
    }

    private fun applyGain(
        src: ShortArray,
        frameCount: Int,
        out: ShortArray,
        outFrame: Int,
    ) {
        var o = outFrame * channels
        var s = 0
        repeat(frameCount) {
            val g = gain.nextFrameGain()
            repeat(channels) {
                val scaled = (src[s++] * g).roundToInt()
                out[o++] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}
