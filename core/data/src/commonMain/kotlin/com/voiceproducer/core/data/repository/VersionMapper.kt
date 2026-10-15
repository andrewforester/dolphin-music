package com.voiceproducer.core.data.repository

import com.voiceproducer.core.database.VersionEntity
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PlanJson
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val rangesSerializer = ListSerializer(ListSerializer(Long.serializer()))

internal class VersionMapper(
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun entity(
        trackId: Long,
        number: Int,
        parentVersionId: Long?,
        plan: CompositionPlan,
        sourceMap: SourceMap,
        audioPath: String,
        editedSection: String?,
        diffRangesMs: List<LongRange>,
    ) = VersionEntity(
        trackId = trackId,
        number = number,
        parentVersionId = parentVersionId,
        planJson = PlanJson.encode(plan),
        sourceMapJson = json.encodeToString(SourceMap.serializer(), sourceMap),
        audioPath = audioPath,
        editedSection = editedSection,
        diffRangesJson = encodeRanges(diffRangesMs),
        createdAt = clock(),
    )

    fun toDomain(
        entity: VersionEntity,
        versionId: Long,
    ) = toDomain(entity.copy(id = versionId))

    fun toDomain(entity: VersionEntity) =
        TrackVersion(
            trackId = entity.trackId,
            versionId = entity.id,
            number = entity.number,
            parentVersionId = entity.parentVersionId,
            plan = PlanJson.decode(entity.planJson),
            sourceMap = json.decodeFromString(SourceMap.serializer(), entity.sourceMapJson),
            audioPath = entity.audioPath,
            editedSection = entity.editedSection,
            diffRangesMs = decodeRanges(entity.diffRangesJson),
        )

    private fun encodeRanges(ranges: List<LongRange>): String =
        json.encodeToString(rangesSerializer, ranges.map { listOf(it.first, it.last) })

    private fun decodeRanges(s: String): List<LongRange> = json.decodeFromString(rangesSerializer, s).map { it[0]..it[1] }
}
