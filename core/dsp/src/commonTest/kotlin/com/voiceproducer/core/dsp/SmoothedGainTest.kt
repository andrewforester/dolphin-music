package com.voiceproducer.core.dsp

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmoothedGainTest {
    @Test
    fun smoothedGainReachesTargetAfterRamp() {
        val g = SmoothedGain(48_000)
        g.setTarget(-18f, 80)
        var prev = 1f
        var last = 1f
        repeat(3_840) {
            last = g.nextFrameGain()
            assertTrue(last <= prev, "monotonic")
            prev = last
        }
        assertEquals(10f.pow(-18f / 20f), last, 1e-3f)
        assertEquals(-18f, g.currentDb, 0.05f)
    }

    @Test
    fun retargetDuringRampRestartsFromCurrent() {
        val g = SmoothedGain(48_000)
        g.setTarget(-18f, 80)
        repeat(1_000) { g.nextFrameGain() }
        val mid = g.nextFrameGain()
        g.setTarget(0f, 80)
        val first = g.nextFrameGain()
        assertTrue(first > mid && first - mid < 0.01f)
        repeat(3_840) { g.nextFrameGain() }
        assertEquals(1f, g.nextFrameGain(), 1e-4f)
    }

    @Test
    fun zeroRampJumpsImmediately() {
        val g = SmoothedGain(48_000)
        g.setTarget(-6f, 0)
        assertEquals(10f.pow(-6f / 20f), g.nextFrameGain(), 1e-5f)
    }
}
