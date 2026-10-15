package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.plan.Section
import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.model.TrackVersion

internal class PlayheadNotices(
    private val agent: VoiceAgent,
    private val engine: AudioEngine,
    private val currentVersion: () -> TrackVersion?,
) {
    private var lastSection: Int? = null

    fun onConnected() {
        if (lastSection == null) send(engine.positionMs.value)
    }

    fun onDisconnected() {
        lastSection = null
    }

    fun onPosition(position: Long) {
        val section = sectionAt(currentSections(), position) ?: return
        if (section.index != lastSection) send(position)
    }

    fun seek(ms: Long) {
        engine.seekTo(ms)
        send(ms)
    }

    fun send(position: Long) {
        if (agent.state.value != AgentState.Listening && agent.state.value != AgentState.Speaking) return
        val sections = currentSections()
        val section = sectionAt(sections, position) ?: return
        lastSection = section.index
        agent.sendContext(AgentNotices.playhead(position, section.name, section.index + 1, sections.size))
    }

    private fun currentSections(): List<Section> = currentVersion()?.let { SectionIndex.sections(it.plan) }.orEmpty()
}

private fun sectionAt(
    sections: List<Section>,
    position: Long,
): Section? = sections.lastOrNull { it.startMs <= position } ?: sections.firstOrNull()
