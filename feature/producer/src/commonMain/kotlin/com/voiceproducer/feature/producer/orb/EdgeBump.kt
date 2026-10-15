package com.voiceproducer.feature.producer.orb

data class EdgeBump(
    val depth: Float,
    val halfLength: Float,
)

private const val RIM = 0.4f
private const val HALF_LENGTH = 2.2f
private const val REACH = 1f
private const val REACH_DEPTH = 0.5f
private const val ATTACH_PROTRUSION = 1f
private const val SNAP_PROTRUSION = 1.7f
private const val NECK_THINNEST = 0.35f
private const val APPROACH_LENGTH = 0.6f
private const val FULL_PROTRUSION = 2f
private const val SMOOTH_A = 3f
private const val SMOOTH_B = 2f

fun stillAttached(
    inset: Float,
    attached: Boolean,
    orbRadius: Float,
): Boolean {
    val protrusion = inset + orbRadius
    val limit = if (attached) SNAP_PROTRUSION else ATTACH_PROTRUSION
    return protrusion <= limit * orbRadius
}

fun edgeBump(
    inset: Float,
    attached: Boolean,
    orbRadius: Float,
): EdgeBump {
    val r = orbRadius
    val protrusion = inset + r
    if (attached) {
        val parked = (PARK_INSET + 1) * r
        val stretch = unit((protrusion - parked) / (SNAP_PROTRUSION * r - parked))
        return EdgeBump(protrusion + RIM * r, HALF_LENGTH * r * (1 - (1 - NECK_THINNEST) * stretch))
    }
    val reach = smoothstep(unit(1 - (inset - r) / (REACH * r)))
    val wrap = smoothstep(unit((FULL_PROTRUSION * r - protrusion) / ((FULL_PROTRUSION - ATTACH_PROTRUSION) * r)))
    val depth = reach * REACH_DEPTH * r * (1 - wrap) + wrap * (protrusion + RIM * r)
    return EdgeBump(depth, HALF_LENGTH * r * (APPROACH_LENGTH + (1 - APPROACH_LENGTH) * reach))
}

private fun unit(v: Float) = v.coerceIn(0f, 1f)

private fun smoothstep(t: Float) = t * t * (SMOOTH_A - SMOOTH_B * t)
