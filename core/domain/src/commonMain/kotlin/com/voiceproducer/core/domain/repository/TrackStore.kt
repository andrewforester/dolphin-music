package com.voiceproducer.core.domain.repository

import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion

interface TrackStore {
    suspend fun composeWindow(plan: CompositionPlan): Pair<String, PcmAudio>

    suspend fun composeFull(plan: CompositionPlan): Pair<String, PcmAudio>

    suspend fun saveVersion(
        parent: TrackVersion,
        plan: CompositionPlan,
        sourceMap: SourceMap,
        master: PcmAudio,
        editedSection: String?,
        diffRangesMs: List<LongRange>,
    ): TrackVersion

    suspend fun version(id: Long): TrackVersion?

    suspend fun loadAudio(v: TrackVersion): PcmAudio

    suspend fun setCurrent(
        trackId: Long,
        versionId: Long,
    )
}
