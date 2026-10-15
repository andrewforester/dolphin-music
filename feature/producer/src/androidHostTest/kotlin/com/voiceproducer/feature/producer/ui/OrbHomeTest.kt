package com.voiceproducer.feature.producer.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbHomeTest : ProducerScreenTestBase() {
    private fun off(demo: Boolean = false) =
        withAgent(AgentUi(AgentState.Disconnected, null, null, micMuted = false))
            .copy(agentActivity = AgentActivity.Idle, demoAvailable = demo)

    @Test
    fun orbTapStartsTheSession() {
        val intents = mutableListOf<ProducerIntent>()
        show(off(), intents)
        rule.onNodeWithTag("mic-button").assertContentDescriptionEquals("Talk to composer").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
    }

    @Test
    fun liveOrbSaysStop() {
        show(withAgent(AgentUi(AgentState.Listening, null, null, micMuted = false)))
        rule.onNodeWithTag("mic-button").assertContentDescriptionEquals("Stop session")
    }

    @Test
    fun orbSitsInItsHomeSlot() {
        show(state())
        val orb = rule.onNodeWithTag("mic-button").getUnclippedBoundsInRoot()
        val slot = rule.onNodeWithTag("orb-home").getUnclippedBoundsInRoot()
        assertEquals(72f, (orb.right - orb.left).value, 0.5f)
        assertEquals(96f, (slot.right - slot.left).value, 0.5f)
        assertEquals((slot.left + slot.right).value / 2, (orb.left + orb.right).value / 2, 0.5f)
        assertEquals((slot.top + slot.bottom).value / 2, (orb.top + orb.bottom).value / 2, 0.5f)
    }

    @Test
    fun homeStaysPutAcrossStates() {
        val current = mutableStateOf(off())
        rule.setContent { ProducerScreen(current.value, positionMs = { 0L }, onIntent = {}) }
        val reference = rule.onNodeWithTag("orb-home").getUnclippedBoundsInRoot()
        val states: List<ProducerUiState> =
            listOf(
                off().copy(agentActivity = AgentActivity.Connecting),
                withAgent(AgentUi(AgentState.Listening, null, null, micMuted = false)),
                withAgent(AgentUi(AgentState.Listening, null, null, micMuted = true)),
                withAgent(AgentUi(AgentState.Speaking, null, null, micMuted = false)).copy(agentActivity = AgentActivity.Speaking),
                off(demo = true),
            )
        for (s in states) {
            current.value = s
            rule.waitForIdle()
            assertEquals(s.agentActivity.toString(), reference, rule.onNodeWithTag("orb-home").getUnclippedBoundsInRoot())
        }
    }

    @Test
    fun demoPillOnlyWhenAvailableAndOff() {
        val current = mutableStateOf(off(demo = true))
        rule.setContent { ProducerScreen(current.value, positionMs = { 0L }, onIntent = {}) }
        rule.onNodeWithTag("demo-button").assertExists()
        current.value = off(demo = false)
        rule.waitForIdle()
        rule.onNodeWithTag("demo-button").assertDoesNotExist()
        current.value = withAgent(AgentUi(AgentState.Listening, null, null, micMuted = false)).copy(demoAvailable = true)
        rule.waitForIdle()
        rule.onNodeWithTag("demo-button").assertDoesNotExist()
        current.value = off(demo = true).copy(agentActivity = AgentActivity.Connecting)
        rule.waitForIdle()
        rule.onNodeWithTag("demo-button").assertDoesNotExist()
    }

    @Test
    fun demoPillSendsStartDemo() {
        val intents = mutableListOf<ProducerIntent>()
        show(off(demo = true), intents)
        rule.onNodeWithTag("demo-button").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.StartDemo), intents)
    }
}
