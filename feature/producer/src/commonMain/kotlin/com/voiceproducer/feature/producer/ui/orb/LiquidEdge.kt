package com.voiceproducer.feature.producer.ui.orb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.util.lerp
import com.voiceproducer.feature.producer.orb.EdgeBump
import com.voiceproducer.feature.producer.orb.OrbArena
import com.voiceproducer.feature.producer.orb.edgeBump
import com.voiceproducer.feature.producer.orb.edgeInset
import com.voiceproducer.feature.producer.orb.nearerEdgeX
import com.voiceproducer.feature.producer.orb.stillAttached
import kotlinx.coroutines.launch

private const val RECOIL_MS = 260
private const val RECOIL_STRETCH = 2.6f
private const val PROFILE_STEPS = 48

@Stable
private class EdgeLink {
    var attached by mutableStateOf(false)
    var recoilFrom by mutableStateOf<Recoil?>(null)
    val recoil = Animatable(1f)
}

private data class Recoil(
    val edgeX: Float,
    val y: Float,
    val bump: EdgeBump,
)

@Composable
internal fun LiquidEdge(
    centre: () -> Offset,
    arena: OrbArena,
    modifier: Modifier = Modifier,
) {
    val link = remember { EdgeLink() }
    val latestArena by rememberUpdatedState(arena)
    val latestCentre by rememberUpdatedState(centre)
    LaunchedEffect(link) {
        snapshotFlow { latestCentre() }.collect { c ->
            val r = latestArena.orbRadius
            val inset = edgeInset(c.x, latestArena)
            val next = stillAttached(inset, link.attached, r)
            if (link.attached && !next) {
                link.recoilFrom = Recoil(nearerEdgeX(c.x, latestArena), c.y, edgeBump(inset, attached = true, orbRadius = r))
                launch {
                    link.recoil.snapTo(0f)
                    link.recoil.animateTo(1f, tween(RECOIL_MS, easing = FastOutSlowInEasing))
                    link.recoilFrom = null
                }
            }
            link.attached = next
        }
    }
    val visible by remember {
        derivedStateOf {
            val c = centre()
            link.recoilFrom != null ||
                edgeBump(edgeInset(c.x, latestArena), link.attached, latestArena.orbRadius).depth > 0f
        }
    }
    if (!visible) return
    Spacer(
        modifier
            .drawBehind {
                val c = centre()
                val bump = edgeBump(edgeInset(c.x, arena), link.attached, arena.orbRadius)
                drawBump(nearerEdgeX(c.x, arena), c.y, bump)
                link.recoilFrom?.let { from ->
                    val t = link.recoil.value
                    val halfLength = lerp(from.bump.halfLength, from.bump.halfLength * RECOIL_STRETCH, t)
                    drawBump(from.edgeX, from.y, EdgeBump(lerp(from.bump.depth, 0f, t), halfLength))
                }
            }.testTag("orb-edge"),
    )
}

private fun DrawScope.drawBump(
    edgeX: Float,
    y: Float,
    bump: EdgeBump,
) {
    if (bump.depth <= 0f || bump.halfLength <= 0f) return
    val inward = if (edgeX == 0f) 1f else -1f
    val path =
        Path().apply {
            moveTo(edgeX, y - bump.halfLength)
            for (i in 1..PROFILE_STEPS) {
                val t = -1f + 2f * i / PROFILE_STEPS
                val s = 1f - t * t
                lineTo(edgeX + inward * bump.depth * s * s, y + t * bump.halfLength)
            }
            close()
        }
    drawPath(path, Color.Black)
}
