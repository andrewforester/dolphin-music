package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.agent.AgentEvent
import com.voiceproducer.core.domain.agent.AgentState
import com.voiceproducer.core.domain.agent.DemoSwitch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProducerViewModelDemoTest : ProducerViewModelTestBase() {
    private val switches = mutableListOf<Boolean>()

    private fun TestScope.demoViewModel() = viewModel(demo = DemoSwitch { switches += it })

    @Test
    fun startDemoSkipsTheTokenAndStartsTheDemo() =
        runTest {
            token = { error("no token for the demo") }
            val vm = demoViewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.StartDemo)
            runCurrent()
            assertEquals(listOf(true), switches)
            assertEquals("demo", agent.started.single().first)
            assertEquals(AgentState.Connecting, vm.state.value.agent.state)
            assertNull(vm.state.value.agent.error)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(1, agent.stops)
            advanceUntilIdle()
        }

    @Test
    fun toggleAgentUsesTheRealAgent() =
        runTest {
            val vm = demoViewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.StartDemo)
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertEquals(listOf(true, false), switches)
            assertEquals(listOf("demo", "tok-1"), agent.started.map { it.first })
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun startDemoWhileLiveDoesNothing() =
        runTest {
            val vm = demoViewModel()
            advanceUntilIdle()
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            vm.onIntent(ProducerIntent.StartDemo)
            runCurrent()
            assertEquals(listOf(false), switches)
            assertEquals(listOf("tok-1"), agent.started.map { it.first })
            assertEquals(0, agent.stops)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun demoAvailableFollowsTheSwitch() =
        runTest {
            assertFalse(viewModel().state.value.demoAvailable)
            assertTrue(demoViewModel().state.value.demoAvailable)
            advanceUntilIdle()
        }

    @Test
    fun demoLinesAreNotStoredAndLeaveWithTheDemo() =
        runTest {
            val vm = demoViewModel()
            advanceUntilIdle()
            val before = vm.state.value.transcript
            vm.onIntent(ProducerIntent.StartDemo)
            runCurrent()
            agent.state.value = AgentState.Listening
            runCurrent()
            agent.events.emit(AgentEvent.UserTranscript("make the chorus heavier"))
            agent.events.emit(AgentEvent.AgentText("Heavier chorus."))
            runCurrent()
            assertEquals(2, vm.state.value.transcript.size - before.size)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceUntilIdle()
            assertTrue(history.rows.isEmpty())
            assertEquals(before, vm.state.value.transcript)
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            assertTrue(agent.contexts.none { "chorus" in it }, agent.contexts.toString())
            assertNull(agent.firstMessages.last())
            vm.onIntent(ProducerIntent.ToggleAgent)
            runCurrent()
            advanceUntilIdle()
        }
}
