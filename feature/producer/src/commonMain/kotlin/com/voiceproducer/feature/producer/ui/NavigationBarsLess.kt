package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun navigationBarsLess(less: Dp): WindowInsets {
    val bars = WindowInsets.navigationBars
    return remember(bars, less) {
        object : WindowInsets {
            override fun getLeft(
                density: Density,
                layoutDirection: LayoutDirection,
            ) = bars.getLeft(density, layoutDirection)

            override fun getTop(density: Density) = bars.getTop(density)

            override fun getRight(
                density: Density,
                layoutDirection: LayoutDirection,
            ) = bars.getRight(density, layoutDirection)

            override fun getBottom(density: Density) = (bars.getBottom(density) - with(density) { less.roundToPx() }).coerceAtLeast(0)
        }
    }
}
