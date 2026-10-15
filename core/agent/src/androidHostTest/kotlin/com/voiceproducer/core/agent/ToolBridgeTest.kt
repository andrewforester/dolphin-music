package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.agent.ToolResult
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBridgeTest {
    private val names = listOf("compose_track", "edit_section", "set_global", "undo_edit")

    @Test
    fun createsOneClientToolPerName() {
        val tools = ToolBridge(names, ToolHandler { _, _ -> ToolResult(true, "x") }).clientTools()
        assertEquals(names.toSet(), tools.keys)
    }

    @Test
    fun successMapsToClientToolSuccess() =
        runBlocking {
            var seen: Pair<String, Map<String, Any?>>? = null
            val bridge =
                ToolBridge(names) { name, params ->
                    seen = name to params
                    ToolResult(true, "done")
                }
            val result = bridge.clientTools().getValue("edit_section").execute(mapOf("section" to "intro", "n" to 2))
            assertEquals(ClientToolResult.success("done"), result)
            assertEquals("edit_section", seen?.first)
            assertEquals(mapOf("section" to "intro", "n" to 2), seen?.second)
        }

    @Test
    fun failureMapsToClientToolFailure() =
        runBlocking {
            val bridge = ToolBridge(names) { _, _ -> ToolResult(false, "no such section") }
            val result = bridge.clientTools().getValue("undo_edit").execute(emptyMap())
            assertEquals(ClientToolResult.failure("no such section"), result)
        }

    @Test
    fun exceptionInHandlerBecomesFailure() =
        runBlocking {
            val bridge = ToolBridge(names) { _, _ -> throw IllegalStateException("boom") }
            val result = bridge.clientTools().getValue("set_global").execute(emptyMap())
            assertNotNull(result)
            assertTrue(!result!!.success)
            assertEquals("boom", result.error)
        }
}
