package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ProxyError

internal object AgentNotices {
    const val TIMELINE_CLEARED =
        "[system] The producer cleared the timeline to start a new track. The previous " +
            "track is saved but no longer loaded; the current plan is empty. Wait for the producer's brief."

    fun playhead(
        positionMs: Long,
        section: String,
        index: Int,
        count: Int,
    ): String = "[system] Producer is at ${clock(positionMs)} \u00B7 $section (section $index of $count)"

    fun trackReady(plan: CompositionPlan): String = "[system] Track ready. " + plan(plan)

    fun planUpdated(plan: CompositionPlan): String = "[system] Plan updated. " + plan(plan)

    fun reverted(plan: CompositionPlan): String = "[system] Reverted. " + plan(plan)

    const val NOTHING_TO_UNDO = "[system] Nothing to undo"

    fun undoFailed(reason: String): String = "[system] Undo failed: $reason"

    fun regenerationFailed(
        section: String,
        reason: String,
    ): String = "[system] Regenerating $section failed: $reason"

    fun plan(plan: CompositionPlan): String {
        if (plan.chunks.isEmpty()) return "No track yet."
        val sections = SectionIndex.sections(plan)
        val count = sections.size
        return buildString {
            append("Plan (${clock(plan.totalMs)}, $count ${if (count == 1) "section" else "sections"}):")
            sections.forEach { s ->
                append("\n${s.index + 1}. ${s.name} ${clock(s.startMs)}\u2013${clock(s.endMs)}")
                val chunk = plan.chunks[s.index] as? GenerationChunk
                if (chunk == null) {
                    append(" \u00B7 reference audio")
                } else {
                    append(" \u00B7 styles: ${chunk.positiveStyles.joinListOrDash()}")
                    append(" \u00B7 avoid: ${chunk.negativeStyles.joinListOrDash()}")
                }
            }
        }
    }

    fun generationFailed(
        reason: String,
        rejected: ProxyError.BadPrompt?,
    ): String =
        if (rejected != null) {
            "[system] Track generation FAILED, no track is coming: the music service rejected the prompt because it " +
                "names artists or copyrighted material. ${rejected.advice("call compose_track again")}"
        } else {
            "[system] Track generation FAILED, no track is coming: $reason. " +
                "Tell the user it failed and ask whether to try again; call compose_track again only if they agree."
        }

    fun editRejected(
        section: String,
        rejected: ProxyError.BadPrompt,
    ): String =
        "[system] The edit to $section FAILED and was not applied: the music service rejected it because the plan " +
            "names artists or copyrighted material. ${rejected.advice("repeat the edit with the new wording")}"

    private fun ProxyError.BadPrompt.advice(nextStep: String): String =
        buildString {
            append("Describe the style without any artist or song names. ")
            if (suggestion != null) append("Suggested prompt: \"$suggestion\". ")
            append("Tell the user what went wrong, offer them this wording, and $nextStep only after the user agrees.")
        }
}

private const val MS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60

private fun List<String>.joinListOrDash(): String = if (isEmpty()) "\u2014" else joinToString(", ")

private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / MS_PER_SECOND
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}
