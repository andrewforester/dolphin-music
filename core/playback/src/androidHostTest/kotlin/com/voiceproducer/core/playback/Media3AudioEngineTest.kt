package com.voiceproducer.core.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import com.voiceproducer.core.model.PcmAudio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class Media3AudioEngineTest {
    private val rate = 24_000
    private lateinit var engine: Media3AudioEngine

    private fun sine(
        freq: Double,
        frames: Int = rate,
    ) = PcmAudio(
        ShortArray(frames * 2) { (sin(2 * PI * freq * (it / 2) / rate) * 8_000).roundToInt().toShort() },
        rate,
        2,
    )

    @Before
    fun setUp() {
        engine = Media3AudioEngine(RuntimeEnvironment.getApplication(), clock = FakeClock(true))
    }

    @After
    fun tearDown() {
        engine.release()
    }

    private fun idlePoll() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))

    @Test
    fun loopsAndReportsPosition() {
        engine.load(sine(440.0))
        assertEquals(1_000L, engine.durationMs.value)
        engine.play()
        advance(engine.player).untilPositionAtLeast(600)
        idlePoll()
        assertTrue("isPlaying", engine.isPlaying.value)
        assertTrue("positionMs=${engine.positionMs.value}", engine.positionMs.value >= 600)

        val loops = mutableListOf<Pair<Player.PositionInfo, Player.PositionInfo>>()
        engine.player.addListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    old: Player.PositionInfo,
                    new: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) loops += old to new
                }
            },
        )
        advance(engine.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        val (from, to) = loops.single()
        assertEquals(0, to.mediaItemIndex)
        assertTrue("looped from ${from.positionMs}", from.positionMs >= 600)
        assertTrue("looped to ${to.positionMs}", to.positionMs < 100)
        assertEquals(Player.STATE_READY, engine.player.playbackState)
        idlePoll()
        assertTrue("isPlaying", engine.isPlaying.value)
    }

    @Test
    fun seekToMovesPosition() {
        engine.load(sine(440.0))
        engine.play()
        advance(engine.player).untilPositionAtLeast(100)
        assertTrue("isPlaying", engine.isPlaying.value)
        engine.pause()
        advance(engine.player).untilPendingCommandsAreFullyHandled()
        assertFalse("isPlaying after pause", engine.isPlaying.value)
        assertEquals("final position on pause", engine.player.currentPosition, engine.positionMs.value)

        engine.seekTo(700)
        advance(engine.player).untilPendingCommandsAreFullyHandled()
        idlePoll()
        assertEquals(700L, engine.player.currentPosition)
        assertEquals(700L, engine.positionMs.value)
    }

    @Test
    fun unloadStopsPlaybackAndResetsPositionAndDuration() {
        engine.load(sine(440.0))
        engine.play()
        advance(engine.player).untilPositionAtLeast(300)
        idlePoll()
        engine.unload()
        idlePoll()
        assertEquals(0L, engine.durationMs.value)
        assertEquals(0L, engine.positionMs.value)
        assertEquals(false, engine.isPlaying.value)
        assertEquals(0, engine.player.mediaItemCount)
        engine.unload()
        engine.load(sine(440.0))
        assertEquals(1_000L, engine.durationMs.value)
    }

    @Test
    fun positionPollStopsWhilePaused() {
        engine.load(sine(440.0))
        advance(engine.player).untilState(Player.STATE_READY)
        val polled = engine.positionMs.value
        engine.player.seekTo(300)
        advance(engine.player).untilPendingCommandsAreFullyHandled()
        idlePoll()
        assertEquals(polled, engine.positionMs.value)
    }

    @Test
    fun audioTrackBufferIsShortForResponsiveDucking() {
        val provider = Media3AudioEngine.audioTrackBufferSizeProvider()
        val frameBytes = 4
        val bytes =
            provider.getBufferSizeInBytes(
                48_000 * frameBytes / 50,
                C.ENCODING_PCM_16BIT,
                DefaultAudioSink.OUTPUT_MODE_PCM,
                frameBytes,
                48_000,
                Format.NO_VALUE,
                1.0,
            )
        val ms = bytes / frameBytes * 1_000 / 48_000
        assertTrue("buffer ms=$ms", ms in 80..120)
    }

    @Test
    fun backwardSeekWithAPendingSwapPlaysTheNewAudioFromTheTarget() {
        val old = sine(440.0)
        val next = PcmAudio(ShortArray(old.samples.size) { (old.samples[it] / 2).toShort() }, rate, 2)
        val written = ByteArrayOutputStream()
        val listener =
            ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
                synchronized(written) { written.write(data) }
            }
        engine.load(old)
        engine.play()
        advance(engine.player).untilPositionAtLeast(500)
        engine.swapMaster(next, listOf(0L until 1_000L))
        assertEquals(0, engine.swapsApplied.value)
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            engine.seekTo(200)
            val states = mutableListOf<Int>()
            engine.player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        states += playbackState
                    }
                },
            )
            advance(engine.player).untilPendingCommandsAreFullyHandled()
            val handledAt = synchronized(written) { written.size() }
            advance(engine.player).untilBackgroundThreadCondition {
                synchronized(written) { written.size() >= handledAt + 8 * 4_800 }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("states $states", states.none { it == Player.STATE_IDLE || it == Player.STATE_ENDED })
            assertTrue("isPlaying", engine.isPlaying.value)
            assertEquals(1, engine.swapsApplied.value)
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
        val samples = synchronized(written) { written.toByteArray() }.toShorts()
        val from = 2 * 4_800
        val fadeIn = 2 * rate / 50
        val expected = next.samples.copyOfRange(from + fadeIn, from + 2 * 4_800)
        val at =
            (0..samples.size - expected.size step 2).firstOrNull { o ->
                expected.indices.all { samples[o + it] == expected[it] }
            }
        assertTrue("new audio from 200 ms after the seek", at != null)
    }

    @Test
    fun forwardSeekInsideTheBufferWithAPendingSwapPlaysTheNewAudioFromTheTarget() {
        val old = sine(440.0, frames = 10 * rate)
        val next = PcmAudio(ShortArray(old.samples.size) { (old.samples[it] / 2).toShort() }, rate, 2)
        engine.load(old)
        advance(engine.player).untilState(Player.STATE_READY)
        RobolectricUtil.runMainLooperUntil { engine.player.bufferedPosition >= 1_500 }
        val target = engine.player.currentPosition + 300
        assertTrue(
            "target $target inside the buffer up to ${engine.player.bufferedPosition}",
            engine.player.bufferedPosition > target + 500,
        )
        engine.swapMaster(next, listOf(0L until 10_000L))
        val written = ByteArrayOutputStream()
        val listener =
            ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
                synchronized(written) { written.write(data) }
            }
        ShadowAudioTrack.addAudioDataListener(listener)
        val heardFrames = 2 * rate
        try {
            engine.seekTo(target)
            engine.play()
            advance(engine.player).untilBackgroundThreadCondition {
                synchronized(written) { written.size() >= heardFrames * 4 }
            }
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
        assertEquals(1, engine.swapsApplied.value)
        val samples = synchronized(written) { written.toByteArray() }.toShorts()
        val from = (2 * target * rate / 1_000).toInt()
        val fadeIn = 2 * rate / 50
        val heard = samples.copyOfRange(fadeIn, 2 * heardFrames)
        val expected = next.samples.copyOfRange(from + fadeIn, from + 2 * heardFrames)
        assertTrue("new audio from the target, no old prefix", heard.contentEquals(expected))
        RobolectricUtil.runMainLooperUntil { engine.isPlaying.value }
        assertTrue("isPlaying", engine.isPlaying.value)
    }

    @Test
    fun swapIsAppliedOnNextPass() {
        assertSwapIsHeardOnALaterPass(listOf(200L until 400L)) { it.fill(0, 2 * 4_800, 2 * 9_600) }
    }

    @Test
    fun fullCoverSwapIsAppliedOnALaterPass() {
        assertSwapIsHeardOnALaterPass(listOf(0L until 1_000L)) { s -> for (i in s.indices) s[i] = (s[i] / 2).toShort() }
    }

    private fun assertSwapIsHeardOnALaterPass(
        diffMs: List<LongRange>,
        edit: (ShortArray) -> Unit,
    ) {
        val old = sine(440.0)
        val next = PcmAudio(old.samples.copyOf().also(edit), rate, 2)
        val written = ByteArrayOutputStream()
        val listener =
            ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
                synchronized(written) { written.write(data) }
            }
        val passBytes = old.samples.size * 2
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            engine.load(old)
            engine.play()
            advance(engine.player).untilPositionAtLeast(500)
            engine.swapMaster(next, diffMs)
            advance(engine.player).untilBackgroundThreadCondition {
                synchronized(written) { written.size() >= PASSES_TO_CAPTURE * passBytes }
            }
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }

        assertEquals(1, engine.swapsApplied.value)
        val samples = synchronized(written) { written.toByteArray() }.toShorts()
        val passes =
            (0 until PASSES_TO_CAPTURE).map { p ->
                samples.copyOfRange(p * old.samples.size, (p + 1) * old.samples.size)
            }
        val fadeIn = 2 * rate / 50
        assertTrue(
            "first pass is the old master",
            passes[0].copyOfRange(fadeIn, old.samples.size).contentEquals(old.samples.copyOfRange(fadeIn, old.samples.size)),
        )
        val kinds =
            passes.drop(1).map {
                when {
                    it.contentEquals(old.samples) -> "old"
                    it.contentEquals(next.samples) -> "new"
                    else -> "mixed"
                }
            }
        val firstNew = kinds.indexOf("new")
        assertTrue("passes: $kinds", firstNew >= 0)
        assertEquals("passes: $kinds", List(firstNew) { "old" } + List(kinds.size - firstNew) { "new" }, kinds)
    }

    private fun ByteArray.toShorts(): ShortArray =
        ShortArray(size / 2).also {
            ByteBuffer
                .wrap(this)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(it)
        }

    private companion object {
        const val PASSES_TO_CAPTURE = 12
    }
}
