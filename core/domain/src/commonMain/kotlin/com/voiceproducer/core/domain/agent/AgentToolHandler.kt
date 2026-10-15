package com.voiceproducer.core.domain.agent

import com.voiceproducer.core.domain.plan.Adherence
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.runCatchingExceptions

interface ProducerCommands {
    suspend fun composeTrack(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): ToolResult

    suspend fun edit(edit: PlanEdit): ToolResult

    suspend fun undo(): ToolResult
}

class AgentToolHandler(
    private val commands: ProducerCommands,
) : ToolHandler {
    override suspend fun handle(
        name: String,
        params: Map<String, Any?>,
    ): ToolResult =
        runCatchingExceptions { dispatch(name, params) }
            .getOrElse { e -> ToolResult(false, "error: ${e.message ?: e::class.simpleName}") }

    private suspend fun dispatch(
        name: String,
        params: Map<String, Any?>,
    ): ToolResult =
        when (name) {
            "compose_track" -> {
                composeTrack(params)
            }

            "edit_section" -> {
                val section = params.string("name") ?: return ToolResult(false, "missing section name")
                commands.edit(
                    PlanEdit.EditSection(section, params.strings("add_styles"), params.strings("remove_styles"), params.adherence()),
                )
            }

            "set_global" -> {
                commands.edit(PlanEdit.SetGlobal(params.strings("add"), params.strings("remove")))
            }

            "undo_edit" -> {
                commands.undo()
            }

            else -> {
                ToolResult(false, "unknown tool '$name'")
            }
        }

    private suspend fun composeTrack(params: Map<String, Any?>): ToolResult {
        val prompt = params.string("prompt") ?: return ToolResult(false, "missing prompt")
        val instrumental = params["instrumental"] as? Boolean ?: false
        val lengthS = ((params["length_s"] as? Number)?.toLong() ?: DEFAULT_LENGTH_S).coerceIn(MIN_LENGTH_S, MAX_LENGTH_S)
        return commands.composeTrack(prompt, instrumental, lengthS * MS_PER_SECOND)
    }

    private fun Map<String, Any?>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.adherence(): Adherence {
        val value = (this["context_adherence"] as? String)?.trim().orEmpty()
        return Adherence.entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Adherence.MEDIUM
    }

    private fun Map<String, Any?>.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

    private companion object {
        const val MS_PER_SECOND = 1000L
        const val DEFAULT_LENGTH_S = 75L
        const val MIN_LENGTH_S = 45L
        const val MAX_LENGTH_S = 120L
    }
}
