package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.plan.PlanEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptLinesTest {
    private fun tool(
        kind: ToolKind,
        section: String? = null,
        detail: String? = null,
    ) = TranscriptItem.Tool(1, kind, section, detail, ToolStatus.Running)

    @Test
    fun toolLineTextPerKind() {
        assertEquals("♪ Writing a new track", toolLineText(tool(ToolKind.Compose)))
        assertEquals(
            "✎ Changing Verse 2 — heavier drums, male baritone",
            toolLineText(tool(ToolKind.EditSection, "Verse 2", "heavier drums, male baritone")),
        )
        assertEquals("✎ Changing Chorus", toolLineText(tool(ToolKind.EditSection, "Chorus")))
        assertEquals("✎ Changing a section — brass", toolLineText(tool(ToolKind.EditSection, null, "brass")))
        assertEquals("↻ Changing the whole track — jazz", toolLineText(tool(ToolKind.SetGlobal, detail = "jazz")))
        assertEquals("↻ Changing the whole track", toolLineText(tool(ToolKind.SetGlobal)))
        assertEquals("↶ Undo", toolLineText(tool(ToolKind.Undo)))
        assertEquals("🗑 New track", toolLineText(tool(ToolKind.NewTrack)))
    }

    @Test
    fun statusMarks() {
        assertNull(toolStatusMark(ToolStatus.Running))
        assertEquals("✓", toolStatusMark(ToolStatus.Done))
        assertEquals("✗", toolStatusMark(ToolStatus.Failed))
    }

    @Test
    fun editDetailIsAddedStylesElseRemovedOnesWithNo() {
        assertEquals("heavier drums, male baritone", editDetail(listOf(" heavier drums ", "", "male baritone"), listOf("vocals")))
        assertEquals("no vocals", editDetail(emptyList(), listOf("vocals")))
        assertEquals("no vocals, no drums", editDetail(listOf("  "), listOf("vocals", " drums")))
        assertNull(editDetail(emptyList(), emptyList()))
        assertNull(editDetail(listOf(" "), listOf("")))
    }

    @Test
    fun toolLineForEdits() {
        assertEquals(
            tool(ToolKind.EditSection, "chorus", "brass"),
            toolLineFor(1, PlanEdit.EditSection("chorus", listOf("brass"), emptyList())),
        )
        assertEquals(
            tool(ToolKind.SetGlobal, null, "no rock"),
            toolLineFor(1, PlanEdit.SetGlobal(emptyList(), listOf("rock"))),
        )
    }

    @Test
    fun revealStartsWithOneWordAndGrowsAtTheSpeakingPace() {
        val text = "one two three four five six seven eight nine ten"
        assertEquals(1, revealedWordCount(text, 0))
        assertEquals(1, revealedWordCount(text, -50))
        assertEquals(1, revealedWordCount(text, 384))
        assertEquals(2, revealedWordCount(text, 385))
        assertEquals(3, revealedWordCount(text, 1_000))
        assertEquals(10, revealedWordCount(text, 60_000))
    }

    @Test
    fun revealIsMonotonicAndCapped() {
        val text = "a b c d e f g"
        var last = 0
        for (ms in 0L..5_000L step 17) {
            val n = revealedWordCount(text, ms)
            assertTrue(n >= last, "count went down at $ms ms")
            assertTrue(n in 1..7)
            last = n
        }
        assertEquals(7, last)
    }

    @Test
    fun revealByWordTotalMatchesRevealByText() {
        val text = "one two three four"
        for (ms in listOf(-5L, 0L, 400L, 900L, 5_000L)) assertEquals(revealedWordCount(text, ms), revealedWords(4, ms))
        assertEquals(0, revealedWords(0, 1_000))
    }

    @Test
    fun revealOfEmptyTextIsZero() {
        assertEquals(0, revealedWordCount("", 1_000))
        assertEquals(0, revealedWordCount("   \n ", 0))
        assertEquals("", revealedText("", 3))
    }

    @Test
    fun revealedTextKeepsOriginalSpacingUpToTheNthWord() {
        val text = "  Sure,  adding\nbrass now."
        assertEquals("", revealedText(text, 0))
        assertEquals("  Sure,", revealedText(text, 1))
        assertEquals("  Sure,  adding\nbrass", revealedText(text, 3))
        assertEquals(text, revealedText(text, 4))
        assertEquals(text, revealedText(text, 99))
    }
}
