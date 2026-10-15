package com.voiceproducer.feature.producer.ui.orb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import com.voiceproducer.feature.producer.orb.OrbArena
import com.voiceproducer.feature.producer.orb.OrbDrop
import com.voiceproducer.feature.producer.orb.OrbPoint
import com.voiceproducer.feature.producer.orb.freePoint
import com.voiceproducer.feature.producer.orb.inMagnet
import com.voiceproducer.feature.producer.orb.onRelease
import com.voiceproducer.feature.producer.orb.parkPoint
import com.voiceproducer.feature.producer.orb.settle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal enum class OrbPlace { Home, Dragging, Free, Parked, Dismissing }

private const val ORB_ON_DISMISS = 0.6f
private const val PULL_IN_MS = 180
private const val GLIDE_MS = 320
private const val STILL_MS = 50L
private val ParkSpring = spring<Offset>(dampingRatio = 0.7f, stiffness = 400f)
private val MagnetSpring = spring<Offset>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

@Stable
internal class OrbMotion(
    private val scope: CoroutineScope,
) {
    var place by mutableStateOf(OrbPlace.Home)
        private set

    var placed by mutableStateOf(false)
        private set

    val centre = Animatable(Offset.Zero, Offset.VectorConverter)
    val size = Animatable(1f)

    val showsDismiss: Boolean get() = place == OrbPlace.Dragging || place == OrbPlace.Dismissing

    private var finger = Offset.Zero
    private var armed = false
    private var magnetised = false
    private val velocity = VelocityTracker()
    private var lastMoveMs = 0L

    suspend fun settleHome(home: Offset) =
        coroutineScope {
            if (place != OrbPlace.Home) return@coroutineScope
            launch { size.animateTo(1f) }
            if (!placed || (centre.value - home).getDistance() < 1f) centre.snapTo(home) else centre.animateTo(home, ParkSpring)
            placed = true
        }

    fun sessionEnded() {
        if (place == OrbPlace.Dragging || place == OrbPlace.Parked || place == OrbPlace.Free) place = OrbPlace.Home
    }

    suspend fun reclamp(arena: OrbArena) {
        when (place) {
            OrbPlace.Parked -> centre.snapTo(parkPoint(centre.value.point(), arena).offset())
            OrbPlace.Free -> centre.snapTo(freePoint(centre.value.point(), arena).offset())
            else -> Unit
        }
    }

    fun dragStart(
        at: Offset,
        timeMs: Long,
        arena: OrbArena,
    ) {
        finger = at
        velocity.resetTracking()
        velocity.addPosition(timeMs, at)
        lastMoveMs = timeMs
        armed = !inMagnet(finger.point(), arena)
        magnetised = false
        place = OrbPlace.Dragging
        scope.launch { centre.stop() }
    }

    fun drag(
        at: Offset,
        timeMs: Long,
        arena: OrbArena,
    ) {
        finger = at
        velocity.addPosition(timeMs, at)
        lastMoveMs = timeMs
        val inZone = inMagnet(finger.point(), arena)
        if (!inZone) armed = true
        val pull = armed && inZone
        if (pull != magnetised) {
            magnetised = pull
            scope.launch { size.animateTo(if (pull) ORB_ON_DISMISS else 1f) }
            if (pull) scope.launch { centre.animateTo(arena.dismiss.offset(), MagnetSpring) }
        }
        if (!pull) scope.launch { centre.snapTo(finger) }
    }

    fun dragEnd(
        timeMs: Long,
        arena: OrbArena,
        home: () -> Offset?,
        onDismiss: () -> Unit,
    ) {
        val v = if (timeMs - lastMoveMs > STILL_MS) Velocity.Zero else velocity.calculateVelocity()
        val drop =
            if (armed) {
                onRelease(finger.point(), OrbPoint(v.x, v.y), arena)
            } else {
                settle(finger.point(), arena)
            }
        when (drop) {
            OrbDrop.Dismiss -> dismiss(home, onDismiss)
            is OrbDrop.Park -> park(drop.to.offset())
            is OrbDrop.Stay -> glide(drop.at.offset())
        }
    }

    fun dragCancel(arena: OrbArena) {
        when (val drop = settle(finger.point(), arena)) {
            is OrbDrop.Park -> park(drop.to.offset())
            is OrbDrop.Stay -> glide(drop.at.offset())
            OrbDrop.Dismiss -> Unit
        }
    }

    private fun dismiss(
        home: () -> Offset?,
        onDismiss: () -> Unit,
    ) {
        place = OrbPlace.Dismissing
        onDismiss()
        scope.launch {
            size.animateTo(0f, tween(PULL_IN_MS))
            home()?.let { centre.snapTo(it) }
            place = OrbPlace.Home
        }
    }

    private fun park(to: Offset) {
        place = OrbPlace.Parked
        scope.launch { size.animateTo(1f) }
        scope.launch { centre.animateTo(to, ParkSpring) }
    }

    private fun glide(to: Offset) {
        place = OrbPlace.Free
        scope.launch { size.animateTo(1f) }
        scope.launch { centre.animateTo(to, tween(GLIDE_MS, easing = LinearOutSlowInEasing)) }
    }
}

private fun Offset.point() = OrbPoint(x, y)

private fun OrbPoint.offset() = Offset(x, y)
