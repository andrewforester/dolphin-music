package com.voiceproducer.feature.producer.ui

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.model.ApiLogEntry
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentSpeech
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SectionStatus
import com.voiceproducer.feature.producer.SectionUi
import com.voiceproducer.feature.producer.SpeechTimeline
import com.voiceproducer.feature.producer.StyleChip
import com.voiceproducer.feature.producer.ToolKind
import com.voiceproducer.feature.producer.ToolStatus
import com.voiceproducer.feature.producer.TrackStatus
import com.voiceproducer.feature.producer.TranscriptItem
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h820dp")
class ProducerScreenTranscriptTest : ProducerScreenTestBase() {
    @Test
    fun transcriptRendersUserAgentAndToolLines() {
        show(
            state(
                transcript =
                    listOf(
                        TranscriptItem.User(1, "make it heavier"),
                        TranscriptItem.Agent(2, "On it."),
                        TranscriptItem.Tool(3, ToolKind.EditSection, "Chorus", "heavier drums", ToolStatus.Done),
                    ),
            ),
        )
        rule.onNodeWithTag("transcript-user").assertTextEquals("make it heavier")
        rule.onNodeWithText("On it.").assertExists()
        rule.onNodeWithText("Composer").assertExists()
        rule.onNodeWithTag("transcript-tool").assertTextEquals("✎ Changing Chorus — heavier drums ✓")
        rule.onNodeWithTag("empty-headline").assertDoesNotExist()
        rule.onNodeWithTag("transcript-live").assertDoesNotExist()
    }

    @Test
    fun toolLineShowsItsStatusAndUpdatesInPlace() {
        fun line(status: ToolStatus) = TranscriptItem.Tool(1, ToolKind.Compose, null, null, status)
        val current = mutableStateOf(state(transcript = listOf(line(ToolStatus.Running))))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.onNodeWithTag("transcript-tool").assertTextEquals("♪ Writing a new track")
        current.value = state(transcript = listOf(line(ToolStatus.Done)))
        rule.waitForIdle()
        rule.onNodeWithTag("transcript-tool").assertTextEquals("♪ Writing a new track ✓")
        current.value = state(transcript = listOf(line(ToolStatus.Failed)))
        rule.waitForIdle()
        rule.onNodeWithTag("transcript-tool").assertTextEquals("♪ Writing a new track ✗")
    }

    @Test
    fun liveUserSpeechShowsAsTypingBubbleAfterTheLines() {
        val lines = (1..30).map { TranscriptItem.User(it.toLong(), "line $it") }
        val current = mutableStateOf(state(transcript = lines))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.waitForIdle()
        current.value = current.value.copy(liveUserText = "make the chorus")
        rule.waitForIdle()
        rule.onNodeWithTag("transcript-live").assertTextEquals("make the chorus…").assertIsDisplayed()
        current.value = current.value.copy(liveUserText = null)
        rule.waitForIdle()
        rule.onNodeWithTag("transcript-live").assertDoesNotExist()
    }

    @Test
    fun newestReplyIsRevealedWordByWordWhileSpoken() {
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(listOf(TranscriptItem.Agent(1, "Earlier reply in full."))))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = speaking(listOf(TranscriptItem.Agent(1, "Earlier reply in full."), TranscriptItem.Agent(2, reply)))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking")
        rule.onNodeWithText("Earlier reply in full.").assertExists()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the chorus")
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals(reply)
    }

    @Test
    fun replyShowsInFullOnceSpeakingStops() {
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(emptyList()))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking")
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)), speaking = false)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertDoesNotExist()
        rule.onNodeWithText(reply).assertExists()
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertDoesNotExist()
        rule.onNodeWithText(reply).assertExists()
    }

    private val timedReply =
        SpeechTimeline() +
            AgentEvent.SpeechChunk(1, "Reworking ", List(10) { it * 100 }, 1_500) +
            AgentEvent.SpeechChunk(1, "the ", List(4) { it * 100 }, 1_500) +
            AgentEvent.SpeechChunk(1, "chorus", List(6) { it * 50 }, 500)

    private fun timed(
        speaking: Boolean = true,
        timeline: SpeechTimeline? = timedReply,
    ) = speaking(listOf(TranscriptItem.Agent(1, reply)), speaking).copy(agentSpeech = timeline?.let { AgentSpeech(1, it) })

    @Test
    fun replyFollowsTheTimingOfItsSpeech() {
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(emptyList()))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = timed()
        rule.mainClock.advanceTimeBy(1_300)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking")
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the")
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the chorus")
    }

    @Test
    fun aPauseInsideATimedReplyDoesNotEndTheReveal() {
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(emptyList()))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = timed()
        rule.mainClock.advanceTimeBy(1_300)
        current.value = timed(speaking = false)
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the")
        current.value = timed()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the chorus")
    }

    @Test
    fun aTimedReplyShowsInFullOnceItsSpeechIsOverAndTheAgentIsQuiet() {
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(emptyList()))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = timed()
        rule.mainClock.advanceTimeBy(3_600)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the chorus")
        current.value = timed(speaking = false)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals(reply)
        current.value = timed(speaking = false, timeline = null)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertDoesNotExist()
        rule.onNodeWithText(reply).assertExists()
    }

    @Test
    fun replyArrivingBeforeSpeechWaitsForTheVoice() {
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(emptyList(), speaking = false))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)), speaking = false)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking")
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking")
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking")
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("transcript-revealing").assertTextEquals("Reworking the chorus")
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)), speaking = false)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertDoesNotExist()
        rule.onNodeWithText(reply).assertExists()
    }

    @Test
    fun revealDoesNotPullAReaderWhoScrolledUp() {
        val lines = (1..29).map { TranscriptItem.User(it.toLong(), "line $it") }
        val longReply = (1..80).joinToString("\n") { "word$it" }
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(lines, speaking = false))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeBy(1_000)
        current.value = speaking(lines + TranscriptItem.Agent(30, longReply))
        rule.mainClock.advanceTimeBy(10_000)
        val up = with(rule.density) { 120.dp.toPx() }
        rule.onNodeWithTag("transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -up) }
        rule.mainClock.advanceTimeBy(1_000)
        val top = rule.onNodeWithTag("transcript-revealing").getUnclippedBoundsInRoot().top
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals(top, rule.onNodeWithTag("transcript-revealing").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun revealFollowsAReaderAtTheEnd() {
        val lines = (1..29).map { TranscriptItem.User(it.toLong(), "line $it") }
        val longReply = (1..80).joinToString("\n") { "word$it" }
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(lines, speaking = false))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeBy(1_000)
        current.value = speaking(lines + TranscriptItem.Agent(30, longReply))
        rule.mainClock.advanceTimeBy(10_000)
        val top = rule.onNodeWithTag("transcript-revealing").getUnclippedBoundsInRoot().top
        rule.mainClock.advanceTimeBy(5_000)
        assertTrue(rule.onNodeWithTag("transcript-revealing").getUnclippedBoundsInRoot().top < top)
    }

    @Test
    fun revealFollowsWhenAWordAddsSeveralLines() {
        val lines = (1..29).map { TranscriptItem.User(it.toLong(), "line $it") }
        val tallReply = (1..40).joinToString("\n\n\n") { "word$it" }
        rule.mainClock.autoAdvance = false
        val current = mutableStateOf(speaking(lines, speaking = false))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeBy(1_000)
        current.value = speaking(lines + TranscriptItem.Agent(30, tallReply))
        rule.mainClock.advanceTimeBy(20_000)
        val card = rule.onNodeWithTag("transcript").getUnclippedBoundsInRoot()
        val reply = rule.onNodeWithTag("transcript-revealing").getUnclippedBoundsInRoot()
        assertTrue("reply end ${reply.bottom} in the card ending at ${card.bottom}", reply.bottom <= card.bottom)
    }

    @Test
    fun replyIsInFullWhenNotSpeaking() {
        show(speaking(listOf(TranscriptItem.Agent(1, reply)), speaking = false))
        rule.onNodeWithText(reply).assertExists()
        rule.onNodeWithTag("transcript-revealing").assertDoesNotExist()
    }

    @Test
    fun emptyTranscriptShowsHeadline() {
        show(state(sections = emptyList(), track = TrackStatus.Empty, activity = AgentActivity.Idle))
        rule.onNodeWithText("Describe a track. Then talk over it.").assertExists()
        rule.onNodeWithTag("mic-label").assertTextEquals("Mic off · Tap to talk")
        rule.onNodeWithContentDescription("Talk to composer").assertExists()
    }

    @Test
    fun micHintSharesTheLabelLine() {
        show(state(activity = AgentActivity.Listening))
        val label = rule.onNodeWithTag("mic-label").getUnclippedBoundsInRoot()
        val hint = rule.onNodeWithTag("mic-hint").getUnclippedBoundsInRoot()
        assertEquals(label.top.value, hint.top.value, 0.5f)
        assertTrue(hint.left > label.right)
    }

    @Test
    fun micLabelChangesBetweenOffLiveAndMuted() {
        val current =
            mutableStateOf(
                state(activity = AgentActivity.Idle).copy(agent = AgentUi(AgentState.Disconnected, null, null, micMuted = false)),
            )
        rule.setContent { ProducerScreen(current.value, positionMs = { 30_000L }, onIntent = {}) }
        rule.onNodeWithTag("mic-label").assertTextEquals("Mic off · Tap to talk")
        rule.onNodeWithTag("mic-hint").assertDoesNotExist()

        current.value = state(activity = AgentActivity.Listening, ducked = true)
        rule.waitForIdle()
        rule.onNodeWithTag("mic-label").assertTextEquals("Live · Listening")
        rule.onNodeWithTag("mic-hint").assertTextEquals("Tap to end")

        current.value = state(activity = AgentActivity.Listening).copy(userVoiceLevel = 0.9f)
        rule.waitForIdle()
        rule.onNodeWithTag("mic-label").assertTextEquals("Live · Hearing you")

        current.value = state(activity = AgentActivity.Listening).copy(agent = AgentUi(AgentState.Listening, null, null, micMuted = true))
        rule.waitForIdle()
        rule.onNodeWithTag("mic-label").assertTextEquals("Muted · Composer can't hear you")
        rule.onNodeWithTag("mic-hint").assertTextEquals("Tap to end")
    }

    @Test
    fun liveButtonIsAStopButton() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(activity = AgentActivity.Speaking).copy(agent = AgentUi(AgentState.Speaking, null, null, micMuted = false)), intents)
        rule.onNodeWithTag("mic-label").assertTextEquals("Live · Composer speaking")
        rule.onNodeWithContentDescription("Stop session").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
    }

    @Test
    fun idleLabelInvitesToContinueAfterASession() {
        val off =
            state(activity = AgentActivity.Idle, transcript = listOf(TranscriptItem.Agent(1, "Hello")))
                .copy(agent = AgentUi(AgentState.Disconnected, null, null, micMuted = false))
        show(off)
        rule.onNodeWithTag("mic-label").assertTextEquals("Mic off · Tap to continue")
        rule.onNodeWithContentDescription("Talk to composer").assertExists()
        rule.onNodeWithTag("agent-error").assertDoesNotExist()
    }

    @Test
    fun restoredReplyShowsInFullWhenASessionStarts() {
        rule.mainClock.autoAdvance = false
        val off = AgentUi(AgentState.Disconnected, null, null, micMuted = false)
        val current = mutableStateOf(state(activity = AgentActivity.Idle).copy(agent = off))
        rule.setContent { ProducerScreen(current.value, onIntent = {}) }
        rule.mainClock.advanceTimeByFrame()
        current.value = state(activity = AgentActivity.Idle, transcript = listOf(TranscriptItem.Agent(1, reply))).copy(agent = off)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText(reply).assertExists()
        current.value = speaking(listOf(TranscriptItem.Agent(1, reply)), speaking = false)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("transcript-revealing").assertDoesNotExist()
        rule.onNodeWithText(reply).assertExists()
    }

    @Test
    fun micLabelShowsConnecting() {
        show(state(activity = AgentActivity.Connecting))
        rule.onNodeWithTag("mic-label").assertTextEquals("Connecting…")
    }

    @Test
    fun chipsShowTheTrackStyles() {
        show(state().copy(chips = listOf(StyleChip("synthwave"), StyleChip("warm pads"))))
        rule.onNodeWithTag("chip-synthwave").assertTextEquals("synthwave")
        rule.onNodeWithTag("chip-warm pads").assertTextEquals("warm pads")
    }

    @Test
    fun agentErrorIsShown() {
        show(state().copy(agent = AgentUi(AgentState.Disconnected, null, null, micMuted = false, error = "No network")))
        rule.onNodeWithTag("agent-error").assertTextEquals("No network")
    }

    @Test
    fun transcriptFollowsNewestLineOnceCapped() {
        fun lines(range: IntRange) = range.map { TranscriptItem.User(it.toLong(), "line $it") }
        val current = mutableStateOf(state(transcript = lines(1..30)))
        rule.setContent { ProducerScreen(current.value, positionMs = { 30_000L }, onIntent = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("line 30").assertIsDisplayed()
        rule.onNodeWithTag("transcript").performScrollToIndex(0)
        rule.waitForIdle()
        current.value = state(transcript = lines(2..31))
        rule.waitForIdle()
        rule.onNodeWithText("line 31").assertIsDisplayed()
    }

    @Test
    fun muteButtonSendsToggleMic() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("mute-button").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleMic), intents)
    }

    @Test
    fun muteLabelFlipsWithMicMuted() {
        show(withAgent(AgentUi(AgentState.Listening, null, null, micMuted = false)))
        rule.onNodeWithText("Mute mic").assertExists()
        rule.onNodeWithText("Unmute mic").assertDoesNotExist()
    }

    @Test
    fun unmuteLabelShownWhenMuted() {
        show(withAgent(AgentUi(AgentState.Listening, null, null, micMuted = true)))
        rule.onNodeWithText("Unmute mic").assertExists()
        rule.onNodeWithText("Mute mic").assertDoesNotExist()
    }

    @Test
    fun muteButtonHiddenWhenAgentDisconnected() {
        show(withAgent(AgentUi(AgentState.Disconnected, null, null, micMuted = false)))
        rule.onNodeWithTag("mute-button").assertDoesNotExist()
    }

    @Test
    fun muteButtonHiddenWhileConnecting() {
        show(withAgent(AgentUi(AgentState.Connecting, null, null, micMuted = false)))
        rule.onNodeWithTag("mute-button").assertDoesNotExist()
    }

    @Test
    fun muteButtonHiddenWithoutMicPermission() {
        show(withAgent(AgentUi(AgentState.Listening, null, null, micMuted = false), micPermission = false))
        rule.onNodeWithTag("mute-button").assertDoesNotExist()
    }

    @Test
    fun apiPillSendsToggleIntentAndShowsCount() {
        val intents = mutableListOf<ProducerIntent>()
        showWithLog(state(), listOf(planEntry, planEntry.copy(id = 2)), intents)
        rule.onNodeWithTag("api-badge", useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithTag("api-button").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleApiLog), intents)
        rule.onNodeWithTag("api-panel").assertDoesNotExist()
    }

    @Test
    fun apiPillHasNoBadgeWithoutEntries() {
        show(state())
        rule.onNodeWithTag("api-button").assertExists()
        rule.onNodeWithTag("api-badge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun panelShowsEntryHeaderAndExpandsOnTap() {
        showWithLog(state().copy(showApiLog = true), listOf(planEntry))
        rule.onNodeWithTag("api-panel").assertIsDisplayed()
        rule.onNodeWithText("music-plan").assertIsDisplayed()
        rule.onNodeWithText("01:02:03").assertExists()
        rule.onNodeWithTag("api-status-1", useUnmergedTree = true).assertTextEquals("200")
        rule.onNodeWithText("840ms").assertExists()
        rule.onNodeWithText("song-abc").assertExists()
        rule.onNodeWithTag("api-request-1", useUnmergedTree = true).assertDoesNotExist()

        rule.onNodeWithTag("api-entry-1").performClick()
        rule.onNodeWithTag("api-request-1", useUnmergedTree = true).assertTextEquals("{\"prompt\":\"synthwave\"}")
        rule.onNodeWithTag("api-response-1", useUnmergedTree = true).assertTextEquals("{\"chunks\":[]}")
        rule.onNodeWithTag("api-copy-1").assertExists()

        rule.onNodeWithTag("api-entry-1").performClick()
        rule.onNodeWithTag("api-request-1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun panelShowsInFlightAndErrorStatus() {
        val inFlight = ApiLogEntry(2, 0, "music-compose", "{}")
        val failed = ApiLogEntry(3, 0, "agent-token", "GET", status = 502, durationMs = 5, error = "boom")
        showWithLog(state().copy(showApiLog = true), listOf(inFlight, failed))
        rule.onNodeWithTag("api-status-2", useUnmergedTree = true).assertTextEquals("…")
        rule.onNodeWithTag("api-status-3", useUnmergedTree = true).assertTextEquals("502")
        rule.onNodeWithTag("api-entry-3").performClick()
        rule.onNodeWithTag("api-response-3", useUnmergedTree = true).assertTextEquals("Error: boom")
    }

    @Test
    fun closeButtonSendsToggleIntent() {
        val intents = mutableListOf<ProducerIntent>()
        showWithLog(state().copy(showApiLog = true), emptyList(), intents)
        rule.onNodeWithTag("api-empty", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("api-close").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleApiLog), intents)
    }

    @Test
    fun copyTextHasRequestAndResponse() {
        assertEquals(
            "music-plan 200 840ms song-id=song-abcdef123\nRequest:\n{\"prompt\":\"synthwave\"}\nResponse:\n{\"chunks\":[]}",
            planEntry.copyText(),
        )
    }

    @Test
    fun apiPillStaysReachableAndClosesOpenPanel() {
        val intents = mutableListOf<ProducerIntent>()
        showWithLog(state().copy(showApiLog = true), listOf(planEntry), intents)
        rule.onNodeWithTag("api-panel").assertIsDisplayed()
        rule.onNodeWithTag("api-button").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleApiLog), intents)
    }

    @Test
    fun panelFollowsNewestEntryAtTheCap() {
        fun entry(id: Long) = planEntry.copy(id = id)
        val log = mutableStateOf((1L..50L).map(::entry))
        rule.setContent {
            ProducerScreen(state().copy(showApiLog = true), positionMs = { 30_000L }, onIntent = {}, apiLog = log.value.toImmutableList())
        }
        rule.onNodeWithTag("api-list", useUnmergedTree = true).performScrollToIndex(0)
        rule.onNodeWithTag("api-entry-1").assertIsDisplayed()
        log.value = (2L..51L).map(::entry)
        rule.waitForIdle()
        rule.onNodeWithTag("api-entry-51").assertIsDisplayed()
    }
}
