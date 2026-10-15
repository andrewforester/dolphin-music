package com.voiceproducer.feature.producer.orb

import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.ProducerUiState
import kotlin.math.PI
import kotlin.math.sin

enum class OrbMood { Off, Connecting, Listening, HearingYou, Speaking, Thinking, Muted }

const val HEARING_LEVEL = 0.5f

private const val HEARING_GROWTH = 0.15f
private const val SPEAKING_DEPTH = 0.06f
private const val SPEAKING_PERIOD_MS = 600f
private const val CONNECTING_DEPTH = 0.02f
private const val CONNECTING_PERIOD_MS = 900f

fun orbMood(state: ProducerUiState): OrbMood =
    when {
        state.agentActivity == AgentActivity.Idle -> OrbMood.Off
        state.agentActivity == AgentActivity.Connecting -> OrbMood.Connecting
        state.agent.micMuted -> OrbMood.Muted
        state.agentActivity == AgentActivity.Speaking -> OrbMood.Speaking
        state.agentActivity == AgentActivity.Thinking -> OrbMood.Thinking
        state.userVoiceLevel > HEARING_LEVEL -> OrbMood.HearingYou
        else -> OrbMood.Listening
    }

fun orbPulseScale(
    mood: OrbMood,
    voiceLevel: Float,
    timeMs: Long,
): Float =
    when (mood) {
        OrbMood.HearingYou -> 1f + HEARING_GROWTH * voiceLevel.coerceIn(0f, 1f)
        OrbMood.Speaking -> 1f + SPEAKING_DEPTH * wave(timeMs, SPEAKING_PERIOD_MS)
        OrbMood.Connecting -> 1f + CONNECTING_DEPTH * wave(timeMs, CONNECTING_PERIOD_MS)
        else -> 1f
    }

private fun wave(
    timeMs: Long,
    periodMs: Float,
): Float = sin((timeMs % periodMs.toLong()) / periodMs * 2 * PI).toFloat()
