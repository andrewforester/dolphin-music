package com.voiceproducer.core.domain.agent

data class AgentToken(
    val value: String,
    val conversationId: String?,
)

class AgentRefused(
    reason: String? = null,
) : Exception(reason ?: "the agent ended the session before it began")

interface AgentTokenSource {
    suspend fun token(): AgentToken

    suspend fun refusal(conversationId: String): Exception
}
