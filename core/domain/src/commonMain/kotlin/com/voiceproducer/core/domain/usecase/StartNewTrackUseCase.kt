package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.domain.repository.TrackLibrary

class StartNewTrackUseCase(
    private val library: TrackLibrary,
    private val engine: AudioEngine,
    private val history: ConversationHistory,
) {
    suspend operator fun invoke(enqueueHistoryWrite: (suspend () -> Unit) -> Unit) {
        library.clearCurrent()
        engine.unload()
        enqueueHistoryWrite { history.clear(null) }
    }
}
