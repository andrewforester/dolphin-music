package com.voiceproducer.feature.producer

import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.TimeRange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProducerUiMappingTest {
    @Test
    fun sectionKindFromName() {
        assertEquals(SectionKind.Intro, sectionKind("Intro"))
        assertEquals(SectionKind.Verse, sectionKind("verse 2"))
        assertEquals(SectionKind.Chorus, sectionKind("Final CHORUS"))
        assertEquals(SectionKind.Chorus, sectionKind("Hook"))
        assertEquals(SectionKind.Bridge, sectionKind("Bridge"))
        assertEquals(SectionKind.Outro, sectionKind("Outro"))
        assertEquals(SectionKind.Outro, sectionKind("Ending"))
        assertEquals(SectionKind.Other, sectionKind("Section 3"))
        assertEquals(SectionKind.Other, sectionKind("Drop"))
        mapOf(
            "Pre-Chorus" to SectionKind.Chorus,
            "Final Chorus" to SectionKind.Chorus,
            "HOOK 2" to SectionKind.Chorus,
            "Ending" to SectionKind.Outro,
            "Verse 1" to SectionKind.Verse,
            "Intro (soft)" to SectionKind.Intro,
            "Middle 8" to SectionKind.Other,
        ).forEach { (name, kind) -> assertEquals(kind, sectionKind(name), name) }
    }

    private fun gen(
        pos: List<String>,
        neg: List<String> = emptyList(),
    ) = GenerationChunk(text = "[X]", durationMs = 1_000, positiveStyles = pos, negativeStyles = neg)

    @Test
    fun chipsAreActiveStylesCommonToEveryGenerationChunk() {
        val plan =
            CompositionPlan(
                listOf(
                    gen(listOf("Lo-Fi", "Warm Pads", "piano"), listOf("Vocals", "harsh")),
                    ReferenceChunk("A", TimeRange(0, 1_000)),
                    gen(listOf("lo-fi", "warm pads", "brass"), listOf("vocals")),
                ),
            )
        assertEquals(
            listOf(StyleChip("Lo-Fi"), StyleChip("Warm Pads")),
            styleChips(plan),
        )
    }

    @Test
    fun chipsCappedAtEightAndEmptyWithoutGenerationChunks() {
        val many = (1..10).map { "s$it" }
        assertEquals(many.take(8).map(::StyleChip), styleChips(CompositionPlan(listOf(gen(many, listOf("n"))))))
        assertTrue(styleChips(CompositionPlan(listOf(gen(emptyList(), listOf("vocals"))))).isEmpty())
        assertTrue(styleChips(CompositionPlan(emptyList())).isEmpty())
        assertTrue(styleChips(CompositionPlan(listOf(ReferenceChunk("A", TimeRange(0, 1_000))))).isEmpty())
    }

    @Test
    fun musicLevelIsLinearGain() {
        assertEquals(1f, musicLevel(0f))
        assertTrue(abs(musicLevel(-6f) - 0.501f) < 0.001f)
        assertTrue(abs(musicLevel(-18f) - 0.126f) < 0.001f)
        assertEquals(1f, musicLevel(3f))
    }

    @Test
    fun transcriptCappedAtThirty() {
        var t = emptyList<TranscriptItem>()
        repeat(35) { t = t.append(TranscriptItem.User(it.toLong(), "u$it")) }
        assertEquals(30, t.size)
        assertEquals(TranscriptItem.User(5, "u5"), t.first())
        assertEquals(TranscriptItem.User(34, "u34"), t.last())
    }

    @Test
    fun agentTextContinuationReplacesLastAgentItem() {
        val t =
            emptyList<TranscriptItem>()
                .append(TranscriptItem.Agent(1, "Sure,"))
                .append(TranscriptItem.Agent(2, "Sure, adding drums."))
        assertEquals(listOf(TranscriptItem.Agent(1, "Sure, adding drums.")), t)
        val undo = TranscriptItem.Tool(4, ToolKind.Undo, null, null, ToolStatus.Done)
        val t2 =
            t
                .append(TranscriptItem.Agent(3, "Done."))
                .append(undo)
                .append(TranscriptItem.Agent(5, "Done. Anything else?"))
        assertEquals(
            listOf(
                TranscriptItem.Agent(1, "Sure, adding drums."),
                TranscriptItem.Agent(3, "Done."),
                undo,
                TranscriptItem.Agent(5, "Done. Anything else?"),
            ),
            t2,
        )
    }

    @Test
    fun voiceLevelRisesAtOnceAndFallsSmoothly() {
        assertEquals(0.8f, smoothVoiceLevel(0.1f, 0.8f))
        val falling = smoothVoiceLevel(0.8f, 0f)
        assertTrue(falling > 0f && falling < 0.8f, "falls part way: $falling")
        assertEquals(0f, smoothVoiceLevel(0.02f, 0f), "snaps to silence")
        assertEquals(1f, smoothVoiceLevel(0f, 1.7f), "clamped to 1")
        assertEquals(0f, smoothVoiceLevel(0f, -0.3f), "clamped to 0")
    }
}
