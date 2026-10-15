package com.voiceproducer.core.dsp

import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class SwappableMaster(
    initial: PcmAudio,
) {
    private class PendingSwap(
        val next: PcmAudio,
        val diffFrames: List<LongRange>,
    )

    private class State(
        val master: PcmAudio,
        val queue: List<PendingSwap>,
        val applied: Int,
    )

    private val state = AtomicReference(State(initial, emptyList(), 0))
    private val _swaps = MutableStateFlow(0)

    val current: PcmAudio get() = state.load().master

    val hasPending: Boolean get() = state.load().queue.isNotEmpty()

    val swaps: StateFlow<Int> = _swaps.asStateFlow()

    fun requestSwap(
        next: PcmAudio,
        diffFrames: List<LongRange>,
    ) {
        while (true) {
            val s = state.load()
            val reference = s.queue.lastOrNull()?.next ?: s.master
            require(next.sameFormatAs(reference)) { "Swap must have the same format as the master" }
            require(next.frames == reference.frames) { "Swap must have the same length as the master" }
            if (state.compareAndSet(s, State(s.master, s.queue + PendingSwap(next, diffFrames), s.applied))) return
        }
    }

    fun read(
        fromFrame: Long,
        maxFrames: Int,
        out: ShortArray,
    ): Int {
        require(fromFrame >= 0) { "fromFrame must be non-negative" }
        require(maxFrames >= 0) { "maxFrames must be non-negative" }
        val after = applyWhile { it.isSafeFor(fromFrame, fromFrame + maxFrames) }
        val audio = after.master
        val frames = minOf(maxFrames.toLong(), audio.frames - fromFrame).coerceAtLeast(0).toInt()
        val ch = audio.channels
        audio.samples.copyInto(out, 0, (fromFrame * ch).toInt(), ((fromFrame + frames) * ch).toInt())
        return frames
    }

    fun applyAllQueued() {
        applyWhile { true }
    }

    private fun applyWhile(ready: (PendingSwap) -> Boolean): State {
        var before: State
        var after: State
        do {
            before = state.load()
            val applying = before.queue.takeWhile(ready)
            after =
                if (applying.isEmpty()) {
                    before
                } else {
                    State(applying.last().next, before.queue.drop(applying.size), before.applied + applying.size)
                }
        } while (after !== before && !state.compareAndSet(before, after))
        if (after !== before) _swaps.update { maxOf(it, after.applied) }
        return after
    }

    private fun PendingSwap.isSafeFor(
        from: Long,
        end: Long,
    ): Boolean = diffFrames.none { range -> from in range || (range.first < end && range.last >= from) }

    companion object {
        fun framesCovering(
            rangeMs: LongRange,
            sampleRate: Int,
        ): LongRange {
            val start = rangeMs.first * sampleRate / 1000
            val endExclusive = ((rangeMs.last + 1) * sampleRate + 999) / 1000
            return start until endExclusive
        }
    }
}
