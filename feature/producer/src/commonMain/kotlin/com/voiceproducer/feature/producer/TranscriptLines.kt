package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.plan.PlanEdit

const val AGENT_WORDS_PER_SECOND = 2.6

private val word = Regex("""\S+""")

fun toolLineText(tool: TranscriptItem.Tool): String {
    val icon =
        when (tool.kind) {
            ToolKind.Compose -> "♪"
            ToolKind.EditSection -> "✎"
            ToolKind.SetGlobal -> "↻"
            ToolKind.Undo -> "↶"
            ToolKind.NewTrack -> "🗑"
        }
    return "$icon ${toolLabel(tool)}"
}

fun toolLabel(tool: TranscriptItem.Tool): String {
    val label =
        when (tool.kind) {
            ToolKind.Compose -> "Writing a new track"
            ToolKind.EditSection -> "Changing ${tool.section ?: "a section"}"
            ToolKind.SetGlobal -> "Changing the whole track"
            ToolKind.Undo -> "Undo"
            ToolKind.NewTrack -> "New track"
        }
    return if (tool.detail == null) label else "$label — ${tool.detail}"
}

fun toolStatusMark(status: ToolStatus): String? =
    when (status) {
        ToolStatus.Running -> null
        ToolStatus.Done -> "✓"
        ToolStatus.Failed -> "✗"
    }

fun revealedWordCount(
    text: String,
    elapsedMs: Long,
): Int = revealedWords(wordCount(text), elapsedMs)

fun revealedWords(
    totalWords: Int,
    elapsedMs: Long,
): Int {
    if (totalWords <= 0) return 0
    val spoken = 1 + (elapsedMs.coerceAtLeast(0) * AGENT_WORDS_PER_SECOND / 1000).toInt()
    return spoken.coerceAtMost(totalWords)
}

fun wordCount(text: String): Int = word.findAll(text).count()

fun revealedText(
    text: String,
    words: Int,
): String {
    if (words <= 0) return ""
    val matches = word.findAll(text).toList()
    return if (words >= matches.size) text else text.substring(0, matches[words - 1].range.last + 1)
}

internal fun editDetail(
    add: List<String>,
    remove: List<String>,
): String? {
    fun clean(styles: List<String>) = styles.map { it.trim() }.filter { it.isNotEmpty() }
    val added = clean(add)
    if (added.isNotEmpty()) return added.joinToString(", ")
    return clean(remove).takeIf { it.isNotEmpty() }?.joinToString(", ") { "no $it" }
}

internal fun toolLineFor(
    id: Long,
    edit: PlanEdit,
): TranscriptItem.Tool =
    when (edit) {
        is PlanEdit.EditSection -> {
            TranscriptItem.Tool(id, ToolKind.EditSection, edit.name, editDetail(edit.add, edit.remove), ToolStatus.Running)
        }

        is PlanEdit.SetGlobal -> {
            TranscriptItem.Tool(id, ToolKind.SetGlobal, null, editDetail(edit.add, edit.remove), ToolStatus.Running)
        }
    }
