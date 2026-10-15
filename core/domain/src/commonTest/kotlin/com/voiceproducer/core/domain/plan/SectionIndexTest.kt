package com.voiceproducer.core.domain.plan

import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionIndexTest {
    @Test
    fun sectionsHaveCumulativeOffsets() {
        val s = SectionIndex.sections(planOf(8000, 20_000, 16_000))
        assertEquals(listOf(0L to 8000L, 8000L to 28_000L, 28_000L to 44_000L), s.map { it.startMs to it.endMs })
        assertEquals(listOf(0, 1, 2), s.map { it.index })
    }

    @Test
    fun sectionNameFromBracketTag() {
        val plan =
            CompositionPlan(
                listOf(
                    GenerationChunk("[Chorus]\nla la", 1000),
                    GenerationChunk("[Verse 2 - quiet]\n…", 1000),
                ),
            )
        assertEquals(listOf("Chorus", "Verse 2 - quiet"), SectionIndex.sections(plan).map { it.name })
    }

    @Test
    fun sectionNameFallback() {
        val plan = CompositionPlan(List(3) { GenerationChunk("[A]", 1000) }.take(2) + GenerationChunk("no tag", 1000))
        assertEquals("Section 3", SectionIndex.sections(plan)[2].name)
    }

    @Test
    fun referenceChunkGetsFallbackName() {
        val plan = CompositionPlan(listOf(ReferenceChunk("s", TimeRange(0, 500))))
        assertEquals("Section 1", SectionIndex.sections(plan)[0].name)
    }
}

private fun planOf(vararg durations: Long) =
    CompositionPlan(
        durations.mapIndexed { i, d -> GenerationChunk(text = "[S$i]", durationMs = d) },
    )
