package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.formatMs
import com.voiceproducer.core.designsystem.glass
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.rememberAnimationMillis
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SectionKind
import com.voiceproducer.feature.producer.SectionStatus
import com.voiceproducer.feature.producer.SectionUi
import com.voiceproducer.feature.producer.TrackStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

private const val HELD_POSITION_TOLERANCE_MS = 250L
private const val HELD_POSITION_TIMEOUT_MS = 600L

private val CardShape = RoundedCornerShape(24.dp)
private val BlockShape = RoundedCornerShape(12.dp)

private class GlowLayer(
    val extra: Dp,
    val alpha: Float,
)

private val GlowLayers =
    listOf(
        GlowLayer(extra = 12.dp, alpha = 0.12f),
        GlowLayer(extra = 6.dp, alpha = 0.22f),
        GlowLayer(extra = 3.dp, alpha = 0.35f),
    )
private const val GLOW_INSET_DIVISOR = 4

internal fun currentSectionIndex(
    sections: List<SectionUi>,
    positionMs: Long,
): Int? = sections.lastOrNull { positionMs >= it.startMs }?.index ?: sections.firstOrNull()?.index

@Composable
internal fun TrackCard(
    state: ProducerUiState,
    positionMs: () -> Long,
    onIntent: (ProducerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = positionMs()
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var heldMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = scrubMs ?: heldMs ?: position
    LaunchedEffect(heldMs, position) {
        val held = heldMs
        if (held != null && abs(position - held) <= HELD_POSITION_TOLERANCE_MS) heldMs = null
    }
    LaunchedEffect(heldMs) {
        if (heldMs != null) {
            delay(HELD_POSITION_TIMEOUT_MS)
            heldMs = null
        }
    }
    val current = currentSectionIndex(state.sections, shownMs)
    val currentName = state.sections.firstOrNull { it.index == current }?.name
    Column(
        modifier
            .fillMaxWidth()
            .glass(
                CardShape,
                Palette.GlassBorderStrong,
            ).padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TrackHeader(state)
        TrackTimeline(
            state = state,
            current = current,
            position = position,
            scrubMs = { scrubMs ?: heldMs },
            onScrub = {
                scrubMs = it
                if (it != null) heldMs = null
            },
            description = "Timeline, ${currentName ?: "no section"}, ${formatMs(shownMs)} of ${formatMs(state.durationMs)}",
            onSeek = {
                heldMs = it
                onIntent(ProducerIntent.SeekToMs(it))
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownPosition = if (currentName != null) "${formatMs(shownMs)} · $currentName" else "0:00"
            Text(shownPosition, style = mono(10.5.sp, color = Palette.Muted), modifier = Modifier.testTag("position"))
            Text(formatMs(state.durationMs), style = mono(10.5.sp, color = Palette.Muted))
        }
        (state.track as? TrackStatus.Error)?.let {
            Text(
                it.message,
                style = mono(11.sp, color = Palette.Error),
                modifier = Modifier.padding(horizontal = 2.dp).testTag("track-error"),
            )
        }
    }
}

@Composable
private fun TrackHeader(state: ProducerUiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.title ?: "Untitled",
            style = sans(22.sp, 800).copy(letterSpacing = (-0.02).em),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("track-title"),
        )
        Text(
            "vol ${(state.musicLevel * 100).toInt()}%",
            style = mono(10.5.sp, color = if (state.ducked) Palette.Amber else Palette.Muted),
            modifier = Modifier.padding(top = 9.dp).testTag("music-level"),
        )
    }
}

@Composable
private fun TrackTimeline(
    state: ProducerUiState,
    current: Int?,
    position: Long,
    scrubMs: () -> Long?,
    onScrub: (Long?) -> Unit,
    description: String,
    onSeek: (Long) -> Unit,
) {
    var loaderSeen by remember { mutableStateOf(false) }
    val loading = showsLoader(state)
    val finishing = !loading && loaderSeen && state.track == TrackStatus.Ready && state.sections.isNotEmpty()
    LaunchedEffect(loading, state.track) {
        if (loading) {
            loaderSeen = true
        } else if (state.track != TrackStatus.Ready) {
            loaderSeen = false
        }
    }
    if (loading || finishing) {
        TimelineLoader(finishing = finishing, onFinish = { loaderSeen = false })
    } else if (state.sections.isEmpty()) {
        TimelinePlaceholder()
    } else {
        Timeline(
            sections = remember(state.sections) { state.sections.toImmutableList() },
            current = current,
            positionMs = position,
            scrubMs = scrubMs,
            onScrub = onScrub,
            durationMs = state.durationMs,
            composing = state.track == TrackStatus.Generating,
            description = description,
            onSeek = onSeek,
        )
    }
}

@Composable
private fun TimelinePlaceholder() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    Palette.Placeholder,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }.testTag("timeline-placeholder"),
        contentAlignment = Alignment.Center,
    ) {
        Text("sections appear here", style = mono(11.sp, color = Palette.Muted))
    }
}

@Composable
private fun Timeline(
    sections: ImmutableList<SectionUi>,
    current: Int?,
    positionMs: Long,
    scrubMs: () -> Long?,
    onScrub: (Long?) -> Unit,
    durationMs: Long,
    composing: Boolean,
    description: String,
    onSeek: (Long) -> Unit,
) {
    val looks = sections.map { blockLook(it, composing) }
    val clock = rememberAnimationMillis(running = looks.any { it.pulses })
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentDurationMs by rememberUpdatedState(durationMs)
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(Unit) {
                fun positionAt(x: Float): Long =
                    if (size.width > 0) (x / size.width).coerceIn(0f, 1f).times(currentDurationMs).toLong() else 0L
                awaitEachGesture {
                    scrubGesture(::positionAt, { currentOnScrub(it) }, { currentOnSeek(it) })
                }
            }.drawWithContent {
                drawContent()
                val ms = scrubMs() ?: positionMs
                val fraction = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                drawPlayhead(size.width * fraction)
            }.semantics { contentDescription = description }
            .testTag("timeline"),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        sections.forEachIndexed { i, s ->
            SectionBlock(s, looks[i], isCurrent = s.index == current, clockMs = { clock.longValue })
        }
    }
}

private suspend fun AwaitPointerEventScope.scrubGesture(
    positionAt: (Float) -> Long,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val down = awaitFirstDown()
    var last = positionAt(down.position.x)
    var seek = false
    try {
        onScrub(last)
        var change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
        while (change != null && !change.isConsumed) {
            last = positionAt(change.position.x)
            if (!change.pressed) {
                seek = true
                break
            }
            onScrub(last)
            if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) change.consume()
            change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
        }
    } finally {
        onScrub(null)
    }
    if (seek) onSeek(last)
}

@Composable
private fun RowScope.SectionBlock(
    section: SectionUi,
    look: BlockLook,
    isCurrent: Boolean,
    clockMs: () -> Long,
) {
    val refill = if (section.status == SectionStatus.Regenerating) rememberAnimationMillis(running = true) else null
    Column(
        Modifier
            .weight((section.endMs - section.startMs).coerceAtLeast(1).toFloat())
            .fillMaxHeight()
            .graphicsLayer {
                scaleY = if (isCurrent) 1f else 0.84f
                alpha = if (look.pulses) 0.45f + 0.45f * abs(sin(clockMs() / 260f)) else 1f
            }.clip(BlockShape)
            .then(
                if (refill != null) {
                    Modifier.drawBehind { drawSectionLoaderFill(look.background, refill.longValue) }
                } else {
                    Modifier.background(look.background)
                },
            ).then(if (look.failed) Modifier.border(1.5.dp, Palette.Error, BlockShape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 8.dp)
            .testTag("section-${section.index}"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(section.name, style = sans(11.5.sp, 700, look.content), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (look.failed) {
            Text(
                look.subLabel,
                style = mono(9.5.sp, 600, Palette.Error),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Palette.ToolBg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        } else {
            Text(look.subLabel, style = mono(9.5.sp, color = look.content), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun DrawScope.drawPlayhead(x: Float) {
    val overhang = 6.dp.toPx()
    val width = 3.dp.toPx()
    for (layer in GlowLayers) {
        val extra = layer.extra.toPx()
        drawRoundRect(
            Palette.PlayheadGlow.copy(alpha = layer.alpha),
            topLeft = Offset(x - (width + extra) / 2, -overhang - extra / GLOW_INSET_DIVISOR),
            size = Size(width + extra, size.height + 2 * overhang + extra / 2),
            cornerRadius = CornerRadius((width + extra) / 2),
        )
    }
    drawRoundRect(
        Palette.Playhead,
        topLeft = Offset(x - width / 2, -overhang),
        size = Size(width, size.height + 2 * overhang),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
}
