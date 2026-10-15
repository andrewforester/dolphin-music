package com.voiceproducer.core.playback.di

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.playback.IosAudioEngine
import org.koin.core.module.Module
import org.koin.dsl.module

actual val playbackModule: Module =
    module {
        single<AudioEngine> { IosAudioEngine() }
    }
