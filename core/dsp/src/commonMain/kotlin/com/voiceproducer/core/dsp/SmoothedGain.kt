package com.voiceproducer.core.dsp

import kotlin.concurrent.Volatile
import kotlin.math.log10
import kotlin.math.pow

private const val MS_PER_SECOND = 1000L
private const val AMPLITUDE_DB_SCALE = 20f

class SmoothedGain(
    private val sampleRate: Int,
) {
    private class Request(
        val linear: Float,
        val rampFrames: Int,
    )

    @Volatile private var pending: Request? = null

    @Volatile private var published = 1f

    private var applied: Request? = null
    private var current = 1f
    private var step = 0f
    private var remaining = 0
    private var target = 1f

    fun setTarget(
        db: Float,
        rampMs: Int,
    ) {
        val frames = (rampMs.toLong() * sampleRate / MS_PER_SECOND).toInt().coerceAtLeast(0)
        pending = Request(10f.pow(db / AMPLITUDE_DB_SCALE), frames)
    }

    fun nextFrameGain(): Float {
        val req = pending
        if (req != null && req !== applied) {
            applied = req
            target = req.linear
            if (req.rampFrames <= 0) {
                current = target
                remaining = 0
            } else {
                remaining = req.rampFrames
                step = (target - current) / remaining
            }
        }
        if (remaining > 0) {
            remaining--
            current = if (remaining == 0) target else current + step
        }
        published = current
        return current
    }

    val currentDb: Float
        get() = published.let { if (it <= 0f) -Float.MAX_VALUE else AMPLITUDE_DB_SCALE * log10(it) }
}
