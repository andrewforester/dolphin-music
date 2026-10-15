package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.CompletableDeferred

class FakeLibrary(
    val master: PcmAudio,
    private val composed: () -> TrackVersion,
    private val render: (CompositionPlan) -> PcmAudio,
) : TrackLibrary {
    val versions = mutableMapOf<Long, TrackVersion>()
    val audio = mutableMapOf<Long, PcmAudio>()
    var latestId: Long? = null
    var windowGate: CompletableDeferred<Unit>? = null
    var windowFailure: Exception? = null

    var failWindowsFrom: Int? = null
    private var windowCalls = 0
    var titleFailure: Exception? = null
    val createGate = CompletableDeferred<Unit>()
    var createFailure: Exception? = null
    var saveFailure: Exception? = null
    var saveGate: CompletableDeferred<Unit>? = null
    val created = mutableListOf<Triple<String, Boolean, Long>>()

    override suspend fun createTrack(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): Pair<TrackVersion, PcmAudio> {
        created += Triple(prompt, instrumental, lengthMs)
        createGate.await()
        createFailure?.let { throw it }
        val v = composed()
        put(v)
        return v to master
    }

    var latestGate: CompletableDeferred<Unit>? = null

    override suspend fun latest(): TrackVersion? {
        latestGate?.await()
        return latestId?.let { versions[it] }
    }

    var orphanSweeps = 0
    var orphanSweepFailure: Exception? = null

    override suspend fun deleteOrphanMasters() {
        orphanSweeps++
        orphanSweepFailure?.let { throw it }
    }

    var clearFailure: Exception? = null
    var clearGate: CompletableDeferred<Unit>? = null
    var clearCurrentCalls = 0
    val setCurrentCalls = mutableListOf<Long>()

    override suspend fun clearCurrent() {
        clearCurrentCalls++
        clearGate?.await()
        clearFailure?.let { throw it }
        latestId = null
    }

    override suspend fun title(trackId: Long): String? = titleFailure?.let { throw it } ?: "Track $trackId"

    override suspend fun composeWindow(plan: CompositionPlan): Pair<String, PcmAudio> {
        windowGate?.await()
        windowFailure?.let { throw it }
        check(failWindowsFrom?.let { windowCalls++ >= it } != true) { "boom" }
        return "B" to render(plan)
    }

    var fullFailure: Exception? = null

    override suspend fun composeFull(plan: CompositionPlan): Pair<String, PcmAudio> {
        fullFailure?.let { throw it }
        return "C" to render(plan)
    }

    override suspend fun saveVersion(
        parent: TrackVersion,
        plan: CompositionPlan,
        sourceMap: SourceMap,
        master: PcmAudio,
        editedSection: String?,
        diffRangesMs: List<LongRange>,
    ): TrackVersion {
        saveGate?.await()
        saveFailure?.let { throw it }
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

    var loadFailure: Exception? = null

    override suspend fun loadAudio(v: TrackVersion): PcmAudio = loadFailure?.let { throw it } ?: audio.getValue(v.versionId)

    override suspend fun setCurrent(
        trackId: Long,
        versionId: Long,
    ) {
        setCurrentCalls += versionId
        latestId = versionId
    }
}

fun FakeLibrary.put(
    v: TrackVersion,
    current: Boolean = true,
) {
    versions[v.versionId] = v
    audio[v.versionId] = master
    if (current) latestId = v.versionId
}
