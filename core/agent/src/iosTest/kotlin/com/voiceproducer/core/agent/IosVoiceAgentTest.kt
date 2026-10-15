package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosVoiceAgentTest {
    private val bridge = FakeAgentBridge()
    private val agent = IosVoiceAgent(bridge)
    private val noTools = ToolHandler { _, _ -> ToolResult(true, "ok") }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = body)

    @Test
    fun `start maps status and mode to state`() =
        test {
            val job = launch { agent.start("tok", noTools, "{}") }
            assertEquals(AgentState.Connecting, agent.state.value)
            bridge.listener.onStatus(BridgeStatus.Connected)
            assertTrue(job.isCompleted, "start returns once connected")
            assertEquals(AgentState.Listening, agent.state.value)
            bridge.listener.onMode(true)
            assertEquals(AgentState.Speaking, agent.state.value)
            bridge.listener.onMode(false)
            assertEquals(AgentState.Listening, agent.state.value)
            bridge.listener.onStatus(BridgeStatus.Disconnected)
            assertEquals(AgentState.Disconnected, agent.state.value)
        }

    @Test
    fun `start passes token plan and agent id`() =
        test {
            launch { agent.start("tok", noTools, "{plan}") }
            assertEquals(listOf(FakeAgentBridge.Start("tok", "{plan}")), bridge.starts)
            bridge.listener.onStatus(BridgeStatus.Connected)
        }

    @Test
    fun `stop during connect ends the started session`() =
        test {
            val job = launch { agent.start("tok", noTools, "{}") }
            val first = bridge.listener
            agent.stop()
            assertEquals(1, bridge.stops)
            assertTrue(job.isCompleted, "a pending start completes when stopped")
            assertEquals(AgentState.Disconnected, agent.state.value)
            first.onStatus(BridgeStatus.Connected)
            assertEquals(AgentState.Disconnected, agent.state.value)
        }

    @Test
    fun `start disconnected before connecting returns and reports disconnected`() =
        test {
            val job = launch { agent.start("tok", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Disconnected)
            assertTrue(job.isCompleted)
            assertEquals(AgentState.Disconnected, agent.state.value)
        }

    @Test
    fun `error while connecting ends start and disconnects`() =
        test {
            val events = mutableListOf<AgentEvent>()
            backgroundScope.launch { agent.events.toList(events) }
            val job = launch { agent.start("tok", noTools, "{}") }
            bridge.listener.onError("boom")
            assertTrue(job.isCompleted)
            assertEquals(AgentState.Disconnected, agent.state.value)
            assertEquals(listOf<AgentEvent>(AgentEvent.Error("boom")), events)
        }

    @Test
    fun `refusal while connecting ends start and reports the refusal`() =
        test {
            val events = mutableListOf<AgentEvent>()
            backgroundScope.launch { agent.events.toList(events) }
            val job = launch { agent.start("tok", noTools, "{}") }
            bridge.listener.onRefused()
            assertTrue(job.isCompleted)
            assertEquals(AgentState.Disconnected, agent.state.value)
            assertEquals(listOf<AgentEvent>(AgentEvent.Refused), events)
        }

    @Test
    fun `refusal after connecting is an event`() =
        test {
            val events = mutableListOf<AgentEvent>()
            backgroundScope.launch { agent.events.toList(events) }
            launch { agent.start("tok", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)
            bridge.listener.onRefused()
            assertEquals(listOf<AgentEvent>(AgentEvent.Refused), events)
        }

    @Test
    fun `error while connected is only an event`() =
        test {
            launch { agent.start("tok", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)
            bridge.listener.onError("glitch")
            assertEquals(AgentState.Listening, agent.state.value)
        }

    @Test
    fun `cancelling a pending start stops the bridge and ignores late callbacks`() =
        test {
            val job = launch { agent.start("tok", noTools, "{}") }
            val first = bridge.listener
            job.cancel()
            assertTrue(job.isCompleted)
            assertEquals(1, bridge.stops)
            assertEquals(AgentState.Disconnected, agent.state.value)
            first.onStatus(BridgeStatus.Connected)
            assertEquals(AgentState.Disconnected, agent.state.value)
        }

    @Test
    fun `a newer start completes the pending one`() =
        test {
            val first = launch { agent.start("a", noTools, "{}") }
            val second = launch { agent.start("b", noTools, "{}") }
            assertTrue(first.isCompleted, "superseded start returns")
            assertEquals(listOf("a", "b"), bridge.starts.map { it.token })
            bridge.listener.onStatus(BridgeStatus.Connected)
            assertTrue(second.isCompleted)
            assertEquals(AgentState.Listening, agent.state.value)
        }

    @Test
    fun `stop does not hang when the bridge never confirms`() =
        test {
            launch { agent.start("tok", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)
            bridge.answerStop = false
            val stopping = launch { agent.stop() }
            assertFalse(stopping.isCompleted, "waits for the bridge to confirm")
            advanceUntilIdle()
            assertTrue(stopping.isCompleted)
            assertEquals(AgentState.Disconnected, agent.state.value)
            val next = launch { agent.start("again", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)
            assertTrue(next.isCompleted, "the lifecycle lock was released")
        }

    @Test
    fun `stop cancelled while waiting for the lifecycle lock still stops the bridge`() =
        runTest(StandardTestDispatcher()) {
            launch { agent.start("tok", noTools, "{}") }
            runCurrent()
            assertEquals(AgentState.Connecting, agent.state.value)
            val stopping = launch(start = CoroutineStart.UNDISPATCHED) { agent.stop() }
            assertEquals(0, bridge.stops)
            stopping.cancel()
            advanceUntilIdle()
            assertEquals(1, bridge.stops, "the session must not stay open with every callback stale")
            assertEquals(AgentState.Disconnected, agent.state.value)
        }

    @Test
    fun `stale listener events are ignored`() =
        test {
            val events = mutableListOf<AgentEvent>()
            backgroundScope.launch { agent.events.toList(events) }
            var handled = 0
            val counting =
                ToolHandler { _, _ ->
                    handled++
                    ToolResult(true, "ok")
                }
            launch { agent.start("a", counting, "{}") }
            val first = bridge.listener
            launch { agent.start("b", noTools, "{}") }
            var staleReply: Pair<Boolean, String>? = null
            first.onToolCall("compose_track", """{"prompt":"p"}""") { ok, msg -> staleReply = ok to msg }
            assertEquals(false to "session ended", staleReply)
            assertEquals(0, handled, "a stale tool call never reaches the handler")
            first.onUserTranscript("old")
            first.onVad(0.9f)
            first.onAgentText("old")
            first.onError("old")
            first.onStatus(BridgeStatus.Connected)
            first.onMode(true)
            assertEquals(emptyList<Any>(), events)
            assertEquals(AgentState.Connecting, agent.state.value)
            bridge.listener.onUserTranscript("new")
            assertEquals(listOf<AgentEvent>(AgentEvent.UserTranscript("new")), events)
            bridge.listener.onStatus(BridgeStatus.Connected)
            assertEquals(AgentState.Listening, agent.state.value)
        }

    @Test
    fun `mute before connect is applied on connect`() =
        test {
            launch { agent.start("a", noTools, "{}") }
            agent.setMicMuted(true)
            assertEquals(emptyList<Any>(), bridge.mutes, "not sent while connecting")
            bridge.listener.onStatus(BridgeStatus.Connected)
            assertEquals(listOf(true), bridge.mutes)

            agent.setMicMuted(false)
            assertEquals(listOf(true, false), bridge.mutes, "sent directly while connected")
            agent.setMicMuted(true)

            launch { agent.start("b", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)
            assertEquals(listOf(true, false, true), bridge.mutes, "a new start resets the mute to open")
        }

    @Test
    fun `sendContext only while connected`() =
        test {
            agent.sendContext("idle")
            launch { agent.start("a", noTools, "{}") }
            agent.sendContext("connecting")
            agent.sendUserText("connecting")
            assertEquals(emptyList<Any>(), bridge.contexts)
            assertEquals(emptyList<Any>(), bridge.userTexts)
            bridge.listener.onStatus(BridgeStatus.Connected)
            agent.sendContext("listening")
            agent.sendUserText("hi")
            bridge.listener.onMode(true)
            agent.sendContext("speaking")
            assertEquals(listOf("listening", "speaking"), bridge.contexts)
            assertEquals(listOf("hi"), bridge.userTexts)
        }

    @Test
    fun `tool call goes to handler and replies`() =
        test {
            var received: Pair<String, Map<String, Any?>>? = null
            val tools =
                ToolHandler { name, params ->
                    if (params["prompt"] == "throw") error("handler failed")
                    received = name to params
                    ToolResult(true, "composed")
                }
            launch { agent.start("a", tools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)

            val ok = CompletableDeferred<Pair<Boolean, String>>()
            bridge.listener.onToolCall("compose_track", """{"prompt":"p"}""") { success, msg -> ok.complete(success to msg) }
            assertEquals(true to "composed", awaitReal(ok))
            assertEquals("compose_track" to mapOf<String, Any?>("prompt" to "p"), received)

            val failed = CompletableDeferred<Pair<Boolean, String>>()
            bridge.listener.onToolCall("compose_track", """{"prompt":"throw"}""") { success, msg -> failed.complete(success to msg) }
            assertEquals(false to "handler failed", awaitReal(failed))
        }

    @Test
    fun `events map`() =
        test {
            val events = mutableListOf<AgentEvent>()
            backgroundScope.launch { agent.events.toList(events) }
            launch { agent.start("a", noTools, "{}") }
            bridge.listener.onStatus(BridgeStatus.Connected)
            bridge.listener.onVad(0.7f)
            bridge.listener.onUserTranscript("you")
            bridge.listener.onAgentText("me")
            bridge.listener.onError("bad")
            assertEquals(
                listOf(
                    AgentEvent.VadScore(0.7f),
                    AgentEvent.UserTranscript("you"),
                    AgentEvent.AgentText("me"),
                    AgentEvent.Error("bad"),
                ),
                events,
            )
            assertFalse(events.any { it is AgentEvent.TentativeTranscript })
        }

    private suspend fun <T> awaitReal(d: CompletableDeferred<T>): T = withContext(Dispatchers.Default) { withTimeout(5_000) { d.await() } }
}
