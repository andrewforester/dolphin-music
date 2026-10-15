package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentRefused
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.DemoSwitch
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.reason
import com.voiceproducer.core.domain.usecase.AgentSessionOpening
import com.voiceproducer.core.domain.usecase.EndAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.ExplainAgentRefusalUseCase
import com.voiceproducer.core.domain.usecase.StartAgentSessionUseCase
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class AgentSession(
    private val agent: VoiceAgent,
    private val tools: ToolHandler,
    private val startSession: StartAgentSessionUseCase,
    private val explainRefusal: ExplainAgentRefusalUseCase,
    private val endSession: EndAgentSessionUseCase,
    private val state: MutableStateFlow<ProducerUiState>,
    private val scope: CoroutineScope,
    private val playhead: PlayheadNotices,
    private val transcript: TranscriptLog,
    private val duck: DuckPresenter,
    private val demo: DemoSwitch?,
    private val currentVersion: () -> TrackVersion?,
) {
    private var agentJob: Job? = null

    private var awaitingReply = false

    private var connectPending = false

    private var connectGeneration = 0

    private var sessionEarlier: String? = null

    private val demoRun = DemoRun(state, transcript)

    private var conversationId: String? = null

    fun toggle() {
        state.update { it.copy(agent = it.agent.copy(error = null)) }
        val connecting = agentJob?.takeIf { it.isActive }
        if (connecting != null || agent.state.value != AgentState.Disconnected) {
            connecting?.cancel()
            setConnectPending(false)
            if (agent.state.value != AgentState.Disconnected) endSession()
            return
        }
        demoRun.end()
        demo?.useDemo(false)
        launchConnect { connect() }
    }

    fun startDemo() {
        val demo = demo ?: return
        if (agentJob?.isActive == true || agent.state.value != AgentState.Disconnected) return
        state.update { it.copy(agent = it.agent.copy(error = null)) }
        demo.useDemo(true)
        demoRun.begin()
        launchConnect { agent.start(DEMO_TOKEN, tools, AgentNotices.plan(currentVersion()?.plan ?: CompositionPlan(emptyList())), null) }
    }

    private fun launchConnect(block: suspend () -> Unit) {
        setConnectPending(true)
        val generation = ++connectGeneration
        agentJob =
            scope.launch {
                try {
                    block()
                } finally {
                    if (generation == connectGeneration) setConnectPending(false)
                }
            }
    }

    private fun setConnectPending(pending: Boolean) {
        connectPending = pending
        state.update { it.copy(agentActivity = activity(agent.state.value, awaitingReply, connectPending)) }
    }

    private suspend fun connect() {
        conversationId = null
        startSession(tools) {
            val earlier = state.value.transcript
            sessionEarlier = earlierConversation(earlier)
            AgentSessionOpening(AgentNotices.plan(currentVersion()?.plan ?: CompositionPlan(emptyList())), firstMessageOverride(earlier))
        }.onSuccess { started ->
            conversationId = started.conversationId
        }.onFailure { e ->
            state.update { it.copy(agent = it.agent.copy(error = connectError(e))) }
        }
    }

    private fun onRefused() {
        val unexplained = connectError(AgentRefused())
        state.update { it.copy(agent = it.agent.copy(error = unexplained)) }
        val id = conversationId
        scope.launch {
            val explained = connectError(explainRefusal(id))
            state.update { if (it.agent.error == unexplained) it.copy(agent = it.agent.copy(error = explained)) else it }
        }
    }

    fun toggleMic() {
        if (agent.state.value.let { it == AgentState.Listening || it == AgentState.Speaking }) {
            val muted = !state.value.agent.micMuted
            agent.setMicMuted(muted)
            state.update { it.copy(agent = it.agent.copy(micMuted = muted), userVoiceLevel = 0f) }
        }
    }

    fun onState(s: AgentState) {
        duck.onState(s)
        if (s == AgentState.Listening || s == AgentState.Speaking) {
            sessionEarlier?.let {
                sessionEarlier = null
                agent.sendContext(it)
            }
            playhead.onConnected()
        }
        if (s == AgentState.Disconnected) {
            playhead.onDisconnected()
            demoRun.end()
        }
        if (s == AgentState.Speaking || s == AgentState.Disconnected) awaitingReply = false
        state.update {
            it.copy(
                agent = it.agent.copy(state = s, micMuted = it.agent.micMuted && s != AgentState.Disconnected),
                agentActivity = activity(s, awaitingReply, connectPending),
                userVoiceLevel = if (s == AgentState.Disconnected) 0f else it.userVoiceLevel,
                liveUserText = if (s == AgentState.Disconnected || s == AgentState.Speaking) null else it.liveUserText,
            )
        }
        duck.onStateChanged(s)
    }

    fun onEvent(event: AgentEvent) {
        duck.onEvent(event)
        when (event) {
            is AgentEvent.UserTranscript -> {
                onUserTranscript(event)
            }

            is AgentEvent.AgentText -> {
                onAgentText(event)
            }

            is AgentEvent.Error -> {
                state.update { it.copy(agent = it.agent.copy(error = event.message)) }
            }

            AgentEvent.Refused -> {
                onRefused()
            }

            is AgentEvent.VadScore -> {
                duck.onVadScore(event.score)
            }

            is AgentEvent.TentativeTranscript -> {
                state.update { it.copy(liveUserText = event.text) }
            }
        }
    }

    private fun onUserTranscript(event: AgentEvent.UserTranscript) {
        val s = agent.state.value
        if (s != AgentState.Disconnected && s != AgentState.Speaking) awaitingReply = true
        state.update {
            it.copy(
                agent = it.agent.copy(lastUserText = event.text),
                agentActivity = activity(it.agent.state, awaitingReply, connectPending),
                liveUserText = null,
                transcript = it.transcript.append(TranscriptItem.User(++transcript.lastItemId, event.text)),
            )
        }
        transcript.store(state.value.transcript.last())
    }

    private fun onAgentText(event: AgentEvent.AgentText) {
        awaitingReply = false
        state.update {
            it.copy(
                agent = it.agent.copy(lastAgentText = event.text),
                liveUserText = null,
                agentActivity = activity(it.agent.state, awaitingReply, connectPending),
                transcript = it.transcript.append(TranscriptItem.Agent(++transcript.lastItemId, event.text)),
            )
        }
        transcript.store(state.value.transcript.last())
    }
}

private fun connectError(e: Throwable): String = "Could not connect to the agent: ${e.reason()}"

private fun activity(
    state: AgentState,
    awaitingReply: Boolean,
    connectPending: Boolean,
): AgentActivity =
    when (state) {
        AgentState.Disconnected -> if (connectPending) AgentActivity.Connecting else AgentActivity.Idle
        AgentState.Connecting -> AgentActivity.Connecting
        AgentState.Listening -> if (awaitingReply) AgentActivity.Thinking else AgentActivity.Listening
        AgentState.Speaking -> AgentActivity.Speaking
    }

private const val DEMO_TOKEN = "demo"
