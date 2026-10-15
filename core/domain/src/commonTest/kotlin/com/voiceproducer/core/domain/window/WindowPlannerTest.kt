package com.voiceproducer.core.domain.window

import com.voiceproducer.core.domain.plan.SectionIndex
import com.voiceproducer.core.dsp.Mixer
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.PcmAudio
import com.voiceproducer.core.model.ReferenceChunk
import com.voiceproducer.core.model.SourceMap
import com.voiceproducer.core.model.TimeRange
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowPlannerTest {
    private val rate = 24_000
    private val planner = WindowPlanner()

    private fun gen(
        name: String,
        ms: Long,
    ) = GenerationChunk(text = "[$name]\nla la", durationMs = ms)

    private val plan =
        CompositionPlan(
            listOf(gen("Intro", 8_000), gen("Verse", 20_000), gen("Chorus", 16_000), gen("Outro", 20_000)),
        )
    private val sections = SectionIndex.sections(plan)
    private val sourceMap = SourceMap.single("A", 64_000)

    private fun ref(
        id: String,
        s: Long,
        e: Long,
    ) = ReferenceChunk(id, TimeRange(s, e))

    private fun frames(ms: Long) = ms * rate / 1000

    private fun silence(
        ms: Long,
        sampleRate: Int = rate,
        channels: Int = 1,
    ) = PcmAudio(ShortArray((ms * sampleRate / 1000 * channels).toInt()), sampleRate, channels)

    private val master by lazy { silence(64_000) }

    @Test
    fun orderStartsWithNextUpcoming() {
        assertEquals(listOf(3, 1), planner.order(listOf(1, 3), sections, positionMs = 30_000))
        assertEquals(listOf(1, 3), planner.order(listOf(1, 3), sections, positionMs = 5_000))
    }

    @Test
    fun orderPutsCurrentlyPlayingSectionLast() {
        assertEquals(listOf(2, 0, 1), planner.order(listOf(0, 1, 2), sections, positionMs = 10_000))
    }

    @Test
    fun orderRejectsUnknownSection() {
        assertFailsWith<IllegalArgumentException> { planner.order(listOf(1, 4), sections, positionMs = 0) }
    }

    @Test
    fun requestHasLeadSectionTail() {
        val r = planner.request(plan, sourceMap, 2)
        assertEquals(3, r.plan.chunks.size)
        assertEquals(ref("A", 25_000, 28_000), r.plan.chunks[0])
        val g = assertIs<GenerationChunk>(r.plan.chunks[1])
        assertEquals(16_000, g.durationMs)
        assertEquals("high", g.contextAdherence)
        assertEquals(ref("A", 44_000, 47_000), r.plan.chunks[2])
        assertEquals(25_000, r.windowStartMs)
        assertEquals(3_000, r.leadMs)
        assertEquals(3_000, r.tailMs)
        assertEquals(22_000, r.expectedMs)
        assertEquals(sections[2], r.section)
    }

    @Test
    fun requestKeepsOtherGenerationFields() {
        val styled =
            GenerationChunk(
                text = "[Chorus]\nhey",
                durationMs = 16_000,
                positiveStyles = listOf("big drums"),
                negativeStyles = listOf("lofi"),
                contextAdherence = "low",
                conditioningRef = JsonPrimitive("x"),
                conditionStrength = JsonPrimitive(0.5),
            )
        val p = CompositionPlan(plan.chunks.toMutableList().also { it[2] = styled })
        val r = planner.request(p, sourceMap, 2)
        assertEquals(styled, r.plan.chunks[1])
    }

    @Test
    fun neverEditedChunkGoesOutAsHighAndEditedAsMedium() {
        assertEquals("high", (planner.request(plan, sourceMap, 2).plan.chunks[1] as GenerationChunk).contextAdherence)
        val edited = (plan.chunks[2] as GenerationChunk).copy(contextAdherence = "medium")
        val p = CompositionPlan(plan.chunks.toMutableList().also { it[2] = edited })
        assertEquals("medium", (planner.request(p, sourceMap, 2).plan.chunks[1] as GenerationChunk).contextAdherence)
    }

    @Test
    fun requestAtTrackStartHasNoLead() {
        val r = planner.request(plan, sourceMap, 0)
        assertIs<GenerationChunk>(r.plan.chunks.first())
        assertEquals(0, r.leadMs)
        assertEquals(0, r.windowStartMs)
        assertEquals(ref("A", 8_000, 11_000), r.plan.chunks.last())
        assertEquals(11_000, r.expectedMs)
    }

    @Test
    fun requestAtTrackEndHasNoTail() {
        val r = planner.request(plan, sourceMap, 3)
        assertIs<GenerationChunk>(r.plan.chunks.last())
        assertEquals(0, r.tailMs)
        assertEquals(23_000, r.expectedMs)
    }

    @Test
    fun requestUsesLatestSongInSourceMap() {
        val r = planner.request(plan, sourceMap.replace(25_000, 47_000, "B", 0), 3)
        assertEquals(ref("B", 16_000, 19_000), r.plan.chunks.first())
    }

    @Test
    fun requestOmitsContextShorterThan50Ms() {
        val p = CompositionPlan(listOf(gen("Pickup", 40), gen("Verse", 20_000), gen("Outro", 30)))
        val r = planner.request(p, SourceMap.single("A", 20_070), 1)
        assertEquals(listOf(gen("Verse", 20_000).copy(contextAdherence = "high")), r.plan.chunks)
        assertEquals(0, r.leadMs)
        assertEquals(0, r.tailMs)
        assertEquals(40, r.windowStartMs)
        assertEquals(20_000, r.expectedMs)
    }

    @Test
    fun requestDropsShortSlicesAtOuterEndsOfContext() {
        val map = sourceMap.replace(25_030, 46_970, "B", 0)
        val r = planner.request(plan, map, 2)
        assertEquals(ref("B", 0, 2_970), r.plan.chunks.first())
        assertEquals(ref("B", 18_970, 21_940), r.plan.chunks.last())
        assertEquals(3, r.plan.chunks.size)
        assertEquals(2_970, r.leadMs)
        assertEquals(2_970, r.tailMs)
        assertEquals(25_030, r.windowStartMs)
        assertTrue(
            r.plan.chunks
                .filterIsInstance<ReferenceChunk>()
                .all { it.durationMs >= 50 },
        )
    }

    @Test
    fun requestDropsEverythingOutsideAnInnerShortSlice() {
        val map = sourceMap.replace(27_000, 27_030, "B", 0)
        val r = planner.request(plan, map, 2)
        assertEquals(ref("A", 27_030, 28_000), r.plan.chunks.first())
        assertEquals(970, r.leadMs)
        assertEquals(27_030, r.windowStartMs)
    }

    @Test
    fun requestRejectsNonGenerationSection() {
        val p = CompositionPlan(plan.chunks.toMutableList().also { it[2] = ref("A", 28_000, 44_000) })
        assertFailsWith<IllegalArgumentException> { planner.request(p, sourceMap, 2) }
    }

    @Test
    fun spliceFadesCenteredInContext() {
        val r = planner.request(plan, sourceMap, 2)
        val s = planner.splice(r, silence(22_000), master)
        assertEquals(frames(25_000), s.startFrame)
        assertEquals(frames(25_000) + 36_000 - 2_400, s.fadeInStartFrame)
        assertEquals(frames(44_000) + 36_000 - 2_400, s.fadeOutStartFrame)
        assertEquals(4_800, s.fadeFrames)
    }

    @Test
    fun spliceAtTrackStartFadesInAtWindowStartAndAtEndHasNoFadeOut() {
        val first = planner.splice(planner.request(plan, sourceMap, 0), silence(11_000), master)
        assertEquals(0, first.startFrame)
        assertEquals(0, first.fadeInStartFrame)
        val last = planner.splice(planner.request(plan, sourceMap, 3), silence(23_000), master)
        assertNull(last.fadeOutStartFrame)
    }

    @Test
    fun spliceFadesAtSectionBoundary() {
        val p = WindowPlanner(placement = FadePlacement.AtSectionBoundary)
        val s = p.splice(p.request(plan, sourceMap, 2), silence(22_000), master)
        assertEquals(frames(25_000), s.startFrame)
        assertEquals(frames(28_000) - 2_400, s.fadeInStartFrame)
        assertEquals(frames(44_000) - 2_400, s.fadeOutStartFrame)
        val first = p.splice(p.request(plan, sourceMap, 0), silence(11_000), master)
        assertEquals(0, first.fadeInStartFrame)
        val last = p.splice(p.request(plan, sourceMap, 3), silence(23_000), master)
        assertNull(last.fadeOutStartFrame)
    }

    @Test
    fun spliceClampsFadesToShortContext() {
        val p = CompositionPlan(listOf(gen("A", 60), gen("B", 10_000), gen("C", 60)))
        val m = silence(10_120)
        for (placement in FadePlacement.entries) {
            val wp = WindowPlanner(placement = placement)
            val r = wp.request(p, SourceMap.single("A", 10_120), 1)
            assertEquals(60, r.leadMs)
            assertEquals(60, r.tailMs)
            val s = wp.splice(r, silence(10_120), m)
            assertEquals(0, s.fadeInStartFrame, "$placement")
            assertEquals(frames(10_120) - 4_800, s.fadeOutStartFrame, "$placement")
        }
    }

    @Test
    fun splicesAreAcceptedByMixerForBothPlacements() {
        for (placement in FadePlacement.entries) {
            val wp = WindowPlanner(placement = placement)
            for (i in sections.indices) {
                val r = wp.request(plan, sourceMap, i)
                val out = Mixer.bake(master, wp.splice(r, silence(r.expectedMs), master))
                assertEquals(master.frames, out.frames, "$placement section $i")
            }
        }
    }

    @Test
    fun spliceFadesBackInsideSectionWhenMidTrackTailIsEmpty() {
        val map = sourceMap.replace(44_000, 44_030, "B", 0)
        for (placement in FadePlacement.entries) {
            val wp = WindowPlanner(placement = placement)
            val r = wp.request(plan, map, 2)
            assertEquals(0, r.tailMs)
            val s = wp.splice(r, silence(r.expectedMs), master)
            assertEquals(frames(44_000) - 4_800, s.fadeOutStartFrame, "$placement")
            assertEquals(master.frames, Mixer.bake(master, s).frames)
        }
    }

    @Test
    fun spliceFadesInAtWindowStartWhenMidTrackLeadIsEmpty() {
        val map = sourceMap.replace(27_970, 28_000, "B", 0)
        for (placement in FadePlacement.entries) {
            val wp = WindowPlanner(placement = placement)
            val r = wp.request(plan, map, 2)
            assertEquals(0, r.leadMs)
            val s = wp.splice(r, silence(r.expectedMs), master)
            assertEquals(frames(28_000), s.startFrame, "$placement")
            assertEquals(frames(28_000), s.fadeInStartFrame, "$placement")
            assertEquals(master.frames, Mixer.bake(master, s).frames)
        }
    }

    @Test
    fun spliceTrimsSlightlyLongAudio() {
        val r = planner.request(plan, sourceMap, 2)
        val s = planner.splice(r, silence(22_080), master)
        assertEquals(frames(22_000), s.audio.frames)
    }

    @Test
    fun splicePadsSlightlyShortAudioWithSilence() {
        val r = planner.request(plan, sourceMap, 2)
        val audio = PcmAudio(ShortArray(frames(21_950).toInt()) { 1000 }, rate, 1)
        val s = planner.splice(r, audio, master)
        assertEquals(frames(22_000), s.audio.frames)
        assertEquals(1000, s.audio.samples[frames(21_950).toInt() - 1].toInt())
        assertEquals(
            0,
            s.audio.samples
                .last()
                .toInt(),
        )
    }

    @Test
    fun spliceRejectsLargeMismatch() {
        val r = planner.request(plan, sourceMap, 2)
        val e = assertFailsWith<WindowLengthMismatch> { planner.splice(r, silence(21_500), master) }
        assertEquals(22_000, e.expectedMs)
        assertEquals(21_500, e.actualMs)
    }

    @Test
    fun spliceRejectsFormatMismatch() {
        val r = planner.request(plan, sourceMap, 2)
        assertFailsWith<IllegalArgumentException> { planner.splice(r, silence(22_000, sampleRate = 44_100), master) }
        assertFailsWith<IllegalArgumentException> { planner.splice(r, silence(22_000, channels = 2), master) }
    }
}
