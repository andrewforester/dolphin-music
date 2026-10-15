package com.voiceproducer.core.playback

import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

internal class SessionObservers(
    engine: AVAudioEngine,
    onConfigurationChange: () -> Unit,
    private val onInterruptionBegan: () -> Unit,
    private val onInterruptionEnded: (shouldResume: Boolean) -> Unit,
) {
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers +=
            center.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, main) { _ ->
                onConfigurationChange()
            }
        observers +=
            center.addObserverForName(
                AVAudioSessionInterruptionNotification,
                AVAudioSession.sharedInstance(),
                main,
            ) { it?.let(::onInterruption) }
    }

    fun remove() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
    }

    private fun onInterruption(notification: NSNotification) {
        val info = notification.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue ?: return
        when (type) {
            AVAudioSessionInterruptionTypeBegan -> {
                onInterruptionBegan()
            }

            AVAudioSessionInterruptionTypeEnded -> {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
                onInterruptionEnded(options and AVAudioSessionInterruptionOptionShouldResume != 0uL)
            }
        }
    }
}
