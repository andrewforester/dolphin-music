package com.voiceproducer.core.codec

import com.voiceproducer.core.model.AudioFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.CoroutineContext

class MediaCodecDecoderThreadingTest {
    @Test
    fun decodesOnTheIoDispatcherByDefault() {
        assertSame(Dispatchers.IO, MediaCodecDecoder(File("unused")).dispatcher)
    }

    @Test
    fun decodeRunsOnTheGivenDispatcher() =
        runBlocking {
            var dispatched = 0
            val counting =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatched++
                        Dispatchers.Default.dispatch(context, block)
                    }
                }
            MediaCodecDecoder(File("unused"), counting).decode(byteArrayOf(1, 0, 2, 0), AudioFormat.Pcm(44100, 1))
            assertTrue("dispatched=$dispatched", dispatched > 0)
        }
}
