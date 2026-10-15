package com.voiceproducer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.BrandOrb
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.ProducerTheme
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.feature.producer.ui.background.StaticBackground
import kotlinx.coroutines.delay

const val SPLASH_HOLD_MS = 900L
private const val SPLASH_FADE_MS = 400

@Composable
fun SplashHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(SPLASH_HOLD_MS)
        shown = false
    }
    Box(modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(shown, enter = EnterTransition.None, exit = fadeOut(tween(SPLASH_FADE_MS))) {
            Splash()
        }
    }
}

@Composable
fun Splash(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("splash")) {
        StaticBackground()
        Canvas(Modifier.align(Alignment.Center).size(288.dp)) {
            drawCircle(Palette.RingInner, radius = 94.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(Palette.RingIdle, radius = 112.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        BrandOrb(Modifier.align(Alignment.Center).size(288.dp))
        Column(
            Modifier.align(Alignment.Center).offset(y = (-220).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PianoKeys(Modifier.size(90.dp, 115.5.dp))
            Text(
                "Dolphin",
                style = sans(30.sp, 700, Palette.Ink),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun PianoKeys(modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val key = 34.5.dp.toPx()
        val gap = 6.dp.toPx()
        val radius = CornerRadius(7.6.dp.toPx())
        val blackThickness = 21.4.dp.toPx()
        val blackLength = 54.dp.toPx()
        val cut = 2.5.dp.toPx()
        repeat(3) { i ->
            drawRoundRect(Palette.Cream, Offset(0f, i * (key + gap)), Size(size.width, key), radius)
        }
        val blacks =
            (1..2).map { i ->
                val centerY = i * (key + gap) - gap / 2
                Offset(size.width - blackLength, centerY - blackThickness / 2)
            }
        blacks.forEach {
            drawRoundRect(
                Color.Black,
                it - Offset(cut, cut),
                Size(blackLength + 2 * cut, blackThickness + 2 * cut),
                CornerRadius(radius.x * 0.8f + cut),
                blendMode = BlendMode.Clear,
            )
        }
        blacks.forEach {
            drawRoundRect(Palette.Ink, it, Size(blackLength, blackThickness), CornerRadius(radius.x * 0.8f))
        }
    }
}

@Preview(name = "Splash", widthDp = 392, heightDp = 820)
@Composable
private fun SplashPreview() {
    ProducerTheme { Splash() }
}
