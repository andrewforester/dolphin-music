package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwappableMasterTest {
    private val master = PcmAudio(ShortArray(10) { it.toShort() }, 24_000, 1)

    private fun edited(base: Int) =
        PcmAudio(
            ShortArray(10) { if (it in 4 until 6) (base + it).toShort() else it.toShort() },
            24_000,
            1,
        )

    private val diff = listOf(4L until 6L)

    private fun SwappableMaster.readList(
        from: Long,
        max: Int,
    ): List<Int> {
        val out = ShortArray(max * current.channels)
        val n = read(from, max, out)
        return out.take(n * current.channels).map { it.toInt() }
    }

    @Test
    fun swapAppliedWhenReadBeforeDiff() {
        val m = SwappableMaster(master)
        m.requestSwap(edited(100), diff)
        assertEquals(listOf(0, 1, 2), m.readList(0, 3))
        assertEquals(listOf(3, 104, 105, 6), m.readList(3, 4))
        assertEquals(edited(100), m.current)
    }

    @Test
    fun swapDeferredWhileReadInsideDiff() {
        val m = SwappableMaster(master)
        assertEquals(listOf(4), m.readList(4, 1))
        m.requestSwap(edited(100), diff)
        assertEquals(listOf(5, 6, 7), m.readList(5, 3))
        assertEquals(master, m.current)
        assertEquals(listOf(3, 4), m.readList(3, 2))
        assertEquals(master, m.current)
        assertEquals(listOf(8, 9), m.readList(8, 2))
        assertEquals(listOf(0, 1, 2), m.readList(0, 3))
        assertEquals(listOf(104, 105), m.readList(4, 2))
        assertEquals(edited(100), m.current)
    }

    @Test
    fun reopenInsideDiffServesTheNewAudio() {
        val m = SwappableMaster(master)
        assertEquals(listOf(4), m.readList(4, 1))
        m.requestSwap(edited(100), diff)
        assertEquals(listOf(5), m.readList(5, 1))
        assertEquals(0, m.swaps.value)
        assertTrue(m.hasPending)
        m.applyAllQueued()
        assertEquals(listOf(105, 6), m.readList(5, 2))
        assertEquals(1, m.swaps.value)
        assertFalse(m.hasPending)
    }

    @Test
    fun swapsApplyInOrder() {
        val m = SwappableMaster(master)
        m.requestSwap(edited(100), diff)
        m.requestSwap(edited(200), diff)
        assertEquals(listOf(0), m.readList(0, 1))
        assertEquals(2, m.swaps.value)
        assertEquals(listOf(204, 205), m.readList(4, 2))
    }

    @Test
    fun queuedSwapsWaitForTheirOwnDiff() {
        val m = SwappableMaster(master)
        val first = edited(100)
        val secondSamples = first.samples.copyOf().also { it[8] = 999 }
        m.requestSwap(first, diff)
        m.requestSwap(PcmAudio(secondSamples, 24_000, 1), listOf(8L until 9L))
        assertEquals(listOf(6, 7, 8), m.readList(6, 3))
        assertEquals(1, m.swaps.value)
        assertEquals(listOf(0), m.readList(0, 1))
        assertEquals(2, m.swaps.value)
        assertEquals(listOf(999), m.readList(8, 1))
    }

    @Test
    fun fullCoverSwapAppliesAtNextPassStart() {
        val m = SwappableMaster(master)
        val next = PcmAudio(ShortArray(10) { (50 + it).toShort() }, 24_000, 1)
        m.requestSwap(next, listOf(0L until 10L))
        assertEquals(listOf(0, 1, 2, 3, 4), m.readList(0, 5))
        assertEquals(listOf(5, 6, 7, 8, 9), m.readList(5, 5))
        assertEquals(0, m.swaps.value)
        m.applyAllQueued()
        assertEquals(1, m.swaps.value)
        assertEquals((50..59).toList(), m.readList(0, 10))
    }

    @Test
    fun diffAtFrameZeroAppliesAtTheVeryNextPass() {
        val m = SwappableMaster(master)
        assertEquals(listOf(0, 1, 2), m.readList(0, 3))
        val next =
            PcmAudio(
                master.samples.copyOf().also {
                    it[0] = 77
                    it[1] = 78
                },
                24_000,
                1,
            )
        m.requestSwap(next, listOf(0L until 2L))
        m.readList(3, 7)
        m.applyAllQueued()
        assertEquals(listOf(77, 78, 2), m.readList(0, 3))
        assertEquals(1, m.swaps.value)
    }

    @Test
    fun allQueuedSwapsApplyOnApplyAllQueued() {
        val m = SwappableMaster(master)
        assertEquals(listOf(5), m.readList(5, 1))
        m.requestSwap(edited(100), diff)
        m.requestSwap(edited(200), diff)
        m.requestSwap(edited(300), diff)
        m.applyAllQueued()
        assertEquals(3, m.swaps.value)
        assertEquals(edited(300), m.current)
        m.applyAllQueued()
        assertEquals(3, m.swaps.value)
    }

    @Test
    fun readPastEndReturnsShortCount() {
        val m = SwappableMaster(master)
        assertEquals(listOf(8, 9), m.readList(8, 5))
        assertEquals(0, m.read(10, 3, ShortArray(3)))
    }

    @Test
    fun readsInterleavedFrames() {
        val stereo = PcmAudio(ShortArray(8) { it.toShort() }, 24_000, 2)
        val m = SwappableMaster(stereo)
        val out = ShortArray(4)
        assertEquals(2, m.read(1, 2, out))
        assertContentEquals(shortArrayOf(2, 3, 4, 5), out)
    }

    @Test
    fun swapMustMatchFormatAndLength() {
        val m = SwappableMaster(master)
        assertFailsWith<IllegalArgumentException> { m.requestSwap(PcmAudio(ShortArray(9), 24_000, 1), diff) }
        assertFailsWith<IllegalArgumentException> { m.requestSwap(PcmAudio(ShortArray(10), 48_000, 1), diff) }
        assertFailsWith<IllegalArgumentException> { m.requestSwap(PcmAudio(ShortArray(10), 24_000, 2), diff) }
    }

    @Test
    fun msRangeConvertsToCoveringFrames() {
        assertEquals(240L until 504L, SwappableMaster.framesCovering(10L..20L, 24_000))
        assertEquals(44L until 133L, SwappableMaster.framesCovering(1L..2L, 44_100))
    }

    @Test
    fun streamedLoopSwitchesToBakedMasterSampleAccurately() {
        val rate = 24_000

        fun sine(
            freq: Double,
            frames: Int,
        ) = PcmAudio(
            ShortArray(frames * 2) { (sin(2 * PI * freq * (it / 2) / rate) * 10_000).roundToInt().toShort() },
            rate,
            2,
        )
        val old = sine(440.0, rate)
        val splice =
            Splice(
                sine(660.0, 12_000),
                startFrame = 6_000,
                fadeInStartFrame = 7_000,
                fadeOutStartFrame = 15_000,
                fadeFrames = 480,
            )
        val baked = Mixer.bake(old, splice)
        val diffRange = splice.diffRange(old.frames)

        val m = SwappableMaster(old)
        val chunk = 1_000
        val out = ShortArray(chunk * 2)
        val stream = mutableListOf<Short>()
        var pos = 0L
        var swapAt = -1L
        repeat(60) {
            if (stream.size / 2 == 9_000) m.requestSwap(baked, listOf(diffRange))
            val n = m.read(pos, chunk, out)
            if (swapAt < 0 && m.current === baked) swapAt = stream.size / 2L
            for (i in 0 until n * 2) stream += out[i]
            pos += n
            if (pos >= old.frames) pos = 0
        }

        assertEquals(16_000L, swapAt)
        val samples = stream.toShortArray()
        for (f in 0 until samples.size / 2) {
            val loopFrame = (f % old.frames).toInt()
            val expected = if (f < swapAt) old else baked
            for (c in 0 until 2) {
                assertEquals(expected.samples[loopFrame * 2 + c], samples[f * 2 + c], "stream frame $f ch $c")
            }
        }
        val secondPass = samples.copyOfRange(old.samples.size, old.samples.size * 2)
        assertContentEquals(baked.samples, secondPass)
    }
}
