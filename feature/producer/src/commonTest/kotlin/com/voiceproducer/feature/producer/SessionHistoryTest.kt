package com.voiceproducer.feature.producer

import com.voiceproducer.core.model.MessageRole
import com.voiceproducer.core.model.StoredMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHistoryTest {
    private val heading = "[system] Earlier conversation on this track, oldest first:"

    @Test
    fun emptyHistorySendsNothing() {
        assertNull(earlierConversation(emptyList()))
    }

    @Test
    fun linesPerRoleOldestFirstWithCollapsedWhitespace() {
        val text =
            earlierConversation(
                listOf(
                    TranscriptItem.User(1, "  make it\n darker  "),
                    TranscriptItem.Agent(2, "On it."),
                    TranscriptItem.Tool(3, ToolKind.EditSection, "Verse 2", "heavier drums", ToolStatus.Done),
                    TranscriptItem.Tool(4, ToolKind.Compose, null, null, ToolStatus.Failed),
                    TranscriptItem.Tool(5, ToolKind.SetGlobal, null, "jazz", ToolStatus.Running),
                    TranscriptItem.Tool(6, ToolKind.Undo, null, null, ToolStatus.Done),
                ),
            )
        assertEquals(
            listOf(
                heading,
                "Producer: make it darker",
                "Composer: On it.",
                "Tool: Changing Verse 2 — heavier drums ✓",
                "Tool: Writing a new track ✗",
                "Tool: Changing the whole track — jazz",
                "Tool: Undo ✓",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun keepsTheLastTwentyMessages() {
        val items = (1..25L).map { TranscriptItem.User(it, "m$it") }
        val lines = earlierConversation(items)!!.lines()
        assertEquals(heading, lines.first())
        assertEquals((6..25).map { "Producer: m$it" }, lines.drop(1))
    }

    @Test
    fun dropsOldestLinesUntilItFitsFourThousandChars() {
        val items = (1..10L).map { TranscriptItem.Agent(it, "x".repeat(990) + it) }
        val text = earlierConversation(items)!!
        assertTrue(text.length <= 4000, "length ${text.length}")
        val lines = text.lines()
        assertEquals(heading, lines.first())
        assertEquals(listOf("8", "9", "10"), lines.drop(1).map { it.removePrefix("Composer: " + "x".repeat(990)) })
    }

    @Test
    fun aSingleOversizedLineIsCutToFit() {
        val text = earlierConversation(listOf(TranscriptItem.User(1, "y".repeat(5000))))!!
        assertEquals(4000, text.length)
        assertTrue(text.startsWith("$heading\nProducer: yyy"))
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun firstMessageContinuesOnlyWithHistory() {
        assertNull(firstMessageOverride(emptyList()))
        assertEquals("Let's continue.", firstMessageOverride(listOf(TranscriptItem.Agent(1, "Hi"))))
    }

    @Test
    fun idleLabelOffersToContinueWithHistory() {
        assertEquals("Mic off · Tap to talk", idleMicLabel(emptyList()))
        assertEquals("Mic off · Tap to continue", idleMicLabel(listOf(TranscriptItem.User(1, "hi"))))
        assertEquals("Mic off · Tap to continue", idleMicLabel(listOf(TranscriptItem.Agent(1, "hi"))))
        assertEquals(
            "Mic off · Tap to continue",
            idleMicLabel(listOf(TranscriptItem.Tool(1, ToolKind.Compose, null, null, ToolStatus.Done))),
        )
    }

    @Test
    fun linesRoundTripThroughTheStore() {
        val items =
            listOf(
                TranscriptItem.User(4, "hi"),
                TranscriptItem.Agent(5, "Hello"),
                TranscriptItem.Tool(6, ToolKind.EditSection, "Verse 2", "heavier drums", ToolStatus.Done),
                TranscriptItem.Tool(7, ToolKind.Undo, null, null, ToolStatus.Failed),
                TranscriptItem.Notice(8, "Couldn't regenerate Chorus: Not enough ElevenLabs credits"),
            )
        assertEquals(
            StoredMessage(MessageRole.Tool, "Changing Verse 2 — heavier drums", "EditSection", "Done", "Verse 2", "heavier drums"),
            storedMessage(items[2]),
        )
        assertEquals(items, items.map { transcriptItem(storedMessage(it), it.id) })
    }

    @Test
    fun aRunningToolRestoresAsFailedAndUnknownRowsAreSkipped() {
        val running = StoredMessage(MessageRole.Tool, "Undo", "Undo", "Running")
        assertEquals(TranscriptItem.Tool(1, ToolKind.Undo, null, null, ToolStatus.Failed), transcriptItem(running, 1))
        assertNull(transcriptItem(StoredMessage(MessageRole.Tool, "?", "Teleport", "Done"), 1))
        assertNull(transcriptItem(StoredMessage(MessageRole.System, "note"), 1))
    }

    @Test
    fun aNoticeIsStoredAsANoticeAndToldToTheNextSession() {
        val notice = TranscriptItem.Notice(1, "Couldn't regenerate Chorus: Not enough ElevenLabs credits")
        assertEquals(StoredMessage(MessageRole.Notice, notice.text), storedMessage(notice))
        assertEquals(
            "$EARLIER_CONVERSATION_HEADING\nNotice: Couldn't regenerate Chorus: Not enough ElevenLabs credits",
            earlierConversation(listOf(notice)),
        )
    }
}
