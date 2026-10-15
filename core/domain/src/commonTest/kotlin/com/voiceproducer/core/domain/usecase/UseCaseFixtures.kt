package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion

internal val plan = CompositionPlan(listOf(GenerationChunk(text = "[Verse]\nla la", durationMs = 1_000)))

internal val master = PcmAudio(ShortArray(1_000), 1_000, 1)

internal fun version(id: Long) =
    TrackVersion(
        trackId = 7,
        versionId = id,
        number = id.toInt(),
        parentVersionId = null,
        plan = plan,
        sourceMap = SourceMap.single("A", plan.totalMs),
        audioPath = "v$id.wav",
        editedSection = null,
        diffRangesMs = emptyList(),
    )
