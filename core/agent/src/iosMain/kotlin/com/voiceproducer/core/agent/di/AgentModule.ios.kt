package com.voiceproducer.core.agent.di

import com.voiceproducer.core.agent.AgentBridge
import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.agent.IosVoiceAgent
import com.voiceproducer.core.agent.demo.withDemo
import com.voiceproducer.core.common.AppScope
import com.voiceproducer.core.domain.agent.VoiceAgent
import org.koin.core.module.Module
import org.koin.dsl.module

actual val agentModule: Module =
    module {
        single<VoiceAgent> {
            IosVoiceAgent(get<AgentBridge>()).withDemo(get<AgentConfig>().demo, get(AppScope))
        }
    }
