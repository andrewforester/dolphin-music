package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.agent.AgentRefused
import com.voiceproducer.core.testing.FakeAgentTokens
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExplainAgentRefusalUseCaseTest {
    private val tokens = FakeAgentTokens()
    private val explainRefusal = ExplainAgentRefusalUseCase(tokens)

    @Test
    fun givesTheReasonRecordedForTheConversation() =
        runTest {
            val quota = IllegalStateException("Not enough ElevenLabs credits")
            tokens.refusal = { quota }

            assertSame(quota, explainRefusal("conv_7"))
            assertEquals(listOf("conv_7"), tokens.refusalLookups)
        }

    @Test
    fun withoutAConversationIdTheRefusalIsUnexplained() =
        runTest {
            val reason = explainRefusal(null)

            assertIs<AgentRefused>(reason)
            assertEquals("the agent ended the session before it began", reason.message)
            assertTrue(tokens.refusalLookups.isEmpty())
        }

    @Test
    fun aFailedLookupLeavesTheRefusalUnexplained() =
        runTest {
            tokens.refusal = { throw IllegalStateException("http 502") }

            val reason = explainRefusal("conv_7")

            assertIs<AgentRefused>(reason)
            assertEquals("the agent ended the session before it began", reason.message)
        }
}
