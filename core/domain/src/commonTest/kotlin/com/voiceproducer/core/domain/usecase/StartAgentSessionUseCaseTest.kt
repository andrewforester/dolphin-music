package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.agent.AgentToken
import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.testing.FakeAgent
import com.voiceproducer.core.testing.FakeAgentTokens
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StartAgentSessionUseCaseTest {
    private var minted = 0
    private var tokenFailure: Exception? = null
    private val tokens = FakeAgentTokens { tokenFailure?.let { throw it } ?: AgentToken("t${++minted}", "conv_$minted") }
    private val agent = FakeAgent()
    private val startAgentSession = StartAgentSessionUseCase(tokens, agent)
    private val tools = ToolHandler { _, _ -> ToolResult(true, "ok") }

    @Test
    fun startsWithAFreshToken() =
        runTest {
            val result = startAgentSession(tools) { AgentSessionOpening("{\"chunks\":[]}", "Welcome back") }

            assertEquals(StartedAgentSession("conv_1"), result.getOrNull())
            assertEquals(listOf("t1" to "{\"chunks\":[]}"), agent.started)
            assertEquals(listOf<String?>("Welcome back"), agent.firstMessages)
            assertSame(tools, agent.tools)
        }

    @Test
    fun aTokenFailureStartsNothingAndNeverReadsTheOpening() =
        runTest {
            tokenFailure = IllegalStateException("http 500")
            var openingRead = false

            val result = startAgentSession(tools) { AgentSessionOpening("plan", null).also { openingRead = true } }

            assertEquals("http 500", result.exceptionOrNull()?.message)
            assertTrue(agent.started.isEmpty())
            assertEquals(false, openingRead)
        }

    @Test
    fun aFailingStartThrows() =
        runTest {
            agent.startFailure = IllegalStateException("sdk down")

            assertFailsWith<IllegalStateException> { startAgentSession(tools) { AgentSessionOpening("plan", null) } }
        }
}
