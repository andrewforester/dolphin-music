package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import kotlin.math.pow

internal const val TRANSCRIPT_LIMIT = 30
internal const val MAX_CHIPS = 8

internal const val DUCKED_BELOW_DB = -1f

internal const val VOICE_RELEASE = 0.6f

internal const val VOICE_FLOOR = 0.03f

internal fun smoothVoiceLevel(
    previous: Float,
    score: Float,
): Float {
    val s = score.coerceIn(0f, 1f)
    val next = if (s >= previous) s else previous * VOICE_RELEASE + s * (1 - VOICE_RELEASE)
    return if (next < VOICE_FLOOR) 0f else next
}

internal fun sectionKind(name: String): SectionKind {
    val n = name.lowercase()
    return when {
        "intro" in n -> SectionKind.Intro
        "verse" in n -> SectionKind.Verse
        "chorus" in n || "hook" in n -> SectionKind.Chorus
        "bridge" in n -> SectionKind.Bridge
        "outro" in n || "ending" in n -> SectionKind.Outro
        else -> SectionKind.Other
    }
}

internal fun styleChips(plan: CompositionPlan): List<StyleChip> {
    val chunks = plan.chunks.filterIsInstance<GenerationChunk>()
    if (chunks.isEmpty()) return emptyList()
    return chunks
        .first()
        .positiveStyles
        .distinctBy { it.lowercase() }
        .filter { style -> chunks.all { c -> c.positiveStyles.any { it.equals(style, ignoreCase = true) } } }
        .take(MAX_CHIPS)
        .map(::StyleChip)
}

private const val DB_PER_AMPLITUDE_DECADE = 20f

internal fun musicLevel(db: Float): Float = 10f.pow(db / DB_PER_AMPLITUDE_DECADE).coerceIn(0f, 1f)

internal fun firstAddedStyle(edit: PlanEdit): String? {
    val add =
        when (edit) {
            is PlanEdit.EditSection -> edit.add
            is PlanEdit.SetGlobal -> edit.add
        }
    return add.map { it.trim() }.firstOrNull { it.isNotEmpty() }
}

internal fun List<TranscriptItem>.append(item: TranscriptItem): List<TranscriptItem> {
    val last = lastOrNull()
    if (item is TranscriptItem.Agent && last is TranscriptItem.Agent && item.text.startsWith(last.text)) {
        return dropLast(1) + last.copy(text = item.text)
    }
    return (this + item).takeLast(TRANSCRIPT_LIMIT)
}

internal fun List<TranscriptItem>.updateTool(
    id: Long,
    change: (TranscriptItem.Tool) -> TranscriptItem.Tool,
) = map { if (it is TranscriptItem.Tool && it.id == id) change(it) else it }
