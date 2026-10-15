package com.voiceproducer.core.dsp
import com.voiceproducer.core.model.PcmAudio

object WavCodec {
    const val HEADER_SIZE = 44

    private const val OFFSET_RIFF = 0
    private const val OFFSET_RIFF_SIZE = 4
    private const val OFFSET_WAVE = 8
    private const val OFFSET_FMT = 12
    private const val OFFSET_FMT_SIZE = 16
    private const val OFFSET_FORMAT = 20
    private const val OFFSET_CHANNELS = 22
    private const val OFFSET_SAMPLE_RATE = 24
    private const val OFFSET_BYTE_RATE = 28
    private const val OFFSET_BLOCK_ALIGN = 32
    private const val OFFSET_BITS = 34
    private const val OFFSET_DATA = 36
    private const val OFFSET_DATA_SIZE = 40
    private const val RIFF_SIZE_BASE = 36
    private const val FMT_CHUNK_SIZE = 16
    private const val BITS_PER_SAMPLE = 16
    private const val TAG_LENGTH = 4
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF

    fun encode(audio: PcmAudio): ByteArray {
        val dataSize = audio.samples.size * 2
        val out = header(audio.sampleRate, audio.channels, dataSize).copyOf(HEADER_SIZE + dataSize)
        audio.samples.forEachIndexed { i, s ->
            out.putInt16(HEADER_SIZE + i * 2, s.toInt())
        }
        return out
    }

    fun header(
        sampleRate: Int,
        channels: Int,
        dataSize: Int,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out.putAscii(OFFSET_RIFF, "RIFF")
        out.putInt32(OFFSET_RIFF_SIZE, RIFF_SIZE_BASE + dataSize)
        out.putAscii(OFFSET_WAVE, "WAVE")
        out.putAscii(OFFSET_FMT, "fmt ")
        out.putInt32(OFFSET_FMT_SIZE, FMT_CHUNK_SIZE)
        out.putInt16(OFFSET_FORMAT, 1)
        out.putInt16(OFFSET_CHANNELS, channels)
        out.putInt32(OFFSET_SAMPLE_RATE, sampleRate)
        out.putInt32(OFFSET_BYTE_RATE, sampleRate * channels * 2)
        out.putInt16(OFFSET_BLOCK_ALIGN, channels * 2)
        out.putInt16(OFFSET_BITS, BITS_PER_SAMPLE)
        out.putAscii(OFFSET_DATA, "data")
        out.putInt32(OFFSET_DATA_SIZE, dataSize)
        return out
    }

    fun decode(bytes: ByteArray): PcmAudio {
        require(bytes.size >= HEADER_SIZE) { "WAV too short" }
        require(bytes.ascii(OFFSET_RIFF) == "RIFF" && bytes.ascii(OFFSET_WAVE) == "WAVE") { "Not a RIFF/WAVE file" }
        require(bytes.ascii(OFFSET_FMT) == "fmt ") { "Missing fmt chunk" }
        require(bytes.int16(OFFSET_FORMAT) == 1) { "Only PCM WAV is supported" }
        require(bytes.int16(OFFSET_BITS) == BITS_PER_SAMPLE) { "Only 16-bit WAV is supported" }
        val channels = bytes.int16(OFFSET_CHANNELS)
        val rate = bytes.int32(OFFSET_SAMPLE_RATE)
        require(bytes.ascii(OFFSET_DATA) == "data") { "Expected data chunk at offset 36" }
        val declared = bytes.int32(OFFSET_DATA_SIZE)
        val available = bytes.size - HEADER_SIZE
        val dataSize = if (declared in 0..available) declared else available
        return pcm16LeToAudio(bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + dataSize), rate, channels)
    }

    fun pcm16LeToAudio(
        bytes: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): PcmAudio {
        val samples = ShortArray(bytes.size / 2) { i -> bytes.int16(i * 2).toShort() }
        return PcmAudio(samples, sampleRate, channels)
    }

    private fun ByteArray.putAscii(
        at: Int,
        s: String,
    ) {
        s.forEachIndexed { i, c -> this[at + i] = c.code.toByte() }
    }

    private fun ByteArray.putInt16(
        at: Int,
        v: Int,
    ) {
        this[at] = v.toByte()
        this[at + 1] = (v shr BYTE_BITS).toByte()
    }

    private fun ByteArray.putInt32(
        at: Int,
        v: Int,
    ) {
        putInt16(at, v)
        putInt16(at + 2, v shr BITS_PER_SAMPLE)
    }

    private fun ByteArray.ascii(at: Int): String = copyOfRange(at, at + TAG_LENGTH).decodeToString()

    private fun ByteArray.int16(at: Int): Int = (this[at].toInt() and BYTE_MASK) or ((this[at + 1].toInt() and BYTE_MASK) shl BYTE_BITS)

    private fun ByteArray.int32(at: Int): Int = int16(at) or (int16(at + 2) shl BITS_PER_SAMPLE)
}
