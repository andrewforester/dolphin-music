package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechChunksTest {
    private fun audioEvent(
        eventId: Any? = 56.0,
        chars: List<String> = listOf("S", "o", "f", "t"),
        starts: List<Any> = listOf(0.0, 80.0, 160.0, 240.0),
        durations: List<Any> = listOf(80.0, 80.0, 80.0, 160.0),
    ): Map<String, Any> =
        buildMap {
            eventId?.let { put("event_id", it) }
            put(
                "alignment",
                mapOf("chars" to chars, "char_start_times_ms" to starts, "char_durations_ms" to durations),
            )
        }

    @Test
    fun alignmentBecomesAChunkThatLastsUntilItsLastCharacterEnds() {
        assertEquals(
            AgentEvent.SpeechChunk(eventId = 56, text = "Soft", startsMs = listOf(0, 80, 160, 240), durationMs = 400),
            speechChunk(audioEvent()),
        )
    }

    @Test
    fun integerValuesAreReadToo() {
        assertEquals(
            AgentEvent.SpeechChunk(eventId = 3, text = "Hi", startsMs = listOf(0, 40), durationMs = 120),
            speechChunk(audioEvent(eventId = 3, chars = listOf("H", "i"), starts = listOf(0, 40), durations = listOf(40, 80))),
        )
    }

    @Test
    fun eventsWithoutTextOrIdAreDropped() {
        assertNull(speechChunk(audioEvent(chars = emptyList(), starts = emptyList(), durations = emptyList())))
        assertNull(speechChunk(audioEvent(eventId = null)))
        assertNull(speechChunk(mapOf("event_id" to 1.0)))
        assertNull(speechChunk(audioEvent(starts = listOf(0.0))))
    }
}
