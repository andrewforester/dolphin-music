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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.model.ApiLogEntry
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SectionStatus
import com.voiceproducer.feature.producer.SectionUi
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
class ProducerScreenTest : ProducerScreenTestBase() {
    @Test
    fun tappingTimelineSeeksToThatPosition() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("timeline").performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        val seek = intents.single() as ProducerIntent.SeekToMs
        assertTrue("seek ${seek.ms} ≈ 12000", abs(seek.ms - 12_000) < 500)
    }

    @Test
    fun titleIsCappedAtTwoLines() {
        assertEquals(2, titleLines("One\nTwo\nThree\nFour"))
    }

    @Test
    fun shortTitleStaysOnOneLine() {
        assertEquals(1, titleLines("Short"))
    }

    @Test
    fun draggingTimelineScrubsThePositionAndSeeksOnceOnRelease() {
        val intents = mutableListOf<ProducerIntent>()
        val position = mutableLongStateOf(30_000)
        rule.setContent { ProducerScreen(state(), positionMs = { position.longValue }, onIntent = { intents += it }) }
        rule.onNodeWithTag("timeline").performTouchInput { down(Offset(width * 0.1f, height / 2f)) }
        rule.onNodeWithTag("position").assertTextEquals("0:04 · Intro")
        rule.onNodeWithTag("timeline").performTouchInput { moveTo(Offset(width * 0.5f, height / 2f)) }
        rule.onNodeWithTag("position").assertTextEquals("0:24 · Chorus")
        rule.onNodeWithTag("timeline").performTouchInput { moveTo(Offset(width * 0.25f, height / 2f)) }
        rule.onNodeWithTag("position").assertTextEquals("0:12 · Verse 1")
        assertTrue("no seek while dragging", intents.isEmpty())
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        val seek = intents.single() as ProducerIntent.SeekToMs
        assertTrue("seek ${seek.ms} ≈ 12000", abs(seek.ms - 12_000) < 500)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("position").assertTextEquals("0:12 · Verse 1")
        position.longValue = seek.ms + 100
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(50)
        position.longValue = 20_000
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("position").assertTextEquals("0:20 · Verse 1")
    }

    @Test
    fun releasedPositionIsDroppedAfterTheTimeoutIfTheEngineNeverCatchesUp() {
        show(state())
        rule.onNodeWithTag("timeline").performTouchInput { down(Offset(width * 0.25f, height / 2f)) }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("position").assertTextEquals("0:12 · Verse 1")
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithTag("position").assertTextEquals("0:30 · Chorus")
    }

    @Test
    fun cancelledDragLeavesScrubModeWithoutSeeking() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            moveTo(Offset(width * 0.5f, height / 2f))
        }
        rule.onNodeWithTag("position").assertTextEquals("0:24 · Chorus")
        rule.onNodeWithTag("timeline").performTouchInput { cancel() }
        rule.onNodeWithTag("position").assertTextEquals("0:30 · Chorus")
        assertTrue("no seek on cancel", intents.isEmpty())
    }

    @Test
    fun draggingPastTheEdgesIsClamped() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * 0.5f, height / 2f))
            moveTo(Offset(width * 2f, height / 2f))
            up()
        }
        assertEquals(48_000L, (intents.single() as ProducerIntent.SeekToMs).ms)
    }

    @Test
    fun timelineDescribesSectionAndPosition() {
        show(state())
        rule.onNodeWithTag("timeline").assertContentDescriptionEquals("Timeline, Chorus, 0:30 of 0:48")
        rule.onNodeWithTag("position").assertTextEquals("0:30 · Chorus")
    }

    @Test
    fun transportButtonsSendTheirIntents() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithContentDescription("Previous section").performClick()
        rule.onNodeWithContentDescription("Pause").performClick()
        rule.onNodeWithContentDescription("Next section").performClick()
        assertEquals(listOf(ProducerIntent.PrevSection, ProducerIntent.TogglePlay, ProducerIntent.NextSection), intents)
    }

    @Test
    fun micButtonSendsToggleAgent() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("mic-button").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.ToggleAgent), intents)
    }

    @Test
    fun undoPillDisabledWhenCannotUndo() {
        show(state(canUndo = false))
        rule.onNodeWithTag("undo-button").assertIsNotEnabled()
    }

    @Test
    fun undoPillEnabledAndSendsIntent() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(canUndo = true), intents)
        rule.onNodeWithTag("undo-button").assertIsEnabled().performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.Undo), intents)
    }

    @Test
    fun newTrackPillIsHiddenWithoutSections() {
        show(state(sections = emptyList(), track = TrackStatus.Empty))
        rule.onNodeWithTag("new-track-button").assertDoesNotExist()
    }

    @Test
    fun newTrackPillIsDisabledWhileGeneratingOrRegenerating() {
        show(state(track = TrackStatus.Generating))
        rule.onNodeWithTag("new-track-button").assertIsNotEnabled()
    }

    @Test
    fun newTrackPillIsDisabledWhileASectionRegenerates() {
        show(state(sections = sections(SectionUi(2, "Chorus", 24_000, 40_000, SectionStatus.Regenerating))))
        rule.onNodeWithTag("new-track-button").assertIsNotEnabled()
    }

    @Test
    fun newTrackPillIsEnabledForAReadyTrack() {
        show(state())
        rule.onNodeWithTag("new-track-button").assertIsEnabled()
    }

    @Test
    fun tappingNewTrackAsksFirstAndSendsNothing() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
        rule.onNodeWithTag("new-track-button").performClick()
        rule.onNodeWithTag("new-track-dialog").assertIsDisplayed()
        rule.onNodeWithText("Start a new track?").assertIsDisplayed()
        rule
            .onNodeWithText(
                "The current track will be cleared from the timeline. It stays saved, but you won't be able to reopen it from here.",
            ).assertIsDisplayed()
        assertTrue(intents.isEmpty())
    }

    @Test
    fun cancelClosesTheConfirmationAndSendsNothing() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("new-track-button").performClick()
        rule.onNodeWithTag("new-track-cancel").performClick()
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
        assertTrue(intents.isEmpty())
    }

    @Test
    fun scrimTapClosesTheConfirmationAndSendsNothing() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("new-track-button").performClick()
        rule.onNodeWithTag("new-track-scrim").performTouchInput { click(Offset(5f, 5f)) }
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
        assertTrue(intents.isEmpty())
    }

    @Test
    fun backClosesTheConfirmationAndSendsNothing() {
        val intents = mutableListOf<ProducerIntent>()
        var onBack: (() -> Unit)? = null
        rule.setContent {
            ProducerScreen(
                state(),
                onIntent = { intents += it },
                backHandler = { enabled, back -> if (enabled) onBack = back },
            )
        }
        assertEquals(null, onBack)
        rule.onNodeWithTag("new-track-button").performClick()
        rule.runOnIdle { onBack!!.invoke() }
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
        assertTrue(intents.isEmpty())
    }

    @Test
    fun confirmIsDisabledAndDialogClosesWhenTheTrackGoesAway() {
        val intents = mutableListOf<ProducerIntent>()
        val shown = mutableStateOf(state())
        rule.setContent { ProducerScreen(shown.value, positionMs = { 30_000L }, onIntent = { intents += it }) }
        rule.onNodeWithTag("new-track-button").performClick()
        shown.value = state(track = TrackStatus.Generating)
        rule.waitForIdle()
        rule.onNodeWithTag("new-track-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("new-track-confirm").performClick()
        assertTrue(intents.isEmpty())
        shown.value = state(sections = emptyList(), track = TrackStatus.Empty)
        rule.waitForIdle()
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
        shown.value = state()
        rule.waitForIdle()
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
    }

    @Test
    fun confirmSendsExactlyOneNewTrackAndCloses() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(), intents)
        rule.onNodeWithTag("new-track-button").performClick()
        rule.onNodeWithTag("new-track-confirm").performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.NewTrack), intents)
        rule.onNodeWithTag("new-track-dialog").assertDoesNotExist()
    }

    @Test
    fun textInputWithoutPermissionSendsTrimmedTextAndClears() {
        val intents = mutableListOf<ProducerIntent>()
        show(state(micPermission = false), intents)
        rule.onNodeWithTag("send-button").assertIsNotEnabled()
        rule.onNodeWithTag("text-input").performTextInput("  heavier chorus  ")
        rule.onNodeWithTag("send-button").assertIsEnabled().performClick()
        assertEquals(listOf<ProducerIntent>(ProducerIntent.SendText("heavier chorus")), intents)
        rule.onNodeWithTag("text-input").assertTextEquals("")
    }

    @Test
    fun textInputHiddenWithMicPermission() {
        show(state(micPermission = true))
        rule.onNodeWithTag("text-input").assertDoesNotExist()
    }

    @Test
    fun regeneratingSectionShowsSubLabel() {
        show(state(sections(SectionUi(2, "Chorus", 24_000, 40_000, SectionStatus.Regenerating))))
        rule.onNodeWithText("regenerating…").assertExists()
    }

    @Test
    fun queuedSectionShowsNextVersion() {
        show(state(sections(SectionUi(2, "Chorus", 24_000, 40_000, SectionStatus.Queued, version = 2, lastTag = "heavier"))))
        rule.onNodeWithText("v3 queued").assertExists()
    }

    @Test
    fun failedSectionShowsSubLabel() {
        show(state(sections(SectionUi(2, "Chorus", 24_000, 40_000, SectionStatus.Failed))))
        rule.onNodeWithText("failed").assertExists()
    }

    @Test
    fun editedSectionShowsVersionAndTag() {
        show(state(sections(SectionUi(2, "Chorus", 24_000, 40_000, SectionStatus.Idle, version = 2, lastTag = "heavier"))))
        rule.onNodeWithText("v2 · heavier").assertExists()
    }

    @Test
    fun generatingTrackShowsComposingOnEverySection() {
        show(state(track = TrackStatus.Generating))
        rule.onNodeWithTag("section-0").assertExists()
        assertEquals(4, rule.onAllNodesWithText("composing…").fetchSemanticsNodes().size)
    }

    @Test
    fun placeholderInsteadOfTimelineWithoutSections() {
        show(state(sections = emptyList(), track = TrackStatus.Empty))
        rule.onNodeWithTag("timeline").assertDoesNotExist()
        rule.onNodeWithText("sections appear here").assertExists()
    }

    @Test
    fun loaderShownWhileGeneratingWithoutSections() {
        show(state(sections = emptyList(), track = TrackStatus.Generating))
        rule.onNodeWithTag("timeline-loader").assertExists()
        rule.onNodeWithTag("timeline").assertDoesNotExist()
        rule.onNodeWithTag("timeline-placeholder").assertDoesNotExist()
    }

    @Test
    fun loaderHiddenWhenReadyEmptyOrError() {
        val shown = mutableStateOf(state())
        rule.setContent { ProducerScreen(shown.value, positionMs = { 30_000L }, onIntent = {}) }
        for (s in listOf(
            state(),
            state(sections = emptyList(), track = TrackStatus.Empty),
            state(sections = emptyList(), track = TrackStatus.Error("boom")),
            state(track = TrackStatus.Generating),
        )) {
            shown.value = s
            rule.waitForIdle()
            rule.onNodeWithTag("timeline-loader").assertDoesNotExist()
        }
    }

    @Test
    fun loaderCompletesAndGivesWayToTheTimelineWhenReady() {
        val shown = mutableStateOf(state(sections = emptyList(), track = TrackStatus.Generating))
        rule.setContent { ProducerScreen(shown.value, positionMs = { 30_000L }, onIntent = {}) }
        rule.onNodeWithTag("timeline-loader").assertExists()
        shown.value = state()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(LOADER_COMPLETE_MS + 100L)
        rule.waitForIdle()
        rule.onNodeWithTag("timeline-loader").assertDoesNotExist()
        rule.onNodeWithTag("timeline").assertExists()
    }

    @Test
    fun loaderDisappearsAtOnceOnError() {
        val shown = mutableStateOf(state(sections = emptyList(), track = TrackStatus.Generating))
        rule.setContent { ProducerScreen(shown.value, positionMs = { 30_000L }, onIntent = {}) }
        shown.value = state(sections = emptyList(), track = TrackStatus.Error("boom"))
        rule.waitForIdle()
        rule.onNodeWithTag("timeline-loader").assertDoesNotExist()
        rule.onNodeWithTag("track-error").assertTextEquals("boom")
    }

    @Test
    fun timelineShownEvenWhenTrackStatusIsError() {
        show(state(track = TrackStatus.Error("boom")))
        rule.onNodeWithTag("timeline").assertExists()
        rule.onNodeWithTag("track-error").assertTextEquals("boom")
    }

    @Test
    fun trackControlsSitJustAboveTheMic() {
        val lines = (1..30).map { TranscriptItem.User(it.toLong(), "line $it") }
        show(state(transcript = lines).copy(chips = listOf(StyleChip("synthwave"))))
        val pills = rule.onNodeWithTag("pills").getUnclippedBoundsInRoot()
        val home = rule.onNodeWithTag("orb-home").getUnclippedBoundsInRoot()
        assertEquals(16f, (home.top - pills.bottom).value, 1f)
        val transcript = rule.onNodeWithTag("transcript").getUnclippedBoundsInRoot()
        assertEquals(6f, transcript.top.value, 1f)
    }

    @Test
    fun transcriptTakesItsWholeHeightWithOneLine() {
        show(state(transcript = listOf(TranscriptItem.User(1, "hi"))))
        val transcript = rule.onNodeWithTag("transcript").getUnclippedBoundsInRoot()
        assertEquals(6f, transcript.top.value, 1f)
    }

    @Test
    fun pillsSpanTheScreenAndStartAtThePadding() {
        val chips = (1..30).map { StyleChip("style $it") }
        show(state().copy(chips = chips))
        val pills = rule.onNodeWithTag("pills").getUnclippedBoundsInRoot()
        assertEquals(0f, pills.left.value, 0.5f)
        assertEquals(392f, pills.right.value, 0.5f)
        assertEquals(
            26f,
            rule
                .onNodeWithTag("chip-style 1")
                .getUnclippedBoundsInRoot()
                .left.value,
            0.5f,
        )
    }

    @Test
    fun pillsKeepToTwoLinesAndScroll() {
        val chips = (1..30).map { StyleChip("style $it") }
        show(state().copy(chips = chips))
        val first = rule.onNodeWithTag("chip-style 1").getUnclippedBoundsInRoot()
        val tops = (1..30).map { rule.onNodeWithTag("chip-style $it").getUnclippedBoundsInRoot().top }.distinct()
        assertEquals(2, tops.size)
        rule.onNodeWithTag("pills").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("chip-style 1").getUnclippedBoundsInRoot().left < first.left)
    }
}
