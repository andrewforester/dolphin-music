package com.voiceproducer.feature.producer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.AgentToolHandler
import com.voiceproducer.core.domain.agent.DemoSwitch
import com.voiceproducer.core.domain.agent.ProducerCommands
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.edit.EditPipeline
import com.voiceproducer.core.domain.edit.EditPipelineFactory
import com.voiceproducer.core.domain.edit.PipelineEvent
import com.voiceproducer.core.domain.plan.EditResult
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.reason
import com.voiceproducer.core.domain.repository.ApiLogFeed
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.domain.runCatchingExceptions
import com.voiceproducer.core.domain.runCatchingIncludingCancellation
import com.voiceproducer.core.domain.usecase.ComposeTrackUseCase
import com.voiceproducer.core.domain.usecase.EndAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.ExplainAgentRefusalUseCase
import com.voiceproducer.core.domain.usecase.RestoreLatestTrackUseCase
import com.voiceproducer.core.domain.usecase.StartAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.StartNewTrackUseCase
import com.voiceproducer.core.model.ApiLogEntry
import com.voiceproducer.core.model.ProxyError
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProducerViewModel(
    private val composeTrackUseCase: ComposeTrackUseCase,
    private val startNewTrack: StartNewTrackUseCase,
    private val restoreLatestTrack: RestoreLatestTrackUseCase,
    startAgentSession: StartAgentSessionUseCase,
    explainAgentRefusal: ExplainAgentRefusalUseCase,
    private val endAgentSession: EndAgentSessionUseCase,
    pipelines: EditPipelineFactory,
    private val library: TrackLibrary,
    private val engine: AudioEngine,
    private val agent: VoiceAgent,
    history: ConversationHistory,
    apiLog: ApiLogFeed,
    private val clock: () -> Long,
    demo: DemoSwitch? = null,
) : ViewModel(),
    ProducerCommands {
    val apiLog: StateFlow<List<ApiLogEntry>> = apiLog.entries

    private val _state = MutableStateFlow(ProducerUiState(demoAvailable = demo != null))
    val state: StateFlow<ProducerUiState> = _state.asStateFlow()

    val positionMs: StateFlow<Long> = engine.positionMs

    private val pipeline = pipelines.create(viewModelScope)
    private val transcript = TranscriptLog(_state, history)
    private val sections = SectionTracker(_state, engine)
    private val playhead = PlayheadNotices(agent, engine) { current }
    private val session =
        AgentSession(
            agent,
            AgentToolHandler(this),
            startAgentSession,
            explainAgentRefusal,
            endAgentSession,
            _state,
            viewModelScope,
            playhead,
            transcript,
            DuckPresenter(engine, _state, viewModelScope, clock),
            demo,
        ) { current }

    private var current: TrackVersion? = null
    private var generating = false

    private var clearing = false

    private var submitting = false

    init {
        viewModelScope.launch {
            restoreLatest()
            library.deleteOrphanMastersQuietly()
        }
        viewModelScope.launch { transcript.runWrites() }
        viewModelScope.launch {
            combine(engine.durationMs, engine.isPlaying, ::Pair).collect { (dur, playing) ->
                _state.update { it.copy(durationMs = dur, isPlaying = playing) }
            }
        }
        viewModelScope.launch { engine.positionMs.collect(playhead::onPosition) }
        viewModelScope.launch { engine.isPlaying.drop(1).collect { playhead.send(engine.positionMs.value) } }
        viewModelScope.launch { pipeline.events.collect(::onPipelineEvent) }
        viewModelScope.launch { engine.swapsApplied.collect(sections::onSwapsApplied) }
        viewModelScope.launch { agent.state.collect(session::onState) }
        viewModelScope.launch { agent.events.collect(session::onEvent) }
    }

    fun onIntent(intent: ProducerIntent) {
        when (intent) {
            ProducerIntent.ToggleAgent -> {
                session.toggle()
            }

            ProducerIntent.StartDemo -> {
                session.startDemo()
            }

            ProducerIntent.TogglePlay -> {
                if (current != null) {
                    if (engine.isPlaying.value) engine.pause() else engine.play()
                }
            }

            ProducerIntent.Undo -> {
                viewModelScope.launch { revert() }
            }

            ProducerIntent.NewTrack -> {
                newTrack()
            }

            ProducerIntent.ToggleApiLog -> {
                _state.update { it.copy(showApiLog = !it.showApiLog) }
            }

            ProducerIntent.ToggleMic -> {
                session.toggleMic()
            }

            is ProducerIntent.SeekToSection, is ProducerIntent.SeekToMs, ProducerIntent.PrevSection, ProducerIntent.NextSection -> {
                engine.navigate(_state.value.sections, intent, playhead::seek)
            }

            is ProducerIntent.SendText -> {
                intent.text
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(agent::sendUserText)
            }

            is ProducerIntent.MicPermission -> {
                _state.update { it.copy(micPermission = intent.granted) }
            }
        }
    }

    override suspend fun composeTrack(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): ToolResult =
        onMain {
            val line = transcript.addTool(ToolKind.Compose)
            val refusal =
                when {
                    generating -> "a track is already generating; wait for it"
                    clearing -> "the timeline is being cleared; try again shortly"
                    submitting || pipeline.busy.value -> "an edit is still in progress; try again shortly"
                    else -> null
                }
            if (refusal != null) return@onMain transcript.refuse(line, refusal)
            generating = true
            _state.update { it.copy(track = TrackStatus.Generating) }
            viewModelScope.launch {
                try {
                    runCatchingExceptions {
                        val (version, master) = composeTrackUseCase(prompt, instrumental, lengthMs)
                        engine.play()
                        pipeline.init(version, master)
                        sections.reset()
                        current = version
                        transcript.startHistory(version.trackId)
                        val title = library.titleOrNull(version.trackId)
                        _state.update { it.copy(track = TrackStatus.Ready, title = title) }
                        _state.showVersion(version, keepStatuses = false)
                        transcript.finishTool(line, landed = true)
                        agent.sendContext(AgentNotices.trackReady(version.plan))
                    }.onFailure { e ->
                        val reason = e.reason()
                        _state.update { it.copy(track = TrackStatus.Error(reason)) }
                        transcript.finishTool(line, landed = false)
                        agent.sendUserText(AgentNotices.generationFailed(reason, e as? ProxyError.BadPrompt))
                    }
                } finally {
                    generating = false
                }
            }
            ToolResult(true, "ok: generating, about 15 seconds")
        }

    override suspend fun edit(edit: PlanEdit): ToolResult =
        onMain {
            val line = toolLineFor(++transcript.lastItemId, edit).also(transcript::add).id
            val refusal =
                when {
                    generating -> "a new track is still generating; wait for it"
                    clearing -> "the timeline is being cleared; try again shortly"
                    current == null -> "there is no track yet; call compose_track first"
                    else -> null
                }
            if (refusal != null) return@onMain transcript.refuse(line, refusal)
            submitting = true
            val result =
                try {
                    runCatchingIncludingCancellation { pipeline.submit(edit) { landed -> transcript.finishTool(line, landed) } }
                        .onFailure { transcript.finishTool(line, landed = false) }
                        .getOrThrow()
                } finally {
                    submitting = false
                }
            when (result) {
                is EditResult.Applied -> {
                    val planSections = SectionIndex.sections(result.plan)
                    val names =
                        result.changedSections
                            .map { planSections[it].name }
                            .distinct()
                            .joinToString(", ")
                    if (edit is PlanEdit.EditSection) transcript.updateTool(line) { it.copy(section = names) }
                    val n = result.changedSections.size
                    ToolResult(true, "ok: regenerating $names ($n ${if (n == 1) "occurrence" else "occurrences"})")
                }

                is EditResult.UnknownSection -> {
                    transcript.refuse(
                        line,
                        "unknown section '${result.name}'; available: ${result.available.joinToString(", ")}",
                    )
                }

                EditResult.NoChange -> {
                    ToolResult(true, "no change needed").also { transcript.finishTool(line, landed = false) }
                }
            }
        }

    override suspend fun undo(): ToolResult = onMain { revert(line = transcript.addTool(ToolKind.Undo)) }

    private suspend fun revert(line: Long? = null): ToolResult =
        onMain {
            fun finish(landed: Boolean) = line?.let { transcript.finishTool(it, landed) }
            when {
                generating -> {
                    ToolResult(false, "a new track is still generating; wait for it").also { finish(false) }
                }

                clearing -> {
                    ToolResult(false, "the timeline is being cleared; try again shortly").also { finish(false) }
                }

                current == null -> {
                    ToolResult(false, "nothing to undo").also { finish(false) }
                }

                submitting || pipeline.busy.value -> {
                    viewModelScope.launch { pipeline.undoAfterEdit(agent, ::finish) }
                    ToolResult(true, "ok: will revert after the current edit finishes")
                }

                else -> {
                    val reverted =
                        runCatchingIncludingCancellation { pipeline.undo() }
                            .onFailure { finish(false) }
                            .getOrThrow()
                    finish(reverted)
                    if (reverted) ToolResult(true, "ok: reverted") else ToolResult(false, "nothing to undo")
                }
            }
        }

    private fun newTrack() {
        val editing = submitting || pipeline.busy.value
        val occupied = generating || clearing || editing
        if (current == null || occupied) return
        clearing = true
        viewModelScope.launch {
            try {
                runCatchingExceptions {
                    startNewTrack(transcript::startPending)
                    pipeline.reset()
                    sections.reset()
                    current = null
                    _state.update {
                        it.copy(
                            track = TrackStatus.Empty,
                            title = null,
                            sections = emptyList(),
                            canUndo = false,
                            chips = emptyList(),
                            liveUserText = null,
                            transcript = emptyList(),
                        )
                    }
                    if (agent.state.value != AgentState.Disconnected) agent.sendContext(AgentNotices.TIMELINE_CLEARED)
                }.onFailure { e ->
                    val error = "Could not start a new track: ${e.reason()}"
                    _state.update { it.copy(agent = it.agent.copy(error = error)) }
                }
            } finally {
                clearing = false
            }
        }
    }

    override fun onCleared() {
        engine.pause()
        endAgentSession()
    }

    private suspend fun restoreLatest() {
        runCatchingExceptions {
            var latestKnown = false
            var title: String? = null
            val restored =
                runCatchingExceptions {
                    restoreLatestTrack(
                        onLatest = { version ->
                            latestKnown = true
                            transcript.restore(version?.trackId) { current != null }
                        },
                        shouldLoad = { version ->
                            title = library.titleOrNull(version.trackId)
                            !generating && current == null
                        },
                    )
                }.onFailure {
                    if (!latestKnown) transcript.restore(null) { current != null }
                }.getOrThrow()
            val (version, master) = restored ?: return
            pipeline.init(version, master)
            sections.reset()
            current = version
            _state.update { it.copy(track = TrackStatus.Ready, title = title) }
            _state.showVersion(version, keepStatuses = false)
        }.onFailure { e ->
            if (!generating) _state.update { it.copy(track = TrackStatus.Error(e.reason())) }
        }
    }

    private fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.SectionRegenerating -> {
                _state.setStatus(event.index, SectionStatus.Regenerating)
            }

            is PipelineEvent.SectionDone -> {
                sections.onDone(event.index, event.edit, event.swaps)
            }

            is PipelineEvent.SectionFailed -> {
                sections.onFailed(event.index)
                val name =
                    _state.value.sections
                        .getOrNull(event.index)
                        ?.name ?: "section ${event.index + 1}"
                transcript.noticeFailure(name, event.reason)
                val badPrompt = event.badPrompt
                if (badPrompt != null) {
                    agent.sendUserText(AgentNotices.editRejected(name, badPrompt))
                } else {
                    agent.sendContext(AgentNotices.regenerationFailed(name, event.reason))
                }
            }

            is PipelineEvent.VersionSaved -> {
                val edited = sections.takeUnsaved()
                current = event.version
                _state.showVersion(event.version, keepStatuses = true)
                agent.sendContext(AgentNotices.planUpdated(event.version.plan))
                jumpToEarliest(event.version, edited, playhead::seek)
            }

            is PipelineEvent.VersionRestored -> {
                val before = current?.plan?.chunks.orEmpty()
                current = event.version
                _state.showVersion(event.version, keepStatuses = true, clearFailed = true)
                val chunks = event.version.plan.chunks
                val changed = chunks.indices.filter { before.getOrNull(it) != chunks[it] }
                changed.forEach(sections::revertSection)
                agent.sendContext(AgentNotices.reverted(event.version.plan))
                jumpToEarliest(event.version, changed, playhead::seek)
            }
        }
    }
}

private fun TranscriptLog.refuse(
    id: Long,
    reason: String,
): ToolResult {
    finishTool(id, landed = false)
    return ToolResult(false, reason)
}

private suspend fun EditPipeline.undoAfterEdit(
    agent: VoiceAgent,
    finish: (Boolean) -> Unit,
) {
    runCatchingExceptions {
        val reverted = undo()
        finish(reverted)
        if (!reverted) agent.sendContext(AgentNotices.NOTHING_TO_UNDO)
    }.onFailure { e ->
        finish(false)
        agent.sendContext(AgentNotices.undoFailed(e.reason()))
    }
}

private suspend fun TrackLibrary.deleteOrphanMastersQuietly() {
    try {
        deleteOrphanMasters()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

private suspend fun TrackLibrary.titleOrNull(trackId: Long): String? =
    try {
        title(trackId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
