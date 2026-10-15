package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.model.TrackVersion
import com.voiceproducer.core.testing.FakeEngine
import com.voiceproducer.core.testing.FakeLibrary
import com.voiceproducer.core.testing.put
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RestoreLatestTrackUseCaseTest {
    private val library = FakeLibrary(master, composed = { version(100) }, render = { master })
    private val engine = FakeEngine()
    private val restoreLatestTrack = RestoreLatestTrackUseCase(library, engine)
    private val latest = mutableListOf<TrackVersion?>()

    @Test
    fun loadsTheLatestVersion() =
        runTest {
            library.put(version(1))

            val restored = restoreLatestTrack(onLatest = { latest += it }, shouldLoad = { true })

            assertEquals(version(1) to master, restored)
            assertEquals(listOf<TrackVersion?>(version(1)), latest)
            assertSame(master, engine.loaded)
        }

    @Test
    fun returnsNullWithoutATrack() =
        runTest {
            assertNull(restoreLatestTrack(onLatest = { latest += it }, shouldLoad = { true }))
            assertEquals(listOf<TrackVersion?>(null), latest)
            assertTrue(engine.calls.isEmpty())
        }

    @Test
    fun leavesTheEngineAloneWhenTheLoadIsNoLongerWanted() =
        runTest {
            library.put(version(1))

            assertNull(restoreLatestTrack(onLatest = { latest += it }, shouldLoad = { false }))
            assertTrue(engine.calls.isEmpty())
        }

    @Test
    fun reportsTheLatestVersionBeforeItsAudioFailsToLoad() =
        runTest {
            library.put(version(1))
            library.loadFailure = IllegalStateException("file missing")

            runCatching { restoreLatestTrack(onLatest = { latest += it }, shouldLoad = { true }) }

            assertEquals(listOf<TrackVersion?>(version(1)), latest)
            assertTrue(engine.calls.isEmpty())
        }
}
