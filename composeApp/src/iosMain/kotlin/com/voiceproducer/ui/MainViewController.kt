package com.voiceproducer.ui

import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.voiceproducer.App
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerViewModel
import com.voiceproducer.feature.producer.ui.asksForMicFirst
import org.koin.compose.viewmodel.koinViewModel
import platform.AVFAudio.AVAudioSession
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.authorizationStatusForMediaType
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

fun mainViewController(): UIViewController =
    ComposeUIViewController {
        val viewModel: ProducerViewModel = koinViewModel()
        LifecycleResumeEffect(Unit) {
            viewModel.onIntent(ProducerIntent.MicPermission(hasMicPermission()))
            onPauseOrDispose {}
        }
        App(
            onIntent = { intent ->
                if (asksForMicFirst(
                        intent,
                        viewModel.state.value,
                        hasPermission = false,
                    )
                ) {
                    toggleAgentWithMic(viewModel)
                } else {
                    viewModel.onIntent(intent)
                }
            },
        )
    }

private fun hasMicPermission(): Boolean =
    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio) == AVAuthorizationStatusAuthorized

@Suppress("DEPRECATION")
private fun toggleAgentWithMic(viewModel: ProducerViewModel) {
    AVAudioSession.sharedInstance().requestRecordPermission { granted ->
        dispatch_async(dispatch_get_main_queue()) {
            viewModel.onIntent(ProducerIntent.MicPermission(granted))
            if (granted) viewModel.onIntent(ProducerIntent.ToggleAgent)
        }
    }
}
