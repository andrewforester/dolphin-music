@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.voiceproducer.core.playback

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineManualRenderingModeOffline
import platform.AVFAudio.AVAudioEngineManualRenderingStatusSuccess
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLog

private const val MAX_OFFLINE_FRAMES = 4096

internal class AudioGraph(
    private val offlineFormat: AVAudioFormat?,
) {
    val engine = AVAudioEngine()
    val node = AVAudioPlayerNode()

    var connectedFormat: AVAudioFormat? = null
        private set

    val isCreated: Boolean get() = connectedFormat != null

    fun ensure(format: AVAudioFormat) {
        val current = connectedFormat
        if (current == null) {
            if (offlineFormat != null) {
                engine.enableManualRenderingMode(
                    AVAudioEngineManualRenderingModeOffline,
                    offlineFormat,
                    MAX_OFFLINE_FRAMES.toUInt(),
                    null,
                )
            }
            engine.attachNode(node)
        } else if (current == format) {
            return
        } else {
            engine.disconnectNodeOutput(node)
        }
        engine.connect(node, engine.mainMixerNode, format)
        connectedFormat = format
    }

    fun start(): Boolean =
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            engine.prepare()
            val started = engine.startAndReturnError(error.ptr)
            if (!started) log("engine failed to start: ${error.value?.localizedDescription}")
            started
        }

    fun reconnect(format: AVAudioFormat) {
        engine.stop()
        engine.disconnectNodeOutput(node)
        engine.connect(node, engine.mainMixerNode, format)
    }

    fun reactivateSession() =
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!AVAudioSession.sharedInstance().setActive(true, error.ptr)) {
                log("session reactivation failed: ${error.value?.localizedDescription}")
            }
        }

    fun renderOffline(frames: Int): FloatArray {
        val fmt = checkNotNull(offlineFormat) { "Not an offline engine" }
        val buffer = AVAudioPCMBuffer(fmt, frames.toUInt())
        val status = engine.renderOffline(frames.toUInt(), buffer, null)
        check(status == AVAudioEngineManualRenderingStatusSuccess) { "render status $status" }
        val channels = fmt.channelCount.toInt()
        val data = buffer.floatChannelData!!
        return FloatArray(buffer.frameLength.toInt() * channels) { data[it % channels]!![it / channels] }
    }
}

private fun log(message: String) = NSLog("VP-Audio " + message.replace("%", "%%"))
