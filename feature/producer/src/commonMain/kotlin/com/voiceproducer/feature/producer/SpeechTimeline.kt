package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentEvent

data class SpeechTimeline(
    val text: String = "",
    val startsMs: List<Long> = emptyList(),
    val endMs: Long = 0,
) {
    operator fun plus(chunk: AgentEvent.SpeechChunk): SpeechTimeline =
        SpeechTimeline(
            text = text + chunk.text,
            startsMs = startsMs + chunk.text.indices.map { endMs + (chunk.startsMs.getOrNull(it) ?: 0) },
            endMs = endMs + chunk.durationMs,
        )

    fun spokenWords(elapsedMs: Long): Int {
        val spoken = startsMs.count { it <= elapsedMs }
        return wordCount(text.take(spoken))
    }
}

fun revealedWords(
    totalWords: Int,
    elapsedMs: Long,
    speech: SpeechTimeline?,
): Int =
    when {
        speech == null -> revealedWords(totalWords, elapsedMs)
        totalWords <= 0 -> 0
        else -> speech.spokenWords(elapsedMs).coerceIn(1, totalWords)
    }
