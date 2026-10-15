package com.voiceproducer.core.domain.playback

import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.flow.StateFlow

interface AudioEngine {
    val positionMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val durationMs: StateFlow<Long>

    val swapsApplied: StateFlow<Int>

    fun load(master: PcmAudio)

    fun unload()

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    fun swapMaster(
        next: PcmAudio,
        diffRangesMs: List<LongRange>,
    )

    fun setDuck(target: DuckTarget)

    fun release()
}
