package com.voiceproducer.feature.producer.ui.orb

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

internal interface OrbShader {
    fun uniform(
        name: String,
        vararg values: Float,
    )

    fun brush(): Brush
}

@Composable
internal expect fun rememberOrbShader(source: String): OrbShader?

internal expect fun Modifier.excludeFromSystemGestures(): Modifier
