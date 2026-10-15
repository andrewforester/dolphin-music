package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.playback.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class SectionTracker(
    private val state: MutableStateFlow<ProducerUiState>,
    private val engine: AudioEngine,
) {
    private val queuedSections = mutableListOf<QueuedSection>()

    private val sectionHistory = mutableMapOf<Int, ArrayDeque<SectionVersion>>()

    private val unsavedDone = mutableSetOf<Int>()

    private class QueuedSection(
        val index: Int,
        val tag: String?,
        val swaps: Int,
    )

    private data class SectionVersion(
        val version: Int,
        val tag: String?,
    )

    fun reset() {
        queuedSections.clear()
        sectionHistory.clear()
        unsavedDone.clear()
    }

    fun onDone(
        index: Int,
        edit: PlanEdit,
        swaps: Int,
    ) {
        unsavedDone += index
        val tag = firstAddedStyle(edit)
        if (swaps <= engine.swapsApplied.value) {
            showNewAudio(index, tag)
        } else {
            queuedSections += QueuedSection(index, tag, swaps)
            state.setStatus(index, SectionStatus.Queued)
        }
    }

    fun onFailed(index: Int) {
        val wasQueued = queuedSections.removeAll { it.index == index }
        if (unsavedDone.remove(index) && !wasQueued) restorePreviousVersion(index)
        state.setStatus(index, SectionStatus.Failed)
    }

    fun takeUnsaved(): List<Int> {
        val edited = unsavedDone.toList()
        unsavedDone.clear()
        return edited
    }

    fun onSwapsApplied(count: Int) {
        val ready = queuedSections.filter { it.swaps <= count }
        queuedSections -= ready.toSet()
        ready.forEach { showNewAudio(it.index, it.tag) }
    }

    fun revertSection(index: Int) {
        val dropped = queuedSections.removeAll { it.index == index }
        val previous = if (dropped) null else sectionHistory[index]?.removeLastOrNull()
        state.update { s ->
            s.copy(
                sections =
                    s.sections.map { sec ->
                        when {
                            sec.index != index -> sec
                            dropped -> sec.copy(status = if (sec.status == SectionStatus.Queued) SectionStatus.Idle else sec.status)
                            previous != null -> sec.copy(version = previous.version, lastTag = previous.tag)
                            else -> sec.copy(version = maxOf(1, sec.version - 1), lastTag = null)
                        }
                    },
            )
        }
    }

    private fun restorePreviousVersion(index: Int) {
        val previous = sectionHistory[index]?.removeLastOrNull() ?: return
        state.update { s ->
            s.copy(
                sections =
                    s.sections.map {
                        if (it.index == index) it.copy(version = previous.version, lastTag = previous.tag) else it
                    },
            )
        }
    }

    private fun showNewAudio(
        index: Int,
        tag: String?,
    ) {
        val shown = state.value.sections.getOrNull(index) ?: return
        sectionHistory.getOrPut(index) { ArrayDeque() }.addLast(SectionVersion(shown.version, shown.lastTag))
        state.update { s ->
            s.copy(
                sections =
                    s.sections.map { sec ->
                        if (sec.index != index) return@map sec
                        sec.copy(
                            status = if (sec.status == SectionStatus.Queued) SectionStatus.Idle else sec.status,
                            version = sec.version + 1,
                            lastTag = tag,
                        )
                    },
            )
        }
    }
}
