package com.voiceproducer.feature.producer.ui.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import com.voiceproducer.core.designsystem.Palette

@Composable
fun StaticBackground(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().testTag("static-background").drawBehind {
            drawRect(Palette.BgBase)
            drawRect(
                Brush.verticalGradient(0f to Palette.ShaderTeal, 0.45f to Palette.Moss, 0.8f to Palette.ShaderTeal, 1f to Palette.Pine),
                alpha = SHADER_OPACITY,
            )
            drawVignette()
        },
    )
}

private const val VIGNETTE_FADE = 0.45f

internal const val SHADER_OPACITY = 0.6f

internal fun DrawScope.drawVignette() {
    drawRect(Brush.verticalGradient(0f to Palette.Vignette, VIGNETTE_FADE to Color.Transparent, 1f to Palette.Vignette))
}
