package com.voiceproducer.core.data.repository

import com.voiceproducer.core.codec.AudioDecoder
import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.database.TrackDao
import com.voiceproducer.core.database.TrackEntity
import com.voiceproducer.core.database.VersionEntity
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TrackVersion

private const val TITLE_MAX = 80

internal fun titleFor(
    prompt: String,
    maxLength: Int = TITLE_MAX,
): String {
    val text = prompt.trim()
    if (text.length <= maxLength) return text
    val head = text.take(maxLength)
    val boundary = if (text[maxLength] == ' ') maxLength else head.lastIndexOf(' ')
    val cut = if (boundary > 0) head.take(boundary) else head
    return cut.trimEnd(' ', ',', ';', ':', '.', '-') + "…"
}

internal class TrackRepository internal constructor(
    private val api: ProxyApi,
    private val dao: TrackDao,
    private val store: AudioStore,
    private val clock: () -> Long,
    private val versions: TrackVersions,
) : TrackLibrary,
    TrackStore by versions {
    constructor(
        api: ProxyApi,
        dao: TrackDao,
        decoder: AudioDecoder,
        store: AudioStore,
        clock: () -> Long,
    ) : this(api, dao, store, clock, TrackVersions(api, dao, decoder, store, clock))

    private val masters = versions.masters
    private val mapper = versions.mapper

    override suspend fun createTrack(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): Pair<TrackVersion, PcmAudio> {
        val fullPrompt = if (instrumental) "$prompt, instrumental, no vocals" else prompt
        val plan = api.createPlan(fullPrompt, lengthMs)
        val (songId, decoded) = composeFull(plan)
        val audio = masters.normalizeLength(decoded, plan.totalMs)
        val track =
            TrackEntity(
                createdAt = clock(),
                prompt = prompt,
                title = titleFor(prompt),
                sampleRate = audio.sampleRate,
                channels = audio.channels,
                currentVersionId = null,
            )
        val name = masters.write(audio)
        var built: VersionEntity? = null
        val versionId =
            masters.deletingOnFailure(name) {
                masters.committing {
                    dao.insertTrackWithVersion(track) { trackId ->
                        val sourceMap = SourceMap.single(songId, plan.totalMs)
                        mapper.entity(trackId, 1, null, plan, sourceMap, name, null, emptyList()).also { built = it }
                    }
                }
            }
        return mapper.toDomain(built!!, versionId) to audio
    }

    override suspend fun latest(): TrackVersion? {
        val versionId = dao.latestTrack()?.currentVersionId ?: return null
        return version(versionId)
    }

    override suspend fun clearCurrent() = dao.clearCurrentVersions()

    override suspend fun title(trackId: Long): String? = dao.track(trackId)?.title

    override suspend fun deleteOrphanMasters() {
        val referenced = dao.audioPaths().toSet()
        val cutoff = clock() - ORPHAN_MIN_AGE_MS
        store
            .list()
            .filter { (name, modified) ->
                name.endsWith(".wav") && name !in referenced && modified != null && modified < cutoff
            }.forEach { (name, _) -> store.delete(name) }
    }

    private companion object {
        const val ORPHAN_MIN_AGE_MS = 10 * 60 * 1000L
    }
}
