package com.voiceproducer.feature.producer

import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentNoticesTest {
    @Test
    fun playheadFormat() {
        assertEquals(
            "[system] Producer is at 0:42 · Verse 2 (section 3 of 4)",
            AgentNotices.playhead(42_999, "Verse 2", 3, 4),
        )
        assertEquals("[system] Producer is at 0:05 · Intro (section 1 of 4)", AgentNotices.playhead(5_000, "Intro", 1, 4))
        assertEquals("[system] Producer is at 1:02 · A (section 2 of 2)", AgentNotices.playhead(62_000, "A", 2, 2))
        assertEquals("[system] Producer is at 10:00 · A (section 1 of 1)", AgentNotices.playhead(600_000, "A", 1, 1))
    }

    private fun chunk(
        text: String,
        ms: Long,
        pos: List<String> = emptyList(),
        neg: List<String> = emptyList(),
    ) = GenerationChunk(text = text, durationMs = ms, positiveStyles = pos, negativeStyles = neg)

    @Test
    fun planListsSectionsWithTimesAndStyles() {
        val plan =
            CompositionPlan(
                listOf(
                    chunk("[Intro]\nla la", 8_000, listOf("warm pads", "soft piano"), listOf("drums")),
                    chunk("[Verse 1]\nwords", 18_000, listOf("piano")),
                ),
            )
        val text = AgentNotices.plan(plan)
        assertEquals(
            "Plan (0:26, 2 sections):\n1. Intro 0:00\u20130:08 \u00B7 styles: warm pads, soft piano \u00B7 avoid: drums\n" +
                "2. Verse 1 0:08\u20130:26 \u00B7 styles: piano \u00B7 avoid: \u2014",
            text,
        )
        assertFalse(text.contains("la la"))
        assertFalse(text.contains("words"))
    }

    @Test
    fun planWithOneSectionIsSingular() {
        assertTrue(AgentNotices.plan(CompositionPlan(listOf(chunk("[A]\nx", 10_000)))).startsWith("Plan (0:10, 1 section):"))
    }

    @Test
    fun emptyPlanSaysNoTrackYet() {
        assertEquals("No track yet.", AgentNotices.plan(CompositionPlan(emptyList())))
    }

    @Test
    fun referenceChunkIsListedAsReferenceAudio() {
        val plan = CompositionPlan(listOf(chunk("[A]\nx", 8_000), ReferenceChunk("song", TimeRange(0, 3_000))))
        assertTrue(AgentNotices.plan(plan).lines().contains("2. Section 2 0:08\u20130:11 \u00B7 reference audio"))
    }

    @Test
    fun chunkWithoutHeaderIsNamedByPosition() {
        val plan = CompositionPlan(listOf(chunk("just words", 5_000, listOf("rock"))))
        assertTrue(AgentNotices.plan(plan).lines()[1].startsWith("1. Section 1 "))
    }

    @Test
    fun trackReadyPrefixesSystem() {
        val plan = CompositionPlan(listOf(chunk("[A]\nx", 5_000)))
        assertTrue(AgentNotices.trackReady(plan).startsWith("[system] Track ready. Plan ("))
        assertTrue(AgentNotices.planUpdated(plan).startsWith("[system] Plan updated. Plan ("))
        assertTrue(AgentNotices.reverted(plan).startsWith("[system] Reverted. Plan ("))
    }

    @Test
    fun undoAndRegenerationNoticesArePrefixed() {
        assertEquals("[system] Nothing to undo", AgentNotices.NOTHING_TO_UNDO)
        assertEquals("[system] Undo failed: boom", AgentNotices.undoFailed("boom"))
        assertEquals("[system] Regenerating Chorus failed: boom", AgentNotices.regenerationFailed("Chorus", "boom"))
    }
}
