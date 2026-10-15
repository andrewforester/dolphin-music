package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.testing.FakeEngine
import com.voiceproducer.core.testing.FakeLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ComposeTrackUseCaseTest {
    private val library = FakeLibrary(master, composed = { version(100) }, render = { master })
    private val engine = FakeEngine()
    private val composeTrack = ComposeTrackUseCase(library, engine)

    @Test
    fun createsTheTrackAndLoadsItsMaster() =
        runTest {
            library.createGate.complete(Unit)

            val result = composeTrack("lofi", instrumental = true, lengthMs = 90_000)

            assertEquals(listOf(Triple("lofi", true, 90_000L)), library.created)
            assertSame(master, engine.loaded)
            assertEquals(version(100) to master, result)
        }
}
