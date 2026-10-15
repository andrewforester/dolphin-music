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
abstract class ProducerScreenTestBase {
    @get:Rule
    val rule = createComposeRule()

    protected fun sections(chorus: SectionUi = SectionUi(2, "Chorus", 24_000, 40_000, SectionStatus.Idle)) =
        listOf(
            SectionUi(0, "Intro", 0, 8_000, SectionStatus.Idle),
            SectionUi(1, "Verse 1", 8_000, 24_000, SectionStatus.Idle),
            chorus,
            SectionUi(3, "Bridge", 40_000, 48_000, SectionStatus.Idle),
        )

    protected fun state(
        sections: List<SectionUi> = sections(),
        track: TrackStatus = TrackStatus.Ready,
        canUndo: Boolean = true,
        micPermission: Boolean = true,
        transcript: List<TranscriptItem> = emptyList(),
        activity: AgentActivity = AgentActivity.Listening,
        ducked: Boolean = false,
    ) = ProducerUiState(
        track = track,
        title = "Dreamy synthwave",
        sections = sections,
        durationMs = 48_000,
        isPlaying = true,
        agent = AgentUi(AgentState.Listening, null, null, micMuted = false),
        canUndo = canUndo,
        micPermission = micPermission,
        transcript = transcript,
        agentActivity = activity,
        ducked = ducked,
    )

    protected fun show(
        state: ProducerUiState,
        intents: MutableList<ProducerIntent> = mutableListOf(),
    ) {
        rule.setContent { ProducerScreen(state, positionMs = { 30_000L }, onIntent = { intents += it }) }
    }

    protected fun titleLines(title: String): Int {
        rule.setContent { ProducerScreen(state().copy(title = title), positionMs = { 30_000L }, onIntent = {}) }
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule
            .onNodeWithTag("track-title")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.single().lineCount
    }

    protected val reply = "Reworking the chorus now with heavier drums and a darker bass line."

    protected fun speaking(
        transcript: List<TranscriptItem>,
        speaking: Boolean = true,
    ) = state(transcript = transcript).copy(
        agent = AgentUi(if (speaking) AgentState.Speaking else AgentState.Listening, null, null, micMuted = false),
    )

    protected fun withAgent(
        agent: AgentUi,
        micPermission: Boolean = true,
    ) = state(micPermission = micPermission).copy(agent = agent)

    protected fun showWithLog(
        state: ProducerUiState,
        apiLog: List<ApiLogEntry>,
        intents: MutableList<ProducerIntent> = mutableListOf(),
    ) {
        rule.setContent { ProducerScreen(state, positionMs = { 30_000L }, onIntent = { intents += it }, apiLog = apiLog.toImmutableList()) }
    }

    protected val planEntry =
        ApiLogEntry(
            1,
            3_723_000,
            "music-plan",
            "{\"prompt\":\"synthwave\"}",
            status = 200,
            durationMs = 840,
            response = "{\"chunks\":[]}",
            songId = "song-abcdef123",
        )
}
