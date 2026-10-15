package com.voiceproducer.core.domain.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class AgentState { Disconnected, Connecting, Listening, Speaking }

sealed interface AgentEvent {
    data class VadScore(
        val score: Float,
    ) : AgentEvent

    data class TentativeTranscript(
        val text: String,
    ) : AgentEvent

    data class UserTranscript(
        val text: String,
    ) : AgentEvent

    data class AgentText(
        val text: String,
    ) : AgentEvent

    data class Error(
        val message: String,
    ) : AgentEvent

    data object Refused : AgentEvent
}

data class ToolResult(
    val ok: Boolean,
    val message: String,
)

fun interface ToolHandler {
    suspend fun handle(
        name: String,
        params: Map<String, Any?>,
    ): ToolResult
}

interface VoiceAgent {
    val state: StateFlow<AgentState>
    val events: Flow<AgentEvent>

    suspend fun start(
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String? = null,
    )

    suspend fun stop()

    fun sendContext(text: String)

    fun sendUserText(text: String)

    fun setMicMuted(muted: Boolean)
}
