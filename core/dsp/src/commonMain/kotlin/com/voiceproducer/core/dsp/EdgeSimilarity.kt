package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object EdgeSimilarity {
    private const val FRAME = 2048
    private const val HOP = 1024

    private const val SILENCE_RMS = 32.768
    private val BANDS = DoubleArray(24) { 100.0 * 100.0.pow(it / 23.0) }

    fun similarity(
        master: PcmAudio,
        splice: Splice,
        leadMs: Long,
        tailMs: Long,
        sectionEndMs: Long,
    ): Float {
        require(master.sameFormatAs(splice.audio)) { "Format mismatch: master and splice audio must match" }
        val start = splice.startFrame
        val leadFrames = master.msToFrames(leadMs)
        val tailStart = master.msToFrames(sectionEndMs)
        val tailFrames = master.msToFrames(tailMs)
        val left = edge(master, start, splice.audio, 0, leadFrames)
        val right = edge(master, tailStart, splice.audio, tailStart - start, tailFrames)
        return minOf(left, right)
    }

    private fun edge(
        master: PcmAudio,
        masterFrom: Long,
        window: PcmAudio,
        windowFrom: Long,
        frames: Long,
    ): Float {
        if (frames <= 0) return 1f
        val sr = master.sampleRate
        val w = mono(window, windowFrom, frames)
        val m = mono(master, masterFrom, frames)
        val wSilent = rms(w) < SILENCE_RMS
        val mSilent = rms(m) < SILENCE_RMS
        return if (wSilent || mSilent) {
            if (wSilent && mSilent) 1f else 0f
        } else {
            correlation(features(w, sr), features(m, sr)).toFloat()
        }
    }

    private fun rms(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else sqrt(x.sumOf { it * it } / x.size)

    private fun mono(
        audio: PcmAudio,
        from: Long,
        frames: Long,
    ): DoubleArray {
        val ch = audio.channels
        val n = minOf(frames, audio.frames - from).coerceAtLeast(0).toInt()
        val base = (from * ch).toInt()
        return DoubleArray(n) { i ->
            var sum = 0.0
            for (c in 0 until ch) sum += audio.samples[base + i * ch + c]
            sum / ch
        }
    }

    private fun features(
        x: DoubleArray,
        sampleRate: Int,
    ): DoubleArray {
        val bands = BANDS.filter { it < sampleRate / 2.0 }
        val frameLen = minOf(FRAME, x.size)
        if (frameLen == 0) return DoubleArray(0)
        val starts = (0..x.size - frameLen step HOP).toList()
        val out = DoubleArray(starts.size * bands.size)
        var k = 0
        for (s in starts) {
            for (f in bands) {
                val c = 2 * cos(2 * PI * f / sampleRate)
                var s1 = 0.0
                var s2 = 0.0
                for (i in s until s + frameLen) {
                    val s0 = x[i] + c * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                out[k++] = log10(s1 * s1 + s2 * s2 - c * s1 * s2 + 1)
            }
        }
        return out
    }

    private fun correlation(
        x: DoubleArray,
        y: DoubleArray,
    ): Double {
        val n = minOf(x.size, y.size)
        if (n == 0) return 1.0
        val mx = x.take(n).average()
        val my = y.take(n).average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        return if (sxx == 0.0 || syy == 0.0) {
            if (sxx == syy && mx == my) 1.0 else 0.0
        } else {
            sxy / sqrt(sxx * syy)
        }
    }
}
