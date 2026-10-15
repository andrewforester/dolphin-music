package com.voiceproducer.core.codec.di

import com.voiceproducer.core.codec.AudioDecoder
import com.voiceproducer.core.codec.IosAudioDecoder
import org.koin.core.module.Module
import org.koin.dsl.module

actual val codecModule: Module =
    module {
        single<AudioDecoder> { IosAudioDecoder() }
    }
