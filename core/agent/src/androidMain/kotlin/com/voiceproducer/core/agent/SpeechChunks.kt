package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentEvent

internal fun speechChunk(audioEvent: Map<String, Any>): AgentEvent.SpeechChunk? {
    val eventId = (audioEvent["event_id"] as? Number)?.toInt()
    val alignment = audioEvent["alignment"] as? Map<*, *>
    val chars = typedList<String>(alignment?.get("chars"))
    val starts = typedList<Number>(alignment?.get("char_start_times_ms"))
    val durations = typedList<Number>(alignment?.get("char_durations_ms"))
    val timing = starts?.zip(durations.orEmpty())?.takeIf { it.size == chars?.size }
    if (eventId == null || chars.isNullOrEmpty() || timing == null) return null
    val last = timing.last()
    return AgentEvent.SpeechChunk(
        eventId = eventId,
        text = chars.joinToString(""),
        startsMs = timing.map { it.first.toInt() },
        durationMs = last.first.toInt() + last.second.toInt(),
    )
}

private inline fun <reified T> typedList(value: Any?): List<T>? =
    (value as? List<*>)?.takeIf { list -> list.all { it is T } }?.map { it as T }
