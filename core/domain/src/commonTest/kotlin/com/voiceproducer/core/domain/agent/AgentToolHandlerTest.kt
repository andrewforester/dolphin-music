package com.voiceproducer.core.domain.agent

import com.voiceproducer.core.domain.plan.Adherence
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.testing.FakeCommands
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolHandlerTest {
    private val commands = FakeCommands()
    private val handler = AgentToolHandler(commands)

    @Test
    fun editSectionParsesLists() =
        runTest {
            val result =
                handler.handle(
                    "edit_section",
                    mapOf("name" to "Chorus", "add_styles" to listOf("brass", 3, "big drums"), "remove_styles" to listOf("pads")),
                )
            assertEquals(ToolResult(true, "edited"), result)
            assertEquals(listOf<PlanEdit>(PlanEdit.EditSection("Chorus", listOf("brass", "big drums"), listOf("pads"))), commands.edits)
        }

    @Test
    fun contextAdherenceIsParsedLeniently() =
        runTest {
            suspend fun adherenceFor(params: Map<String, Any?>): Adherence {
                commands.edits.clear()
                handler.handle("edit_section", mapOf("name" to "Chorus", "add_styles" to listOf("brass")) + params)
                return (commands.edits.single() as PlanEdit.EditSection).adherence
            }
            assertEquals(Adherence.HIGH, adherenceFor(mapOf("context_adherence" to "high")))
            assertEquals(Adherence.HIGH, adherenceFor(mapOf("context_adherence" to " HIGH ")))
            assertEquals(Adherence.MEDIUM, adherenceFor(mapOf("context_adherence" to "medium")))
            assertEquals(Adherence.MEDIUM, adherenceFor(mapOf("context_adherence" to "low")))
            assertEquals(Adherence.MEDIUM, adherenceFor(mapOf("context_adherence" to 42)))
            assertEquals(Adherence.MEDIUM, adherenceFor(emptyMap()))
        }

    @Test
    fun setGlobalParsesLists() =
        runTest {
            handler.handle("set_global", mapOf("add" to listOf("lo-fi"), "remove" to listOf("vocals")))
            assertEquals(listOf<PlanEdit>(PlanEdit.SetGlobal(listOf("lo-fi"), listOf("vocals"))), commands.edits)
        }

    @Test
    fun missingListsBecomeEmpty() =
        runTest {
            handler.handle("edit_section", mapOf("name" to "Verse", "add_styles" to "not a list"))
            handler.handle("set_global", emptyMap())
            assertEquals(
                listOf(PlanEdit.EditSection("Verse", emptyList(), emptyList()), PlanEdit.SetGlobal(emptyList(), emptyList())),
                commands.edits,
            )
        }

    @Test
    fun lengthAsDoubleIsAccepted() =
        runTest {
            handler.handle("compose_track", mapOf("prompt" to "lofi beat", "instrumental" to true, "length_s" to 90.0))
            assertEquals(listOf(Triple("lofi beat", true, 90_000L)), commands.composed)
        }

    @Test
    fun lengthClamped() =
        runTest {
            handler.handle("compose_track", mapOf("prompt" to "a", "instrumental" to false, "length_s" to 10))
            handler.handle("compose_track", mapOf("prompt" to "b", "instrumental" to false, "length_s" to 500))
            handler.handle("compose_track", mapOf("prompt" to "c"))
            assertEquals(listOf(45_000L, 120_000L, 75_000L), commands.composed.map { it.third })
            assertFalse(commands.composed.last().second)
        }

    @Test
    fun undoCallsCommands() =
        runTest {
            assertEquals(ToolResult(true, "undone"), handler.handle("undo_edit", emptyMap()))
            assertEquals(1, commands.undos)
        }

    @Test
    fun unknownToolFails() =
        runTest {
            val result = handler.handle("delete_everything", emptyMap())
            assertFalse(result.ok)
            assertTrue("delete_everything" in result.message)
        }

    @Test
    fun missingNameFails() =
        runTest {
            val result = handler.handle("edit_section", mapOf("name" to " ", "add_styles" to listOf("brass")))
            assertFalse(result.ok)
            assertTrue(commands.edits.isEmpty())
        }

    @Test
    fun missingPromptFails() =
        runTest {
            val result = handler.handle("compose_track", mapOf("length_s" to 60))
            assertFalse(result.ok)
            assertTrue(commands.composed.isEmpty())
        }

    @Test
    fun commandFailureBecomesErrorResult() =
        runTest {
            val failing =
                AgentToolHandler(
                    object : ProducerCommands by commands {
                        override suspend fun undo(): ToolResult = error("disk full")
                    },
                )
            assertEquals(ToolResult(false, "error: disk full"), failing.handle("undo_edit", emptyMap()))
        }
}
