package com.voiceproducer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositionPlanJsonTest {
    @Test
    fun roundTripsReferenceChunk() {
        val plan = CompositionPlan(listOf(ReferenceChunk("s1", TimeRange(1000, 4000))))
        val json = PlanJson.encode(plan)
        assertTrue(json.contains("\"song_id\":\"s1\""), json)
        assertTrue(json.contains("\"range\":{\"start_ms\":1000,\"end_ms\":4000}"), json)
        assertEquals(plan, PlanJson.decode(json))
    }

    @Test
    fun referenceChunkDurationIsRangeLength() {
        assertEquals(3000L, ReferenceChunk("s1", TimeRange(1000, 4000)).durationMs)
    }

    @Test
    fun totalMsSumsDurations() {
        assertEquals(44_000L, planOf(8000, 20_000, 16_000).totalMs)
    }

    @Test
    fun roundTripsGenerationChunkWithoutEmittingNulls() {
        val plan = CompositionPlan(listOf(GenerationChunk("[A]", 1000, listOf("x"), contextAdherence = "high")))
        val json = PlanJson.encode(plan)
        assertTrue(!json.contains("null"), json)
        assertEquals(plan, PlanJson.decode(json))
    }

    @Test
    fun decodesMixedChunksByShape() {
        val plan =
            PlanJson.decode(
                """{"chunks":[{"song_id":"s","range":{"start_ms":0,"end_ms":10}},{"text":"t","duration_ms":5}]}""",
            )
        assertTrue(plan.chunks[0] is ReferenceChunk)
        assertTrue(plan.chunks[1] is GenerationChunk)
    }
}

internal fun planOf(vararg durations: Long) =
    CompositionPlan(
        durations.mapIndexed { i, d -> GenerationChunk(text = "[S$i]", durationMs = d) },
    )
