package com.voiceproducer.feature.producer

import com.voiceproducer.core.model.MessageRole
import com.voiceproducer.core.model.StoredMessage

internal const val EARLIER_CONVERSATION_HEADING = "[system] Earlier conversation on this track, oldest first:"
internal const val EARLIER_CONVERSATION_MAX_MESSAGES = 20
internal const val EARLIER_CONVERSATION_MAX_CHARS = 4000

internal const val CONTINUE_FIRST_MESSAGE = "Let's continue."

private val whitespace = Regex("""\s+""")

fun earlierConversation(history: List<TranscriptItem>): String? {
    val lines = history.takeLast(EARLIER_CONVERSATION_MAX_MESSAGES).map(::historyLine).toMutableList()
    if (lines.isEmpty()) return null
    var chars = EARLIER_CONVERSATION_HEADING.length + lines.sumOf { 1 + it.length }
    while (lines.size > 1 && chars > EARLIER_CONVERSATION_MAX_CHARS) chars -= 1 + lines.removeAt(0).length
    if (chars > EARLIER_CONVERSATION_MAX_CHARS) {
        val room = EARLIER_CONVERSATION_MAX_CHARS - EARLIER_CONVERSATION_HEADING.length - 1
        lines[0] = lines[0].take(room - 1) + "…"
    }
    return (listOf(EARLIER_CONVERSATION_HEADING) + lines).joinToString("\n")
}

private fun historyLine(item: TranscriptItem): String =
    when (item) {
        is TranscriptItem.User -> {
            "Producer: " + oneLine(item.text)
        }

        is TranscriptItem.Agent -> {
            "Composer: " + oneLine(item.text)
        }

        is TranscriptItem.Tool -> {
            "Tool: " +
                oneLine(
                    toolStatusMark(item.status)?.let { "${toolLabel(item)} $it" } ?: toolLabel(item),
                )
        }

        is TranscriptItem.Notice -> {
            "Notice: " + oneLine(item.text)
        }
    }

private fun oneLine(text: String) = text.replace(whitespace, " ").trim()

fun firstMessageOverride(history: List<TranscriptItem>): String? = if (history.isEmpty()) null else CONTINUE_FIRST_MESSAGE

fun idleMicLabel(history: List<TranscriptItem>): String = if (history.isEmpty()) "Mic off · Tap to talk" else "Mic off · Tap to continue"

internal fun storedMessage(item: TranscriptItem): StoredMessage =
    when (item) {
        is TranscriptItem.User -> {
            StoredMessage(MessageRole.User, item.text)
        }

        is TranscriptItem.Agent -> {
            StoredMessage(MessageRole.Agent, item.text)
        }

        is TranscriptItem.Tool -> {
            StoredMessage(MessageRole.Tool, toolLabel(item), item.kind.name, item.status.name, item.section, item.detail)
        }

        is TranscriptItem.Notice -> {
            StoredMessage(MessageRole.Notice, item.text)
        }
    }

internal fun transcriptItem(
    message: StoredMessage,
    id: Long,
): TranscriptItem? =
    when (message.role) {
        MessageRole.User -> {
            TranscriptItem.User(id, message.text)
        }

        MessageRole.Agent -> {
            TranscriptItem.Agent(id, message.text)
        }

        MessageRole.Tool -> {
            ToolKind.entries.firstOrNull { it.name == message.toolKind }?.let { kind ->
                val status = ToolStatus.entries.firstOrNull { it.name == message.toolStatus }
                TranscriptItem.Tool(
                    id,
                    kind,
                    message.toolSection,
                    message.toolDetail,
                    if (status == null || status == ToolStatus.Running) ToolStatus.Failed else status,
                )
            }
        }

        MessageRole.Notice -> {
            TranscriptItem.Notice(id, message.text)
        }

        MessageRole.System -> {
            null
        }
    }
