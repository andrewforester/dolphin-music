package com.voiceproducer.core.agent.demo

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.sin

const val DEMO_GREETING = "Hi! I'm your composer. Tell me what to change."

private val EXCHANGES =
    listOf(
        "Make the chorus heavier" to "Heavier chorus: I'd push the drums and add a distorted guitar layer.",
        "Drop the vocals in the verse" to "An instrumental verse, then: the melody moves to the synth.",
        "Slow down the intro" to "A slower intro: half-time drums and longer pads.",
    )

private const val CONNECT_MS = 1_200L
private const val GREET_MS = 2_500L
private const val WAIT_MS = 1_500L
private const val TALK_MS = 2_400L
private const val VAD_STEP_MS = 100L
private const val THINK_MS = 1_500L
private const val REPLY_MS = 3_000L
private const val EVENT_BUFFER = 64
private const val VAD_BASE = 0.55
private const val VAD_SWING = 0.35
private const val VAD_PACE = 3.0
private const val TENTATIVE_PARTS = 3

class DemoVoiceAgent(
    private val scope: CoroutineScope,
) : VoiceAgent {
    private val mutableState = MutableStateFlow(AgentState.Disconnected)
    override val state: StateFlow<AgentState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<AgentEvent> = mutableEvents

    private var script: Job? = null

    @Volatile
    private var muted = false

    @Volatile
    private var typed: String? = null

    override suspend fun start(
        token: String,
        tools: ToolHandler,
        plan: String,
        firstMessage: String?,
    ) {
        script?.cancelAndJoin()
        muted = false
        typed = null
        mutableState.value = AgentState.Connecting
        script = scope.launch { run() }
    }

    override suspend fun stop() {
        script?.cancelAndJoin()
        script = null
        mutableState.value = AgentState.Disconnected
    }

    override fun sendContext(text: String) = Unit

    override fun sendUserText(text: String) {
        typed = text
    }

    override fun setMicMuted(muted: Boolean) {
        this.muted = muted
    }

    private suspend fun run() {
        delay(CONNECT_MS)
        reply(DEMO_GREETING, GREET_MS)
        var exchange = 0
        while (true) {
            mutableState.value = AgentState.Listening
            delay(WAIT_MS)
            val (line, answer) = EXCHANGES[exchange % EXCHANGES.size]
            val text = typed
            when {
                text != null -> {
                    typed = null
                    think()
                    reply("Got it: $text", REPLY_MS)
                }

                muted -> {
                    delay(TALK_MS)
                }

                else -> {
                    talk(line)
                    think()
                    reply(answer, REPLY_MS)
                    exchange++
                }
            }
        }
    }

    private suspend fun talk(line: String) {
        val steps = (TALK_MS / VAD_STEP_MS).toInt()
        val half = line.substring(0, line.length / 2)
        for (step in 0 until steps) {
            if (step == steps / TENTATIVE_PARTS) mutableEvents.emit(AgentEvent.TentativeTranscript(half))
            if (step == steps * (TENTATIVE_PARTS - 1) / TENTATIVE_PARTS) mutableEvents.emit(AgentEvent.TentativeTranscript(line))
            mutableEvents.emit(AgentEvent.VadScore((VAD_BASE + VAD_SWING * sin(step / VAD_PACE)).toFloat()))
            delay(VAD_STEP_MS)
        }
        mutableEvents.emit(AgentEvent.UserTranscript(line))
        mutableEvents.emit(AgentEvent.VadScore(0f))
    }

    private suspend fun think() {
        mutableState.value = AgentState.Listening
        delay(THINK_MS)
    }

    private suspend fun reply(
        text: String,
        ms: Long,
    ) {
        mutableState.value = AgentState.Speaking
        mutableEvents.emit(AgentEvent.AgentText(text))
        delay(ms)
    }
}
