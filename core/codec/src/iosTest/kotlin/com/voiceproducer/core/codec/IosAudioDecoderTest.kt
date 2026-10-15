@file:OptIn(ExperimentalForeignApi::class)

package com.voiceproducer.core.codec

import com.voiceproducer.core.model.AudioFormat
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosAudioDecoderTest {
    private val decoder = IosAudioDecoder()

    @Test
    fun `decodes on the IO dispatcher by default`() {
        assertSame(Dispatchers.IO, IosAudioDecoder().dispatcher)
    }

    @Test
    fun `cancelled decode stops between chunks`() {
        assertFailsWith<CancellationException> { decoder.decodeCompressed(aacSine(), Job().apply { cancel() }) }
    }

    @Test
    fun `pcm passes through`() =
        runTest {
            val samples = shortArrayOf(1, -2, 300, -400, 5000, -6000, 32767, -32768)
            val bytes = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, s ->
                bytes[i * 2] = s.toInt().toByte()
                bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
            }
            val out = decoder.decode(bytes, AudioFormat.Pcm(44100, 2))
            assertEquals(44100, out.sampleRate)
            assertEquals(2, out.channels)
            assertContentEquals(samples, out.samples)
        }

    @Test
    fun `compressed decodes to pcm16`() =
        runTest {
            val out = decoder.decode(aacSine(), AudioFormat.Compressed("audio/mpeg"))
            assertEquals(44100, out.sampleRate)
            assertEquals(1, out.channels)
            assertTrue(abs(out.frames - 22050) <= 2048, "frames=${out.frames}")
            var sum = 0.0
            out.samples.forEach {
                val v = it / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / out.samples.size)
            assertTrue(rms > 0.1, "rms=$rms")
        }

    @Test
    fun `garbage bytes throw`() =
        runTest {
            val bytes = Random(42).nextBytes(100)
            val e = runCatching { decoder.decode(bytes, AudioFormat.Compressed("audio/mpeg")) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException || e is IllegalStateException, "got $e")
        }

    private fun aacSine(): ByteArray {
        val path = NSTemporaryDirectory() + "fixture-${NSUUID().UUIDString}.m4a"
        val url = NSURL.fileURLWithPath(path)
        try {
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val settings =
                    mapOf<Any?, Any?>(
                        AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatMPEG4AAC),
                        AVSampleRateKey to NSNumber(double = 44100.0),
                        AVNumberOfChannelsKey to NSNumber(int = 1),
                    )
                val file: AVAudioFile? = AVAudioFile(forWriting = url, settings = settings, error = err.ptr)
                val f = checkNotNull(file) { "writer failed: ${err.value?.localizedDescription}" }
                val frames = 22050u
                val buffer = AVAudioPCMBuffer(pCMFormat = f.processingFormat, frameCapacity = frames)
                buffer.frameLength = frames
                val ch = checkNotNull(buffer.floatChannelData)[0]!!
                for (i in 0 until frames.toInt()) {
                    ch[i] = (0.8 * sin(2.0 * PI * 440.0 * i / 44100.0)).toFloat()
                }
                check(f.writeFromBuffer(buffer, err.ptr)) { "write failed: ${err.value?.localizedDescription}" }
                f.close()
            }
            return readBytes(url)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    private fun readBytes(url: NSURL): ByteArray {
        val data = checkNotNull(NSData.dataWithContentsOfURL(url)) { "cannot read fixture" }
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }
}
