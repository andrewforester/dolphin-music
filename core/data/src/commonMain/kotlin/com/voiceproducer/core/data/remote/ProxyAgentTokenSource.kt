package com.voiceproducer.core.data.remote

import com.voiceproducer.core.domain.agent.AgentRefused
import com.voiceproducer.core.domain.agent.AgentToken
import com.voiceproducer.core.domain.agent.AgentTokenSource
import com.voiceproducer.core.model.ProxyError

internal class ProxyAgentTokenSource(
    private val api: ProxyApi,
) : AgentTokenSource {
    override suspend fun token(): AgentToken = api.agentToken()

    override suspend fun refusal(conversationId: String): Exception =
        try {
            AgentRefused(api.agentRefusal(conversationId))
        } catch (e: ProxyError.QuotaExceeded) {
            e
        }
}
