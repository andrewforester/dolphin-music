package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.PillShape
import com.voiceproducer.core.designsystem.glass
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.model.ApiLogEntry
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SectionStatus
import com.voiceproducer.feature.producer.StyleChip
import com.voiceproducer.feature.producer.TrackStatus
import com.voiceproducer.feature.producer.ui.background.StaticBackground
import com.voiceproducer.feature.producer.ui.orb.OrbLayer
import com.voiceproducer.feature.producer.ui.orb.OrbRenderer
import com.voiceproducer.feature.producer.ui.orb.StaticOrb
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.roundToInt

private val MicIntoNavBar = 8.dp

private val OrbBlockLift = 4.dp

@Composable
fun ProducerScreen(
    state: ProducerUiState,
    onIntent: (ProducerIntent) -> Unit,
    modifier: Modifier = Modifier,
    positionMs: () -> Long = { 0L },
    background: @Composable (ProducerUiState, positionMs: () -> Long) -> Unit = { _, _ -> StaticBackground() },
    apiLog: ImmutableList<ApiLogEntry> = persistentListOf(),
    formatTime: (Long) -> String = ::utcTime,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    orb: OrbRenderer = StaticOrb,
) {
    var pillsTopPx by remember { mutableIntStateOf(0) }
    var orbHome by remember { mutableStateOf<Offset?>(null) }
    var confirmNewTrack by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        background(state, positionMs)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(navigationBarsLess(MicIntoNavBar))
                .imePadding()
                .padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                TranscriptCard(
                    remember(state.transcript) { state.transcript.toImmutableList() },
                    Modifier.fillMaxSize(),
                    liveUserText = state.liveUserText,
                    agentConnected = state.agent.state != AgentState.Disconnected,
                    agentSpeaking = state.agent.state == AgentState.Speaking,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.padding(horizontal = 16.dp)) { TrackCard(state, positionMs, onIntent) }
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Transport(state, onIntent)
                    Pills(state, apiLog.size, onIntent, onNewTrack = { confirmNewTrack = true }, onPlaced = { pillsTopPx = it })
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = OrbBlockLift),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.agent.error?.let { ErrorLine(it, tag = "agent-error") }
                if (!state.micPermission) TextInputRow(onSend = { onIntent(ProducerIntent.SendText(it)) })
                OrbHome(state, onPlaced = { orbHome = it })
            }
        }
        if (state.showApiLog) {
            Box(Modifier.fillMaxWidth().height(with(LocalDensity.current) { pillsTopPx.toDp() })) {
                ApiLogPanel(apiLog, formatTime, onClose = { onIntent(ProducerIntent.ToggleApiLog) })
            }
        }
        OrbLayer(state, orbHome, orb, onIntent)
        val canConfirm = !state.isWorking
        val hasTrack = state.sections.isNotEmpty()
        LaunchedEffect(hasTrack) { if (!hasTrack) confirmNewTrack = false }
        if (confirmNewTrack && hasTrack) {
            NewTrackConfirmation(
                backHandler = backHandler,
                canConfirm = canConfirm,
                onCancel = { confirmNewTrack = false },
                onConfirm = {
                    confirmNewTrack = false
                    onIntent(ProducerIntent.NewTrack)
                },
            )
        }
    }
}

@Composable
private fun Transport(
    state: ProducerUiState,
    onIntent: (ProducerIntent) -> Unit,
) {
    val hasTrack = state.sections.isNotEmpty()
    Row(
        Modifier.alpha(if (hasTrack) 1f else 0.7f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RoundButton(52.dp, "Previous section", "prev-button", glass = true, onClick = { onIntent(ProducerIntent.PrevSection) }) {
            drawSkip(forward = false, Palette.TextOnGlass)
        }
        RoundButton(68.dp, if (state.isPlaying) "Pause" else "Play", "play-button", glass = false, onClick = {
            onIntent(ProducerIntent.TogglePlay)
        }) {
            if (state.isPlaying) drawPause(Palette.Ink) else drawPlay(Palette.Ink)
        }
        RoundButton(52.dp, "Next section", "next-button", glass = true, onClick = { onIntent(ProducerIntent.NextSection) }) {
            drawSkip(forward = true, Palette.TextOnGlass)
        }
    }
}

@Composable
private fun RoundButton(
    size: Dp,
    description: String,
    tag: String,
    glass: Boolean,
    onClick: () -> Unit,
    icon: DrawScope.() -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (glass) Modifier.glass(CircleShape, Palette.GlassBorderStrong) else Modifier.background(Palette.CreamButton))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .drawBehind(icon)
            .testTag(tag),
    )
}

private fun DrawScope.drawSkip(
    forward: Boolean,
    color: Color,
) {
    val bar = 3.dp.toPx()
    val tri = 11.dp.toPx()
    val h = 14.dp.toPx()
    val gap = 1.dp.toPx()
    val left = (size.width - bar - gap - tri) / 2
    val top = (size.height - h) / 2
    val barX = if (forward) left + tri + gap else left
    val triX = if (forward) left else left + bar + gap
    drawRoundRect(color, Offset(barX, top), Size(bar, h), CornerRadius(1.dp.toPx()))
    val path =
        Path().apply {
            if (forward) {
                moveTo(triX, top)
                lineTo(triX + tri, top + h / 2)
                lineTo(triX, top + h)
            } else {
                moveTo(triX + tri, top)
                lineTo(triX, top + h / 2)
                lineTo(triX + tri, top + h)
            }
            close()
        }
    drawPath(path, color)
}

private fun DrawScope.drawPlay(color: Color) {
    val w = 17.dp.toPx()
    val h = 22.dp.toPx()
    val left = (size.width - w) / 2 + 2.5.dp.toPx()
    val top = (size.height - h) / 2
    drawPath(
        Path().apply {
            moveTo(left, top)
            lineTo(left + w, top + h / 2)
            lineTo(left, top + h)
            close()
        },
        color,
    )
}

private fun DrawScope.drawPause(color: Color) {
    val w = 5.dp.toPx()
    val h = 20.dp.toPx()
    val gap = 5.dp.toPx()
    val left = (size.width - 2 * w - gap) / 2
    val top = (size.height - h) / 2
    drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(1.5.dp.toPx()))
    drawRoundRect(color, Offset(left + w + gap, top), Size(w, h), CornerRadius(1.5.dp.toPx()))
}

@Composable
private fun Pills(
    state: ProducerUiState,
    apiCalls: Int,
    onIntent: (ProducerIntent) -> Unit,
    onNewTrack: () -> Unit,
    onPlaced: (Int) -> Unit,
) {
    PillRows(
        contentPadding = 16.dp,
        modifier =
            Modifier
                .heightIn(min = 22.dp)
                .onGloballyPositioned { onPlaced(it.positionInRoot().y.roundToInt()) }
                .testTag("pills"),
    ) {
        state.chips.forEach { Chip(it) }
        if (state.sections.isNotEmpty()) {
            ActionPill("↶ Undo", "undo-button", enabled = state.canUndo) { onIntent(ProducerIntent.Undo) }
            ActionPill("New track", "new-track-button", enabled = !state.isWorking, onClick = onNewTrack)
        }
        if (state.micPermission && (state.agent.state == AgentState.Listening || state.agent.state == AgentState.Speaking)) {
            ActionPill(if (state.agent.micMuted) "Unmute mic" else "Mute mic", "mute-button", enabled = true) {
                onIntent(ProducerIntent.ToggleMic)
            }
        }
        if (state.demoAvailable && state.agent.state == AgentState.Disconnected && state.agentActivity == AgentActivity.Idle) {
            ActionPill("Demo", "demo-button", enabled = true) { onIntent(ProducerIntent.StartDemo) }
        }
        ApiPill(apiCalls) { onIntent(ProducerIntent.ToggleApiLog) }
    }
}

@Composable
private fun Chip(chip: StyleChip) {
    Text(
        chip.label,
        style = mono(11.5.sp, 500, Palette.SubText),
        modifier =
            Modifier
                .glass(PillShape, Palette.GlassBorderStrong)
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("chip-${chip.label}"),
    )
}

@Composable
private fun ActionPill(
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = sans(12.5.sp, 700, Palette.Cream),
        modifier =
            Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .clip(PillShape)
                .background(Palette.Glass)
                .border(1.dp, Palette.Cream.copy(alpha = 0.45f), PillShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag(tag),
    )
}

@Composable
private fun ApiPill(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(PillShape)
            .background(Palette.Glass)
            .border(1.dp, Palette.Cream.copy(alpha = 0.45f), PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = if (count > 0) 6.dp else 12.dp, top = 5.dp, bottom = 5.dp)
            .testTag("api-button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("API", style = sans(12.5.sp, 700, Palette.Cream))
        if (count > 0) {
            Text(
                count.toString(),
                style = sans(10.5.sp, 700, Palette.Ink),
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(Palette.Ochre)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .testTag("api-badge"),
            )
        }
    }
}

private val ProducerUiState.isWorking: Boolean
    get() = track == TrackStatus.Generating || sections.any { it.status != SectionStatus.Idle && it.status != SectionStatus.Failed }

@Composable
private fun NewTrackConfirmation(
    backHandler: @Composable (Boolean, () -> Unit) -> Unit,
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    backHandler(true, onCancel)
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onCancel() } }
            .testTag("new-track-scrim"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), Palette.GlassBorderStrong)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp)
                .testTag("new-track-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Start a new track?", style = sans(18.sp, 700, Palette.Cream))
            Text(
                "The current track will be cleared from the timeline. It stays saved, but you won't be able to reopen it from here.",
                style = sans(14.sp, 400, lineHeight = 1.4f),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ActionPill("Cancel", "new-track-cancel", enabled = true, onClick = onCancel)
                ActionPill("New track", "new-track-confirm", enabled = canConfirm, onClick = onConfirm)
            }
        }
    }
}
