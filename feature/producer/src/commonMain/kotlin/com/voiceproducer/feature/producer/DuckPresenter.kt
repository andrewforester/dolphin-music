package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.duck.DuckController
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.model.DuckTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DUCK_TICK_MS = 50L

private const val VOICE_STALE_MS = 400L

internal class DuckPresenter(
    private val engine: AudioEngine,
    private val state: MutableStateFlow<ProducerUiState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val duck = DuckController()
    private var duckJob: Job? = null
    private var appliedDuck: DuckTarget? = null

    private var lastVadMs = 0L

    fun onState(s: AgentState) {
        duck.onState(s, clock())
    }

    fun onEvent(event: AgentEvent) {
        duck.onEvent(event, clock())
    }

    fun onStateChanged(s: AgentState) {
        if (s != AgentState.Disconnected || appliedDuck != null) startTicker()
    }

    fun onVadScore(score: Float) {
        val s = state.value
        if (s.agent.micMuted || s.agent.state == AgentState.Disconnected) return
        lastVadMs = clock()
        val level = smoothVoiceLevel(s.userVoiceLevel, score)
        if (level != s.userVoiceLevel) state.update { it.copy(userVoiceLevel = level) }
    }

    private fun startTicker() {
        if (duckJob?.isActive == true) return
        duckJob =
            scope.launch {
                while (true) {
                    val target = duck.target(clock())
                    if (target != appliedDuck) {
                        engine.setDuck(target)
                        appliedDuck = target
                        state.update { it.copy(musicLevel = musicLevel(target.db), ducked = target.db < DUCKED_BELOW_DB) }
                    }
                    if (state.value.userVoiceLevel > 0f && clock() - lastVadMs > VOICE_STALE_MS) {
                        state.update { it.copy(userVoiceLevel = 0f) }
                    }
                    if (state.value.agent.state == AgentState.Disconnected) break
                    delay(DUCK_TICK_MS)
                }
            }
    }
}
