package com.voiceproducer.feature.producer.ui.orb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

@Composable
internal actual fun rememberOrbShader(source: String): OrbShader? =
    remember(source) { runCatching { SkiaOrbShader(RuntimeShaderBuilder(RuntimeEffect.makeForShader(source))) }.getOrNull() }

internal actual fun Modifier.excludeFromSystemGestures(): Modifier = this

private class SkiaOrbShader(
    private val builder: RuntimeShaderBuilder,
) : OrbShader {
    override fun uniform(
        name: String,
        vararg values: Float,
    ) {
        when (values.size) {
            1 -> builder.uniform(name, values[0])
            2 -> builder.uniform(name, values[0], values[1])
            else -> builder.uniform(name, values[0], values[1], values[2])
        }
    }

    override fun brush(): Brush = ShaderBrush(builder.makeShader().asComposeShader())
}
