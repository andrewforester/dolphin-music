package com.voiceproducer

import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.data.ProxyConfig

data class AppConfig(
    val proxy: ProxyConfig,
    val agent: AgentConfig,
)
