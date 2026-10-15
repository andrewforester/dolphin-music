package com.voiceproducer.core.codec

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.voiceproducer.core.dsp.WavCodec
import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import android.media.AudioFormat as AndroidAudioFormat

class MediaCodecDecoder(
    private val cacheDir: File,
    internal val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioDecoder {
    override suspend fun decode(
        bytes: ByteArray,
        format: AudioFormat,
    ): PcmAudio =
        withContext(dispatcher) {
            when (format) {
                is AudioFormat.Pcm -> WavCodec.pcm16LeToAudio(bytes, format.sampleRate, format.channels)
                is AudioFormat.Compressed -> decodeCompressed(bytes, coroutineContext)
            }
        }

    internal fun decodeCompressed(
        bytes: ByteArray,
        context: CoroutineContext,
    ): PcmAudio {
        val tempFile = File.createTempFile("decode-", ".audio", cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            tempFile.writeBytes(bytes)
            extractor.setDataSource(tempFile.absolutePath)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("No audio track found")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            return runDecodeLoop(extractor, codec, inputFormat, context)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
            tempFile.delete()
        }
    }

    private fun runDecodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        context: CoroutineContext,
    ): PcmAudio {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            context.ensureActive()
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding =
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AndroidAudioFormat.ENCODING_PCM_16BIT
                        }
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    Unit
                }

                else -> {
                    if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            appendAsPcm16(buf, pcmEncoding, out)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        }
        return WavCodec.pcm16LeToAudio(out.toByteArray(), sampleRate, channels)
    }

    private fun appendAsPcm16(
        buf: ByteBuffer,
        encoding: Int,
        out: ByteArrayOutputStream,
    ) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        if (encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buf.asFloatBuffer()
            val bytes = ByteArray(floats.remaining() * 2)
            for (i in 0 until floats.remaining()) {
                val s = (floats.get(i) * PCM16_SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[i * 2] = s.toByte()
                bytes[i * 2 + 1] = (s shr Byte.SIZE_BITS).toByte()
            }
            out.write(bytes)
        } else {
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            out.write(bytes)
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PCM16_SCALE = 32768f
    }
}
