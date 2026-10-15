package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.agent.AgentRefused
import com.voiceproducer.core.domain.agent.AgentTokenSource
import com.voiceproducer.core.domain.runCatchingExceptions

class ExplainAgentRefusalUseCase(
    private val tokens: AgentTokenSource,
) {
    suspend operator fun invoke(conversationId: String?): Exception =
        conversationId
            ?.let { id -> runCatchingExceptions { tokens.refusal(id) }.getOrNull() }
            ?: AgentRefused()
}
