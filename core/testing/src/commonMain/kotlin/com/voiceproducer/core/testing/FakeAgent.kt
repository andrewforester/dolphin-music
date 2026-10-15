package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAgent : VoiceAgent {
    override val state = MutableStateFlow(AgentState.Disconnected)
    override val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)

    val contexts = mutableListOf<String>()
    val playheads = mutableListOf<String>()
    val userTexts = mutableListOf<String>()
    val started = mutableListOf<Pair<String, String>>()
    val firstMessages = mutableListOf<String?>()
    var tools: ToolHandler? = null
    var startGate: CompletableDeferred<Unit>? = null
    var startFailure: Exception? = null
    var stops = 0
    var micMuted: Boolean? = null

    override suspend fun start(
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String?,
    ) {
        started += token to plan
        firstMessages += firstMessage
        this.tools = tools
        startFailure?.let { throw it }
        state.value = AgentState.Connecting
        startGate?.await()
    }

    override suspend fun stop() {
        stops++
        state.value = AgentState.Disconnected
    }

    override fun sendContext(text: String) {
        if (text.startsWith("[system] Producer is at")) playheads += text else contexts += text
    }

    override fun sendUserText(text: String) {
        userTexts += text
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }
}
