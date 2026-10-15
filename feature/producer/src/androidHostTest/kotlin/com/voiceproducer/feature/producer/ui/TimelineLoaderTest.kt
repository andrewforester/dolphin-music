package com.voiceproducer.feature.producer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLoaderTest {
    @Test
    fun completionStartsAtTheDrawnProgressAndRisesMonotonicallyToOne() {
        val from = 0.7f
        assertEquals(from, completedProgress(from, 0f), 0f)
        var last = from
        for (i in 1..10) {
            val p = completedProgress(from, i / 10f)
            assertTrue(p >= last)
            last = p
        }
        assertEquals(1f, last, 0f)
    }
}
