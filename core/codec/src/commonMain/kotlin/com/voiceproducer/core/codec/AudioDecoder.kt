package com.voiceproducer.core.codec

import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.PcmAudio

interface AudioDecoder {
    suspend fun decode(
        bytes: ByteArray,
        format: AudioFormat,
    ): PcmAudio
}
