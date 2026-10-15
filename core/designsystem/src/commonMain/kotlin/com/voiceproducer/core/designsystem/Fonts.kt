package com.voiceproducer.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.voiceproducer.core.designsystem.generated.resources.Res
import com.voiceproducer.core.designsystem.generated.resources.hanken_grotesk
import com.voiceproducer.core.designsystem.generated.resources.jetbrains_mono
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

@Composable
private fun variableFontFamily(
    resource: FontResource,
    vararg weights: Int,
): FontFamily {
    val fonts =
        weights.map { weight ->
            Font(resource, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
        }
    return remember(fonts) { FontFamily(fonts) }
}

@Composable
internal fun hankenGrotesk(): FontFamily = variableFontFamily(Res.font.hanken_grotesk, 400, 500, 600, 700, 800)

@Composable
internal fun jetBrainsMono(): FontFamily = variableFontFamily(Res.font.jetbrains_mono, 400, 500)
