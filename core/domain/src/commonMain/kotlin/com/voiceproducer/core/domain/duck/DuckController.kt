package com.voiceproducer.core.domain.duck

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.model.DuckTarget

class DuckController {
    private var state = AgentState.Disconnected
    private var lastSpeechSignalMs: Long? = null

    fun onState(
        state: AgentState,
        nowMs: Long,
    ) {
        if (this.state == AgentState.Speaking && state != AgentState.Speaking) {
            lastSpeechSignalMs = nowMs
        }
        if (state == AgentState.Disconnected) lastSpeechSignalMs = null
        this.state = state
    }

    fun onEvent(
        event: AgentEvent,
        nowMs: Long,
    ) {
        if (state == AgentState.Disconnected) return
        val isSpeech =
            when (event) {
                is AgentEvent.VadScore -> event.score > VAD_SPEECH_THRESHOLD
                is AgentEvent.TentativeTranscript -> true
                is AgentEvent.UserTranscript, is AgentEvent.AgentText, is AgentEvent.Error, AgentEvent.Refused -> false
            }
        if (isSpeech) lastSpeechSignalMs = nowMs
    }

    fun target(nowMs: Long): DuckTarget =
        when {
            state == AgentState.Disconnected -> UNDUCKED
            state == AgentState.Speaking -> SPEECH
            isHolding(nowMs) -> SPEECH
            else -> ACTIVE
        }

    private fun isHolding(nowMs: Long): Boolean {
        val last = lastSpeechSignalMs ?: return false
        return nowMs - last < HOLD_MS
    }

    companion object {
        const val VAD_SPEECH_THRESHOLD = 0.5f
        const val HOLD_MS = 1000L
        const val UNDUCKED_DB = 0f
        const val ACTIVE_DB = -6f
        const val SPEECH_DB = -18f
        const val SLOW_RAMP_MS = 400
        const val FAST_RAMP_MS = 80

        private val UNDUCKED = DuckTarget(UNDUCKED_DB, SLOW_RAMP_MS)
        private val ACTIVE = DuckTarget(ACTIVE_DB, SLOW_RAMP_MS)
        private val SPEECH = DuckTarget(SPEECH_DB, FAST_RAMP_MS)
    }
}
