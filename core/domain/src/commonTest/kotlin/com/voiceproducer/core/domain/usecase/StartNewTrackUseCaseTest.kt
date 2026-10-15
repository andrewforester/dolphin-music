package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.testing.FakeEngine
import com.voiceproducer.core.testing.FakeHistory
import com.voiceproducer.core.testing.FakeLibrary
import com.voiceproducer.core.testing.put
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartNewTrackUseCaseTest {
    private val library = FakeLibrary(master, composed = { version(100) }, render = { master })
    private val engine = FakeEngine()
    private val history = FakeHistory()
    private val startNewTrack = StartNewTrackUseCase(library, engine, history)

    @Test
    fun clearsCurrentUnloadsAndClearsUnassignedHistory() =
        runTest {
            library.put(version(1))
            val queued = mutableListOf<suspend () -> Unit>()

            startNewTrack(queued::add)

            assertEquals(1, library.clearCurrentCalls)
            assertEquals(1, engine.unloadCalls)
            assertTrue(history.cleared.isEmpty())
            queued.forEach { it() }
            assertEquals(listOf<Long?>(null), history.cleared)
        }
}
