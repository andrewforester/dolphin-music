package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.dsp.SwappableMaster
import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertEquals
import kotlin.test.fail

class Swap(
    val master: PcmAudio,
    val diffRangesMs: List<LongRange>,
)

class SwapCheckingEngine(
    durationMs: Long,
) : AudioEngine {
    val swaps = mutableListOf<Swap>()
    override val positionMs = MutableStateFlow(0L)
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(true)
    override val durationMs: StateFlow<Long> = MutableStateFlow(durationMs)
    override val swapsApplied: StateFlow<Int> = MutableStateFlow(0)

    override fun load(master: PcmAudio) = Unit

    override fun unload() = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(ms: Long) = Unit

    lateinit var queued: PcmAudio
    var failSwaps = false

    var failSwapsFrom: Int? = null

    override fun swapMaster(
        next: PcmAudio,
        diffRangesMs: List<LongRange>,
    ) {
        check(!failSwaps) { "engine rejected the swap" }
        failSwapsFrom?.let { check(swaps.size < it) { "engine rejected the swap" } }
        val prev = queued
        assertEquals(prev.frames, next.frames)
        val covered = BooleanArray(next.frames.toInt())
        for (r in diffRangesMs) {
            for (f in SwappableMaster.framesCovering(r, next.sampleRate)) {
                if (f < covered.size) covered[f.toInt()] = true
            }
        }
        for (f in covered.indices) {
            if (covered[f]) continue
            for (c in 0 until next.channels) {
                val i = f * next.channels + c
                if (prev.samples[i] != next.samples[i]) fail("Frame $f changed outside diff ranges $diffRangesMs")
            }
        }
        swaps += Swap(next, diffRangesMs)
        queued = next
    }

    override fun setDuck(target: DuckTarget) = Unit

    override fun release() = Unit
}
