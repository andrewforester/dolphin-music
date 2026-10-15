package com.voiceproducer.core.domain.repository

import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.TrackVersion

interface TrackLibrary : TrackStore {
    suspend fun createTrack(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): Pair<TrackVersion, PcmAudio>

    suspend fun latest(): TrackVersion?

    suspend fun clearCurrent()

    suspend fun title(trackId: Long): String?

    suspend fun deleteOrphanMasters()
}
