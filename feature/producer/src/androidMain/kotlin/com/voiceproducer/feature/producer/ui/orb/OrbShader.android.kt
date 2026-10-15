package com.voiceproducer.feature.producer.ui.orb

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

@Composable
internal actual fun rememberOrbShader(source: String): OrbShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember(source) { AgslOrbShader(RuntimeShader(source)) } else null

internal actual fun Modifier.excludeFromSystemGestures(): Modifier = systemGestureExclusion()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslOrbShader(
    private val shader: RuntimeShader,
) : OrbShader {
    private val brush = ShaderBrush(shader)

    override fun uniform(
        name: String,
        vararg values: Float,
    ) = shader.setFloatUniform(name, values)

    override fun brush(): Brush = brush
}
