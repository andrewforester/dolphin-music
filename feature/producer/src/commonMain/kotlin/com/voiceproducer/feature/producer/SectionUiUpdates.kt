package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.model.TrackVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PREV_RESTARTS_AFTER_MS = 2_000L

internal fun MutableStateFlow<ProducerUiState>.showVersion(
    version: TrackVersion,
    keepStatuses: Boolean,
    clearFailed: Boolean = false,
) {
    update { s ->
        val sections =
            SectionIndex.sections(version.plan).map { sec ->
                val old = if (keepStatuses) s.sections.getOrNull(sec.index) else null
                val status = old?.status?.takeUnless { clearFailed && it == SectionStatus.Failed }
                SectionUi(
                    sec.index,
                    sec.name,
                    sec.startMs,
                    sec.endMs,
                    status ?: SectionStatus.Idle,
                    version = old?.version ?: 1,
                    lastTag = old?.lastTag,
                )
            }
        s.copy(sections = sections, canUndo = version.parentVersionId != null, chips = styleChips(version.plan))
    }
}

internal fun MutableStateFlow<ProducerUiState>.setStatus(
    index: Int,
    status: SectionStatus,
) {
    update { s ->
        s.copy(sections = s.sections.map { if (it.index == index) it.copy(status = status) else it })
    }
}

internal fun jumpToEarliest(
    version: TrackVersion,
    sections: List<Int>,
    seek: (Long) -> Unit,
) {
    val starts = SectionIndex.sections(version.plan).filter { it.index in sections }
    starts.minOfOrNull { it.startMs }?.let(seek)
}

internal fun AudioEngine.seekToSection(
    sections: List<SectionUi>,
    previous: Boolean,
    seek: (Long) -> Unit,
) {
    if (sections.isEmpty()) return
    val position = positionMs.value
    val i = sections.indexOfLast { it.startMs <= position }.coerceAtLeast(0)
    if (!previous && i == sections.lastIndex) {
        val duration = durationMs.value
        if (duration > 0) {
            pause()
            seek(duration - 1)
        }
        return
    }
    val target =
        if (previous) {
            if (i == 0 || position - sections[i].startMs > PREV_RESTARTS_AFTER_MS) sections[i] else sections[i - 1]
        } else {
            sections[i + 1]
        }
    seek(target.startMs)
}

internal fun AudioEngine.navigate(
    sections: List<SectionUi>,
    intent: ProducerIntent,
    seek: (Long) -> Unit,
) {
    when (intent) {
        is ProducerIntent.SeekToSection -> {
            sections.getOrNull(intent.index)?.let { seek(it.startMs) }
        }

        is ProducerIntent.SeekToMs -> {
            val duration = durationMs.value
            if (duration > 0) seek(intent.ms.coerceIn(0, duration - 1))
        }

        ProducerIntent.PrevSection -> {
            seekToSection(sections, previous = true, seek)
        }

        ProducerIntent.NextSection -> {
            seekToSection(sections, previous = false, seek)
        }

        else -> {
            Unit
        }
    }
}
