package com.voiceproducer.feature.producer.ui.background

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.feature.producer.ProducerUiState
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.max

@Composable
actual fun ShaderBackground(
    state: ProducerUiState,
    positionMs: () -> Long,
    modifier: Modifier,
) {
    val builder = remember { runCatching { RuntimeShaderBuilder(RuntimeEffect.makeForShader(FOREST_SHADER)) }.getOrNull() }
    if (builder == null) StaticBackground(modifier) else SkiaForestBackground(state, positionMs, builder, modifier)
}

@Composable
private fun SkiaForestBackground(
    state: ProducerUiState,
    positionMs: () -> Long,
    builder: RuntimeShaderBuilder,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(state)
    val position by rememberUpdatedState(positionMs)
    val driver = remember { ShaderDriver() }
    val frame = remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    driver.step((now - last) / 1e9f, latest, position())
                    last = now
                    frame.longValue = now
                }
            }
        }
    }
    val sink =
        remember(builder) {
            object : UniformSink {
                override fun float(
                    name: String,
                    value: Float,
                ) = builder.uniform(name, value)

                override fun float2(
                    name: String,
                    x: Float,
                    y: Float,
                ) = builder.uniform(name, x, y)
            }
        }
    val downscale = max(1f, LocalDensity.current.density / 1.5f)
    Box(modifier.fillMaxSize().drawBehind { drawRect(Palette.BgBase) }) {
        Spacer(
            Modifier
                .layout { measurable, constraints ->
                    val w = max(1, (constraints.maxWidth / downscale).toInt())
                    val h = max(1, (constraints.maxHeight / downscale).toInt())
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }.graphicsLayer {
                    scaleX = downscale
                    scaleY = downscale
                    transformOrigin = TransformOrigin(0f, 0f)
                    alpha = SHADER_OPACITY
                    compositingStrategy = CompositingStrategy.Offscreen
                }.drawBehind {
                    frame.longValue
                    driver.writeUniforms(size.width, size.height, sink)
                    drawRect(ShaderBrush(builder.makeShader().asComposeShader()))
                },
        )
        Spacer(Modifier.fillMaxSize().drawBehind { drawVignette() })
    }
}
