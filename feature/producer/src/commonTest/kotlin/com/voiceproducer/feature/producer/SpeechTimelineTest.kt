package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTimelineTest {
    private val hello = AgentEvent.SpeechChunk(eventId = 7, text = "Hello", startsMs = listOf(0, 80, 160, 240, 320), durationMs = 400)
    private val thisIs =
        AgentEvent.SpeechChunk(
            eventId = 7,
            text = ", this is",
            startsMs = listOf(0, 40, 80, 120, 160, 200, 240, 280, 320),
            durationMs = 400,
        )

    @Test
    fun anEmptyTimelineHasSpokenNothing() {
        assertEquals(0, SpeechTimeline().spokenWords(1_000))
        assertEquals(0, SpeechTimeline().endMs)
    }

    @Test
    fun aWordCountsAsSpokenFromItsFirstCharacter() {
        val timeline = SpeechTimeline() + hello + thisIs
        assertEquals(0, timeline.spokenWords(-1))
        assertEquals(1, timeline.spokenWords(0))
        assertEquals(1, timeline.spokenWords(479))
        assertEquals(2, timeline.spokenWords(480))
        assertEquals(2, timeline.spokenWords(679))
        assertEquals(3, timeline.spokenWords(680))
        assertEquals(3, timeline.spokenWords(10_000))
        assertEquals(800, timeline.endMs)
    }

    @Test
    fun eachChunkStartsWhereThePreviousOneEnded() {
        val one = AgentEvent.SpeechChunk(eventId = 7, text = "one ", startsMs = listOf(0, 100, 200, 300), durationMs = 400)
        val two = AgentEvent.SpeechChunk(eventId = 7, text = "two", startsMs = listOf(0, 100, 200), durationMs = 300)
        val timeline = SpeechTimeline() + one + two
        assertEquals(1, timeline.spokenWords(399))
        assertEquals(2, timeline.spokenWords(400))
        assertEquals(700, timeline.endMs)
    }

    @Test
    fun withATimelineTheRevealFollowsTheVoiceNotTheFixedPace() {
        val timeline = SpeechTimeline() + hello + thisIs
        assertEquals(1, revealedWords(5, 0, timeline))
        assertEquals(1, revealedWords(5, 479, timeline))
        assertEquals(2, revealedWords(5, 480, timeline))
        assertEquals(3, revealedWords(5, 60_000, timeline))
        assertEquals(2, revealedWords(2, 60_000, timeline))
        assertEquals(0, revealedWords(0, 60_000, timeline))
    }

    @Test
    fun withoutATimelineTheRevealKeepsTheSpeakingPace() {
        for (ms in listOf(0L, 384L, 385L, 1_000L, 60_000L)) assertEquals(revealedWords(10, ms), revealedWords(10, ms, null))
    }
}
