@file:OptIn(ExperimentalAtomicApi::class)

package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.runCatchingExceptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

class IosVoiceAgent(
    private val bridge: AgentBridge,
) : VoiceAgent {
    private val _state = MutableStateFlow(AgentState.Disconnected)
    override val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<AgentEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lifecycle = Mutex()

    private val generation = AtomicLong(0)

    private val pendingStart = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val stateLock = NSLock()
    private var status = BridgeStatus.Disconnected
    private var speaking = false

    @Volatile private var micMuted = false

    override suspend fun start(
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String?,
    ) {
        val gen = generation.incrementAndFetch()
        pendingStart.exchange(null)?.complete(Unit)
        lifecycle.withLock {
            if (generation.load() != gen) return
            micMuted = false
            update(gen, BridgeStatus.Connecting, speaking = false)
            val settled = CompletableDeferred<Unit>()
            pendingStart.store(settled)
            if (generation.load() != gen) {
                pendingStart.compareAndSet(settled, null)
                return
            }
            try {
                bridge.start(token, plan, GenerationListener(gen, tools, settled))
                settled.await()
            } catch (e: CancellationException) {
                if (generation.compareAndSet(gen, gen + 1)) {
                    bridge.stop {}
                    update(gen + 1, BridgeStatus.Disconnected, speaking = false)
                }
                throw e
            } finally {
                pendingStart.compareAndSet(settled, null)
            }
        }
    }

    override suspend fun stop() {
        val gen = generation.incrementAndFetch()
        pendingStart.exchange(null)?.complete(Unit)
        try {
            withContext(NonCancellable) {
                lifecycle.withLock {
                    val done = CompletableDeferred<Unit>()
                    bridge.stop { done.complete(Unit) }
                    withTimeoutOrNull(STOP_TIMEOUT_MS) { done.await() }
                }
            }
        } finally {
            update(gen, BridgeStatus.Disconnected, speaking = false)
        }
    }

    override fun sendContext(text: String) {
        if (isConnected()) bridge.sendContext(text)
    }

    override fun sendUserText(text: String) {
        if (isConnected()) bridge.sendUserText(text)
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
        if (isConnected()) bridge.setMicMuted(muted)
    }

    private fun isConnected(): Boolean = _state.value.let { it == AgentState.Listening || it == AgentState.Speaking }

    private fun emit(
        gen: Long,
        event: AgentEvent,
    ) {
        if (generation.load() == gen) _events.tryEmit(event)
    }

    private fun update(
        gen: Long,
        status: BridgeStatus? = null,
        speaking: Boolean? = null,
    ): Boolean {
        stateLock.lock()
        try {
            if (generation.load() != gen) return false
            status?.let { this.status = it }
            speaking?.let { this.speaking = it }
            _state.value = mapAgentState(this.status, this.speaking)
            return true
        } finally {
            stateLock.unlock()
        }
    }

    private inner class GenerationListener(
        private val gen: Long,
        private val tools: ToolHandler,
        private val settled: CompletableDeferred<Unit>,
    ) : AgentBridgeListener {
        override fun onStatus(status: BridgeStatus) {
            if (update(gen, status) && status == BridgeStatus.Connected && micMuted) bridge.setMicMuted(true)
            if (status != BridgeStatus.Connecting) settled.complete(Unit)
        }

        override fun onMode(speaking: Boolean) {
            update(gen, speaking = speaking)
        }

        override fun onVad(score: Float) = emit(gen, AgentEvent.VadScore(score))

        override fun onUserTranscript(text: String) = emit(gen, AgentEvent.UserTranscript(text))

        override fun onAgentText(text: String) = emit(gen, AgentEvent.AgentText(text))

        override fun onAgentTextCorrected(text: String) = emit(gen, AgentEvent.AgentTextCorrected(text))

        override fun onError(message: String) = endStart(AgentEvent.Error(message))

        override fun onRefused() = endStart(AgentEvent.Refused)

        private fun endStart(event: AgentEvent) {
            emit(gen, event)
            stateLock.lock()
            val connecting =
                try {
                    generation.load() == gen && status == BridgeStatus.Connecting
                } finally {
                    stateLock.unlock()
                }
            if (connecting) update(gen, BridgeStatus.Disconnected, speaking = false)
            settled.complete(Unit)
        }

        override fun onToolCall(
            name: String,
            paramsJson: String,
            reply: (Boolean, String) -> Unit,
        ) {
            if (generation.load() != gen) {
                reply(false, "session ended")
                return
            }
            scope.launch {
                var result = ToolResult(false, "cancelled")
                try {
                    result =
                        runCatchingExceptions { tools.handle(name, parseToolParams(paramsJson)) }
                            .getOrElse { e -> ToolResult(false, e.message ?: e::class.simpleName ?: "error") }
                } finally {
                    reply(result.ok, result.message)
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal fun mapAgentState(
    status: BridgeStatus,
    speaking: Boolean,
): AgentState =
    when (status) {
        BridgeStatus.Connecting -> AgentState.Connecting
        BridgeStatus.Connected -> if (speaking) AgentState.Speaking else AgentState.Listening
        BridgeStatus.Disconnected -> AgentState.Disconnected
    }
