package com.voiceproducer.core.agent.demo

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DemoVoiceAgentTest {
    private val noTools = ToolHandler { _, _ -> ToolResult(true, "") }

    private val loopMs = 1_200L + 2_500 + 1_500 + 2_400 + 1_500 + 3_000

    private fun TestScope.recording(a: DemoVoiceAgent): MutableList<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch { a.events.toList(events) }
        runCurrent()
        return events
    }

    @Test
    fun connectsThenGreets() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            assertEquals(AgentState.Connecting, a.state.value)
            advanceTimeBy(1_201)
            assertEquals(AgentState.Speaking, a.state.value)
            assertEquals(AgentEvent.AgentText(DEMO_GREETING), events.last())
        }

    @Test
    fun userTurnSendsVoiceThenTranscript() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            advanceTimeBy(1_200L + 2_500 + 1_500 + 2_401)
            assertTrue(events.filterIsInstance<AgentEvent.VadScore>().count { it.score > 0.2f } >= 20)
            assertTrue(events.any { it is AgentEvent.TentativeTranscript })
            assertEquals(AgentEvent.UserTranscript("Make the chorus heavier"), events.filterIsInstance<AgentEvent.UserTranscript>().first())
            assertEquals(AgentEvent.VadScore(0f), events.last())
            assertEquals(AgentState.Listening, a.state.value)
        }

    @Test
    fun repliesAfterThinking() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            advanceTimeBy(1_200L + 2_500 + 1_500 + 2_400 + 1_501)
            assertEquals(AgentState.Speaking, a.state.value)
            assertEquals(AgentEvent.AgentText("Heavier chorus: I'd push the drums and add a distorted guitar layer."), events.last())
        }

    @Test
    fun loopsWithoutGreetingAgain() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            advanceTimeBy(loopMs * 3)
            assertEquals(1, events.count { it == AgentEvent.AgentText(DEMO_GREETING) })
            assertEquals(
                listOf("Make the chorus heavier", "Drop the vocals in the verse"),
                events.filterIsInstance<AgentEvent.UserTranscript>().map { it.text }.take(2),
            )
        }

    @Test
    fun stopDisconnectsAndGoesQuiet() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            advanceTimeBy(3_000)
            a.stop()
            assertEquals(AgentState.Disconnected, a.state.value)
            val count = events.size
            advanceTimeBy(20_000)
            assertEquals(count, events.size)
            assertEquals(AgentState.Disconnected, a.state.value)
        }

    @Test
    fun mutedSendsNoVoice() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            a.setMicMuted(true)
            advanceTimeBy(loopMs)
            assertTrue(events.none { it is AgentEvent.VadScore || it is AgentEvent.UserTranscript || it is AgentEvent.TentativeTranscript })
        }

    @Test
    fun typedTextGetsAnEchoReply() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            val events = recording(a)
            a.start("demo", noTools, "")
            advanceTimeBy(1_200L + 2_500 + 100)
            a.sendUserText("more bass")
            advanceTimeBy(1_500L + 1_500 + 1)
            assertEquals(AgentState.Speaking, a.state.value)
            assertEquals(AgentEvent.AgentText("Got it: more bass"), events.last())
        }

    @Test
    fun neverCallsTools() =
        runTest {
            val a = DemoVoiceAgent(backgroundScope)
            a.start("demo", { name, _ -> fail("tool $name called") }, "")
            advanceTimeBy(60_000)
            a.stop()
        }
}
