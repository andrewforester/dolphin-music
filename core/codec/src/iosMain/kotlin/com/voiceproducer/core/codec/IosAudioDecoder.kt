package com.voiceproducer.core.codec

import com.voiceproducer.core.dsp.WavCodec
import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.PcmAudio
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAudioDecoder(
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
        val path = NSTemporaryDirectory() + "decode-${NSUUID().UUIDString}.audio"
        try {
            writeFile(path, bytes)
            return readPcm16(NSURL.fileURLWithPath(path), context)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    private fun writeFile(
        path: String,
        bytes: ByteArray,
    ) {
        val data =
            if (bytes.isEmpty()) {
                NSData()
            } else {
                bytes.usePinned {
                    NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
                }
            }
        check(data.writeToFile(path, atomically = false)) { "Cannot write temp audio file" }
    }

    private fun openFile(
        url: NSURL,
        err: ObjCObjectVar<NSError?>,
    ): AVAudioFile =
        runCatching {
            AVAudioFile(
                forReading = url,
                commonFormat = AVAudioPCMFormatInt16,
                interleaved = true,
                error = err.ptr,
            )
        }.getOrElse { e ->
            if (e !is NullPointerException) throw e
            throw IllegalArgumentException(err.value?.localizedDescription ?: "Cannot open audio", e)
        }

    private fun readPcm16(
        url: NSURL,
        context: CoroutineContext,
    ): PcmAudio =
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val file = openFile(url, err)
            val format = file.processingFormat
            val channels = format.channelCount.toInt()
            val sampleRate = format.sampleRate.toInt()
            if (channels <= 0 || sampleRate <= 0) {
                throw IllegalArgumentException("Unsupported audio: sampleRate=$sampleRate channels=$channels")
            }
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = READ_FRAMES)
            var out = ShortArray(maxOf(file.length.toInt(), 0) * channels)
            var written = 0
            while (file.framePosition < file.length) {
                context.ensureActive()
                if (!file.readIntoBuffer(buffer, frameCount = READ_FRAMES, error = err.ptr)) {
                    throw IllegalArgumentException(err.value?.localizedDescription ?: "Audio read failed")
                }
                val frames = buffer.frameLength.toInt()
                if (frames == 0) break
                val n = frames * channels
                if (written + n > out.size) out = out.copyOf(maxOf(out.size * 2, written + n))
                val src = checkNotNull(buffer.int16ChannelData)[0]!!
                for (i in 0 until n) out[written + i] = src[i]
                written += n
            }
            PcmAudio(out.copyOf(written), sampleRate, channels)
        }

    private companion object {
        const val READ_FRAMES = 8192u
    }
}
