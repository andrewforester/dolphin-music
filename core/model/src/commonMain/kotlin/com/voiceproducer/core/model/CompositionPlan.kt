package com.voiceproducer.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class TimeRange(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)

@Serializable(ChunkSerializer::class)
sealed interface Chunk {
    val durationMs: Long
}

@Serializable
data class GenerationChunk(
    val text: String,
    @SerialName("duration_ms") override val durationMs: Long,
    @SerialName("positive_styles") val positiveStyles: List<String> = emptyList(),
    @SerialName("negative_styles") val negativeStyles: List<String> = emptyList(),
    @SerialName("context_adherence") val contextAdherence: String? = null,
    @SerialName("conditioning_ref") val conditioningRef: JsonElement? = null,
    @SerialName("condition_strength") val conditionStrength: JsonElement? = null,
) : Chunk

@Serializable
data class ReferenceChunk(
    @SerialName("song_id") val songId: String,
    val range: TimeRange,
) : Chunk {
    override val durationMs: Long get() = range.endMs - range.startMs
}

@Serializable
data class CompositionPlan(
    val chunks: List<Chunk>,
) {
    val totalMs: Long get() = chunks.sumOf { it.durationMs }
}

object PlanJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    fun encode(p: CompositionPlan): String = json.encodeToString(CompositionPlan.serializer(), p)

    fun decode(s: String): CompositionPlan = json.decodeFromString(CompositionPlan.serializer(), s)
}
