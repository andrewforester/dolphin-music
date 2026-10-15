package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.delay

class FakeStore(
    initial: TrackVersion,
    initialMaster: PcmAudio,
    render: (CompositionPlan) -> PcmAudio,
) : TrackStore {
    val log = mutableListOf<String>()
    val windowPlans = mutableListOf<CompositionPlan>()
    val fullPlans = mutableListOf<CompositionPlan>()
    val current = mutableListOf<Pair<Long, Long>>()
    private val versions = mutableMapOf(initial.versionId to initial)
    private val audio = mutableMapOf(initial.versionId to initialMaster)
    private val songIds = ArrayDeque(listOf("B", "C", "D", "E"))
    var windowAudio: (CompositionPlan) -> PcmAudio = render
    var fullAudio: (CompositionPlan) -> PcmAudio = render
    var generationDelayMs = 0L
    var saveFailure = false
    var loadDelayMs = 0L

    fun put(
        v: TrackVersion,
        master: PcmAudio,
    ) {
        versions[v.versionId] = v
        audio[v.versionId] = master
    }

    override suspend fun composeWindow(plan: CompositionPlan): Pair<String, PcmAudio> {
        log += "window"
        windowPlans += plan
        delay(generationDelayMs)
        return songIds.removeFirst() to windowAudio(plan)
    }

    override suspend fun composeFull(plan: CompositionPlan): Pair<String, PcmAudio> {
        log += "full"
        fullPlans += plan
        delay(generationDelayMs)
        return songIds.removeFirst() to fullAudio(plan)
    }

    override suspend fun saveVersion(
        parent: TrackVersion,
        plan: CompositionPlan,
        sourceMap: SourceMap,
        master: PcmAudio,
        editedSection: String?,
        diffRangesMs: List<LongRange>,
    ): TrackVersion {
        log += "save"
        check(!saveFailure) { "disk full" }
        val id = versions.keys.max() + 1
        val v =
            TrackVersion(
                parent.trackId,
                id,
                id.toInt(),
                parent.versionId,
                plan,
                sourceMap,
                "v$id.wav",
                editedSection,
                diffRangesMs,
            )
        versions[id] = v
        audio[id] = master
        return v
    }

    override suspend fun version(id: Long): TrackVersion? = versions[id]

    override suspend fun loadAudio(v: TrackVersion): PcmAudio {
        delay(loadDelayMs)
        return audio.getValue(v.versionId)
    }

    override suspend fun setCurrent(
        trackId: Long,
        versionId: Long,
    ) {
        current += trackId to versionId
    }
}
