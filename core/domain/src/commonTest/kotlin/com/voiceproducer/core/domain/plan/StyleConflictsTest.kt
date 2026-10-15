package com.voiceproducer.core.domain.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StyleConflictsTest {
    @Test
    fun genderWordsMatchWholeWords() {
        assertEquals(StyleConflicts.Gender.Female, StyleConflicts.gender("Female vocals"))
        assertEquals(StyleConflicts.Gender.Female, StyleConflicts.gender("a woman singing"))
        assertEquals(StyleConflicts.Gender.Male, StyleConflicts.gender("male lead"))
        assertEquals(StyleConflicts.Gender.Male, StyleConflicts.gender("deep MAN voice"))
        assertNull(StyleConflicts.gender("dream pop"))
        assertNull(StyleConflicts.gender("woman and man"))
    }

    @Test
    fun voiceTypesImplyGender() {
        assertEquals(StyleConflicts.Gender.Female, StyleConflicts.gender("soprano lead vocal"))
        assertEquals(StyleConflicts.Gender.Female, StyleConflicts.gender("mezzo-soprano"))
        assertEquals(StyleConflicts.Gender.Female, StyleConflicts.gender("contralto"))
        assertEquals(StyleConflicts.Gender.Male, StyleConflicts.gender("baritone lead vocal"))
        assertEquals(StyleConflicts.Gender.Male, StyleConflicts.gender("bass vocals"))
        assertEquals(StyleConflicts.Gender.Male, StyleConflicts.gender("basso"))
        assertNull(StyleConflicts.gender("tenor"))
        assertEquals(StyleConflicts.Gender.Female, StyleConflicts.gender("female tenor vocals"))
    }

    @Test
    fun duetHasNoGender() {
        assertNull(StyleConflicts.gender("male and female duet vocals"))
    }

    @Test
    fun bassGuitarIsNotAVoiceType() {
        assertEquals(emptySet(), StyleConflicts.voiceTypes("bass guitar"))
        assertEquals(emptySet(), StyleConflicts.voiceTypes("deep bass"))
        assertNull(StyleConflicts.gender("bass guitar"))
        assertEquals(setOf("bass"), StyleConflicts.voiceTypes("Bass Vocal"))
        assertEquals(setOf("mezzo"), StyleConflicts.voiceTypes("mezzo-soprano"))
    }

    @Test
    fun instrumentalAndVocalStyles() {
        assertEquals(true, StyleConflicts.isInstrumental("instrumental"))
        assertEquals(true, StyleConflicts.isInstrumental("No Vocals"))
        assertEquals(false, StyleConflicts.isInstrumental("female vocals"))
        assertEquals(true, StyleConflicts.isVocal("female vocals"))
        assertEquals(true, StyleConflicts.isVocal("choir"))
        assertEquals(false, StyleConflicts.isVocal("no vocals"))
        assertEquals(false, StyleConflicts.isVocal("warm pads"))
    }

    @Test
    fun maleAddedMovesFemaleAndDifferentVoiceTypeToNegatives() {
        val r =
            StyleConflicts.resolve(
                added = listOf("male baritone lead vocal"),
                existing = listOf("female tenor vocals", "dream pop", "warm pads"),
            )
        assertEquals(listOf("female tenor vocals"), r.moved)
        assertEquals(listOf("female vocals"), r.negatives)
    }

    @Test
    fun differentVoiceTypeWithoutGenderConflictIsMoved() {
        val r = StyleConflicts.resolve(listOf("tenor"), listOf("baritone vocals", "pop"))
        assertEquals(listOf("baritone vocals"), r.moved)
        assertEquals(emptyList(), r.negatives)
    }

    @Test
    fun duetAddedConflictsWithNothing() {
        val r = StyleConflicts.resolve(listOf("male and female duet vocals"), listOf("female vocals", "male vocals"))
        assertEquals(emptyList(), r.moved)
        assertEquals(emptyList(), r.negatives)
    }

    @Test
    fun instrumentalAddedMovesVocalsAndViceVersa() {
        val a = StyleConflicts.resolve(listOf("instrumental"), listOf("female vocals", "synth"))
        assertEquals(listOf("female vocals"), a.moved)
        assertEquals(listOf("vocals"), a.negatives)
        val b = StyleConflicts.resolve(listOf("male vocals"), listOf("instrumental", "synth"))
        assertEquals(listOf("instrumental"), b.moved)
        assertEquals(listOf("female vocals", "instrumental"), b.negatives)
    }

    @Test
    fun otherAddedStylesAreNeverMoved() {
        val r = StyleConflicts.resolve(listOf("male vocals", "female backing vocals"), listOf("pop"))
        assertEquals(emptyList(), r.moved)
    }

    @Test
    fun instrumentUsesOfVoiceTypeWordsAreNotVoices() {
        for (style in listOf("baritone sax", "alto saxophone", "tenor sax solo", "soprano recorder", "bass clarinet", "alto flute")) {
            assertEquals(emptySet(), StyleConflicts.voiceTypes(style), style)
            assertNull(StyleConflicts.gender(style), style)
        }
        assertEquals(setOf("baritone"), StyleConflicts.voiceTypes("baritone vocals"))
        assertEquals(setOf("baritone"), StyleConflicts.voiceTypes("male baritone"))
        assertEquals(setOf("alto"), StyleConflicts.voiceTypes("alto"))
        assertEquals(StyleConflicts.Gender.Male, StyleConflicts.gender("male baritone"))
        val r = StyleConflicts.resolve(listOf("male vocals"), listOf("alto sax", "pop"))
        assertEquals(emptyList(), r.moved)
    }

    @Test
    fun addingBothGendersSkipsTheGenderRule() {
        val r = StyleConflicts.resolve(listOf("male lead vocals", "female backing vocals"), listOf("female choir", "male rap"))
        assertEquals(emptyList(), r.moved)
        assertEquals(emptyList(), r.negatives)
    }

    @Test
    fun contradictedNegativesAreFound() {
        assertEquals(
            listOf("female vocals"),
            StyleConflicts.contradictedNegatives(listOf("female soprano"), listOf("female vocals", "male vocals", "lofi")),
        )
        assertEquals(
            listOf("vocals", "instrumental"),
            StyleConflicts.contradictedNegatives(listOf("female vocals"), listOf("vocals", "instrumental", "autotune vocals")),
        )
        assertEquals(emptyList(), StyleConflicts.contradictedNegatives(listOf("male vocals", "female vocals"), listOf("female vocals")))
    }
}
