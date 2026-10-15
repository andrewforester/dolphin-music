package com.voiceproducer.core.domain.plan
import com.voiceproducer.core.model.Chunk
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk

data class Section(
    val index: Int,
    val name: String,
    val startMs: Long,
    val endMs: Long,
)

object SectionIndex {
    private val header = Regex("""^\[(.+?)]""")

    fun sections(plan: CompositionPlan): List<Section> {
        var offset = 0L
        return plan.chunks.mapIndexed { i, chunk ->
            val start = offset
            offset += chunk.durationMs
            Section(i, nameOf(chunk, i), start, offset)
        }
    }

    private fun nameOf(
        chunk: Chunk,
        index: Int,
    ): String {
        val firstLine = (chunk as? GenerationChunk)?.text?.lineSequence()?.firstOrNull()
        return firstLine
            ?.let { header.find(it) }
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Section ${index + 1}"
    }
}
