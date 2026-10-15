package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.agent.AgentToken
import com.voiceproducer.core.domain.agent.AgentTokenSource

class FakeAgentTokens(
    var token: suspend () -> AgentToken = { AgentToken("tok-1", "conv_1") },
) : AgentTokenSource {
    val refusalLookups = mutableListOf<String>()
    var refusal: suspend (String) -> Exception = { error("no refusal for $it") }

    override suspend fun token(): AgentToken = token.invoke()

    override suspend fun refusal(conversationId: String): Exception {
        refusalLookups += conversationId
        return refusal.invoke(conversationId)
    }
}
