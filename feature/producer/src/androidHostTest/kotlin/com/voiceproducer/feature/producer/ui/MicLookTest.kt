package com.voiceproducer.feature.producer.ui

import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.ToolKind
import com.voiceproducer.feature.producer.ToolStatus
import com.voiceproducer.feature.producer.TrackStatus
import com.voiceproducer.feature.producer.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLookTest {
    private fun state(
        activity: AgentActivity,
        muted: Boolean = false,
        voice: Float = 0f,
        agentState: AgentState = if (activity == AgentActivity.Idle) AgentState.Disconnected else AgentState.Listening,
    ) = ProducerUiState(
        agent = AgentUi(agentState, null, null, micMuted = muted),
        agentActivity = activity,
        userVoiceLevel = voice,
    )

    private fun look(
        activity: AgentActivity,
        muted: Boolean = false,
        voice: Float = 0f,
    ) = micLook(state(activity, muted, voice))

    @Test
    fun offWhenDisconnected() {
        val l = look(AgentActivity.Idle)
        assertEquals(MicMode.Off, l.mode)
        assertEquals("Mic off · Tap to talk", l.label)
        assertNull(l.hint)
    }

    @Test
    fun offEvenIfMuteFlagIsLeftOver() {
        assertEquals(MicMode.Off, look(AgentActivity.Idle, muted = true).mode)
    }

    @Test
    fun offLabelDoesNotDependOnTrack() {
        val l = micLook(state(AgentActivity.Idle).copy(track = TrackStatus.Generating))
        assertEquals("Mic off · Tap to talk", l.label)
    }

    @Test
    fun offInvitesToContinueOnceTheTrackHasAConversation() {
        val tool = TranscriptItem.Tool(1, ToolKind.Compose, null, null, ToolStatus.Done)
        assertEquals("Mic off · Tap to continue", micLook(state(AgentActivity.Idle).copy(transcript = listOf(tool))).label)
        assertEquals("Mic off · Tap to talk", micLook(state(AgentActivity.Idle).copy(transcript = emptyList())).label)
    }

    @Test
    fun connecting() {
        val l = look(AgentActivity.Connecting)
        assertEquals(MicMode.Connecting, l.mode)
        assertEquals("Connecting…", l.label)
        assertNull(l.hint)
    }

    @Test
    fun connectingBeatsMuted() {
        assertEquals(MicMode.Connecting, look(AgentActivity.Connecting, muted = true).mode)
    }

    @Test
    fun liveListening() {
        val l = look(AgentActivity.Listening)
        assertEquals(MicMode.Live, l.mode)
        assertEquals("Live · Listening", l.label)
        assertEquals("Tap to end", l.hint)
    }

    @Test
    fun liveHearingYouWhileUserSpeaks() {
        assertEquals("Live · Hearing you", look(AgentActivity.Listening, voice = 0.8f).label)
        assertEquals("Live · Listening", look(AgentActivity.Listening, voice = 0.2f).label)
    }

    @Test
    fun liveComposerSpeaking() {
        val l = look(AgentActivity.Speaking)
        assertEquals(MicMode.Live, l.mode)
        assertEquals("Live · Composer speaking", l.label)
        assertEquals("Tap to end", l.hint)
    }

    @Test
    fun liveThinking() {
        val l = look(AgentActivity.Thinking)
        assertEquals(MicMode.Live, l.mode)
        assertEquals("Live · Thinking", l.label)
    }

    @Test
    fun muted() {
        val l = look(AgentActivity.Listening, muted = true)
        assertEquals(MicMode.Muted, l.mode)
        assertEquals("Muted · Composer can't hear you", l.label)
        assertEquals("Tap to end", l.hint)
    }

    @Test
    fun precedenceMutedBeatsSpeakingBeatsThinkingBeatsListening() {
        assertEquals(MicMode.Muted, look(AgentActivity.Speaking, muted = true, voice = 0.9f).mode)
        assertEquals(MicMode.Muted, look(AgentActivity.Thinking, muted = true).mode)
        assertEquals("Live · Composer speaking", look(AgentActivity.Speaking, voice = 0.9f).label)
        assertEquals("Live · Thinking", look(AgentActivity.Thinking, voice = 0.9f).label)
    }

    @Test
    fun onlyStartingAVoiceSessionAsksForTheMic() {
        val off = state(AgentActivity.Idle)
        val live = state(AgentActivity.Listening)
        assertTrue(asksForMicFirst(ProducerIntent.ToggleAgent, off, hasPermission = false))
        assertFalse(asksForMicFirst(ProducerIntent.ToggleAgent, off, hasPermission = true))
        assertFalse(asksForMicFirst(ProducerIntent.ToggleAgent, live, hasPermission = false))
        assertFalse(asksForMicFirst(ProducerIntent.ToggleAgent, state(AgentActivity.Connecting), hasPermission = false))
        assertFalse(asksForMicFirst(ProducerIntent.StartDemo, off, hasPermission = false))
    }
}
