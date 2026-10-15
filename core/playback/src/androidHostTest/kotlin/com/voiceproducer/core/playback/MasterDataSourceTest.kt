package com.voiceproducer.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.voiceproducer.core.dsp.SwappableMaster
import com.voiceproducer.core.dsp.WavCodec
import com.voiceproducer.core.model.PcmAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class MasterDataSourceTest {
    private val audio = PcmAudio(ShortArray(2 * 300) { (it * 37 - 5_000).toShort() }, 24_000, 2)
    private val wav = WavCodec.encode(audio)

    private fun readAll(
        source: MasterDataSource,
        chunk: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(chunk)
        while (true) {
            val n = source.read(buf, 0, chunk)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun spec(
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec
        .Builder()
        .setUri(MasterDataSource.URI)
        .setPosition(position)
        .setLength(length)
        .build()

    @Test
    fun servesWholeWavFile() {
        val source = MasterDataSource(SwappableMaster(audio))
        assertEquals(wav.size.toLong(), source.open(spec()))
        assertEquals(Uri.parse("master://current"), source.uri)
        assertArrayEquals(wav, readAll(source, 1_000))
        source.close()
    }

    @Test
    fun servesFromPositionWithUnalignedOddReads() {
        val source = MasterDataSource(SwappableMaster(audio))
        assertEquals((wav.size - 47).toLong(), source.open(spec(position = 47)))
        assertArrayEquals(wav.copyOfRange(47, wav.size), readAll(source, 7))
        source.close()
    }

    @Test
    fun honoursBoundedLength() {
        val source = MasterDataSource(SwappableMaster(audio))
        assertEquals(101L, source.open(spec(position = 30, length = 101)))
        assertArrayEquals(wav.copyOfRange(30, 131), readAll(source, 64))
        source.close()
    }

    @Test
    fun readsTheSwappedMaster() {
        val master = SwappableMaster(audio)
        val next = PcmAudio(audio.samples.copyOf().also { it[2 * 290] = 1234 }, 24_000, 2)
        master.requestSwap(next, listOf(290L until 291L))
        val source = MasterDataSource(master)
        source.open(spec())
        assertArrayEquals(WavCodec.encode(next), readAll(source, 64))
        source.close()
    }

    @Test
    fun fullCoverSwapAppliesWhenTheNextPassOpensFromTheStart() {
        val master = SwappableMaster(audio)
        val next = PcmAudio(ShortArray(audio.samples.size) { 7 }, 24_000, 2)
        val source = MasterDataSource(master)
        source.open(spec())
        master.requestSwap(next, listOf(0L until audio.frames))
        assertArrayEquals(wav, readAll(source, 100))
        source.close()
        assertEquals(0, master.swaps.value)

        source.open(spec())
        assertEquals(1, master.swaps.value)
        assertArrayEquals(WavCodec.encode(next), readAll(source, 100))
        source.close()
    }

    @Test
    fun openAtDataStartIsAPassStart() {
        val master = SwappableMaster(audio)
        master.requestSwap(PcmAudio(ShortArray(audio.samples.size) { 7 }, 24_000, 2), listOf(0L until audio.frames))
        MasterDataSource(master).open(spec(position = WavCodec.HEADER_SIZE.toLong()))
        assertEquals(1, master.swaps.value)
    }

    @Test
    fun openInsideAPendingDiffServesTheNewMaster() {
        val master = SwappableMaster(audio)
        val next = PcmAudio(ShortArray(audio.samples.size) { 7 }, 24_000, 2)
        master.requestSwap(next, listOf(0L until audio.frames))
        val source = MasterDataSource(master)
        source.open(spec(position = 400))
        assertEquals(1, master.swaps.value)
        assertArrayEquals(WavCodec.encode(next).copyOfRange(400, wav.size), readAll(source, 100))
        source.close()
    }

    @Test
    fun continuousReadAcrossAPendingDiffStaysOnTheOldMaster() {
        val master = SwappableMaster(audio)
        val source = MasterDataSource(master)
        source.open(spec(position = 400))
        val buf = ByteArray(100)
        val head = ByteArrayOutputStream()
        head.write(buf, 0, source.read(buf, 0, 100))
        master.requestSwap(PcmAudio(ShortArray(audio.samples.size) { 7 }, 24_000, 2), listOf(0L until audio.frames))
        val rest = readAll(source, 100)
        assertArrayEquals(wav.copyOfRange(400, wav.size), head.toByteArray() + rest)
        assertEquals(0, master.swaps.value)
        source.close()
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsPositionPastEnd() {
        MasterDataSource(SwappableMaster(audio)).open(spec(position = wav.size + 1L))
    }
}
