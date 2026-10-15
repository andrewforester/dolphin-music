package com.voiceproducer.feature.producer.ui.orb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.orb.OrbArena
import com.voiceproducer.feature.producer.orb.OrbPoint
import com.voiceproducer.feature.producer.orb.orbMood
import com.voiceproducer.feature.producer.ui.micLook
import com.voiceproducer.feature.producer.ui.orbDescription
import kotlin.math.roundToInt

private val OrbSize = 72.dp
private val DismissSize = 56.dp
private val DismissAboveNavBar = 64.dp
private val MagnetRadius = 80.dp
private val StickGap = 24.dp
private val GripWidth = 48.dp
private val GripHeight = 160.dp
private val CrossArm = 9.dp
private val CrossStroke = 2.5.dp
private const val DISMISS_ALPHA = 0.85f

@Composable
internal fun OrbLayer(
    state: ProducerUiState,
    home: Offset?,
    orb: OrbRenderer,
    onIntent: (ProducerIntent) -> Unit,
) {
    val look = rememberOrbLook(orbMood(state), state.userVoiceLevel)
    var layer by remember { mutableStateOf(IntSize.Zero) }
    val arena = orbArena(layer)
    val live = state.agent.state != AgentState.Disconnected
    val scope = rememberCoroutineScope()
    val motion = remember { OrbMotion(scope) }

    LaunchedEffect(home, motion.place) { home?.let { motion.settleHome(it) } }
    LaunchedEffect(live) { if (!live) motion.sessionEnded() }
    LaunchedEffect(arena) { motion.reclamp(arena) }

    Box(Modifier.fillMaxSize().onSizeChanged { layer = it }) {
        DismissTarget(motion.showsDismiss, arena.dismiss)
        if (home == null || !motion.placed) return@Box
        LiquidEdge({ motion.centre.value }, arena, Modifier.fillMaxSize())
        val drag = rememberOrbDrag(motion, arena, live, { home }, onIntent)
        if (motion.place == OrbPlace.Parked || motion.place == OrbPlace.Dragging) EdgeGrip(motion, arena, drag, onIntent)
        DraggableOrb(state, motion, drag, onIntent) { orb(look, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun orbArena(layer: IntSize): OrbArena {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    return with(density) {
        val r = OrbSize.toPx() / 2
        val dismiss = OrbPoint(layer.width / 2f, layer.height - navBottom - DismissAboveNavBar.toPx())
        OrbArena(
            width = layer.width.toFloat(),
            safeTop = statusTop.toFloat(),
            safeBottom = dismiss.y - DismissSize.toPx() - r,
            bottom = layer.height - navBottom - r,
            orbRadius = r,
            dismiss = dismiss,
            magnetRadius = MagnetRadius.toPx(),
            stickGap = StickGap.toPx(),
        )
    }
}

@Composable
private fun DismissTarget(
    visible: Boolean,
    at: OrbPoint,
) {
    AnimatedVisibility(
        visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier =
            Modifier.offset {
                val half = DismissSize.toPx() / 2
                IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt())
            },
    ) {
        Box(
            Modifier
                .size(DismissSize)
                .background(Palette.Ink.copy(alpha = DISMISS_ALPHA), CircleShape)
                .drawBehind { drawCross() }
                .testTag("orb-dismiss"),
        )
    }
}

@Composable
private fun DraggableOrb(
    state: ProducerUiState,
    motion: OrbMotion,
    drag: OrbDrag,
    onIntent: (ProducerIntent) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset {
                val r = OrbSize.toPx() / 2
                IntOffset((motion.centre.value.x - r).roundToInt(), (motion.centre.value.y - r).roundToInt())
            }.size(OrbSize)
            .graphicsLayer {
                scaleX = motion.size.value
                scaleY = motion.size.value
            }.excludeFromSystemGestures()
            .orbDrag(drag, remember { Anchor() })
            .clickable(
                remember { MutableInteractionSource() },
                indication = null,
                enabled = motion.place != OrbPlace.Dismissing,
                role = Role.Button,
            ) { onIntent(ProducerIntent.ToggleAgent) }
            .semantics { contentDescription = orbDescription(micLook(state).mode) }
            .testTag("mic-button"),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun EdgeGrip(
    motion: OrbMotion,
    arena: OrbArena,
    drag: OrbDrag,
    onIntent: (ProducerIntent) -> Unit,
) {
    Box(
        Modifier
            .offset {
                val x = if (motion.centre.value.x < arena.width / 2) 0f else arena.width - GripWidth.toPx()
                IntOffset(x.roundToInt(), (motion.centre.value.y - GripHeight.toPx() / 2).roundToInt())
            }.size(GripWidth, GripHeight)
            .excludeFromSystemGestures()
            .orbDrag(drag, remember { Anchor() })
            .clickable(remember { MutableInteractionSource() }, indication = null) { onIntent(ProducerIntent.ToggleAgent) }
            .testTag("orb-grip"),
    )
}

private class OrbDrag(
    val motion: OrbMotion,
    val live: Boolean,
    val arena: () -> OrbArena,
    val home: () -> Offset?,
    val onDismiss: () -> Unit,
)

private class Anchor {
    var coords: LayoutCoordinates? = null
}

@Composable
private fun rememberOrbDrag(
    motion: OrbMotion,
    arena: OrbArena,
    live: Boolean,
    home: () -> Offset?,
    onIntent: (ProducerIntent) -> Unit,
): OrbDrag {
    val latestArena by rememberUpdatedState(arena)
    val latestHome by rememberUpdatedState(home)
    val latestOnIntent by rememberUpdatedState(onIntent)
    return remember(
        motion,
        live,
    ) { OrbDrag(motion, live, { latestArena }, { latestHome() }, { latestOnIntent(ProducerIntent.ToggleAgent) }) }
}

private fun Modifier.orbDrag(
    drag: OrbDrag,
    anchor: Anchor,
): Modifier =
    onGloballyPositioned { anchor.coords = it }
        .pointerInput(drag) {
            if (!drag.live) return@pointerInput
            val motion = drag.motion
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                motion.dragStart(anchor.coords?.localToRoot(start.position) ?: motion.centre.value, start.uptimeMillis, drag.arena())
                val lifted =
                    drag(start.id) { change ->
                        change.consume()
                        anchor.coords?.let { motion.drag(it.localToRoot(change.position), change.uptimeMillis, drag.arena()) }
                    }
                if (lifted) {
                    val up = currentEvent.changes.firstOrNull()?.uptimeMillis ?: start.uptimeMillis
                    motion.dragEnd(up, drag.arena(), drag.home, drag.onDismiss)
                } else {
                    motion.dragCancel(drag.arena())
                }
            }
        }

private fun DrawScope.drawCross() {
    val arm = CrossArm.toPx()
    val stroke = CrossStroke.toPx()
    drawLine(Palette.Cream, center + Offset(-arm, -arm), center + Offset(arm, arm), stroke, StrokeCap.Round)
    drawLine(Palette.Cream, center + Offset(-arm, arm), center + Offset(arm, -arm), stroke, StrokeCap.Round)
}
