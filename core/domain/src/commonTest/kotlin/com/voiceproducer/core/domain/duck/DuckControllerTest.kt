package com.voiceproducer.core.domain.duck

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.model.DuckTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class DuckControllerTest {
    private val unity = DuckTarget(0f, 400)
    private val active = DuckTarget(-6f, 400)
    private val speech = DuckTarget(-18f, 80)

    @Test
    fun disconnectedIsUnity() {
        assertEquals(unity, DuckController().target(0))
    }

    @Test
    fun activeSessionDucksSix() {
        for (state in listOf(AgentState.Connecting, AgentState.Listening)) {
            val c = DuckController()
            c.onState(state, 0)
            assertEquals(active, c.target(10), "state $state")
        }
    }

    @Test
    fun agentSpeakingDucksEighteenFast() {
        val c = DuckController()
        c.onState(AgentState.Speaking, 0)
        assertEquals(speech, c.target(5_000))
    }

    @Test
    fun vadAboveThresholdDucks() {
        val c = DuckController()
        c.onState(AgentState.Listening, 0)
        c.onEvent(AgentEvent.VadScore(0.51f), 100)
        assertEquals(speech, c.target(100))
    }

    @Test
    fun vadBelowThresholdDoesNot() {
        val c = DuckController()
        c.onState(AgentState.Listening, 0)
        c.onEvent(AgentEvent.VadScore(0.5f), 100)
        assertEquals(active, c.target(100))
    }

    @Test
    fun tentativeTranscriptDucks() {
        val c = DuckController()
        c.onState(AgentState.Listening, 0)
        c.onEvent(AgentEvent.TentativeTranscript("hel"), 100)
        assertEquals(speech, c.target(100))
    }

    @Test
    fun otherEventsDoNotDuck() {
        val c = DuckController()
        c.onState(AgentState.Listening, 0)
        c.onEvent(AgentEvent.UserTranscript("hi"), 100)
        c.onEvent(AgentEvent.AgentText("hi"), 100)
        c.onEvent(AgentEvent.Error("x"), 100)
        assertEquals(active, c.target(100))
    }

    @Test
    fun holdsForOneSecondAfterSpeech() {
        val c = DuckController()
        c.onState(AgentState.Listening, 0)
        c.onEvent(AgentEvent.VadScore(0.9f), 1_000)
        assertEquals(speech, c.target(1_000 + 999))
        assertEquals(active, c.target(1_000 + 1_001))
    }

    @Test
    fun holdsAfterAgentStopsSpeaking() {
        val c = DuckController()
        c.onState(AgentState.Speaking, 0)
        c.onState(AgentState.Listening, 2_000)
        assertEquals(speech, c.target(2_000 + 999))
        assertEquals(active, c.target(2_000 + 1_001))
    }

    @Test
    fun disconnectResetsDuck() {
        val c = DuckController()
        c.onState(AgentState.Speaking, 0)
        c.onState(AgentState.Disconnected, 100)
        assertEquals(unity, c.target(100))
        c.onState(AgentState.Connecting, 200)
        assertEquals(active, c.target(200))
    }
}
