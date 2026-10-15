package com.voiceproducer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioFormatTest {
    @Test
    fun parsesFormats() {
        assertEquals(AudioFormat.Pcm(24000, 1), AudioFormat.parse("pcm_24000", 1))
        assertEquals(AudioFormat.Compressed("audio/mpeg"), AudioFormat.parse("mp3_44100_128", 2))
        assertEquals(AudioFormat.Compressed("audio/opus"), AudioFormat.parse("opus_48000_64", 2))
    }

    @Test
    fun rejectsUnknown() {
        assertFailsWith<IllegalArgumentException> { AudioFormat.parse("flac_44100", 2) }
        assertFailsWith<IllegalArgumentException> { AudioFormat.parse("pcm_abc", 2) }
    }
}
