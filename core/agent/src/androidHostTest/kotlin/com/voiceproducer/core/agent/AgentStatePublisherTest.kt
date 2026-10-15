package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentEvent
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class AgentStatePublisherTest {
    private val generation = AtomicLong(1)
    private val publisher = AgentStatePublisher(generation)

    private fun TestScope.collected(): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { publisher.events.collect { events += it } }
        return events
    }

    private fun connected(gen: Long) {
        publisher.update(gen, ConversationStatus.CONNECTING, ConversationMode.LISTENING)
        publisher.update(gen, status = ConversationStatus.CONNECTED)
    }

    @Test
    fun theAgentLeavingBeforeSayingAnythingIsARefusal() =
        runTest {
            val events = collected()
            connected(1)

            publisher.agentLeft(1)

            assertEquals(listOf<AgentEvent>(AgentEvent.Refused), events)
        }

    @Test
    fun theAgentLeavingAfterItSpokeIsAnOrdinaryEnd() =
        runTest {
            val events = collected()
            connected(1)
            publisher.update(1, mode = ConversationMode.SPEAKING)

            publisher.agentLeft(1)

            assertEquals(emptyList<AgentEvent>(), events)
        }

    @Test
    fun theAgentLeavingAfterItsFirstWordsIsAnOrdinaryEnd() =
        runTest {
            val events = collected()
            connected(1)
            publisher.emit(1, AgentEvent.AgentText("Hey"))

            publisher.agentLeft(1)

            assertEquals(listOf<AgentEvent>(AgentEvent.AgentText("Hey")), events)
        }

    @Test
    fun aNewSessionForgetsThatTheAgentSpokeInTheLastOne() =
        runTest {
            connected(1)
            publisher.update(1, mode = ConversationMode.SPEAKING)
            generation.set(2)
            val events = collected()
            connected(2)

            publisher.agentLeft(2)

            assertEquals(listOf<AgentEvent>(AgentEvent.Refused), events)
        }

    @Test
    fun anOutdatedSessionLeavingSaysNothing() =
        runTest {
            val events = collected()
            connected(1)
            generation.set(2)

            publisher.agentLeft(1)

            assertEquals(emptyList<AgentEvent>(), events)
        }
}
