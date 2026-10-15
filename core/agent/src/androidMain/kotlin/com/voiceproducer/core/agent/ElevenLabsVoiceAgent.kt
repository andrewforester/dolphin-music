package com.voiceproducer.core.agent

import android.content.Context
import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.runCatchingExceptions
import io.elevenlabs.AgentOverrides
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import io.elevenlabs.Overrides
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import io.elevenlabs.models.DisconnectionDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class ElevenLabsVoiceAgent(
    private val context: Context,
    private val useMediaStream: Boolean = true,
    sdkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appScope: CoroutineScope,
) : VoiceAgent {
    private val generation = AtomicLong(0)
    private val publisher = AgentStatePublisher(generation)
    override val state: StateFlow<AgentState> = publisher.state
    override val events: Flow<AgentEvent> = publisher.events

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val lifecycle = Mutex()

    @Volatile private var session: ConversationSession? = null

    private val earlyContexts = mutableListOf<String>()

    @Volatile private var micMuted = false

    private val audioMode =
        AudioModeRestorer(context, appScope, lifecycle) {
            session != null || publisher.state.value != AgentState.Disconnected
        }
    private val sdk = SdkSessions(context, sdkDispatcher, audioMode)

    override suspend fun start(
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String?,
    ) {
        val gen = generation.incrementAndGet()
        lifecycle.withLock {
            if (generation.get() != gen) return
            endCurrentSession()
            micMuted = false
            synchronized(publisher.lock) { earlyContexts.clear() }
            publisher.update(gen, ConversationStatus.CONNECTING, ConversationMode.LISTENING)
            runCatchingExceptions {
                val started = sdk.startOffMain(buildConfig(gen, token, tools, plan, firstMessage))
                if (generation.get() != gen) {
                    sdk.endQuietly(started)
                    return
                }
                val early =
                    synchronized(publisher.lock) {
                        session = started
                        earlyContexts.toList().also { earlyContexts.clear() }
                    }
                try {
                    early.forEach(started::sendContextualUpdate)
                } catch (_: Exception) {
                }
                if (micMuted) applyMute(started)
            }.onFailure { e ->
                if (generation.get() == gen) {
                    session = null
                    publisher.update(gen, status = ConversationStatus.DISCONNECTED)
                    publisher.emitAlways(AgentEvent.Error(e.message ?: e::class.java.simpleName))
                }
                audioMode.afterSessionEnded()
            }
        }
    }

    private fun buildConfig(
        gen: Long,
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String?,
    ) = ConversationConfig(
        conversationToken = token.takeIf { it.isNotBlank() },
        dynamicVariables = mapOf("plan" to plan),
        overrides = firstMessage?.let { Overrides(agent = AgentOverrides(firstMessage = it)) },
        clientTools = ToolBridge(TOOL_NAMES, tools).clientTools(),
        onModeChange = { m -> publisher.update(gen, mode = m) },
        onStatusChange = { st ->
            publisher.update(gen, status = st)
            if (st == ConversationStatus.DISCONNECTED || st == ConversationStatus.ERROR) audioMode.afterSessionEnded()
        },
        onVadScore = { publisher.emit(gen, AgentEvent.VadScore(it)) },
        onTentativeUserTranscriptEvent = { text, _ -> publisher.emit(gen, AgentEvent.TentativeTranscript(text)) },
        onUserTranscriptEvent = { text, _ -> publisher.emit(gen, AgentEvent.UserTranscript(text)) },
        onAgentResponseEvent = { text, _ -> publisher.emit(gen, AgentEvent.AgentText(text)) },
        onDisconnect = { details ->
            if (details is DisconnectionDetails.Agent) publisher.agentLeft(gen)
            if (generation.get() == gen) session = null
            publisher.update(gen, status = ConversationStatus.DISCONNECTED)
            audioMode.afterSessionEnded()
        },
        onError = { code, message -> publisher.emit(gen, AgentEvent.Error(message ?: "error $code")) },
        useMediaStream = useMediaStream,
    )

    override suspend fun stop() {
        val gen = generation.incrementAndGet()
        try {
            lifecycle.withLock { endCurrentSession() }
        } finally {
            publisher.update(gen, ConversationStatus.DISCONNECTED, ConversationMode.LISTENING)
        }
    }

    fun release() {
        val gen = generation.incrementAndGet()
        val s = session
        session = null
        publisher.update(gen, ConversationStatus.DISCONNECTED, ConversationMode.LISTENING)
        scope.cancel()
        if (s != null) {
            appScope.launch { sdk.endQuietly(s) }
        }
    }

    override fun sendContext(text: String) {
        val s =
            synchronized(publisher.lock) {
                if (state.value != AgentState.Listening && state.value != AgentState.Speaking) return
                session ?: run {
                    earlyContexts += text
                    return
                }
            }
        s.sendContextualUpdate(text)
    }

    override fun sendUserText(text: String) {
        connectedSession()?.sendUserMessage(text)
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
        session?.let(::applyMute)
    }

    private fun applyMute(s: ConversationSession) {
        scope.launch {
            try {
                s.setMicMuted(micMuted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun connectedSession(): ConversationSession? =
        session?.takeIf { state.value == AgentState.Listening || state.value == AgentState.Speaking }

    private suspend fun endCurrentSession() {
        val s = session ?: return
        session = null
        sdk.endQuietly(s)
    }

    companion object {
        val TOOL_NAMES = listOf("compose_track", "edit_section", "set_global", "undo_edit")
    }
}

internal fun mapAgentState(
    status: ConversationStatus,
    mode: ConversationMode,
): AgentState =
    when (status) {
        ConversationStatus.CONNECTING -> {
            AgentState.Connecting
        }

        ConversationStatus.CONNECTED -> {
            if (mode == ConversationMode.SPEAKING) AgentState.Speaking else AgentState.Listening
        }

        ConversationStatus.DISCONNECTED,
        ConversationStatus.DISCONNECTING,
        ConversationStatus.ERROR,
        -> {
            AgentState.Disconnected
        }
    }
