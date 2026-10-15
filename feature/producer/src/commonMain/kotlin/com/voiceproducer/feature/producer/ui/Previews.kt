package com.voiceproducer.feature.producer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.AgentUi
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SectionStatus
import com.voiceproducer.feature.producer.SectionUi
import com.voiceproducer.feature.producer.StyleChip
import com.voiceproducer.feature.producer.ToolKind
import com.voiceproducer.feature.producer.ToolStatus
import com.voiceproducer.feature.producer.TrackStatus
import com.voiceproducer.feature.producer.TranscriptItem

private val agentOn = AgentUi(AgentState.Listening, lastUserText = null, lastAgentText = null, micMuted = false)

private val previewSections =
    listOf(
        SectionUi(0, "Intro", 0, 16_000, SectionStatus.Idle),
        SectionUi(1, "Verse", 16_000, 48_000, SectionStatus.Idle),
        SectionUi(2, "Chorus", 48_000, 72_000, SectionStatus.Idle, version = 2, lastTag = "heavier"),
        SectionUi(3, "Verse", 72_000, 104_000, SectionStatus.Regenerating),
        SectionUi(4, "Chorus", 104_000, 128_000, SectionStatus.Queued, version = 2, lastTag = "heavier"),
        SectionUi(5, "Outro", 128_000, 144_000, SectionStatus.Idle),
    )

private val previewTranscript =
    listOf(
        TranscriptItem.User(1, "Something like a night drive, warm synths, slow."),
        TranscriptItem.Agent(2, "Sketching a 96 BPM synthwave loop — intro, verse, chorus."),
        TranscriptItem.Tool(3, ToolKind.Compose, null, null, ToolStatus.Done),
        TranscriptItem.User(4, "Make the chorus heavier, and a baritone on the second verse."),
        TranscriptItem.Tool(5, ToolKind.EditSection, "Chorus", "heavier drums", ToolStatus.Done),
        TranscriptItem.Tool(6, ToolKind.EditSection, "Verse 2", "male baritone", ToolStatus.Running),
        TranscriptItem.Tool(7, ToolKind.SetGlobal, null, "no vocals", ToolStatus.Failed),
        TranscriptItem.Agent(8, "Reworking the chorus now; you'll hear it next time round."),
    )

internal val emptyPreviewState = ProducerUiState(micPermission = true)

internal val generatingPreviewState =
    ProducerUiState(
        track = TrackStatus.Generating,
        title = "Night drive",
        agent = agentOn.copy(state = AgentState.Speaking),
        agentActivity = AgentActivity.Speaking,
        micPermission = true,
        transcript = previewTranscript.take(2),
    )

internal val playingPreviewState =
    ProducerUiState(
        track = TrackStatus.Ready,
        title = "Night drive",
        sections = previewSections,
        durationMs = 144_000,
        isPlaying = true,
        agent = agentOn.copy(state = AgentState.Speaking),
        agentActivity = AgentActivity.Speaking,
        canUndo = true,
        micPermission = true,
        transcript = previewTranscript,
        chips = listOf(StyleChip("synthwave"), StyleChip("96 bpm"), StyleChip("warm pads")),
        musicLevel = 0.5f,
    )

internal val listeningDuckedPreviewState =
    playingPreviewState.copy(
        agent = agentOn,
        agentActivity = AgentActivity.Listening,
        musicLevel = 0.13f,
        ducked = true,
        liveUserText = "and drop the vocals in the",
    )

internal val hearingYouPreviewState = listeningDuckedPreviewState.copy(userVoiceLevel = 0.9f)

internal val connectingPreviewState = emptyPreviewState.copy(agentActivity = AgentActivity.Connecting)

internal val manyPillsPreviewState =
    playingPreviewState.copy(
        chips =
            listOf(
                "synthwave",
                "96 bpm",
                "warm pads",
                "night drive",
                "analog bass",
                "gated reverb",
                "no vocals",
                "female alto",
                "tape hiss",
                "minor key",
            ).map(::StyleChip),
    )

internal val noMicPreviewState = emptyPreviewState.copy(micPermission = false)

internal val errorsPreviewState =
    ProducerUiState(
        track = TrackStatus.Error("Generation failed: the music service timed out"),
        title = "Night drive",
        agent = AgentUi(AgentState.Disconnected, null, null, micMuted = false, error = "Can't reach the composer — check the network"),
        micPermission = true,
        transcript = previewTranscript.take(1),
    )

internal val sectionFailedPreviewState =
    playingPreviewState.copy(
        agent = AgentUi(AgentState.Disconnected, null, null, micMuted = false),
        agentActivity = AgentActivity.Idle,
        sections =
            previewSections.map {
                if (it.index == 3) it.copy(status = SectionStatus.Failed) else it.copy(status = SectionStatus.Idle)
            },
        transcript =
            previewTranscript.take(2) +
                listOf(
                    TranscriptItem.Tool(10, ToolKind.EditSection, "Verse", "driving acoustic guitar", ToolStatus.Failed),
                    TranscriptItem.Notice(11, "Couldn't regenerate Verse: Not enough ElevenLabs credits: 137 left, 325 needed"),
                ),
    )

@Preview(name = "Section failed", widthDp = 392, heightDp = 820)
@Composable
private fun SectionFailedPreview() = PreviewScreen(sectionFailedPreviewState)

@Preview(name = "Empty", widthDp = 392, heightDp = 820)
@Composable
private fun EmptyPreview() = PreviewScreen(emptyPreviewState)

@Preview(name = "Generating", widthDp = 392, heightDp = 820)
@Composable
private fun GeneratingPreview() = PreviewScreen(generatingPreviewState)

@Preview(name = "Playing, edited + regenerating", widthDp = 392, heightDp = 820)
@Composable
private fun PlayingPreview() = PreviewScreen(playingPreviewState)

@Preview(name = "Listening, ducked", widthDp = 392, heightDp = 820)
@Composable
private fun ListeningDuckedPreview() = PreviewScreen(listeningDuckedPreviewState)

@Preview(name = "Hearing you", widthDp = 392, heightDp = 820)
@Composable
private fun HearingYouPreview() = PreviewScreen(hearingYouPreviewState)

@Preview(name = "Connecting", widthDp = 392, heightDp = 820)
@Composable
private fun ConnectingPreview() = PreviewScreen(connectingPreviewState)

@Preview(name = "Many pills", widthDp = 392, heightDp = 820)
@Composable
private fun ManyPillsPreview() = PreviewScreen(manyPillsPreviewState)

@Preview(name = "No mic permission", widthDp = 392, heightDp = 820)
@Composable
private fun NoMicPreview() = PreviewScreen(noMicPreviewState)

@Preview(name = "Errors", widthDp = 392, heightDp = 820)
@Composable
private fun ErrorsPreview() = PreviewScreen(errorsPreviewState)
