package com.voiceproducer.core.agent.demo

import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.CoroutineScope

fun VoiceAgent.withDemo(
    demo: Boolean,
    scope: CoroutineScope,
): VoiceAgent = if (demo) SwitchableVoiceAgent(this, DemoVoiceAgent(scope)) else this
