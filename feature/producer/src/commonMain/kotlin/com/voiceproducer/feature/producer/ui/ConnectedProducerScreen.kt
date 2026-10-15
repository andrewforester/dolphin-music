package com.voiceproducer.feature.producer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerViewModel
import com.voiceproducer.feature.producer.ui.background.ShaderBackground
import com.voiceproducer.feature.producer.ui.orb.ShaderOrb
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProducerScreen(
    modifier: Modifier = Modifier,
    viewModel: ProducerViewModel = koinViewModel(),
    onIntent: ((ProducerIntent) -> Unit)? = null,
    formatTime: (Long) -> String = ::utcTime,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val apiLog by viewModel.apiLog.collectAsStateWithLifecycle()
    val position = viewModel.positionMs.collectAsStateWithLifecycle()
    val send = onIntent ?: viewModel::onIntent
    backHandler(state.showApiLog) { send(ProducerIntent.ToggleApiLog) }
    ProducerScreen(
        state,
        onIntent = send,
        modifier = modifier,
        positionMs = { position.value },
        background = { s, p -> ShaderBackground(s, p) },
        apiLog = remember(apiLog) { apiLog.toImmutableList() },
        formatTime = formatTime,
        backHandler = backHandler,
        orb = ShaderOrb,
    )
}
