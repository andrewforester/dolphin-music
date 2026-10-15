package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentState
import io.elevenlabs.models.ConversationMode.LISTENING
import io.elevenlabs.models.ConversationMode.SPEAKING
import io.elevenlabs.models.ConversationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStateMappingTest {
    @Test fun connecting() = assertEquals(AgentState.Connecting, mapAgentState(ConversationStatus.CONNECTING, SPEAKING))

    @Test fun connectedListening() = assertEquals(AgentState.Listening, mapAgentState(ConversationStatus.CONNECTED, LISTENING))

    @Test fun connectedSpeaking() = assertEquals(AgentState.Speaking, mapAgentState(ConversationStatus.CONNECTED, SPEAKING))

    @Test fun speakingWhileDisconnectedIsDisconnected() =
        assertEquals(AgentState.Disconnected, mapAgentState(ConversationStatus.DISCONNECTED, SPEAKING))

    @Test fun disconnectingAndErrorAreDisconnected() {
        assertEquals(AgentState.Disconnected, mapAgentState(ConversationStatus.DISCONNECTING, LISTENING))
        assertEquals(AgentState.Disconnected, mapAgentState(ConversationStatus.ERROR, SPEAKING))
    }
}
