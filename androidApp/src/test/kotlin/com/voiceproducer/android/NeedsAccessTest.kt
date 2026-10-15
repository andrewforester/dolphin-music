package com.voiceproducer.android

import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.needsAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsAccessTest {
    private fun state(agent: AgentState) = ProducerUiState(agent = AgentUi(agent, null, null, micMuted = false))

    @Test
    fun startingTheComposerNeedsAccess() {
        assertTrue(needsAccess(ProducerIntent.ToggleAgent, state(AgentState.Disconnected)))
    }

    @Test
    fun stoppingARunningSessionDoesNot() {
        assertFalse(needsAccess(ProducerIntent.ToggleAgent, state(AgentState.Listening)))
    }

    @Test
    fun otherIntentsDoNot() {
        assertFalse(needsAccess(ProducerIntent.TogglePlay, state(AgentState.Disconnected)))
        assertFalse(needsAccess(ProducerIntent.StartDemo, state(AgentState.Disconnected)))
    }
}
