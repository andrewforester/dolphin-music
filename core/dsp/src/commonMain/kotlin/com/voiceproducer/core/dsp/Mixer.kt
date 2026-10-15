package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object Mixer {
    fun crossfadeGains(t: Float): Pair<Float, Float> {
        val a = t * (PI.toFloat() / 2f)
        return cos(a) to sin(a)
    }

    fun bake(
        master: PcmAudio,
        splice: Splice,
    ): PcmAudio {
        val new = splice.audio
        require(master.sameFormatAs(new)) { "Format mismatch: master and splice audio must match" }
        val fade = splice.fadeFrames
        require(fade > 0) { "fadeFrames must be positive" }
        require(splice.fadeInStartFrame >= 0) { "fadeInStartFrame must be >= 0" }
        require(splice.fadeInStartFrame >= splice.startFrame) { "fadeInStartFrame must be >= startFrame" }
        val fadeOutStart = splice.fadeOutStartFrame
        if (fadeOutStart != null) {
            require(fadeOutStart >= splice.fadeInStartFrame + fade) {
                "fadeOutStartFrame must be >= fadeInStartFrame + fadeFrames"
            }
        }
        val diff = splice.diffRange(master.frames)
        require(diff.last < master.frames) {
            "Splice region exceeds master length"
        }
        require(diff.last - splice.startFrame < new.frames) { "Splice audio ends before the region that needs it" }

        val ch = master.channels
        val out = master.samples.copyOf()
        val fadeInStart = splice.fadeInStartFrame
        val bodyStart = fadeInStart + fade
        for (frame in diff) {
            val newBase = ((frame - splice.startFrame) * ch).toInt()
            val base = (frame * ch).toInt()
            val inFadeIn = frame < bodyStart
            val inFadeOut = fadeOutStart != null && frame >= fadeOutStart
            if (!inFadeIn && !inFadeOut) {
                for (c in 0 until ch) out[base + c] = new.samples[newBase + c]
                continue
            }
            val pos = if (inFadeIn) frame - fadeInStart else frame - fadeOutStart!!
            val t = (pos + 0.5f) / fade
            val (g1, g2) = crossfadeGains(t)
            val oldGain = if (inFadeIn) g1 else g2
            val newGain = if (inFadeIn) g2 else g1
            for (c in 0 until ch) {
                val mixed = master.samples[base + c] * oldGain + new.samples[newBase + c] * newGain
                out[base + c] = mixed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return PcmAudio(out, master.sampleRate, ch)
    }
}
