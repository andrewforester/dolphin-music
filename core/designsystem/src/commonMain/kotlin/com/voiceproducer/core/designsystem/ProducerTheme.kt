package com.voiceproducer.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors =
    darkColorScheme(
        background = Palette.BgBase,
        surface = Palette.Glass,
        primary = Palette.Cream,
        onPrimary = Palette.Ink,
        secondary = Palette.Amber,
        onSurface = Palette.TextOnGlass,
        onBackground = Palette.TextOnGlass,
        error = Palette.Error,
    )

@Composable
fun ProducerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
