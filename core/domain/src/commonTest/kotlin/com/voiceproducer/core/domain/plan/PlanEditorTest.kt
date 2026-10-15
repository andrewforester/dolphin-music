package com.voiceproducer.core.domain.plan

import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlanEditorTest {
    private fun chunk(
        name: String,
        pos: List<String> = emptyList(),
        neg: List<String> = emptyList(),
    ) = GenerationChunk("[$name]\nla", 1000, pos, neg)

    private fun applied(r: EditResult) = assertIs<EditResult.Applied>(r)

    private fun threeSections() =
        CompositionPlan(
            listOf(chunk("Chorus 1"), chunk("Verse"), chunk("Chorus 2")),
        )

    @Test
    fun addMovesStyleFromNegativeToPositive() {
        val plan = CompositionPlan(listOf(chunk("Chorus", pos = listOf("synth"), neg = listOf("big drums"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("big drums"), emptyList())))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("big drums", "synth"), c.positiveStyles)
        assertEquals(emptyList(), c.negativeStyles)
        assertEquals(listOf(0), r.changedSections)
    }

    @Test
    fun addedStylesGoFirstInTheGivenOrder() {
        val plan = CompositionPlan(listOf(chunk("Chorus", pos = listOf("pop", "synth", "warm pads"))))
        val r =
            applied(
                PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("deep baritone vocal", "subdued harmony"), emptyList())),
            )
        assertEquals(
            listOf("deep baritone vocal", "subdued harmony", "pop", "synth", "warm pads"),
            (r.plan.chunks[0] as GenerationChunk).positiveStyles,
        )
        val g = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("lofi", "Synth", "tape"), listOf("pop"))))
        assertEquals(listOf("lofi", "tape", "synth", "warm pads"), (g.plan.chunks[0] as GenerationChunk).positiveStyles)
    }

    @Test
    fun removeAddsToNegative() {
        val plan = CompositionPlan(listOf(chunk("Chorus", pos = listOf("vocals", "synth"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("Chorus", emptyList(), listOf("Vocals"))))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("synth"), c.positiveStyles)
        assertEquals(listOf("Vocals"), c.negativeStyles)
    }

    @Test
    fun noDuplicatesCaseInsensitive() {
        val plan = CompositionPlan(listOf(chunk("Chorus", pos = listOf("big drums"))))
        assertEquals(
            EditResult.NoChange,
            PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("Big Drums"), emptyList())),
        )
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("Big Drums", "big drums", "bass"), emptyList())))
        assertEquals(listOf("bass", "big drums"), (r.plan.chunks[0] as GenerationChunk).positiveStyles)
    }

    @Test
    fun editByBaseNameHitsAllOccurrences() {
        val r = applied(PlanEditor.apply(threeSections(), PlanEdit.EditSection("chorus", listOf("x"), emptyList())))
        assertEquals(listOf(0, 2), r.changedSections)
        assertEquals(emptyList(), (r.plan.chunks[1] as GenerationChunk).positiveStyles)
    }

    @Test
    fun editByFullNameHitsOne() {
        val r = applied(PlanEditor.apply(threeSections(), PlanEdit.EditSection("CHORUS 2", listOf("x"), emptyList())))
        assertEquals(listOf(2), r.changedSections)
    }

    @Test
    fun numberedQueryWithoutExactMatchIsUnknown() {
        val r = PlanEditor.apply(threeSections(), PlanEdit.EditSection("Chorus 3", listOf("x"), emptyList()))
        assertEquals(EditResult.UnknownSection("Chorus 3", listOf("Chorus 1", "Verse", "Chorus 2")), r)
    }

    @Test
    fun plainNameDoesNotMatchDifferentBaseName() {
        val plan = CompositionPlan(listOf(chunk("Chorus"), chunk("Final Chorus")))
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("x"), emptyList())))
        assertEquals(listOf(0), r.changedSections)
    }

    @Test
    fun unknownSectionListsAvailable() {
        val r = PlanEditor.apply(threeSections(), PlanEdit.EditSection("bridge", listOf("x"), emptyList()))
        assertEquals(EditResult.UnknownSection("bridge", listOf("Chorus 1", "Verse", "Chorus 2")), r)
    }

    @Test
    fun availableNamesAreDistinct() {
        val plan = CompositionPlan(listOf(chunk("Verse"), chunk("Chorus"), chunk("Verse")))
        val r = PlanEditor.apply(plan, PlanEdit.EditSection("bridge", listOf("x"), emptyList()))
        assertEquals(EditResult.UnknownSection("bridge", listOf("Verse", "Chorus")), r)
    }

    @Test
    fun setGlobalChangesAll() {
        val r = applied(PlanEditor.apply(threeSections(), PlanEdit.SetGlobal(listOf("lofi"), emptyList())))
        assertEquals(listOf(0, 1, 2), r.changedSections)
    }

    @Test
    fun changedSectionsListsOnlyActuallyChanged() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("lofi")), chunk("B")))
        val r = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("lofi"), emptyList())))
        assertEquals(listOf(1), r.changedSections)
    }

    @Test
    fun noChangeWhenStylesAlreadyPresent() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("lofi"), neg = listOf("vocals"))))
        assertEquals(EditResult.NoChange, PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("LoFi"), listOf("Vocals"))))
    }

    @Test
    fun referenceChunkIsUntouchedAndNeverMatched() {
        val ref = ReferenceChunk("song", TimeRange(0, 1000))
        val plan = CompositionPlan(listOf(ref, chunk("Chorus")))
        val r = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("x"), emptyList())))
        assertEquals(ref, r.plan.chunks[0])
        assertEquals(listOf(1), r.changedSections)
        assertEquals(
            EditResult.UnknownSection("section 1", listOf("Chorus")),
            PlanEditor.apply(plan, PlanEdit.EditSection("section 1", listOf("x"), emptyList())),
        )
    }

    @Test
    fun blankStylesAreIgnoredAndEmptyEditIsNoChange() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("lofi"))))
        assertEquals(EditResult.NoChange, PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("", "   "), listOf("\t"))))
        assertEquals(EditResult.NoChange, PlanEditor.apply(plan, PlanEdit.SetGlobal(emptyList(), emptyList())))
    }

    @Test
    fun untrimmedStylesAreNormalizedAndNotDuplicated() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("big drums"))))
        assertEquals(EditResult.NoChange, PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf(" Big   Drums "), emptyList())))
        val r = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("  deep   bass "), emptyList())))
        assertEquals(listOf("deep bass", "big drums"), (r.plan.chunks[0] as GenerationChunk).positiveStyles)
    }

    @Test
    fun styleInBothAddAndRemoveIsRemoved() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("vocals"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("Vocals", "synth"), listOf("vocals"))))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("synth"), c.positiveStyles)
        assertEquals(listOf("vocals"), c.negativeStyles)
    }

    @Test
    fun sectionNameWhitespaceIsCollapsed() {
        val r = applied(PlanEditor.apply(threeSections(), PlanEdit.EditSection("chorus  2", listOf("x"), emptyList())))
        assertEquals(listOf(2), r.changedSections)
        val r2 = applied(PlanEditor.apply(threeSections(), PlanEdit.EditSection("Chorus\t2", listOf("x"), emptyList())))
        assertEquals(listOf(2), r2.changedSections)
    }

    @Test
    fun editSectionUsesRequestedAdherence() {
        val plan =
            CompositionPlan(
                listOf(
                    chunk("Verse").copy(contextAdherence = "medium"),
                    chunk("Chorus").copy(contextAdherence = "medium"),
                ),
            )
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("brass"), emptyList(), Adherence.HIGH)))
        assertEquals(plan.chunks[0], r.plan.chunks[0])
        assertEquals("high", (r.plan.chunks[1] as GenerationChunk).contextAdherence)
    }

    @Test
    fun editSectionSetsMediumAdherenceOnChangedChunksOnly() {
        val plan =
            CompositionPlan(
                listOf(
                    chunk("Verse").copy(contextAdherence = "high"),
                    chunk("Chorus").copy(contextAdherence = "high"),
                ),
            )
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("chorus", listOf("brass"), emptyList())))
        assertEquals(plan.chunks[0], r.plan.chunks[0])
        val c = r.plan.chunks[1] as GenerationChunk
        assertEquals("medium", c.contextAdherence)
        assertEquals(listOf("brass"), c.positiveStyles)
    }

    @Test
    fun setGlobalLeavesAdherenceAlone() {
        val plan = CompositionPlan(listOf(chunk("A").copy(contextAdherence = "low"), chunk("B")))
        val r = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("lofi"), emptyList())))
        assertEquals(listOf("low", null), r.plan.chunks.map { (it as GenerationChunk).contextAdherence })
    }

    @Test
    fun editSectionMovesConflictingVoiceToNegatives() {
        val plan = CompositionPlan(listOf(chunk("Verse", pos = listOf("female tenor vocals", "dream pop", "warm pads"))))
        val r =
            applied(
                PlanEditor.apply(plan, PlanEdit.EditSection("verse", listOf("male baritone lead vocal"), emptyList())),
            )
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("male baritone lead vocal", "dream pop", "warm pads"), c.positiveStyles)
        assertEquals(listOf("female tenor vocals", "female vocals"), c.negativeStyles)
        assertEquals("medium", c.contextAdherence)
    }

    @Test
    fun setGlobalAlsoAppliesConflicts() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("male vocals", "rock"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.SetGlobal(listOf("female soprano"), emptyList())))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("female soprano", "rock"), c.positiveStyles)
        assertEquals(listOf("male vocals"), c.negativeStyles)
    }

    @Test
    fun explicitRemovesStillLandInNegativesAndAddedIsNeverNegative() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("female vocals", "pop"), neg = listOf("male vocals"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("a", listOf("male vocals"), listOf("pop"))))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("male vocals"), c.positiveStyles)
        assertEquals(listOf("pop", "female vocals"), c.negativeStyles)
    }

    @Test
    fun instrumentalAddMovesVocalsToNegatives() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("female vocals", "pop"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("a", listOf("instrumental"), emptyList())))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("instrumental", "pop"), c.positiveStyles)
        assertEquals(listOf("female vocals", "vocals"), c.negativeStyles)
    }

    @Test
    fun redundantEditLeavesAdherenceAlone() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("pop")).copy(contextAdherence = "high")))
        assertEquals(EditResult.NoChange, PlanEditor.apply(plan, PlanEdit.EditSection("a", listOf("pop"), emptyList())))
    }

    @Test
    fun flippingGenderBackClearsStaleCanonicalNegative() {
        val plan =
            CompositionPlan(
                listOf(chunk("A", pos = listOf("male baritone lead vocal", "pop"), neg = listOf("female vocals", "female tenor vocals"))),
            )
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("a", listOf("female soprano"), emptyList())))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("female soprano", "pop"), c.positiveStyles)
        assertEquals(listOf("male baritone lead vocal", "male vocals").sorted(), c.negativeStyles.sorted())
    }

    @Test
    fun readdingVocalsAfterInstrumentalClearsStaleNegatives() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("instrumental", "pop"), neg = listOf("vocals", "female vocals"))))
        val r = applied(PlanEditor.apply(plan, PlanEdit.EditSection("a", listOf("female vocals"), emptyList())))
        val c = r.plan.chunks[0] as GenerationChunk
        assertEquals(listOf("female vocals", "pop"), c.positiveStyles)
        assertEquals(listOf("instrumental", "male vocals"), c.negativeStyles)
    }

    @Test
    fun addingBothGendersAddsNoGenderNegatives() {
        val plan = CompositionPlan(listOf(chunk("A", pos = listOf("pop"))))
        val r =
            applied(
                PlanEditor.apply(plan, PlanEdit.EditSection("a", listOf("male lead vocals", "female backing vocals"), emptyList())),
            )
        assertEquals(emptyList(), (r.plan.chunks[0] as GenerationChunk).negativeStyles)
    }
}
