package com.voiceproducer.core.dsp
import com.voiceproducer.core.model.PcmAudio

data class Splice(
    val audio: PcmAudio,
    val startFrame: Long,
    val fadeInStartFrame: Long,
    val fadeOutStartFrame: Long?,
    val fadeFrames: Int,
) {
    fun diffRange(masterFrames: Long): LongRange = fadeInStartFrame until (fadeOutStartFrame?.let { it + fadeFrames } ?: masterFrames)
}
