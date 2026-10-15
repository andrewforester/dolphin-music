package com.voiceproducer.feature.access.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceproducer.feature.access.AccessViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AccessSheet(
    modifier: Modifier = Modifier,
    viewModel: AccessViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccessSheet(state, onIntent = viewModel::onIntent, modifier = modifier)
}
