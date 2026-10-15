package com.voiceproducer.core.domain.edit

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.TrackStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class EditPipelineFactory(
    private val store: TrackStore,
    private val engine: AudioEngine,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun create(scope: CoroutineScope): EditPipeline = EditPipeline(store, engine, scope = scope, cpuDispatcher = cpuDispatcher)
}
