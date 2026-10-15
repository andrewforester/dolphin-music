package com.voiceproducer.core.agent

import com.voiceproducer.core.domain.agent.ToolHandler
import com.voiceproducer.core.domain.runCatchingExceptions
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToolBridge(
    private val names: List<String>,
    private val handler: ToolHandler,
) {
    fun clientTools(): Map<String, ClientTool> = names.associateWith { name -> BridgedTool(name) }

    private inner class BridgedTool(
        private val name: String,
    ) : ClientTool {
        override suspend fun execute(parameters: Map<String, Any>): ClientToolResult =
            runCatchingExceptions {
                val params: Map<String, Any?> = parameters
                val result = withContext(Dispatchers.Default) { handler.handle(name, params) }
                if (result.ok) ClientToolResult.success(result.message) else ClientToolResult.failure(result.message)
            }.getOrElse { e -> ClientToolResult.failure(e.message ?: e::class.java.simpleName) }
    }
}
