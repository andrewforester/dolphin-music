package com.voiceproducer.core.designsystem

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Modifier.glass(
    shape: Shape,
    border: Color = Palette.GlassBorder,
): Modifier = background(Palette.Glass, shape).border(1.dp, border, shape)

val PillShape = RoundedCornerShape(percent = 50)

@Composable
fun sans(
    size: TextUnit,
    weight: Int = 400,
    color: Color = Palette.TextOnGlass,
    lineHeight: Float? = null,
) = TextStyle(
    fontFamily = hankenGrotesk(),
    fontSize = size,
    fontWeight = FontWeight(weight),
    color = color,
    lineHeight = lineHeight?.let { (size.value * it).sp } ?: TextUnit.Unspecified,
)

@Composable
fun mono(
    size: TextUnit,
    weight: Int = 400,
    color: Color = Palette.TextOnGlass,
) = TextStyle(
    fontFamily = jetBrainsMono(),
    fontSize = size,
    fontWeight = FontWeight(weight),
    color = color,
)

private const val SECONDS_PER_MINUTE = 60L

fun formatMs(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "${s / SECONDS_PER_MINUTE}:${(s % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

@Composable
fun rememberAnimationMillis(running: Boolean): LongState {
    val millis = remember { mutableLongStateOf(0L) }
    if (running) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameMillis { it }
            while (true) withInfiniteAnimationFrameMillis { millis.longValue = it - start }
        }
    }
    return millis
}
