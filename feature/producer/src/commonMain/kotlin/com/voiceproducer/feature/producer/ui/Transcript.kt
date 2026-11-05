package com.voiceproducer.feature.producer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.glass
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.feature.producer.AgentSpeech
import com.voiceproducer.feature.producer.SpeechTimeline
import com.voiceproducer.feature.producer.ToolStatus
import com.voiceproducer.feature.producer.TranscriptItem
import com.voiceproducer.feature.producer.revealedText
import com.voiceproducer.feature.producer.revealedWords
import com.voiceproducer.feature.producer.toolLineText
import com.voiceproducer.feature.producer.toolStatusMark
import com.voiceproducer.feature.producer.wordCount
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.PI
import kotlin.math.sin

private val CardShape = RoundedCornerShape(22.dp)
private val BubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 5.dp, bottomStart = 18.dp)

private const val TO_END_PX = 100_000f

@Composable
internal fun TranscriptCard(
    items: ImmutableList<TranscriptItem>,
    modifier: Modifier = Modifier,
    liveUserText: String? = null,
    agentConnected: Boolean = false,
    agentSpeaking: Boolean = false,
    speech: AgentSpeech? = null,
    bottomInset: Dp = 0.dp,
) {
    val list = rememberLazyListState()
    val newestAgentId = items.lastOrNull { it is TranscriptItem.Agent }?.id
    val timed = speech != null && speech.itemId == newestAgentId
    val revealingId = rememberRevealingId(newestAgentId, agentConnected, agentSpeaking, timed)
    val count = items.size + (if (liveUserText != null) 1 else 0)
    FollowNewest(list, items.lastOrNull(), liveUserText, count)
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().glass(CardShape).testTag("transcript"),
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (count == 0) {
            item(key = "empty") { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { EmptyState() } }
        }
        items(items, key = { it.id }) { item ->
            when (item) {
                is TranscriptItem.User -> {
                    UserLine(item.text)
                }

                is TranscriptItem.Agent -> {
                    AgentLine(
                        item.text,
                        revealing = item.id == revealingId,
                        speaking = agentSpeaking,
                        timeline = speech?.takeIf { it.itemId == item.id }?.timeline,
                    )
                }

                is TranscriptItem.Tool -> {
                    ToolLine(item)
                }

                is TranscriptItem.Notice -> {
                    NoticeLine(item.text)
                }
            }
        }
        if (liveUserText != null) {
            item(key = "live") { if (liveUserText.isEmpty()) PendingUserLine() else UserLine(liveUserText, live = true) }
        }
    }
}

@Composable
private fun rememberRevealingId(
    newestAgentId: Long?,
    agentConnected: Boolean,
    agentSpeaking: Boolean,
    timed: Boolean,
): Long? {
    val newest by rememberUpdatedState(newestAgentId)
    var heardId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(agentSpeaking, timed) { if (!agentSpeaking && !timed) heardId = newest }
    LaunchedEffect(newestAgentId, agentConnected, agentSpeaking) {
        if (!agentConnected && !agentSpeaking) heardId = newestAgentId
    }
    return newestAgentId.takeIf { (agentSpeaking || agentConnected) && it != heardId }
}

@Composable
private fun FollowNewest(
    list: LazyListState,
    lastItem: TranscriptItem?,
    liveUserText: String?,
    count: Int,
) {
    LaunchedEffect(lastItem, liveUserText) {
        if (count > 0) {
            list.animateScrollToItem(count - 1)
            list.scrollBy(TO_END_PX)
        }
    }
    LaunchedEffect(list) {
        var atEnd = true
        var position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        snapshotFlow { list.layoutInfo }.collect {
            val now = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            val followed = atEnd && now == position
            if (followed && list.canScrollForward && !list.isScrollInProgress) list.scrollBy(TO_END_PX)
            position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            atEnd = !list.canScrollForward
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Describe a track. Then talk over it.",
            style = sans(24.sp, 800, lineHeight = 1.1f).copy(letterSpacing = (-0.02).em, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().testTag("empty-headline"),
        )
        Text(
            "Tap the composer below and say what you hear in your head.",
            style = sans(17.sp, 500, Palette.SubText, lineHeight = 1.4f).copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UserLine(
    text: String,
    live: Boolean = false,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxWidth(0.84f), contentAlignment = Alignment.CenterEnd) {
            val style = sans(16.5.sp, 400, Palette.Ink, lineHeight = 1.4f)
            Text(
                if (live) "$text…" else text,
                style = if (live) style.copy(fontStyle = FontStyle.Italic) else style,
                modifier =
                    Modifier
                        .alpha(if (live) 0.7f else 1f)
                        .background(Palette.Bubble, BubbleShape)
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                        .testTag(if (live) "transcript-live" else "transcript-user"),
            )
        }
    }
}

@Composable
private fun PendingUserLine() {
    val phase by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DOTS_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier
                .background(Palette.Bubble, BubbleShape)
                .padding(horizontal = 16.dp, vertical = 17.dp)
                .testTag("transcript-pending"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(DOTS) { i ->
                val wave = (sin(2 * PI * (phase - i / DOTS.toFloat())).toFloat() + 1f) / 2f
                Box(
                    Modifier
                        .size(7.dp)
                        .graphicsLayer {
                            alpha = DOT_DIM + (1f - DOT_DIM) * wave
                            scaleX = DOT_SMALL + (1f - DOT_SMALL) * wave
                            scaleY = scaleX
                        }.background(Palette.Ink, CircleShape),
                )
            }
        }
    }
}

private const val DOTS = 3

private const val DOTS_PERIOD_MS = 1_100

private const val DOT_DIM = 0.25f

private const val DOT_SMALL = 0.75f

@Composable
private fun AgentLine(
    text: String,
    revealing: Boolean,
    speaking: Boolean,
    timeline: SpeechTimeline?,
) {
    Column(Modifier.fillMaxWidth(0.94f).testTag("transcript-agent"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Composer", style = mono(10.sp, 500, Palette.ComposerLabel))
        val style = sans(17.sp, 500, lineHeight = 1.45f)
        if (revealing) RevealingText(text, style, speaking, timeline) else Text(text, style = style)
    }
}

@Composable
private fun RevealingText(
    text: String,
    style: TextStyle,
    speaking: Boolean,
    timeline: SpeechTimeline?,
) {
    val total = remember(text) { wordCount(text) }
    val currentTotal by rememberUpdatedState(total)
    val currentTimeline by rememberUpdatedState(timeline)
    val currentSpeaking by rememberUpdatedState(speaking)
    val elapsed = remember { mutableLongStateOf(0L) }
    val start = remember { longArrayOf(-1L) }

    fun shown(): Int {
        val speech = currentTimeline
        val over = speech != null && !currentSpeaking && elapsed.longValue >= speech.endMs
        return if (over) currentTotal else revealedWords(currentTotal, elapsed.longValue, speech)
    }
    LaunchedEffect(total, speaking) {
        if (!speaking && start[0] < 0) return@LaunchedEffect
        while (shown() < currentTotal) {
            withFrameMillis { now ->
                if (start[0] < 0) start[0] = now
                elapsed.longValue = now - start[0]
            }
        }
    }
    val words by remember { derivedStateOf { shown() } }
    Text(revealedText(text, words), style = style, modifier = Modifier.testTag("transcript-revealing"))
}

@Composable
private fun NoticeLine(text: String) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        "⚠ $text",
        style = mono(12.sp, color = Palette.Error).copy(lineHeight = 18.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Palette.ToolBg, shape)
                .border(1.dp, Palette.Error.copy(alpha = 0.6f), shape)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("transcript-notice"),
    )
}

@Composable
private fun ToolLine(tool: TranscriptItem.Tool) {
    val mark = toolStatusMark(tool.status)
    val color = if (tool.status == ToolStatus.Failed) Palette.Error else Palette.ToolText
    Text(
        toolLineText(tool) + (mark?.let { " $it" } ?: ""),
        style = mono(12.sp, color = color).copy(lineHeight = 18.sp),
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (tool.status == ToolStatus.Running) 0.7f else 1f)
                .background(Palette.ToolBg, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("transcript-tool"),
    )
}
