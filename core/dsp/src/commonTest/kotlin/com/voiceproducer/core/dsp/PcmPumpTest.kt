package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.PcmAudio
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmPumpTest {
    private fun ramp(
        frames: Int = 1000,
        channels: Int = 1,
        offset: Int = 0,
    ) = PcmAudio(ShortArray(frames * channels) { (it / channels + offset).toShort() }, 1000, channels)

    private fun PcmPump.take(n: Int): List<Int> {
        val out = ShortArray(n * channels)
        fill(out, n)
        return out.map { it.toInt() }
    }

    @Test
    fun fillReadsSequentially() {
        val pump = PcmPump(SwappableMaster(ramp()))
        assertEquals((0..299).toList(), pump.take(300))
        assertEquals((300..599).toList(), pump.take(300))
        assertEquals(600L, pump.cursor)
    }

    @Test
    fun fillWrapsAtEndOfMaster() {
        val pump = PcmPump(SwappableMaster(ramp()))
        pump.seek(900)
        assertEquals((900..999).toList() + (0..99).toList(), pump.take(200))
        assertEquals(100L, pump.cursor)
    }

    @Test
    fun fillAcrossPassEndAppliesQueuedSwapAtFrameZero() {
        val old = ramp()
        val next =
            PcmAudio(
                old.samples.copyOf().also {
                    for (i in 0..99) it[i] = (i + 10000).toShort()
                    for (i in 950..999) {
                        it[i] =
                            (i + 20000).toShort()
                    }
                },
                1000,
                1,
            )
        val master = SwappableMaster(old)
        val pump = PcmPump(master)
        pump.seek(900)
        master.requestSwap(next, listOf(950L..999L, 0L..99L))
        assertEquals((900..999).toList() + (10000..10099).toList(), pump.take(200))
        assertEquals(1, master.swaps.value)
    }

    @Test
    fun seekToZeroAppliesQueuedSwaps() {
        val master = SwappableMaster(ramp())
        val pump = PcmPump(master)
        pump.seek(500)
        master.requestSwap(ramp(offset = 5000), listOf(0L..999L))
        assertEquals(0, master.swaps.value)
        pump.seek(0)
        assertEquals((5000..5009).toList(), pump.take(10))
    }

    @Test
    fun restartKeepsPosition() {
        val pump = PcmPump(SwappableMaster(ramp()))
        pump.seek(450)
        assertEquals((450..499).toList(), pump.take(50))
    }

    @Test
    fun duckRampsAcrossFills() {
        val master = SwappableMaster(PcmAudio(ShortArray(1000) { 10000 }, 1000, 1))
        val pump = PcmPump(master)
        pump.setDuck(DuckTarget(-6f, 10))
        val all = pump.take(5) + pump.take(10)
        for (i in 1 until all.size) assertTrue(all[i] <= all[i - 1], "monotonic at $i: $all")
        val expected = 10000 * 10f.pow(-6f / 20f)
        assertTrue(abs(all.last() - expected) <= 2, "last=${all.last()} expected=$expected")
        val maxStep = (10000 - expected) / 10
        for (i in 1 until all.size) assertTrue(all[i - 1] - all[i] <= maxStep + 2, "jump at $i: $all")
    }

    @Test
    fun firstFillIsUnduckedUntilSetDuck() {
        val pump = PcmPump(SwappableMaster(ramp()))
        assertEquals((0..9).toList(), pump.take(10))
    }

    @Test
    fun stereoFramesStayInterleaved() {
        val stereo = PcmAudio(shortArrayOf(1, -1, 2, -2, 3, -3, 4, -4), 1000, 2)
        val pump = PcmPump(SwappableMaster(stereo))
        assertEquals(listOf(1, -1, 2, -2, 3, -3), pump.take(3))
    }
}
