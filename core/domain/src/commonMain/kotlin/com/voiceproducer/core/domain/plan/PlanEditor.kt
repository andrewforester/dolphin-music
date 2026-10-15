package com.voiceproducer.core.domain.plan
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ReferenceChunk

enum class Adherence(
    val wire: String,
) {
    MEDIUM("medium"),
    HIGH("high"),
}

sealed interface PlanEdit {
    data class EditSection(
        val name: String,
        val add: List<String>,
        val remove: List<String>,
        val adherence: Adherence = Adherence.MEDIUM,
    ) : PlanEdit

    data class SetGlobal(
        val add: List<String>,
        val remove: List<String>,
    ) : PlanEdit
}

sealed interface EditResult {
    data class Applied(
        val plan: CompositionPlan,
        val changedSections: List<Int>,
    ) : EditResult

    data class UnknownSection(
        val name: String,
        val available: List<String>,
    ) : EditResult

    data object NoChange : EditResult
}

object PlanEditor {
    private val whitespace = Regex("""\s+""")
    private val trailingNumber = Regex("""\s*\d+$""")

    fun apply(
        plan: CompositionPlan,
        edit: PlanEdit,
    ): EditResult {
        val sections = SectionIndex.sections(plan).filter { plan.chunks[it.index] is GenerationChunk }
        val (targets, rawAdd, rawRemove) =
            when (edit) {
                is PlanEdit.SetGlobal -> {
                    Triple(sections.map { it.index }.toSet(), edit.add, edit.remove)
                }

                is PlanEdit.EditSection -> {
                    val matched = match(sections, edit.name)
                    if (matched.isEmpty()) {
                        return EditResult.UnknownSection(edit.name, sections.map { it.name }.distinct())
                    }
                    Triple(matched.toSet(), edit.add, edit.remove)
                }
            }

        return applyToTargets(plan, edit, targets, rawAdd, rawRemove)
    }

    private fun applyToTargets(
        plan: CompositionPlan,
        edit: PlanEdit,
        targets: Set<Int>,
        rawAdd: List<String>,
        rawRemove: List<String>,
    ): EditResult {
        val remove = cleanStyles(rawRemove)
        val add = cleanStyles(rawAdd).withoutAny(remove)
        if (add.isEmpty() && remove.isEmpty()) return EditResult.NoChange

        val changed = mutableListOf<Int>()
        val chunks =
            plan.chunks.mapIndexed { i, chunk ->
                if (i !in targets || chunk !is GenerationChunk) return@mapIndexed chunk
                val prepended = chunk.positiveStyles.withPrepended(add).withoutAny(remove)
                val conflicts = StyleConflicts.resolve(add, prepended.withoutAny(add))
                val pos = prepended.withoutAny(conflicts.moved)
                val neg =
                    chunk.negativeStyles
                        .withoutAny(add)
                        .withoutAny(StyleConflicts.contradictedNegatives(add, chunk.negativeStyles))
                        .withAdded(remove)
                        .withAdded((conflicts.moved + conflicts.negatives).withoutAny(pos))
                if (pos == chunk.positiveStyles && neg == chunk.negativeStyles) {
                    chunk
                } else {
                    changed += i
                    val adherence = if (edit is PlanEdit.EditSection) edit.adherence.wire else chunk.contextAdherence
                    chunk.copy(positiveStyles = pos, negativeStyles = neg, contextAdherence = adherence)
                }
            }
        return if (changed.isEmpty()) EditResult.NoChange else EditResult.Applied(CompositionPlan(chunks), changed)
    }

    private fun match(
        sections: List<Section>,
        query: String,
    ): List<Int> {
        val q = normalize(query)
        val exact = sections.filter { normalize(it.name) == q }
        return when {
            exact.isNotEmpty() -> exact.map { it.index }
            base(q) != q -> emptyList()
            else -> sections.filter { base(normalize(it.name)) == q }.map { it.index }
        }
    }

    private fun normalize(s: String) = squash(s).lowercase()

    private fun squash(s: String) = s.trim().replace(whitespace, " ")

    private fun cleanStyles(styles: List<String>): List<String> =
        emptyList<String>().withAdded(styles.map(::squash).filter { it.isNotEmpty() })

    private fun base(s: String) = s.replace(trailingNumber, "")

    private fun List<String>.withAdded(styles: List<String>): List<String> {
        val result = toMutableList()
        for (s in styles) if (result.none { it.equals(s, ignoreCase = true) }) result += s
        return result
    }

    private fun List<String>.withPrepended(styles: List<String>): List<String> =
        styles.filter { s -> none { it.equals(s, ignoreCase = true) } } + this

    private fun List<String>.withoutAny(styles: List<String>): List<String> =
        filter { existing -> styles.none { it.equals(existing, ignoreCase = true) } }
}
