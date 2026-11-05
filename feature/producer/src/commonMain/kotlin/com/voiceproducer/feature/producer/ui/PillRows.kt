package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun secondLineStart(
    widths: List<Int>,
    gap: Int,
    width: Int,
): Int {
    fun span(
        from: Int,
        to: Int,
    ) = if (from >= to) 0 else (from until to).sumOf { widths[it] } + gap * (to - from - 1)
    var lineStart = 0
    var breakAt = widths.size
    for (i in widths.indices) {
        if (i > lineStart && span(lineStart, i + 1) > width) {
            if (breakAt != widths.size) return (1 until widths.size).minBy { maxOf(span(0, it), span(it, widths.size)) }
            breakAt = i
            lineStart = i
        }
    }
    return breakAt
}

@Composable
internal fun PillRows(
    modifier: Modifier = Modifier,
    gap: Dp = 5.dp,
    contentPadding: Dp = 0.dp,
    minLines: Int = 1,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val padPx = with(LocalDensity.current) { contentPadding.roundToPx() }
        val viewport = constraints.maxWidth - 2 * padPx
        Layout(content, Modifier.horizontalScroll(rememberScrollState())) { measurables, _ ->
            val gapPx = gap.roundToPx()
            val placeables = measurables.map { it.measure(Constraints()) }
            val split = secondLineStart(placeables.map { it.width }, gapPx, viewport)
            val lines = listOf(placeables.subList(0, split), placeables.subList(split, placeables.size)).filter { it.isNotEmpty() }

            fun lineWidth(line: List<Placeable>) = line.sumOf { it.width } + gapPx * (line.size - 1)
            val contentWidth = maxOf(viewport, lines.maxOfOrNull(::lineWidth) ?: 0)
            val scrolls = contentWidth > viewport
            val heights = lines.map { line -> line.maxOf { it.height } }
            val lineHeight = heights.firstOrNull() ?: 0
            val shownLines = maxOf(lines.size, minLines)
            val height = heights.sum() + lineHeight * (shownLines - lines.size) + gapPx * (shownLines - 1).coerceAtLeast(0)
            layout(contentWidth + 2 * padPx, height) {
                var y = 0
                lines.forEachIndexed { i, line ->
                    var x = padPx + if (scrolls) 0 else (contentWidth - lineWidth(line)) / 2
                    line.forEach {
                        it.place(x, y + (heights[i] - it.height) / 2)
                        x += it.width + gapPx
                    }
                    y += heights[i] + gapPx
                }
            }
        }
    }
}
