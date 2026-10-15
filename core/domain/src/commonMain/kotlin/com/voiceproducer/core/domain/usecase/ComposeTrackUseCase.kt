package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.TrackVersion

class ComposeTrackUseCase(
    private val library: TrackLibrary,
    private val engine: AudioEngine,
) {
    suspend operator fun invoke(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): Pair<TrackVersion, PcmAudio> {
        val (version, master) = library.createTrack(prompt, instrumental, lengthMs)
        engine.load(master)
        return version to master
    }
}
