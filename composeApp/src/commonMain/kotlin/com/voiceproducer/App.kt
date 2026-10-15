package com.voiceproducer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceproducer.core.designsystem.ProducerTheme
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.access.AccessIntent
import com.voiceproducer.feature.access.AccessViewModel
import com.voiceproducer.feature.access.ui.AccessSheet
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.ProducerViewModel
import com.voiceproducer.feature.producer.ui.ProducerScreen
import com.voiceproducer.feature.producer.ui.utcTime
import com.voiceproducer.ui.SplashHost
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App(
    modifier: Modifier = Modifier,
    onIntent: ((ProducerIntent) -> Unit)? = null,
    formatTime: (Long) -> String = ::utcTime,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val producer: ProducerViewModel = koinViewModel()
    val access: AccessViewModel = koinViewModel()
    val accessState by access.state.collectAsStateWithLifecycle()
    val forward = onIntent ?: producer::onIntent
    backHandler(accessState.sheetOpen) { access.onIntent(AccessIntent.Dismiss) }
    ProducerTheme {
        SplashHost {
            Box(modifier) {
                ProducerScreen(
                    viewModel = producer,
                    onIntent = { intent ->
                        if (needsAccess(intent, producer.state.value)) access.guard { forward(intent) } else forward(intent)
                    },
                    formatTime = formatTime,
                    backHandler = backHandler,
                )
                AccessSheet(state = accessState, onIntent = access::onIntent)
            }
        }
    }
}

fun needsAccess(
    intent: ProducerIntent,
    state: ProducerUiState,
): Boolean = intent == ProducerIntent.ToggleAgent && state.agent.state == AgentState.Disconnected
