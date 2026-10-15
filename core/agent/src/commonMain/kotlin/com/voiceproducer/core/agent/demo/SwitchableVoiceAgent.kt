package com.voiceproducer.core.agent.demo

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.DemoSwitch
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class SwitchableVoiceAgent(
    private val real: VoiceAgent,
    private val demo: VoiceAgent,
) : VoiceAgent,
    DemoSwitch {
    private val selected = MutableStateFlow(real)
    private val current get() = selected.value

    override val state: StateFlow<AgentState> =
        object : StateFlow<AgentState> {
            private val changes = selected.flatMapLatest { it.state }.distinctUntilChanged()
            override val value: AgentState get() = current.state.value
            override val replayCache: List<AgentState> get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<AgentState>): Nothing {
                changes.collect(collector)
                error("the selected agent's state never completes")
            }
        }

    override val events: Flow<AgentEvent> = selected.flatMapLatest { it.events }

    override fun useDemo(on: Boolean) {
        if (current.state.value == AgentState.Disconnected) selected.value = if (on) demo else real
    }

    override suspend fun start(
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String?,
    ) = current.start(token, tools, plan, firstMessage)

    override suspend fun stop() = current.stop()

    override fun sendContext(text: String) = current.sendContext(text)

    override fun sendUserText(text: String) = current.sendUserText(text)

    override fun setMicMuted(muted: Boolean) = current.setMicMuted(muted)
}
