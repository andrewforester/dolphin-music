package com.voiceproducer.feature.producer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoaderProgressTest {
    @Test
    fun startsAtZero() = assertEquals(0f, loaderProgress(0))

    @Test
    fun negativeElapsedIsZero() = assertEquals(0f, loaderProgress(-500))

    @Test
    fun isMonotonicNonDecreasing() {
        var last = 0f
        for (t in 0L..600_000L step 250L) {
            val p = loaderProgress(t)
            assertTrue(p >= last, "progress went back at $t ms: $p < $last")
            last = p
        }
    }

    @Test
    fun staysWithinMaxAndNeverReachesOne() {
        for (t in listOf(1L, 1_000L, 15_000L, 60_000L, 3_600_000L, Long.MAX_VALUE)) {
            val p = loaderProgress(t)
            assertTrue(p <= LOADER_MAX, "$t ms: $p exceeds max")
            assertTrue(p < 1f)
        }
    }

    @Test
    fun reachesTheSlowdownPointAtTheExpectedDuration() {
        assertEquals(LOADER_SLOWDOWN_AT, loaderProgress(LOADER_EXPECTED_MS), 0.001f)
    }

    @Test
    fun easesOut() {
        val first = loaderProgress(5_000) - loaderProgress(0)
        val last = loaderProgress(15_000) - loaderProgress(10_000)
        assertTrue(first > last, "first third $first should climb faster than the last $last")
    }

    @Test
    fun keepsMovingPastTheSlowdownPoint() {
        val t = LOADER_EXPECTED_MS
        for (after in listOf(1_000L, 5_000L, 10_000L, 20_000L)) {
            assertTrue(loaderProgress(t + after) > loaderProgress(t + after - 1_000), "stalled $after ms past the slowdown")
        }
    }

    @Test
    fun speedIsContinuousAtTheSlowdownPoint() {
        val t = LOADER_EXPECTED_MS
        val before = loaderProgress(t) - loaderProgress(t - 100)
        val after = loaderProgress(t + 100) - loaderProgress(t)
        assertTrue(after <= before, "speed after the slowdown point $after should not exceed $before")
        assertTrue(abs(after - before) < before * 0.05f, "speed jumps at the slowdown point: $before -> $after")
    }

    @Test
    fun speedDropsByTheSlowdownFactorPerPercentPastTheSlowdownPoint() {
        fun speedAt(fill: Float): Float {
            val t = (LOADER_EXPECTED_MS..LOADER_EXPECTED_MS * 10).first { loaderProgress(it) >= fill }
            return (loaderProgress(t + 50) - loaderProgress(t)) / 50
        }
        val ratio = speedAt(0.94f) / speedAt(0.93f)
        assertEquals(LOADER_SLOWDOWN_PER_PERCENT, ratio, 0.03f)
    }

    @Test
    fun scalesWithTheExpectedDuration() {
        assertEquals(loaderProgress(7_500, 15_000), loaderProgress(3_000, 6_000), 0.0001f)
        assertEquals(LOADER_SLOWDOWN_AT, loaderProgress(SECTION_LOADER_EXPECTED_MS, SECTION_LOADER_EXPECTED_MS), 0.001f)
    }
}
