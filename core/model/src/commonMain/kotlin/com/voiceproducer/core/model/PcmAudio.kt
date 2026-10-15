package com.voiceproducer.core.model

private const val MS_PER_SECOND = 1000L

class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
    }

    val frames: Long get() = (samples.size / channels).toLong()

    fun msToFrames(ms: Long): Long = ms * sampleRate / MS_PER_SECOND

    fun framesToMs(frames: Long): Long = frames * MS_PER_SECOND / sampleRate

    fun sameFormatAs(other: PcmAudio): Boolean = sampleRate == other.sampleRate && channels == other.channels

    override fun equals(other: Any?): Boolean = other is PcmAudio && sameFormatAs(other) && samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * (31 * sampleRate + channels) + samples.contentHashCode()

    override fun toString(): String = "PcmAudio(frames=$frames, sampleRate=$sampleRate, channels=$channels)"
}
