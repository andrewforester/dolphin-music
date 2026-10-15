package com.voiceproducer.feature.producer.ui

import androidx.compose.runtime.Composable
import com.voiceproducer.core.designsystem.ProducerTheme
import com.voiceproducer.feature.producer.ProducerUiState

private const val PREVIEW_PLAYHEAD_MS = 54_000L

internal fun previewPositionMs(state: ProducerUiState): Long = if (state.isPlaying) PREVIEW_PLAYHEAD_MS else 0

@Composable
internal fun PreviewScreen(state: ProducerUiState) {
    ProducerTheme { ProducerScreen(state, onIntent = {}, positionMs = { previewPositionMs(state) }) }
}
