package com.voiceproducer.feature.producer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class DemoRun(
    private val state: MutableStateFlow<ProducerUiState>,
    private val transcript: TranscriptLog,
) {
    private var before: List<TranscriptItem>? = null

    fun begin() {
        before = state.value.transcript
        transcript.storing = false
    }

    fun end() {
        val kept = before ?: return
        before = null
        transcript.storing = true
        state.update { it.copy(transcript = kept, liveUserText = null) }
    }
}
