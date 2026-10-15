package com.voiceproducer.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceMapTest {
    private fun ref(
        id: String,
        s: Long,
        e: Long,
    ) = ReferenceChunk(id, TimeRange(s, e))

    private fun edited() = SourceMap.single("A", 60_000).replace(25_000, 47_000, "B", 0)

    @Test
    fun singleCoversWholeTrack() {
        assertEquals(listOf(SourceSegment(0, 44_000, "A", 0)), SourceMap.single("A", 44_000).segments)
    }

    @Test
    fun refsWithinOneSegment() {
        assertEquals(listOf(ref("A", 25_000, 28_000)), SourceMap.single("A", 44_000).refs(25_000, 28_000))
    }

    @Test
    fun replaceSplitsSegment() {
        assertEquals(
            listOf(
                SourceSegment(0, 25_000, "A", 0),
                SourceSegment(25_000, 47_000, "B", 0),
                SourceSegment(47_000, 60_000, "A", 47_000),
            ),
            edited().segments,
        )
    }

    @Test
    fun replaceAdjustsRightRemainderSourceStart() {
        val m = SourceMap.single("A", 60_000).replace(10_000, 20_000, "B", 500).replace(15_000, 30_000, "C", 100)
        assertEquals(
            listOf(
                SourceSegment(0, 10_000, "A", 0),
                SourceSegment(10_000, 15_000, "B", 500),
                SourceSegment(15_000, 30_000, "C", 100),
                SourceSegment(30_000, 60_000, "A", 30_000),
            ),
            m.segments,
        )
        val n = SourceMap.single("A", 60_000).replace(10_000, 30_000, "B", 0).replace(5_000, 20_000, "C", 0)
        assertEquals(SourceSegment(20_000, 30_000, "B", 10_000), n.segments[2])
    }

    @Test
    fun refsAcrossTwoSongs() {
        assertEquals(
            listOf(ref("B", 19_000, 22_000), ref("A", 47_000, 50_000)),
            edited().refs(44_000, 50_000),
        )
    }

    @Test
    fun replaceMergesAdjacentSameSource() {
        val m = edited().replace(25_000, 47_000, "A", 25_000)
        assertEquals(listOf(SourceSegment(0, 60_000, "A", 0)), m.segments)
    }

    @Test
    fun replaceWholeTrack() {
        val m = edited().replace(0, 60_000, "C", 0)
        assertEquals(listOf(SourceSegment(0, 60_000, "C", 0)), m.segments)
    }

    @Test
    fun invalidArgumentsRejected() {
        val m = SourceMap.single("A", 1000)
        assertFailsWith<IllegalArgumentException> { m.refs(-1, 10) }
        assertFailsWith<IllegalArgumentException> { m.refs(10, 10) }
        assertFailsWith<IllegalArgumentException> { m.refs(0, 1001) }
        assertFailsWith<IllegalArgumentException> { m.replace(5, 5, "B", 0) }
        assertFailsWith<IllegalArgumentException> { m.replace(0, 1001, "B", 0) }
        assertFailsWith<IllegalArgumentException> {
            SourceMap(listOf(SourceSegment(0, 10, "A", 0), SourceSegment(11, 20, "B", 0)))
        }
        assertFailsWith<IllegalArgumentException> { SourceMap(listOf(SourceSegment(5, 10, "A", 0))) }
        assertFailsWith<IllegalArgumentException> { SourceMap(listOf(SourceSegment(0, 0, "A", 0))) }
    }

    @Test
    fun serializationRoundTrip() {
        val m = edited()
        val json = Json.encodeToString(m)
        assertEquals(m, Json.decodeFromString<SourceMap>(json))
    }
}
