package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ReplySpeech(
    private val state: MutableStateFlow<ProducerUiState>,
) {
    private val timelines = mutableMapOf<Int, SpeechTimeline>()

    private var replyEventId: Int? = null

    fun onChunk(chunk: AgentEvent.SpeechChunk) {
        val timeline = (timelines[chunk.eventId] ?: SpeechTimeline()) + chunk
        timelines[chunk.eventId] = timeline
        if (chunk.eventId == replyEventId) show(timeline)
    }

    fun onReply(eventId: Int?) {
        replyEventId = eventId
        show(eventId?.let { timelines[it] })
    }

    fun end() {
        timelines.clear()
        replyEventId = null
        show(null)
    }

    private fun show(timeline: SpeechTimeline?) {
        state.update { s ->
            val reply = s.transcript.lastOrNull() as? TranscriptItem.Agent
            s.copy(agentSpeech = if (timeline == null || reply == null) null else AgentSpeech(reply.id, timeline))
        }
    }
}
