package com.voiceproducer.core.domain.edit

import com.voiceproducer.core.domain.plan.EditResult
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.plan.PlanEditor
import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.reason
import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.domain.runCatchingExceptions
import com.voiceproducer.core.domain.runCatchingIncludingCancellation
import com.voiceproducer.core.domain.window.WindowPlanner
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.ProxyError
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

sealed interface PipelineEvent {
    data class SectionRegenerating(
        val index: Int,
    ) : PipelineEvent

    data class SectionDone(
        val index: Int,
        val edit: PlanEdit,
        val swaps: Int,
    ) : PipelineEvent

    data class SectionFailed(
        val index: Int,
        val reason: String,
        val badPrompt: ProxyError.BadPrompt? = null,
    ) : PipelineEvent

    data class VersionSaved(
        val version: TrackVersion,
    ) : PipelineEvent

    data class VersionRestored(
        val version: TrackVersion,
    ) : PipelineEvent
}

class EditPipeline(
    private val store: TrackStore,
    private val engine: AudioEngine,
    private val planner: WindowPlanner = WindowPlanner(),
    private val edgeThreshold: Float = EDGE_SIMILARITY_THRESHOLD,
    private val scope: CoroutineScope,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val swapper = MasterSwapper(store, engine, planner, edgeThreshold, cpuDispatcher)
    private val edits = EditQueue()

    val busy: StateFlow<Boolean> = edits.busy

    private val work = Mutex()

    private val planLock = Mutex()

    @Volatile private lateinit var version: TrackVersion

    @Volatile private var loaded = false

    fun init(
        version: TrackVersion,
        master: PcmAudio,
    ) {
        loaded = true
        this.version = version
        swapper.master = master
        edits.editingPlan = version.plan
        swapper.swapsQueued = 0
    }

    fun reset() {
        loaded = false
        edits.queue.clear()
        edits.editingPlan = CompositionPlan(emptyList())
        swapper.swapsQueued = 0
    }

    suspend fun submit(
        edit: PlanEdit,
        onFinished: (landed: Boolean) -> Unit = {},
    ): EditResult {
        check(loaded) { "no track is loaded" }
        var queued: QueuedEdit? = null
        val result =
            planLock.withLock {
                PlanEditor.apply(edits.editingPlan, edit).also {
                    if (it is EditResult.Applied) {
                        edits.editingPlan = it.plan
                        queued = QueuedEdit(edit, it.changedSections).also(edits.queue::add)
                        edits.updateBusy()
                    }
                }
            }
        val job = queued ?: return result
        scope.launch {
            var landed = false
            try {
                landed = work.withLock { regenerate(job) }
            } finally {
                withContext(NonCancellable) {
                    planLock.withLock {
                        edits.queue.remove(job)
                        edits.resyncEditingPlan(version.plan)
                        edits.updateBusy()
                    }
                }
                onFinished(landed)
            }
        }
        return result
    }

    suspend fun undo(): Boolean {
        if (!loaded) return false
        planLock.withLock {
            edits.undos++
            edits.updateBusy()
        }
        try {
            val restored = work.withLock { restoreParent() }
            restored?.let { _events.emit(PipelineEvent.VersionRestored(it)) }
            return restored != null
        } finally {
            withContext(NonCancellable) {
                planLock.withLock {
                    edits.undos--
                    edits.resyncEditingPlan(version.plan)
                    edits.updateBusy()
                }
            }
        }
    }

    private suspend fun restoreParent(): TrackVersion? {
        val current = version
        val parent = current.parentVersionId?.let { store.version(it) } ?: return null
        val audio = store.loadAudio(parent)
        swapper.queueSwap(audio, current.diffRangesMs)
        runCatching { store.setCurrent(parent.trackId, parent.versionId) }
            .onFailure { swapper.queueSwap(swapper.master, current.diffRangesMs) }
            .getOrThrow()
        version = parent
        swapper.master = audio
        return parent
    }

    private suspend fun regenerate(job: QueuedEdit): Boolean {
        val committed = version
        val applied = PlanEditor.apply(committed.plan, job.edit) as? EditResult.Applied
        val problem =
            when {
                applied == null -> {
                    "The edit no longer changes the current version"
                }

                !sameDurations(committed.plan, applied.plan) -> {
                    "The edit changed section lengths"
                }

                committed.sourceMap.segments
                    .last()
                    .timelineEndMs != applied.plan.totalMs -> {
                    "The source map does not cover the plan"
                }

                else -> {
                    null
                }
            }
        if (problem != null) {
            (applied?.changedSections ?: job.changed).forEach { _events.fail(it, problem) }
            return false
        }
        return regenerateApplied(job, committed, applied!!)
    }

    private suspend fun regenerateApplied(
        job: QueuedEdit,
        committed: TrackVersion,
        applied: EditResult.Applied,
    ): Boolean {
        val newPlan = applied.plan
        val sections = SectionIndex.sections(newPlan)
        val order = planner.order(applied.changedSections, sections, engine.positionMs.value)
        val parentMaster = swapper.master
        val progress = Progress(committed.plan, committed.sourceMap)
        try {
            if (job.edit is PlanEdit.SetGlobal) {
                regenerateFull(job.edit, newPlan, order, announced = 0, progress)
            } else {
                regenerateWindows(job.edit, newPlan, order, progress)
            }
        } catch (e: CancellationException) {
            if (progress.diffs.isNotEmpty()) withContext(NonCancellable) { swapper.rollBack(parentMaster, progress.diffs) }
            throw e
        }
        if (progress.done.isEmpty()) return false

        val editedSection =
            (job.edit as? PlanEdit.EditSection)?.let {
                progress.done
                    .sorted()
                    .map { sections[it].name }
                    .distinct()
                    .joinToString(", ")
            }
        val saved = withContext(NonCancellable) { commit(committed, progress, editedSection, parentMaster) }
        return saved && progress.done.toSet() == applied.changedSections.toSet()
    }

    private suspend fun regenerateWindows(
        edit: PlanEdit,
        newPlan: CompositionPlan,
        order: List<Int>,
        progress: Progress,
    ) {
        for ((k, index) in order.withIndex()) {
            _events.emit(PipelineEvent.SectionRegenerating(index))
            val matched =
                runCatchingExceptions { swapper.swapWindow(newPlan, index, progress) }
                    .getOrElse { e ->
                        _events.fail(index, e.reason(), e)
                        return
                    }
            if (!matched) {
                regenerateFull(edit, newPlan, order.drop(k), announced = 1, progress)
                return
            }
            progress.chunks[index] = newPlan.chunks[index]
            progress.done += index
            _events.emit(PipelineEvent.SectionDone(index, edit, swapper.swapsQueued))
        }
    }

    private suspend fun regenerateFull(
        edit: PlanEdit,
        newPlan: CompositionPlan,
        sections: List<Int>,
        announced: Int,
        progress: Progress,
    ) {
        sections.drop(announced).forEach { _events.emit(PipelineEvent.SectionRegenerating(it)) }
        runCatchingExceptions { swapper.swapFull(newPlan, progress) }
            .onFailure { e ->
                sections.forEach { _events.fail(it, e.reason(), e) }
                return
            }
        progress.chunks.indices.forEach { progress.chunks[it] = newPlan.chunks[it] }
        progress.done += sections
        sections.forEach { _events.emit(PipelineEvent.SectionDone(it, edit, swapper.swapsQueued)) }
    }

    private suspend fun commit(
        parent: TrackVersion,
        progress: Progress,
        editedSection: String?,
        parentMaster: PcmAudio,
    ): Boolean {
        val merged = merge(progress.diffs)
        val plan = CompositionPlan(progress.chunks)
        return runCatchingIncludingCancellation {
            val saved = store.saveVersion(parent, plan, progress.sourceMap, swapper.master, editedSection, merged)
            version = saved
            _events.emit(PipelineEvent.VersionSaved(saved))
        }.onFailure { e ->
            swapper.rollBack(parentMaster, progress.diffs)
            progress.done.forEach { _events.fail(it, "Could not save the version: ${e.reason()}") }
        }.isSuccess
    }

    companion object {
        const val EDGE_SIMILARITY_THRESHOLD = 0.80f
        private const val MS_PER_SECOND = 1000L

        internal fun toMs(
            frames: LongRange,
            sampleRate: Int,
        ): LongRange {
            val start = frames.first * MS_PER_SECOND / sampleRate
            val endExclusive = ((frames.last + 1) * MS_PER_SECOND + sampleRate - 1) / sampleRate
            return start until endExclusive
        }

        internal fun merge(ranges: List<LongRange>): List<LongRange> {
            val out = mutableListOf<LongRange>()
            for (r in ranges.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    out[out.lastIndex] = last.first..maxOf(last.last, r.last)
                } else {
                    out += r
                }
            }
            return out
        }
    }
}

private suspend fun MutableSharedFlow<PipelineEvent>.fail(
    index: Int,
    reason: String,
    cause: Throwable? = null,
) = emit(PipelineEvent.SectionFailed(index, reason, cause as? ProxyError.BadPrompt))

private fun sameDurations(
    a: CompositionPlan,
    b: CompositionPlan,
): Boolean = a.chunks.size == b.chunks.size && a.chunks.indices.all { a.chunks[it].durationMs == b.chunks[it].durationMs }
