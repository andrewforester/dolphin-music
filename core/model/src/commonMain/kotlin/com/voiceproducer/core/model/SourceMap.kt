package com.voiceproducer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SourceSegment(
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val songId: String,
    val sourceStartMs: Long,
) {
    val lengthMs: Long get() = timelineEndMs - timelineStartMs
    val sourceEndMs: Long get() = sourceStartMs + lengthMs
}

@Serializable
data class SourceMap(
    val segments: List<SourceSegment>,
) {
    init {
        var expectedStart = 0L
        for (s in segments) {
            require(s.timelineStartMs == expectedStart) { "Segments must be contiguous from 0: $s" }
            require(s.timelineEndMs > s.timelineStartMs) { "Empty segment: $s" }
            expectedStart = s.timelineEndMs
        }
    }

    private val totalMs: Long get() = segments.lastOrNull()?.timelineEndMs ?: 0L

    fun refs(
        fromMs: Long,
        toMs: Long,
    ): List<ReferenceChunk> {
        requireRange(fromMs, toMs)
        return segments
            .filter { it.timelineStartMs < toMs && it.timelineEndMs > fromMs }
            .map {
                val start = maxOf(fromMs, it.timelineStartMs)
                val end = minOf(toMs, it.timelineEndMs)
                val offset = it.sourceStartMs - it.timelineStartMs
                ReferenceChunk(it.songId, TimeRange(start + offset, end + offset))
            }
    }

    fun replace(
        fromMs: Long,
        toMs: Long,
        songId: String,
        sourceStartMs: Long,
    ): SourceMap {
        requireRange(fromMs, toMs)
        val left =
            segments
                .filter { it.timelineStartMs < fromMs }
                .map { it.copy(timelineEndMs = minOf(it.timelineEndMs, fromMs)) }
        val right =
            segments.filter { it.timelineEndMs > toMs }.map {
                val start = maxOf(it.timelineStartMs, toMs)
                SourceSegment(start, it.timelineEndMs, it.songId, it.sourceStartMs + (start - it.timelineStartMs))
            }
        val result = left + SourceSegment(fromMs, toMs, songId, sourceStartMs) + right
        return SourceMap(merge(result))
    }

    private fun requireRange(
        fromMs: Long,
        toMs: Long,
    ) {
        require(fromMs >= 0 && fromMs < toMs && toMs <= totalMs) { "Invalid range [$fromMs, $toMs) for total $totalMs" }
    }

    private fun merge(list: List<SourceSegment>): List<SourceSegment> {
        val out = mutableListOf<SourceSegment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && last.songId == s.songId && last.sourceEndMs == s.sourceStartMs) {
                out[out.lastIndex] = last.copy(timelineEndMs = s.timelineEndMs)
            } else {
                out += s
            }
        }
        return out
    }

    companion object {
        fun single(
            songId: String,
            totalMs: Long,
        ): SourceMap {
            require(totalMs > 0) { "totalMs must be positive" }
            return SourceMap(listOf(SourceSegment(0, totalMs, songId, 0)))
        }
    }
}
