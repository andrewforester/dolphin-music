package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class EndAgentSessionUseCase(
    private val agent: VoiceAgent,
    private val appScope: CoroutineScope,
) {
    operator fun invoke() {
        appScope.launch { agent.stop() }
    }
}
