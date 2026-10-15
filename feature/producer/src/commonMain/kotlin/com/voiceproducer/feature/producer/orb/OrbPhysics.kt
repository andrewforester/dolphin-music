package com.voiceproducer.feature.producer.orb

import kotlin.math.hypot

data class OrbPoint(
    val x: Float,
    val y: Float,
)

data class OrbArena(
    val width: Float,
    val safeTop: Float,
    val safeBottom: Float,
    val bottom: Float,
    val orbRadius: Float,
    val dismiss: OrbPoint,
    val magnetRadius: Float,
    val stickGap: Float,
)

sealed interface OrbDrop {
    data class Park(
        val to: OrbPoint,
    ) : OrbDrop

    data class Stay(
        val at: OrbPoint,
    ) : OrbDrop

    data object Dismiss : OrbDrop
}

internal const val PARK_INSET = -0.3f
private const val FLING_SECONDS = 0.12f

fun nearerEdgeX(
    centreX: Float,
    arena: OrbArena,
): Float = if (centreX < arena.width / 2) 0f else arena.width

fun edgeInset(
    centreX: Float,
    arena: OrbArena,
): Float = if (centreX < arena.width / 2) centreX else arena.width - centreX

fun parkPoint(
    release: OrbPoint,
    arena: OrbArena,
): OrbPoint {
    val inset = PARK_INSET * arena.orbRadius
    val x = if (release.x < arena.width / 2) inset else arena.width - inset
    val top = arena.safeTop + arena.orbRadius
    return OrbPoint(x, release.y.coerceIn(top, maxOf(top, arena.safeBottom)))
}

fun freePoint(
    p: OrbPoint,
    arena: OrbArena,
): OrbPoint {
    val r = arena.orbRadius
    val top = arena.safeTop + r
    return OrbPoint(p.x.coerceIn(r, maxOf(r, arena.width - r)), p.y.coerceIn(top, maxOf(top, arena.bottom)))
}

fun sticks(
    p: OrbPoint,
    arena: OrbArena,
): Boolean = edgeInset(p.x, arena) - arena.orbRadius <= arena.stickGap

fun inMagnet(
    p: OrbPoint,
    arena: OrbArena,
): Boolean = hypot(p.x - arena.dismiss.x, p.y - arena.dismiss.y) <= arena.magnetRadius

fun settle(
    p: OrbPoint,
    arena: OrbArena,
): OrbDrop = if (sticks(p, arena)) OrbDrop.Park(parkPoint(p, arena)) else OrbDrop.Stay(freePoint(p, arena))

fun onRelease(
    p: OrbPoint,
    velocity: OrbPoint,
    arena: OrbArena,
): OrbDrop =
    if (inMagnet(p, arena)) {
        OrbDrop.Dismiss
    } else {
        settle(OrbPoint(p.x + velocity.x * FLING_SECONDS, p.y + velocity.y * FLING_SECONDS), arena)
    }
