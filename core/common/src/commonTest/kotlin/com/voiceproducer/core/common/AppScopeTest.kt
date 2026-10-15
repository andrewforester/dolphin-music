package com.voiceproducer.core.common

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppScopeTest {
    @Test
    fun aFailingJobIsLoggedAndTheScopeKeepsWorking() =
        runTest {
            val logged = mutableListOf<String>()
            val scope = appScope(StandardTestDispatcher(testScheduler)) { logged += it }
            scope.launch { error("stop failed") }
            testScheduler.advanceUntilIdle()
            var ran = false
            scope.launch { ran = true }
            testScheduler.advanceUntilIdle()
            assertEquals(1, logged.size)
            assertTrue(logged.single().contains("stop failed"), logged.single())
            assertTrue(ran)
        }
}
