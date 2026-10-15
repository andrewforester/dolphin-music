package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal class AgentStatePublisher(
    private val generation: AtomicLong,
) {
    private val _state = MutableStateFlow(AgentState.Disconnected)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<AgentEvent> = _events.asSharedFlow()

    val lock = Any()
    private var status = ConversationStatus.DISCONNECTED
    private var mode = ConversationMode.LISTENING
    private var agentSpoke = false

    fun emit(
        gen: Long,
        event: AgentEvent,
    ) {
        synchronized(lock) {
            if (generation.get() != gen) return
            if (event is AgentEvent.AgentText) agentSpoke = true
        }
        _events.tryEmit(event)
    }

    fun agentLeft(gen: Long) {
        val refused = synchronized(lock) { generation.get() == gen && !agentSpoke }
        if (refused) _events.tryEmit(AgentEvent.Refused)
    }

    fun emitAlways(event: AgentEvent) {
        _events.tryEmit(event)
    }

    fun update(
        gen: Long,
        status: ConversationStatus? = null,
        mode: ConversationMode? = null,
    ) {
        synchronized(lock) {
            if (generation.get() != gen) return
            if (status == ConversationStatus.CONNECTING) agentSpoke = false
            if (mode == ConversationMode.SPEAKING) agentSpoke = true
            status?.let { this.status = it }
            mode?.let { this.mode = it }
            _state.value = mapAgentState(this.status, this.mode)
        }
    }
}
