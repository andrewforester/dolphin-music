package com.voiceproducer.core.data.remote

import com.voiceproducer.core.model.ApiLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiLogTest {
    @Test
    fun startAddsInFlightEntryAndFinishFillsIt() {
        val lines = mutableListOf<String>()
        val log = ApiLog(clock = { 1234 }, logger = { lines += it })
        val id = log.start("music-plan", "{req}")
        val inFlight = log.entries.value.single()
        assertEquals(ApiLogEntry(id, 1234, "music-plan", "{req}"), inFlight)
        assertTrue(lines.isEmpty())

        log.finish(id, 200, 15, response = "{resp}", songId = "s1")
        val done = log.entries.value.single()
        assertEquals(200, done.status)
        assertEquals(15L, done.durationMs)
        assertEquals("{resp}", done.response)
        assertEquals("s1", done.songId)
        assertNull(done.error)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("#$id music-plan status=200 15ms song=s1"), lines.single())
        assertTrue(lines.single().contains("REQUEST:\n{req}\nRESPONSE:\n{resp}"))
    }

    @Test
    fun capsAtFiftyKeepingTheNewest() {
        val log = ApiLog()
        repeat(60) { log.start("agent-token", "r$it") }
        val entries = log.entries.value
        assertEquals(50, entries.size)
        assertEquals("r10", entries.first().request)
        assertEquals("r59", entries.last().request)
        assertEquals(entries.map { it.id }.sorted(), entries.map { it.id })
        log.finish(1, 200, 1)
        assertEquals(entries, log.entries.value)
    }

    @Test
    fun truncatesLongStringsWithMarker() {
        val log = ApiLog()
        val id = log.start("music-plan", "a".repeat(25_000))
        log.finish(id, 500, 1, response = "b".repeat(20_000), error = "c".repeat(20_001))
        val e = log.entries.value.single()
        assertEquals(20_000 + ApiLog.TRUNCATED_MARKER.length, e.request.length)
        assertTrue(e.request.endsWith("…(truncated)"))
        assertEquals(20_000, e.response!!.length)
        assertTrue(e.error!!.endsWith("…(truncated)"))
    }

    @Test
    fun concurrentAppendsAndUpdatesLoseNothing() =
        runBlocking {
            val log = ApiLog()
            (0 until 8)
                .map {
                    async(Dispatchers.Default) {
                        repeat(5) { n ->
                            val id = log.start("music-plan", "r")
                            log.finish(id, 200, n.toLong())
                        }
                    }
                }.awaitAll()
            val entries = log.entries.value
            assertEquals(40, entries.size)
            assertEquals(40, entries.map { it.id }.toSet().size)
            assertTrue(entries.all { it.status == 200 })
        }
}
