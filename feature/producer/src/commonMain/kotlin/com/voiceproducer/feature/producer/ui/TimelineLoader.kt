package com.voiceproducer.feature.producer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.rememberAnimationMillis
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SECTION_LOADER_EXPECTED_MS
import com.voiceproducer.feature.producer.TrackStatus
import com.voiceproducer.feature.producer.loaderProgress
import kotlin.math.PI
import kotlin.math.sin

internal const val LOADER_COMPLETE_MS = 250

private const val WAVE_PERIOD_MS = 1_400f

internal fun completedProgress(
    from: Float,
    completion: Float,
): Float = from + (1f - from) * completion.coerceIn(0f, 1f)

internal fun showsLoader(state: ProducerUiState): Boolean = state.track == TrackStatus.Generating && state.sections.isEmpty()

@Composable
internal fun TimelineLoader(
    finishing: Boolean,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = rememberAnimationMillis(running = !finishing)
    val completion = remember { Animatable(0f) }
    val drawn = remember { floatArrayOf(0f) }
    val onDone by rememberUpdatedState(onFinish)
    LaunchedEffect(finishing) {
        if (finishing) {
            completion.animateTo(1f, tween(LOADER_COMPLETE_MS, easing = LinearEasing))
            onDone()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .drawWithCache {
                val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(12.dp.toPx()))) }
                onDrawBehind {
                    val progress =
                        completedProgress(drawn[0], completion.value).takeIf { finishing }
                            ?: loaderProgress(clock.longValue).also { drawn[0] = it }
                    drawLoaderFill(progress, clock.longValue, outline)
                    drawLoaderOutline()
                }
            }.testTag("timeline-loader"),
        contentAlignment = Alignment.Center,
    ) {
        Text("composing…", style = mono(11.sp, color = Palette.Muted))
    }
}

private fun DrawScope.drawLoaderFill(
    progress: Float,
    timeMs: Long,
    outline: Path,
) {
    val front = size.width * progress
    if (front <= 0f) return
    val amplitude = 4.dp.toPx()
    val phase = (timeMs / WAVE_PERIOD_MS) * 2 * PI.toFloat()
    val path =
        Path().apply {
            moveTo(0f, 0f)
            val steps = 24
            for (i in 0..steps) {
                val y = size.height * i / steps
                lineTo(front + amplitude * sin(phase + y / size.height * 2 * PI.toFloat()), y)
            }
            lineTo(0f, size.height)
            close()
        }
    clipPath(outline) { drawPath(path, Palette.Teal.copy(alpha = 0.32f)) }
}

internal fun DrawScope.drawSectionLoaderFill(
    color: Color,
    elapsedMs: Long,
) {
    drawRect(color.copy(alpha = 0.3f))
    val front = size.height * (1 - loaderProgress(elapsedMs, SECTION_LOADER_EXPECTED_MS))
    val amplitude = 3.dp.toPx()
    val phase = (elapsedMs / WAVE_PERIOD_MS) * 2 * PI.toFloat()
    val path =
        Path().apply {
            moveTo(0f, size.height)
            val steps = 16
            for (i in 0..steps) {
                val x = size.width * i / steps
                lineTo(x, front + amplitude * sin(phase + x / size.width * 2 * PI.toFloat()))
            }
            lineTo(size.width, size.height)
            close()
        }
    drawPath(path, color)
}

private fun DrawScope.drawLoaderOutline() {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        Palette.Placeholder,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
