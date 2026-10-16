package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentState

enum class SectionStatus { Idle, Regenerating, Queued, Failed }

enum class SectionKind { Intro, Verse, Chorus, Bridge, Outro, Other }

data class SectionUi(
    val index: Int,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val status: SectionStatus,
    val kind: SectionKind = sectionKind(name),
    val version: Int = 1,
    val lastTag: String? = null,
)

sealed interface TranscriptItem {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
    ) : TranscriptItem

    data class Agent(
        override val id: Long,
        val text: String,
    ) : TranscriptItem

    data class Tool(
        override val id: Long,
        val kind: ToolKind,
        val section: String?,
        val detail: String?,
        val status: ToolStatus,
    ) : TranscriptItem

    data class Notice(
        override val id: Long,
        val text: String,
    ) : TranscriptItem
}

data class AgentSpeech(
    val itemId: Long,
    val timeline: SpeechTimeline,
)

enum class ToolKind { Compose, EditSection, SetGlobal, Undo, NewTrack }

enum class ToolStatus { Running, Done, Failed }

data class StyleChip(
    val label: String,
)

enum class AgentActivity { Idle, Connecting, Listening, Thinking, Speaking }

sealed interface TrackStatus {
    data object Empty : TrackStatus

    data object Generating : TrackStatus

    data object Ready : TrackStatus

    data class Error(
        val message: String,
    ) : TrackStatus
}

data class AgentUi(
    val state: AgentState,
    val lastUserText: String?,
    val lastAgentText: String?,
    val micMuted: Boolean,
    val error: String? = null,
)

data class ProducerUiState(
    val track: TrackStatus = TrackStatus.Empty,
    val title: String? = null,
    val sections: List<SectionUi> = emptyList(),
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val agent: AgentUi = AgentUi(AgentState.Disconnected, lastUserText = null, lastAgentText = null, micMuted = false),
    val canUndo: Boolean = false,
    val micPermission: Boolean = false,
    val transcript: List<TranscriptItem> = emptyList(),
    val liveUserText: String? = null,
    val agentSpeech: AgentSpeech? = null,
    val chips: List<StyleChip> = emptyList(),
    val musicLevel: Float = 1f,
    val ducked: Boolean = false,
    val agentActivity: AgentActivity = AgentActivity.Idle,
    val userVoiceLevel: Float = 0f,
    val showApiLog: Boolean = false,
    val demoAvailable: Boolean = false,
)

sealed interface ProducerIntent {
    data object ToggleAgent : ProducerIntent

    data object StartDemo : ProducerIntent

    data object TogglePlay : ProducerIntent

    data object Undo : ProducerIntent

    data object ToggleMic : ProducerIntent

    data object ToggleApiLog : ProducerIntent

    data object NewTrack : ProducerIntent

    data class SeekToSection(
        val index: Int,
    ) : ProducerIntent

    data class SeekToMs(
        val ms: Long,
    ) : ProducerIntent

    data object PrevSection : ProducerIntent

    data object NextSection : ProducerIntent

    data class SendText(
        val text: String,
    ) : ProducerIntent

    data class MicPermission(
        val granted: Boolean,
    ) : ProducerIntent
}
