package com.voiceproducer.core.domain.plan

import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PlanJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanSampleDecodeTest {
    private fun sample(): String {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("plan_sample.json"))
        return stream.use { it.readBytes().decodeToString() }
    }

    @Test
    fun decodesSamplePlanFromApi() {
        val plan = PlanJson.decode(sample())
        assertTrue(plan.chunks.isNotEmpty())
        assertTrue(plan.chunks.all { it is GenerationChunk })
        assertEquals(listOf("Intro", "Verse 1", "Chorus", "Bridge", "Final Chorus"), SectionIndex.sections(plan).map { it.name })
        assertEquals(75_000L, plan.totalMs)
    }

    @Test
    fun sampleSurvivesReencodeDecode() {
        val plan = PlanJson.decode(sample())
        assertEquals(plan, PlanJson.decode(PlanJson.encode(plan)))
    }

    @Test
    fun reencodeKeepsEmptyListsAndEveryNonNullField() {
        val original = Json.parseToJsonElement(sample()).jsonObject
        val reencoded = Json.parseToJsonElement(PlanJson.encode(PlanJson.decode(sample()))).jsonObject
        val chunks = reencoded.getValue("chunks").jsonArray
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(JsonArray(emptyList()), chunk.jsonObject["negative_styles"])
            assertTrue(chunk.jsonObject.containsKey("positive_styles"))
        }

        fun JsonElement.withoutNulls(): JsonElement =
            when (this) {
                is JsonObject -> JsonObject(filterValues { it !is JsonNull }.mapValues { it.value.withoutNulls() })
                is JsonArray -> JsonArray(map { it.withoutNulls() })
                else -> this
            }
        assertEquals(original.withoutNulls(), reencoded)
    }
}
