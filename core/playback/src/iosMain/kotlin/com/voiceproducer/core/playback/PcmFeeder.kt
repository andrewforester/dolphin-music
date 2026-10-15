@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.voiceproducer.core.playback

import com.voiceproducer.core.dsp.PcmPump
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.concurrent.Volatile

private const val PCM16_SCALE = 32768f

internal class PcmFeeder(
    private val graph: AudioGraph,
    private val queueDepth: Int,
) {
    private val feedQueue = dispatch_queue_create("vp.audio.feed", null)

    private var feedPump: PcmPump? = null
    private var feedFormat: AVAudioFormat? = null
    private var feedFrames = 0
    private var generation = 0
    private var scratch = ShortArray(0)
    private val freeBuffers = ArrayDeque<AVAudioPCMBuffer>()

    private var startFrame = 0L

    @Volatile
    private var framesScheduled = 0L

    fun configure(
        pump: PcmPump,
        format: AVAudioFormat,
        frames: Int,
    ) = onFeedQueue {
        feedPump = pump
        feedFormat = format
        feedFrames = frames
        freeBuffers.clear()
    }

    fun clear() =
        onFeedQueue {
            feedPump = null
            freeBuffers.clear()
        }

    fun start() =
        onFeedQueue {
            generation++
            feedPump?.let(::startFeeding)
        }

    fun stop(seekTo: Long?) {
        val graphCreated = graph.isCreated
        onFeedQueue {
            generation++
            if (graphCreated) graph.node.stop()
            if (seekTo != null) feedPump?.seek(seekTo)
        }
    }

    fun seek(
        frame: Long,
        restart: Boolean,
    ): Long {
        var landed = 0L
        val graphCreated = graph.isCreated
        onFeedQueue {
            generation++
            if (graphCreated) graph.node.stop()
            feedPump?.let {
                it.seek(frame)
                landed = it.cursor
                if (restart) startFeeding(it)
            }
        }
        return landed
    }

    fun currentFrame(
        pump: PcmPump?,
        playing: Boolean,
        heldFrame: Long,
    ): Long {
        if (pump == null) return 0
        val played = if (playing && pump.frames > 0) playedFrames(pump) else null
        return if (played == null) heldFrame else (startFrame + played) % pump.frames
    }

    private fun playedFrames(pump: PcmPump): Long? {
        val node = graph.node
        val playerTime = node.lastRenderTime?.let(node::playerTimeForNodeTime)
        return if (playerTime == null || !playerTime.sampleTimeValid || playerTime.sampleRate <= 0.0) {
            null
        } else {
            (playerTime.sampleTime.toDouble() * pump.sampleRate / playerTime.sampleRate)
                .toLong()
                .coerceIn(0, framesScheduled)
        }
    }

    private fun startFeeding(p: PcmPump) {
        startFrame = p.cursor
        framesScheduled = 0
        val g = generation
        repeat(queueDepth) { scheduleNext(g) }
    }

    private fun scheduleNext(g: Int) {
        val p = feedPump ?: return
        val fmt = feedFormat ?: return
        val frames = feedFrames
        val channels = p.channels
        if (scratch.size < frames * channels) scratch = ShortArray(frames * channels)
        p.fill(scratch, frames)
        val buffer = freeBuffers.removeFirstOrNull() ?: AVAudioPCMBuffer(fmt, frames.toUInt())
        buffer.frameLength = frames.toUInt()
        val data = buffer.floatChannelData!!
        for (ch in 0 until channels) {
            val out = data[ch]!!
            var s = ch
            for (i in 0 until frames) {
                out[i] = scratch[s] / PCM16_SCALE
                s += channels
            }
        }
        framesScheduled += frames
        graph.node.scheduleBuffer(buffer) {
            dispatch_async(feedQueue) {
                if (buffer.format == feedFormat) freeBuffers.addLast(buffer)
                if (generation == g) scheduleNext(g)
            }
        }
    }

    private fun onFeedQueue(block: () -> Unit) = dispatch_sync(feedQueue, block)
}
