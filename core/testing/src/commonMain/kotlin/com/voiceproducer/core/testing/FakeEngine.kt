package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.flow.MutableStateFlow

class FakeEngine : AudioEngine {
    val calls = mutableListOf<String>()
    val ducks = mutableListOf<DuckTarget>()
    var loaded: PcmAudio? = null
    override val positionMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    override val durationMs = MutableStateFlow(0L)
    override val swapsApplied = MutableStateFlow(0)

    val unloadCalls: Int get() = calls.count { it == "unload" }

    override fun load(master: PcmAudio) {
        calls += "load"
        loaded = master
        swapsApplied.value = 0
        durationMs.value = master.framesToMs(master.frames)
    }

    override fun unload() {
        calls += "unload"
        loaded = null
        isPlaying.value = false
        positionMs.value = 0
        durationMs.value = 0
        swapsApplied.value = 0
    }

    override fun play() {
        calls += "play"
        isPlaying.value = true
    }

    override fun pause() {
        calls += "pause"
        isPlaying.value = false
    }

    override fun seekTo(ms: Long) {
        calls += "seekTo($ms)"
    }

    override fun swapMaster(
        next: PcmAudio,
        diffRangesMs: List<LongRange>,
    ) {
        calls += "swap"
    }

    override fun setDuck(target: DuckTarget) {
        ducks += target
    }

    override fun release() = Unit
}
