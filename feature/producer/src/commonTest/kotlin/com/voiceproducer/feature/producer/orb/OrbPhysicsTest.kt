package com.voiceproducer.feature.producer.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrbPhysicsTest {
    private val r = 100f

    private val arena =
        OrbArena(
            width = 1080f,
            safeTop = 100f,
            safeBottom = 1900f,
            bottom = 2050f,
            orbRadius = r,
            dismiss = OrbPoint(540f, 2150f),
            magnetRadius = 220f,
            stickGap = 48f,
        )

    private val still = OrbPoint(0f, 0f)

    private fun assertNear(
        expected: OrbPoint,
        actual: OrbPoint,
    ) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test
    fun parkedOrbSinksPastTheNearerEdge() {
        assertNear(OrbPoint(-30f, 800f), parkPoint(OrbPoint(300f, 800f), arena))
        assertNear(OrbPoint(1110f, 800f), parkPoint(OrbPoint(700f, 800f), arena))
    }

    @Test
    fun parkClampsToTheSafeArea() {
        assertEquals(200f, parkPoint(OrbPoint(10f, -50f), arena).y)
        assertEquals(1900f, parkPoint(OrbPoint(10f, 2300f), arena).y)
    }

    @Test
    fun releasedAwayFromTheEdgesTheOrbStays() {
        assertEquals(OrbDrop.Stay(OrbPoint(400f, 900f)), onRelease(OrbPoint(400f, 900f), still, arena))
        assertEquals(OrbDrop.Stay(OrbPoint(160f, 900f)), onRelease(OrbPoint(160f, 900f), still, arena))
    }

    @Test
    fun releasedNearAnEdgeTheOrbSticks() {
        assertNear(OrbPoint(-30f, 900f), assertIs<OrbDrop.Park>(onRelease(OrbPoint(148f, 900f), still, arena)).to)
        assertIs<OrbDrop.Park>(onRelease(OrbPoint(1080f - 140f, 900f), still, arena))
    }

    @Test
    fun releaseInMagnetDismisses() = assertEquals(OrbDrop.Dismiss, onRelease(OrbPoint(600f, 2100f), still, arena))

    @Test
    fun aFlingCarriesTheOrbABit() {
        val drop = onRelease(OrbPoint(400f, 900f), OrbPoint(0f, -1000f), arena)
        assertEquals(OrbDrop.Stay(OrbPoint(400f, 780f)), drop)
    }

    @Test
    fun aFlingTowardsAnEdgeSticks() {
        assertNear(OrbPoint(-30f, 900f), assertIs<OrbDrop.Park>(onRelease(OrbPoint(300f, 900f), OrbPoint(-2000f, 0f), arena)).to)
    }

    @Test
    fun aFlingStopsInsideTheScreen() {
        val drop = onRelease(OrbPoint(400f, 300f), OrbPoint(0f, -5000f), arena)
        assertEquals(OrbDrop.Stay(OrbPoint(400f, 200f)), drop)
        assertEquals(OrbDrop.Stay(OrbPoint(400f, 2050f)), onRelease(OrbPoint(400f, 1900f), OrbPoint(0f, 5000f), arena))
    }

    @Test
    fun magnetZone() {
        assertTrue(inMagnet(OrbPoint(540f + 220f, 2150f), arena))
        assertFalse(inMagnet(OrbPoint(540f + 221f, 2150f), arena))
    }

    @Test
    fun noBumpFarFromTheEdges() {
        assertEquals(0f, edgeBump(inset = 400f, attached = false, orbRadius = r).depth)
        assertEquals(0f, edgeBump(inset = 2 * r + 1f, attached = false, orbRadius = r).depth)
    }

    @Test
    fun theBumpReachesOutAsTheOrbNears() {
        val depths = listOf(200f, 170f, 140f, 110f, 100f).map { edgeBump(it, attached = false, orbRadius = r).depth }
        assertEquals(depths.sorted(), depths)
        assertTrue(depths.last() > 0f)
    }

    @Test
    fun aParkedOrbSitsInALongShallowBump() {
        val bump = edgeBump(inset = -30f, attached = true, orbRadius = r)
        assertEquals(110f, bump.depth, 0.5f)
        assertEquals(220f, bump.halfLength, 0.5f)
    }

    @Test
    fun pulledAwayTheBumpThinsIntoANeck() {
        val parked = edgeBump(-30f, attached = true, orbRadius = r)
        val pulled = edgeBump(50f, attached = true, orbRadius = r)
        assertTrue(pulled.depth > parked.depth)
        assertTrue(pulled.halfLength < parked.halfLength)
    }

    @Test
    fun attachesWhenHalfInAndSnapsWhenPulledOut() {
        assertFalse(stillAttached(inset = 10f, attached = false, orbRadius = r))
        assertTrue(stillAttached(inset = 0f, attached = false, orbRadius = r))
        assertTrue(stillAttached(inset = 70f, attached = true, orbRadius = r))
        assertFalse(stillAttached(inset = 71f, attached = true, orbRadius = r))
    }

    @Test
    fun theBumpGrowsWithoutJumpsUpToTheAttachPoint() {
        val insets = (200 downTo 0 step 5).map { it.toFloat() }
        val depths = insets.map { edgeBump(it, attached = false, orbRadius = r).depth }
        depths.zipWithNext().forEach { (a, b) -> assertTrue(b - a < 20f, "jump from $a to $b") }
        assertEquals(edgeBump(0f, attached = true, orbRadius = r).depth, depths.last(), 1f)
    }
}
