package com.voiceproducer.core.model

sealed interface AudioFormat {
    data class Pcm(
        val sampleRate: Int,
        val channels: Int,
    ) : AudioFormat

    data class Compressed(
        val mime: String,
    ) : AudioFormat

    companion object {
        fun parse(
            header: String,
            channels: Int,
        ): AudioFormat {
            val parts = header.split('_')
            return when (parts[0]) {
                "pcm" -> {
                    Pcm(
                        parts.getOrNull(1)?.toIntOrNull()
                            ?: throw IllegalArgumentException("Bad PCM sample rate in '$header'"),
                        channels,
                    )
                }

                "mp3" -> {
                    Compressed("audio/mpeg")
                }

                "opus" -> {
                    Compressed("audio/opus")
                }

                else -> {
                    throw IllegalArgumentException("Unsupported audio format '$header'")
                }
            }
        }
    }
}
