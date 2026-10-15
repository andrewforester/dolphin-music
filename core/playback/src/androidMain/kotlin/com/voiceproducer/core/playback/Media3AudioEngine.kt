package com.voiceproducer.core.playback

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.dsp.SwappableMaster
import com.voiceproducer.core.model.DuckTarget
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3AudioEngine(
    private val context: Context,
    clock: Clock = Clock.DEFAULT,
) : AudioEngine {
    private val ducking = DuckingAudioProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _positionMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _durationMs = MutableStateFlow(0L)
    private val _swapsApplied = MutableStateFlow(0)

    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    override val swapsApplied: StateFlow<Int> = _swapsApplied.asStateFlow()

    private var master: SwappableMaster? = null
    private var mediaSourceFactory: ProgressiveMediaSource.Factory? = null

    private var reopening = false
    private var swapForwarding: Job? = null
    private var positionPoll: Job? = null

    @VisibleForTesting
    internal val player: ExoPlayer =
        ExoPlayer
            .Builder(context, DuckingRenderersFactory())
            .setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_MS)
                    .build(),
            ).setClock(clock)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                )
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (!isPlaying && reopening && playWhenReady) return
                            _isPlaying.value = isPlaying
                            if (isPlaying) startPositionPoll() else stopPositionPoll()
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_BUFFERING || !reopening) return
                            reopening = false
                            onIsPlayingChanged(isPlaying)
                        }
                    },
                )
            }

    private fun startPositionPoll() {
        positionPoll?.cancel()
        positionPoll =
            scope.launch {
                while (isActive) {
                    publishPosition()
                    delay(POSITION_POLL_MS)
                }
            }
    }

    private fun stopPositionPoll() {
        positionPoll?.cancel()
        positionPoll = null
        publishPosition()
    }

    private fun publishPosition() {
        _positionMs.value = player.currentPosition
    }

    override fun load(master: PcmAudio) {
        val swappable = SwappableMaster(master)
        this.master = swappable
        swapForwarding?.cancel()
        _swapsApplied.value = 0
        swapForwarding =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                swappable.swaps.collect { _swapsApplied.value = it }
            }
        _durationMs.value = master.framesToMs(master.frames)
        val dataSourceFactory = DataSource.Factory { MasterDataSource(swappable) }
        val factory = ProgressiveMediaSource.Factory(dataSourceFactory, DefaultExtractorsFactory())
        mediaSourceFactory = factory
        reopening = false
        player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(MasterDataSource.URI)))
        player.prepare()
        publishPosition()
    }

    override fun unload() {
        if (master == null) return
        player.pause()
        player.stop()
        player.clearMediaItems()
        swapForwarding?.cancel()
        swapForwarding = null
        master = null
        _swapsApplied.value = 0
        _durationMs.value = 0
        publishPosition()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(ms: Long) {
        val factory = mediaSourceFactory
        if (factory != null && master?.hasPending == true) {
            reopening = true
            player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(MasterDataSource.URI)), ms)
            player.prepare()
        } else {
            player.seekTo(ms)
        }
        publishPosition()
    }

    override fun swapMaster(
        next: PcmAudio,
        diffRangesMs: List<LongRange>,
    ) {
        val current = checkNotNull(master) { "Nothing loaded" }
        current.requestSwap(next, diffRangesMs.map { SwappableMaster.framesCovering(it, next.sampleRate) })
    }

    override fun setDuck(target: DuckTarget) = ducking.setTarget(target)

    override fun release() {
        scope.cancel()
        player.release()
    }

    private inner class DuckingRenderersFactory : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink
                .Builder(context)
                .setAudioProcessors(arrayOf(ducking))
                .setAudioOutputProvider(
                    AudioTrackAudioOutputProvider
                        .Builder(context)
                        .setAudioTrackBufferSizeProvider(audioTrackBufferSizeProvider())
                        .build(),
                ).build()
    }

    internal companion object {
        fun audioTrackBufferSizeProvider(): DefaultAudioSink.AudioTrackBufferSizeProvider =
            DefaultAudioTrackBufferSizeProvider
                .Builder()
                .setTargetPcmBufferDurationUs(TARGET_PCM_BUFFER_US)
                .build()

        private const val POSITION_POLL_MS = 50L
        private const val TARGET_PCM_BUFFER_US = 100_000
        private const val MIN_BUFFER_MS = 1_000
        private const val MAX_BUFFER_MS = 2_000
        private const val BUFFER_FOR_PLAYBACK_MS = 250
    }
}
