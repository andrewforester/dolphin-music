package com.voiceproducer.feature.producer.orb

import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbMoodTest {
    private fun state(
        activity: AgentActivity,
        agent: AgentState = AgentState.Listening,
        muted: Boolean = false,
        voice: Float = 0f,
    ) = ProducerUiState(
        agent = AgentUi(agent, null, null, micMuted = muted),
        agentActivity = activity,
        userVoiceLevel = voice,
    )

    @Test
    fun offWhenIdle() = assertEquals(OrbMood.Off, orbMood(state(AgentActivity.Idle, agent = AgentState.Disconnected)))

    @Test
    fun offEvenIfMuteFlagIsLeftOver() =
        assertEquals(OrbMood.Off, orbMood(state(AgentActivity.Idle, agent = AgentState.Disconnected, muted = true)))

    @Test
    fun connecting() = assertEquals(OrbMood.Connecting, orbMood(state(AgentActivity.Connecting, agent = AgentState.Connecting)))

    @Test
    fun mutedBeatsEverythingLive() = assertEquals(OrbMood.Muted, orbMood(state(AgentActivity.Speaking, muted = true)))

    @Test
    fun speaking() = assertEquals(OrbMood.Speaking, orbMood(state(AgentActivity.Speaking)))

    @Test
    fun thinking() = assertEquals(OrbMood.Thinking, orbMood(state(AgentActivity.Thinking)))

    @Test
    fun hearingAboveThreshold() = assertEquals(OrbMood.HearingYou, orbMood(state(AgentActivity.Listening, voice = HEARING_LEVEL + 0.01f)))

    @Test
    fun listeningAtOrBelowThreshold() = assertEquals(OrbMood.Listening, orbMood(state(AgentActivity.Listening, voice = HEARING_LEVEL)))

    @Test
    fun stillMoodsDoNotPulse() {
        for (m in listOf(OrbMood.Off, OrbMood.Listening, OrbMood.Thinking, OrbMood.Muted)) {
            for (t in 0L..1200L step 100) assertEquals(1f, orbPulseScale(m, 1f, t), "$m at $t")
        }
    }

    @Test
    fun hearingGrowsWithVoiceUpTo15Percent() {
        assertEquals(1f, orbPulseScale(OrbMood.HearingYou, 0f, 0), 1e-4f)
        assertEquals(1.15f, orbPulseScale(OrbMood.HearingYou, 1f, 0), 1e-4f)
        assertEquals(1.15f, orbPulseScale(OrbMood.HearingYou, 3f, 0), 1e-4f)
    }

    @Test
    fun speakingBreathesSixPercentOn600ms() {
        val samples = (0L until 600L step 10).map { orbPulseScale(OrbMood.Speaking, 0f, it) }
        assertEquals(1.06f, samples.max(), 2e-3f)
        assertEquals(0.94f, samples.min(), 2e-3f)
        assertEquals(orbPulseScale(OrbMood.Speaking, 0f, 123), orbPulseScale(OrbMood.Speaking, 0f, 723), 1e-4f)
    }

    @Test
    fun connectingShimmersWithin2Percent() {
        (0L..1200L step 50).forEach { assertTrue(abs(orbPulseScale(OrbMood.Connecting, 0f, it) - 1f) <= 0.02f) }
    }
}
