package com.voiceproducer.core.agent.di

import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.agent.ElevenLabsVoiceAgent
import com.voiceproducer.core.agent.demo.withDemo
import com.voiceproducer.core.common.AppScope
import com.voiceproducer.core.domain.agent.VoiceAgent
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val agentModule: Module =
    module {
        single<VoiceAgent> {
            ElevenLabsVoiceAgent(androidContext(), useMediaStream = true, appScope = get(AppScope))
                .withDemo(getOrNull<AgentConfig>()?.demo == true, get(AppScope))
        }
    }
