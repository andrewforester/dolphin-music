package com.voiceproducer.feature.producer

import kotlin.math.exp
import kotlin.math.ln

const val LOADER_EXPECTED_MS = 15_000L

const val SECTION_LOADER_EXPECTED_MS = 6_000L

const val LOADER_SLOWDOWN_AT = 0.9f

const val LOADER_SLOWDOWN_PER_PERCENT = 0.7f

const val LOADER_MAX = 0.99f

private const val EASE = 1.5
private val EASE_SPAN = 1 - exp(-EASE)

private val DECAY = ln(1 / LOADER_SLOWDOWN_PER_PERCENT.toDouble()) / 0.01

fun loaderProgress(
    elapsedMs: Long,
    expectedMs: Long = LOADER_EXPECTED_MS,
): Float {
    val x = elapsedMs.toDouble() / expectedMs
    return when {
        elapsedMs <= 0 -> 0f
        x <= 1 -> (LOADER_SLOWDOWN_AT * (1 - exp(-EASE * x)) / EASE_SPAN).toFloat()
        else -> (LOADER_SLOWDOWN_AT + pastSlowdown(x - 1)).coerceAtMost(LOADER_MAX.toDouble()).toFloat()
    }
}

private fun pastSlowdown(dx: Double): Double {
    val v0 = LOADER_SLOWDOWN_AT * EASE * exp(-EASE) / EASE_SPAN
    return ln(1 + DECAY * v0 * dx) / DECAY
}
