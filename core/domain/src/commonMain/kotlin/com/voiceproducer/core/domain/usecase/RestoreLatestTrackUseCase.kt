package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.TrackVersion

class RestoreLatestTrackUseCase(
    private val library: TrackLibrary,
    private val engine: AudioEngine,
) {
    suspend operator fun invoke(
        onLatest: suspend (TrackVersion?) -> Unit,
        shouldLoad: suspend (TrackVersion) -> Boolean,
    ): Pair<TrackVersion, PcmAudio>? {
        val version = library.latest()
        onLatest(version)
        version ?: return null
        val master = library.loadAudio(version)
        return if (shouldLoad(version)) {
            engine.load(master)
            version to master
        } else {
            null
        }
    }
}
