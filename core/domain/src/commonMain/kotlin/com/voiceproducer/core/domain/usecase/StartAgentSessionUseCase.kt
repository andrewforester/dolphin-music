package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.agent.AgentTokenSource
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.runCatchingExceptions

data class AgentSessionOpening(
    val plan: String,
    val firstMessage: String?,
)

data class StartedAgentSession(
    val conversationId: String?,
)

class StartAgentSessionUseCase(
    private val tokens: AgentTokenSource,
    private val agent: VoiceAgent,
) {
    suspend operator fun invoke(
        tools: ToolHandler,
        opening: () -> AgentSessionOpening,
    ): Result<StartedAgentSession> {
        val token = runCatchingExceptions { tokens.token() }.getOrElse { return Result.failure(it) }
        val (plan, firstMessage) = opening()
        agent.start(token.value, tools, plan, firstMessage)
        return Result.success(StartedAgentSession(token.conversationId))
    }
}
