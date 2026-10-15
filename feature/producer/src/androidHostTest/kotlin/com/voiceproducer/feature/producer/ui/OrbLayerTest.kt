package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h820dp-mdpi")
class OrbLayerTest {
    private companion object {
        const val HOLD_MS = 200L
        const val FLING_MS = 100L
        const val GRAB_SHIFT = 30f
        const val SCREEN_WIDTH = 392f
        const val PARKED_LEFT = -0.3f * 36f
        const val PARKED_RIGHT = 392f + 0.3f * 36f
    }

    @get:Rule
    val rule = createComposeRule()

    private val live =
        ProducerUiState(
            agent = AgentUi(AgentState.Listening, null, null, micMuted = false),
            agentActivity = AgentActivity.Listening,
            micPermission = true,
        )
    private val off = live.copy(agent = live.agent.copy(state = AgentState.Disconnected), agentActivity = AgentActivity.Idle)

    private fun dismissCentre(height: Float = 820f) = Offset(196f, height - 64f)

    private fun show(
        state: ProducerUiState,
        intents: MutableList<ProducerIntent> = mutableListOf(),
        hostHeight: MutableState<Dp>? = null,
    ): MutableState<ProducerUiState> {
        val current = mutableStateOf(state)
        rule.setContent {
            val screen = @Composable {
                ProducerScreen(current.value, positionMs = { 0L }, onIntent = { intents += it })
            }
            if (hostHeight == null) screen() else Box(Modifier.fillMaxWidth().height(hostHeight.value)) { screen() }
        }
        rule.waitForIdle()
        return current
    }

    private fun Offset.pair() = x to y

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun centre(r: DpRect) = Offset((r.left + r.right).value / 2, (r.top + r.bottom).value / 2)

    private fun orbCentre() = centre(bounds("mic-button"))

    private fun assertOrbAtHome() {
        val home = centre(bounds("orb-home"))
        val orb = orbCentre()
        assertEquals("orb x", home.x, orb.x, 1f)
        assertEquals("orb y", home.y, orb.y, 1f)
    }

    private fun pressAndMoveTo(vararg targets: Pair<Float, Float>) {
        val orb = orbCentre()
        val shift =
            when {
                orb.x < 0f -> Offset(GRAB_SHIFT, 0f)
                orb.x > SCREEN_WIDTH -> Offset(-GRAB_SHIFT, 0f)
                else -> Offset.Zero
            }
        val start = orb + shift
        rule.onNodeWithTag("mic-button").performTouchInput {
            val grab = center + shift
            down(grab)
            var from = start
            for ((x, y) in targets) {
                val target = Offset(x, y)
                for (i in 1..10) moveTo(grab + (from - start) + (target - from) * (i / 10f))
                from = target
            }
        }
    }

    private fun release() {
        rule.onNodeWithTag("mic-button").performTouchInput {
            advanceEventTime(HOLD_MS)
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun setState(
        current: MutableState<ProducerUiState>,
        state: ProducerUiState,
    ) {
        current.value = state
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun dragOrbTo(vararg targets: Pair<Float, Float>) {
        pressAndMoveTo(*targets)
        release()
    }

    @Test
    fun offOrbDoesNotDrag() {
        show(off)
        pressAndMoveTo(80f to 300f)
        rule.onNodeWithTag("orb-dismiss").assertDoesNotExist()
        release()
        assertOrbAtHome()
    }

    @Test
    fun dismissShowsOnlyWhileDragging() {
        show(live)
        rule.onNodeWithTag("orb-dismiss").assertDoesNotExist()
        pressAndMoveTo(150f to 300f)
        rule.waitForIdle()
        rule.onNodeWithTag("orb-dismiss").assertExists()
        assertEquals(dismissCentre().y, centre(bounds("orb-dismiss")).y, 1f)
        release()
        rule.onNodeWithTag("orb-dismiss").assertDoesNotExist()
    }

    @Test
    fun theScreenIsNotDimmedWhileDragging() {
        show(live)
        pressAndMoveTo(150f to 300f)
        rule.waitForIdle()
        rule.onNodeWithTag("orb-dim").assertDoesNotExist()
        release()
    }

    @Test
    fun releasedAwayFromTheEdgesTheOrbStays() {
        show(live)
        dragOrbTo(150f to 300f)
        assertEquals(150f, orbCentre().x, 1f)
        assertEquals(300f, orbCentre().y, 1f)
        rule.onNodeWithTag("orb-edge").assertDoesNotExist()
    }

    @Test
    fun releasedAtTheLeftEdgeTheOrbSinksIn() {
        show(live)
        dragOrbTo(150f to 300f, 40f to 300f)
        assertEquals(PARKED_LEFT, orbCentre().x, 1f)
        assertEquals(300f, orbCentre().y, 1f)
    }

    @Test
    fun releasedAtTheRightEdgeTheOrbSinksIn() {
        show(live)
        dragOrbTo(150f to 200f, 360f to 200f)
        assertEquals(PARKED_RIGHT, orbCentre().x, 1f)
        assertEquals(200f, orbCentre().y, 1f)
    }

    @Test
    fun aFlingCarriesTheOrbOnAndStopsInsideTheScreen() {
        show(live)
        dragOrbTo(196f to 400f)
        rule.onNodeWithTag("mic-button").performTouchInput { swipe(center, center + Offset(0f, -120f), FLING_MS) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val y = orbCentre().y
        assertTrue("y $y", y < 400f - 120f - 20f)
        assertTrue("y $y", y >= 36f)
        assertEquals(196f, orbCentre().x, 1f)
    }

    @Test
    fun aFlingTowardsAnEdgeSticks() {
        show(live)
        dragOrbTo(196f to 400f)
        rule.onNodeWithTag("mic-button").performTouchInput { swipe(center, center + Offset(-100f, 0f), FLING_MS) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(PARKED_LEFT, orbCentre().x, 1f)
    }

    @Test
    fun dropOnDismissEndsTheSessionOnce() {
        val intents = mutableListOf<ProducerIntent>()
        show(live, intents)
        pressAndMoveTo(150f to 300f, dismissCentre().pair())
        release()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
        assertOrbAtHome()
    }

    @Test
    fun liftingFromHomeDoesNotDismissAtOnce() {
        val intents = mutableListOf<ProducerIntent>()
        show(live, intents)
        val home = orbCentre()
        dragOrbTo(home.x to home.y - 20f)
        assertTrue(intents.isEmpty())
    }

    @Test
    fun parkedTapEndsTheSession() {
        val intents = mutableListOf<ProducerIntent>()
        show(live, intents)
        dragOrbTo(150f to 300f, 40f to 300f)
        rule.onNodeWithTag("mic-button").performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
    }

    @Test
    fun smallWobbleIsATap() {
        val intents = mutableListOf<ProducerIntent>()
        show(live, intents)
        rule.onNodeWithTag("mic-button").performTouchInput {
            down(center)
            moveBy(Offset(3f, 2f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
        assertOrbAtHome()
    }

    @Test
    fun disconnectReturnsHome() {
        val current = show(live)
        dragOrbTo(80f to 300f)
        setState(current, off)
        assertOrbAtHome()
    }

    @Test
    fun disconnectMidDragCancelsWithoutIntent() {
        val intents = mutableListOf<ProducerIntent>()
        val current = show(live, intents)
        pressAndMoveTo(150f to 300f, dismissCentre().pair())
        current.value = off
        rule.waitForIdle()
        release()
        assertTrue(intents.isEmpty())
        assertOrbAtHome()
        rule.onNodeWithTag("orb-dismiss").assertDoesNotExist()
    }

    @Test
    fun nextSessionStartsAtHome() {
        val current = show(live)
        dragOrbTo(80f to 300f)
        setState(current, off)
        setState(current, live)
        assertOrbAtHome()
    }

    @Test
    fun parkedOrbStaysOnItsEdgeWhenTheLayoutShrinks() {
        val height = mutableStateOf(820.dp)
        show(live, hostHeight = height)
        dragOrbTo(150f to 600f, 360f to 600f)
        assertEquals(600f, orbCentre().y, 1f)
        height.value = 500.dp
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(PARKED_RIGHT, orbCentre().x, 1f)
        assertTrue("y ${orbCentre().y}", orbCentre().y <= 344f + 1f)
    }

    @Test
    fun noEdgeAtHome() {
        show(live)
        rule.onNodeWithTag("orb-edge").assertDoesNotExist()
    }

    @Test
    fun edgeShowsWhenParked() {
        show(live)
        dragOrbTo(150f to 300f, 40f to 300f)
        rule.onNodeWithTag("orb-edge").assertExists()
    }

    @Test
    fun edgeGrowsAsTheOrbNearsTheEdge() {
        show(live)
        pressAndMoveTo(196f to 300f, 60f to 300f)
        rule.waitForIdle()
        rule.onNodeWithTag("orb-edge").assertExists()
        release()
    }

    @Test
    fun edgeGoesWhenPulledAway() {
        show(live)
        dragOrbTo(150f to 300f, 40f to 300f)
        pressAndMoveTo(196f to 300f)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithTag("orb-edge").assertDoesNotExist()
        release()
    }

    @Test
    fun orbStartsAtHomeWithoutFlyingIn() {
        rule.mainClock.autoAdvance = false
        rule.setContent { ProducerScreen(live, positionMs = { 0L }, onIntent = {}) }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("orb-edge").assertDoesNotExist()
        assertOrbAtHome()
    }

    @Test
    fun tapDuringPullInDoesNotToggleAgain() {
        val intents = mutableListOf<ProducerIntent>()
        show(live, intents)
        pressAndMoveTo(150f to 300f, dismissCentre().pair())
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mic-button").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("mic-button").performTouchInput { click(center) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
    }

    @Test
    fun aParkedOrbHasAGripAlongTheEdge() {
        show(live)
        rule.onNodeWithTag("orb-grip").assertDoesNotExist()
        dragOrbTo(150f to 300f, 40f to 300f)
        val grip = bounds("orb-grip")
        assertEquals(0f, grip.left.value, 1f)
        assertEquals(48f, (grip.right - grip.left).value, 1f)
        assertEquals(300f, centre(grip).y, 1f)
    }

    @Test
    fun theGripPullsTheOrbOut() {
        show(live)
        dragOrbTo(150f to 300f, 40f to 300f)
        rule.onNodeWithTag("orb-grip").performTouchInput {
            down(Offset(width * 0.5f, height * 0.8f))
            for (i in 1..10) moveBy(Offset(16f, 0f))
            advanceEventTime(HOLD_MS)
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("x ${orbCentre().x}", orbCentre().x > 100f)
        rule.onNodeWithTag("orb-grip").assertDoesNotExist()
    }
}
