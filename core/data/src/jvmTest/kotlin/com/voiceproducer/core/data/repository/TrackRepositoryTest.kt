package com.voiceproducer.core.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.voiceproducer.core.codec.AudioDecoder
import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.data.remote.FixedCredentials
import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.database.AppDatabase
import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.SourceMap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackRepositoryTest {
    private class FlakyFileSystem(
        delegate: FileSystem,
    ) : ForwardingFileSystem(delegate) {
        var failWrites = false
        var onWrite: () -> Unit = {}

        override fun sink(
            file: Path,
            mustCreate: Boolean,
        ): Sink {
            if (failWrites) throw IOException("disk full")
            onWrite()
            return super.sink(file, mustCreate)
        }
    }

    private val fakeFs = FakeFileSystem()
    private val fs = FlakyFileSystem(fakeFs)
    private val db: AppDatabase =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun pcm(
        ms: Long,
        fill: Short = 7,
    ) = PcmAudio(ShortArray((ms * 24).toInt()) { fill }, 24000, 1)

    private val audio = pcm(10_000)
    private var decoded = audio
    private val decoder =
        object : AudioDecoder {
            override suspend fun decode(
                bytes: ByteArray,
                format: AudioFormat,
            ) = decoded
        }
    private val requests = mutableListOf<HttpRequestData>()
    private val plan = CompositionPlan(listOf(GenerationChunk("[A]", 10_000)))
    private var now = 1_000L

    private val api =
        ProxyApi(
            HttpClient(
                MockEngine { req ->
                    requests += req
                    if (req.url.encodedPath.endsWith("music-plan")) {
                        respond(
                            """{"chunks":[{"text":"[A]","duration_ms":10000}]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            byteArrayOf(9),
                            HttpStatusCode.OK,
                            headersOf(
                                "song-id" to listOf("song-1"),
                                "x-audio-format" to listOf("pcm_24000"),
                            ),
                        )
                    }
                },
            ),
            "https://p.example/fn",
            FixedCredentials(null),
        )

    private val repo =
        TrackRepository(
            api,
            db.trackDao(),
            decoder,
            AudioStore(fs, "/audio".toPath()),
        ) { now++ }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun createTrackPersistsVersionOne() =
        runTest {
            val (v, pcm) = repo.createTrack("lofi beat", instrumental = false, lengthMs = 10_000)
            assertEquals(1, v.number)
            assertNull(v.parentVersionId)
            assertEquals(SourceMap.single("song-1", 10_000), v.sourceMap)
            assertEquals(plan, v.plan)
            assertEquals(audio, pcm)
            assertTrue(v.audioPath.endsWith(".wav"), v.audioPath)
            assertTrue(fakeFs.exists("/audio".toPath() / v.audioPath))
            assertEquals(audio, repo.loadAudio(v))
            assertTrue(v.diffRangesMs.isEmpty())
            assertNull(v.editedSection)
        }

    @Test
    fun instrumentalAppendsSuffixToPrompt() =
        runTest {
            val prompt = "x".repeat(60)
            repo.createTrack(prompt, instrumental = true, lengthMs = 10_000)
            val body = (requests.first().body as TextContent).text
            assertTrue(body.contains("$prompt, instrumental, no vocals"), body)
        }

    @Test
    fun saveVersionLinksParentAndBecomesCurrent() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val sm = SourceMap.single("song-2", 10_000)
            val v2 = repo.saveVersion(v1, plan, sm, audio, "Chorus", listOf(1000L..2000L))
            assertEquals(2, v2.number)
            assertEquals(v1.versionId, v2.parentVersionId)
            assertEquals(v1.trackId, v2.trackId)
            assertEquals("Chorus", v2.editedSection)
            assertEquals(v2, repo.latest())
        }

    @Test
    fun latestReturnsCurrentVersion() =
        runTest {
            assertNull(repo.latest())
            val (v1, _) = repo.createTrack("p", false, 10_000)
            assertEquals(v1, repo.latest())
            val v2 = repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList())
            repo.setCurrent(v1.trackId, v1.versionId)
            assertEquals(v1, repo.latest())
            repo.setCurrent(v1.trackId, v2.versionId)
            assertEquals(v2, repo.latest())
        }

    @Test
    fun shortPromptIsTheWholeTitle() =
        runTest {
            val (v1, _) = repo.createTrack("x".repeat(50), false, 10_000)
            assertEquals("x".repeat(50), repo.title(v1.trackId))
            assertNull(repo.title(v1.trackId + 1))
        }

    @Test
    fun longPromptTitleIsCutAtAWordBoundaryWithEllipsis() =
        runTest {
            val prompt = "Warm, melancholic autumn instrumental with soft piano, brushed drums and a slow cello line"
            assertTrue(prompt.length > 80)
            val (v1, _) = repo.createTrack(prompt, false, 10_000)
            val title = repo.title(v1.trackId)!!
            assertEquals("Warm, melancholic autumn instrumental with soft piano, brushed drums and a slow…", title)
            assertTrue(title.length <= 81)
        }

    @Test
    fun titleCutDoesNotSplitAWordEvenAtTheLimit() {
        assertEquals("aaaa bbbb…", titleFor("aaaa bbbb cccc", maxLength = 9))
        assertEquals("aaaa bbbb…", titleFor("aaaa bbbb cccc", maxLength = 10))
        assertEquals("aaaa bbbb cccc", titleFor("aaaa bbbb cccc", maxLength = 14))
        assertEquals("x".repeat(5) + "…", titleFor("x".repeat(9), maxLength = 5))
    }

    @Test
    fun clearCurrentKeepsVersionsButLatestIsNull() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val v2 = repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList())
            repo.clearCurrent()
            assertNull(repo.latest())
            assertEquals(v1, repo.version(v1.versionId))
            assertEquals(v2, repo.version(v2.versionId))
            assertEquals(audio, repo.loadAudio(v2))
            assertEquals("p", repo.title(v1.trackId))
            val (v3, _) = repo.createTrack("q", false, 10_000)
            assertEquals(v3, repo.latest())
        }

    @Test
    fun latestPicksNewestTrack() =
        runTest {
            repo.createTrack("first", false, 10_000)
            val (second, _) = repo.createTrack("second", false, 10_000)
            assertEquals(second, repo.latest())
        }

    @Test
    fun undoTargetIsParent() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val v2 = repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList())
            assertEquals(v1, repo.version(assertNotNull(v2.parentVersionId)))
        }

    @Test
    fun diffRangesRoundTrip() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val ranges = listOf(0L..500L, 4000L..9000L)
            val v2 = repo.saveVersion(v1, plan, v1.sourceMap, audio, "A", ranges)
            assertEquals(ranges, repo.version(v2.versionId)?.diffRangesMs)
        }

    @Test
    fun composeFullAndWindowReturnSongIdAndAudio() =
        runTest {
            assertEquals("song-1" to audio, repo.composeFull(plan))
            assertEquals("song-1" to audio, repo.composeWindow(plan))
        }

    @Test
    fun createTrackPadsShortMasterWithSilence() =
        runTest {
            decoded = pcm(9_950)
            val (v, pcm) = repo.createTrack("p", false, 10_000)
            assertEquals(240_000, pcm.samples.size)
            assertEquals(0, pcm.samples.last().toInt())
            assertEquals(pcm, repo.loadAudio(v))
        }

    @Test
    fun createTrackTrimsLongMaster() =
        runTest {
            decoded = pcm(10_050)
            val (_, pcm) = repo.createTrack("p", false, 10_000)
            assertEquals(240_000, pcm.samples.size)
        }

    @Test
    fun createTrackRejectsLargeLengthMismatch() =
        runTest {
            decoded = pcm(9_800)
            val e = assertFailsWith<IllegalStateException> { repo.createTrack("p", false, 10_000) }
            assertTrue(e.message!!.startsWith("master length mismatch"))
            assertNull(repo.latest())
        }

    @Test
    fun saveVersionNormalizesAndRejectsMaster() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val v2 = repo.saveVersion(v1, plan, v1.sourceMap, pcm(10_040), null, emptyList())
            assertEquals(240_000, repo.loadAudio(v2).samples.size)
            assertFailsWith<IllegalStateException> {
                repo.saveVersion(v2, plan, v1.sourceMap, pcm(10_200), null, emptyList())
            }
            assertEquals(v2, repo.latest())
        }

    @Test
    fun saveVersionRejectsDifferentFormat() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val other = PcmAudio(ShortArray(480_000), 48000, 1)
            assertFailsWith<IllegalArgumentException> {
                repo.saveVersion(v1, plan, v1.sourceMap, other, null, emptyList())
            }
        }

    @Test
    fun failedCreateTrackLeavesNoOrphanAndKeepsPreviousTrack() =
        runTest {
            val (good, _) = repo.createTrack("good", false, 10_000)
            fs.failWrites = true
            assertFailsWith<IOException> { repo.createTrack("bad", false, 10_000) }
            fs.failWrites = false
            assertEquals(good, repo.latest())
            assertEquals(listOf(good.audioPath), fakeFs.list("/audio".toPath()).map { it.name })
        }

    private fun readDuringWrite(read: suspend () -> Int): () -> Int? {
        var seen: Int? = -1
        fs.onWrite = {
            fs.onWrite = {}
            val result = CompletableFuture<Int>()
            thread { result.complete(runBlocking { read() }) }
            seen =
                try {
                    result.get(5, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    null
                }
        }
        return { seen }
    }

    @Test
    fun createTrackWritesTheMasterOutsideTheTransaction() =
        runTest {
            val (good, _) = repo.createTrack("good", false, 10_000)
            val seen = readDuringWrite { db.trackDao().maxVersionNumber(good.trackId) }
            repo.createTrack("next", false, 10_000)
            assertEquals(1, seen())
        }

    @Test
    fun saveVersionWritesTheMasterOutsideTheTransaction() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val seen = readDuringWrite { db.trackDao().maxVersionNumber(v1.trackId) }
            repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList())
            assertEquals(1, seen())
        }

    @Test
    fun failedDatabaseWriteDeletesTheWrittenMaster() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            fs.onWrite = { db.close() }
            assertFailsWith<Exception> { repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList()) }
            assertEquals(listOf(v1.audioPath), fakeFs.list("/audio".toPath()).map { it.name })
        }

    @Test
    fun cancellationWhileWritingTheMasterLeavesNoRowAndNoFile() =
        runTest {
            val job = launch { repo.createTrack("p", false, 10_000) }
            fs.onWrite = {
                fs.onWrite = {}
                job.cancel()
            }
            job.join()
            assertTrue(job.isCancelled)
            assertNull(repo.latest())
            assertTrue(fakeFs.list("/audio".toPath()).isEmpty())
        }

    @Test
    fun deleteOrphanMastersRemovesOnlyOldUnreferencedMasters() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            val dir = "/audio".toPath()
            fakeFs.write(dir / "master-orphan.wav") { writeUtf8("x") }
            fakeFs.write(dir / "notes.txt") { writeUtf8("x") }
            repo.deleteOrphanMasters()
            assertTrue(fakeFs.exists(dir / "master-orphan.wav"))
            now = fakeFs.metadata(dir / "master-orphan.wav").lastModifiedAtMillis!! + 60 * 60 * 1000
            repo.deleteOrphanMasters()
            assertEquals(setOf(v1.audioPath, "notes.txt"), fakeFs.list(dir).map { it.name }.toSet())
        }

    @Test
    fun failedSaveVersionKeepsCurrentVersion() =
        runTest {
            val (v1, _) = repo.createTrack("p", false, 10_000)
            fs.failWrites = true
            assertFailsWith<IOException> { repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList()) }
            fs.failWrites = false
            assertEquals(v1, repo.latest())
            val v2 = repo.saveVersion(v1, plan, v1.sourceMap, audio, null, emptyList())
            assertEquals(2, v2.number)
        }

    @Test
    fun setCurrentRejectsForeignVersion() =
        runTest {
            val (a, _) = repo.createTrack("a", false, 10_000)
            val (b, _) = repo.createTrack("b", false, 10_000)
            assertFailsWith<IllegalStateException> { repo.setCurrent(a.trackId, b.versionId) }
            assertEquals(b, repo.latest())
        }
}
