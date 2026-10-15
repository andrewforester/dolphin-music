package com.voiceproducer.core.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.voiceproducer.core.dsp.SwappableMaster
import com.voiceproducer.core.dsp.WavCodec

@OptIn(UnstableApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MasterDataSource(
    private val master: SwappableMaster,
) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var frameBytes = 0
    private var header = ByteArray(0)
    private var frames = ShortArray(0)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        master.applyAllQueued()
        val audio = master.current
        frameBytes = 2 * audio.channels
        val dataSize = audio.frames * frameBytes
        header = WavCodec.header(audio.sampleRate, audio.channels, dataSize.toInt())
        val total = WavCodec.HEADER_SIZE + dataSize
        if (dataSpec.position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - position else minOf(dataSpec.length, total - position)
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        when {
            length == 0 -> 0
            bytesRemaining == 0L -> C.RESULT_END_OF_INPUT
            else -> readNext(buffer, offset, length)
        }

    private fun readNext(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val wanted = minOf(length.toLong(), bytesRemaining, MAX_READ_BYTES.toLong()).toInt()
        val read = if (position < WavCodec.HEADER_SIZE) readHeader(buffer, offset, wanted) else readPcm(buffer, offset, wanted)
        if (read == 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    private fun readHeader(
        buffer: ByteArray,
        offset: Int,
        wanted: Int,
    ): Int {
        val n = minOf(wanted, WavCodec.HEADER_SIZE - position.toInt())
        header.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }

    private fun readPcm(
        buffer: ByteArray,
        offset: Int,
        wanted: Int,
    ): Int {
        val dataPos = position - WavCodec.HEADER_SIZE
        val firstFrame = dataPos / frameBytes
        val skip = (dataPos % frameBytes).toInt()
        val frameCount = (skip + wanted + frameBytes - 1) / frameBytes
        val channels = frameBytes / 2
        if (frames.size < frameCount * channels) frames = ShortArray(frameCount * channels)
        val gotFrames = master.read(firstFrame, frameCount, frames)
        val n = minOf(wanted, gotFrames * frameBytes - skip).coerceAtLeast(0)
        for (i in 0 until n) {
            val byteIndex = skip + i
            val sample = frames[byteIndex shr 1].toInt()
            buffer[offset + i] = (if (byteIndex and 1 == 0) sample else sample shr Byte.SIZE_BITS).toByte()
        }
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    companion object {
        val URI: Uri = "master://current".toUri()

        private const val MAX_READ_BYTES = 64 * 1024
    }
}
