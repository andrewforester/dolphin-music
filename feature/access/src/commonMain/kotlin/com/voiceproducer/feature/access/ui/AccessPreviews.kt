package com.voiceproducer.feature.access.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.ProducerTheme
import com.voiceproducer.feature.access.AccessUiState

@Composable
private fun PreviewSheet(state: AccessUiState) {
    ProducerTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Palette.ShaderTeal, 0.45f to Palette.Moss, 1f to Palette.Pine)),
        ) {
            AccessSheet(state, onIntent = {}, onOpenGuide = {})
        }
    }
}

@Preview
@Composable
private fun AccessEmptyPreview() = PreviewSheet(AccessUiState(sheetOpen = true))

@Preview
@Composable
private fun AccessFilledPreview() = PreviewSheet(AccessUiState(sheetOpen = true, code = "river-copper-violin-dawn"))

@Preview
@Composable
private fun AccessCheckingPreview() = PreviewSheet(AccessUiState(sheetOpen = true, code = "river-copper-violin-dawn", checking = true))

@Preview
@Composable
private fun AccessRejectedPreview() =
    PreviewSheet(
        AccessUiState(sheetOpen = true, code = "river-copper-violin", error = "That code didn't work. Check it and try again."),
    )
