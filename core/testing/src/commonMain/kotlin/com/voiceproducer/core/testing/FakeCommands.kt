package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.agent.ProducerCommands
import com.voiceproducer.core.domain.agent.ToolResult
import com.voiceproducer.core.domain.plan.PlanEdit

class FakeCommands : ProducerCommands {
    val composed = mutableListOf<Triple<String, Boolean, Long>>()
    val edits = mutableListOf<PlanEdit>()
    var undos = 0

    override suspend fun composeTrack(
        prompt: String,
        instrumental: Boolean,
        lengthMs: Long,
    ): ToolResult {
        composed += Triple(prompt, instrumental, lengthMs)
        return ToolResult(true, "composed")
    }

    override suspend fun edit(edit: PlanEdit): ToolResult {
        edits += edit
        return ToolResult(true, "edited")
    }

    override suspend fun undo(): ToolResult {
        undos++
        return ToolResult(true, "undone")
    }
}
