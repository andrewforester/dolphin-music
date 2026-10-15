package com.voiceproducer.feature.producer.di

import com.voiceproducer.core.domain.agent.DemoSwitch
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.feature.producer.ProducerViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

val producerModule: Module =
    module {
        viewModel {
            ProducerViewModel(
                composeTrackUseCase = get(),
                startNewTrack = get(),
                restoreLatestTrack = get(),
                startAgentSession = get(),
                explainAgentRefusal = get(),
                endAgentSession = get(),
                pipelines = get(),
                library = get(),
                engine = get(),
                agent = get(),
                history = get(),
                apiLog = get(),
                clock = ::nowMs,
                demo = get<VoiceAgent>() as? DemoSwitch,
            )
        }
    }

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
