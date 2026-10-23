package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.orb.HEARING_LEVEL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PENDING_LINE_MS = 4_000L

internal class PendingUserLine(
    private val state: MutableStateFlow<ProducerUiState>,
    private val scope: CoroutineScope,
) {
    private var expiry: Job? = null

    fun onVoice() {
        val s = state.value
        if (s.agent.state != AgentState.Listening || s.agent.micMuted || s.userVoiceLevel <= HEARING_LEVEL) return
        if (s.liveUserText == null) state.update { it.copy(liveUserText = "") }
        expiry?.cancel()
        expiry =
            scope.launch {
                delay(PENDING_LINE_MS)
                state.update { if (it.liveUserText == "") it.copy(liveUserText = null) else it }
            }
    }
}
