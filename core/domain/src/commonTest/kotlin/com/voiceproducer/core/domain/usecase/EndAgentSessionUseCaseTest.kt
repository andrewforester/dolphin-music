package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.testing.FakeAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals

class EndAgentSessionUseCaseTest {
    private val agent = FakeAgent()
    private val appScope = TestScope()
    private val endAgentSession = EndAgentSessionUseCase(agent, appScope)

    @Test
    fun stopsInTheAppScopeAfterTheCallerIsCancelled() {
        val caller = CoroutineScope(Job())
        caller.launch(Dispatchers.Unconfined) { endAgentSession() }
        caller.cancel()
        assertEquals(0, agent.stops)

        appScope.advanceUntilIdle()

        assertEquals(1, agent.stops)
    }
}
