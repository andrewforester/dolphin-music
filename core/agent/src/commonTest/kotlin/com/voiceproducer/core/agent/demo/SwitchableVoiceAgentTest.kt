package com.voiceproducer.core.agent.demo

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchableVoiceAgentTest {
    private class Recorder : VoiceAgent {
        override val state = MutableStateFlow(AgentState.Disconnected)
        override val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()

        override suspend fun start(
            token: String,
            tools: ToolHandler,
            plan: String,
            firstMessage: String?,
        ) {
            calls += "start $token"
            state.value = AgentState.Listening
        }

        override suspend fun stop() {
            calls += "stop"
            state.value = AgentState.Disconnected
        }

        override fun sendContext(text: String) {
            calls += "context $text"
        }

        override fun sendUserText(text: String) {
            calls += "text $text"
        }

        override fun setMicMuted(muted: Boolean) {
            calls += "muted $muted"
        }
    }

    private val noTools = ToolHandler { _, _ -> ToolResult(true, "") }

    @Test
    fun usesTheRealAgentByDefault() =
        runTest {
            val real = Recorder()
            val demo = Recorder()
            val a = SwitchableVoiceAgent(real, demo)
            a.start("tok", noTools, "")
            runCurrent()
            assertEquals(listOf("start tok"), real.calls)
            assertEquals(AgentState.Listening, a.state.value)
        }

    @Test
    fun followsTheSelectedAgent() =
        runTest {
            val real = Recorder()
            val demo = Recorder()
            val a = SwitchableVoiceAgent(real, demo)
            val events = mutableListOf<AgentEvent>()
            backgroundScope.launch { a.events.toList(events) }
            runCurrent()
            a.useDemo(true)
            a.start("demo", noTools, "")
            a.sendContext("hello")
            a.setMicMuted(true)
            runCurrent()
            assertEquals(listOf("start demo", "context hello", "muted true"), demo.calls)
            assertEquals(emptyList(), real.calls)
            assertEquals(AgentState.Listening, a.state.value)
            demo.events.emit(AgentEvent.AgentText("hi"))
            runCurrent()
            assertEquals(listOf<AgentEvent>(AgentEvent.AgentText("hi")), events)
        }

    @Test
    fun switchIgnoredWhileConnected() =
        runTest {
            val real = Recorder()
            val demo = Recorder()
            val a = SwitchableVoiceAgent(real, demo)
            a.useDemo(true)
            a.start("demo", noTools, "")
            runCurrent()
            a.useDemo(false)
            a.stop()
            runCurrent()
            assertEquals(listOf("start demo", "stop"), demo.calls)
            assertEquals(AgentState.Disconnected, a.state.value)
            a.useDemo(false)
            a.start("tok", noTools, "")
            runCurrent()
            assertEquals(listOf("start tok"), real.calls)
        }
}
