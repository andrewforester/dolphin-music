package com.voiceproducer.core.data.repository

import com.voiceproducer.core.codec.AudioDecoder
import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.database.TrackDao
import com.voiceproducer.core.database.VersionEntity
import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion

internal class TrackVersions(
    private val api: ProxyApi,
    private val dao: TrackDao,
    private val decoder: AudioDecoder,
    private val store: AudioStore,
    clock: () -> Long,
) : TrackStore {
    internal val masters = MasterFiles(store)
    internal val mapper = VersionMapper(clock)

    override suspend fun composeWindow(plan: CompositionPlan): Pair<String, PcmAudio> = composeFull(plan)

    override suspend fun composeFull(plan: CompositionPlan): Pair<String, PcmAudio> {
        val result = api.compose(plan)
        return result.songId to decoder.decode(result.bytes, result.format)
    }

    override suspend fun saveVersion(
        parent: TrackVersion,
        plan: CompositionPlan,
        sourceMap: SourceMap,
        master: PcmAudio,
        editedSection: String?,
        diffRangesMs: List<LongRange>,
    ): TrackVersion {
        val track = checkNotNull(dao.track(parent.trackId)) { "unknown track ${parent.trackId}" }
        require(master.sampleRate == track.sampleRate && master.channels == track.channels) {
            "master format ${master.sampleRate} Hz/${master.channels} ch differs from track " +
                "${track.sampleRate} Hz/${track.channels} ch"
        }
        val name = masters.write(masters.normalizeLength(master, plan.totalMs))
        var built: VersionEntity? = null
        val versionId =
            masters.deletingOnFailure(name) {
                masters.committing {
                    dao.appendVersion(parent.trackId) { number ->
                        mapper
                            .entity(
                                parent.trackId,
                                number,
                                parent.versionId,
                                plan,
                                sourceMap,
                                name,
                                editedSection,
                                diffRangesMs,
                            ).also { built = it }
                    }
                }
            }
        return mapper.toDomain(built!!, versionId)
    }

    override suspend fun version(id: Long): TrackVersion? = dao.version(id)?.let(mapper::toDomain)

    override suspend fun loadAudio(v: TrackVersion): PcmAudio = store.read(v.audioPath)

    override suspend fun setCurrent(
        trackId: Long,
        versionId: Long,
    ) {
        check(dao.setCurrentVersion(trackId, versionId) == 1) {
            "version $versionId does not belong to track $trackId"
        }
    }
}
