package com.voiceproducer.core.playback

import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.dsp.PcmPump
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
import platform.AVFAudio.AVAudioFormat

class IosAudioEngine private constructor(
    private val bufferFrames: Int,
    queueDepth: Int,
    offlineFormat: AVAudioFormat?,
) : AudioEngine {
    constructor(bufferFrames: Int = DEFAULT_BUFFER_FRAMES, queueDepth: Int = 3) : this(bufferFrames, queueDepth, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _positionMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _durationMs = MutableStateFlow(0L)
    private val _swapsApplied = MutableStateFlow(0)

    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    override val swapsApplied: StateFlow<Int> = _swapsApplied.asStateFlow()

    private val graph = AudioGraph(offlineFormat)
    private val feeder = PcmFeeder(graph, queueDepth)

    private var master: SwappableMaster? = null
    private var pump: PcmPump? = null

    private var loadedFormat: AVAudioFormat? = null
    private var playing = false
    private var duck: DuckTarget? = null

    private var heldFrame = 0L
    private var resumeAfterInterruption = false
    private var swapForwarding: Job? = null
    private var positionPoll: Job? = null

    internal val isGraphCreated: Boolean get() = graph.isCreated

    private val sessionObservers =
        SessionObservers(
            graph.engine,
            onConfigurationChange = {
                val fmt = graph.connectedFormat
                if (fmt != null && pump != null) {
                    val wasPlaying = playing
                    val frame = heldFrame
                    stopPlayback(seekTo = frame)
                    graph.reconnect(fmt)
                    heldFrame = frame
                    _positionMs.publish(master, heldFrame)
                    _swapsApplied.syncFrom(master)
                    if (wasPlaying) play()
                }
            },
            onInterruptionBegan = {
                resumeAfterInterruption = playing
                pause()
            },
            onInterruptionEnded = { shouldResume ->
                if (resumeAfterInterruption && shouldResume) {
                    graph.reactivateSession()
                    play()
                }
                resumeAfterInterruption = false
            },
        )

    override fun load(master: PcmAudio) {
        val wasPlaying = playing
        stopPlayback()
        val swappable = SwappableMaster(master)
        val newPump = PcmPump(swappable)
        duck?.let { newPump.setDuck(DuckTarget(it.db, 0)) }
        this.master = swappable
        pump = newPump
        val newFormat = loadedFormat?.takeIf { it.matches(master) } ?: format(master.sampleRate, master.channels)
        loadedFormat = newFormat
        val frames = (bufferFrames.toLong() * master.sampleRate / DEFAULT_SAMPLE_RATE).toInt().coerceAtLeast(1)
        feeder.configure(newPump, newFormat, frames)
        swapForwarding?.cancel()
        _swapsApplied.value = 0
        swapForwarding =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                swappable.swaps.collect { _swapsApplied.value = it }
            }
        _durationMs.value = master.framesToMs(master.frames)
        heldFrame = 0
        _positionMs.publish(this.master, heldFrame)
        if (wasPlaying) play()
    }

    override fun unload() {
        if (master == null) return
        stopPlayback()
        if (graph.isCreated) graph.engine.pause()
        feeder.clear()
        swapForwarding?.cancel()
        swapForwarding = null
        master = null
        pump = null
        heldFrame = 0
        _swapsApplied.value = 0
        _durationMs.value = 0
        _positionMs.value = 0
    }

    override fun play() {
        if (playing || pump == null) return
        graph.ensure(checkNotNull(loadedFormat))
        if (!graph.engine.running && !graph.start()) return
        feeder.start()
        graph.node.play()
        playing = true
        _isPlaying.value = true
        positionPoll?.cancel()
        positionPoll =
            scope.launch {
                while (isActive) {
                    heldFrame = feeder.currentFrame(pump, playing, heldFrame)
                    _positionMs.publish(master, heldFrame)
                    delay(POSITION_POLL_MS)
                }
            }
    }

    override fun pause() {
        if (!playing) return
        val frame = feeder.currentFrame(pump, playing, heldFrame)
        stopPlayback(seekTo = frame)
        graph.engine.pause()
        heldFrame = frame
        _positionMs.publish(master, heldFrame)
        _swapsApplied.syncFrom(master)
    }

    override fun seekTo(ms: Long) {
        val current = master?.current ?: return
        val frame = current.msToFrames(ms.coerceIn(0, _durationMs.value))
        val landed = feeder.seek(frame, restart = playing)
        if (playing) graph.node.play()
        heldFrame = landed
        _positionMs.publish(master, heldFrame)
        _swapsApplied.syncFrom(master)
    }

    override fun swapMaster(
        next: PcmAudio,
        diffRangesMs: List<LongRange>,
    ) {
        val current = checkNotNull(master) { "Nothing loaded" }
        current.requestSwap(next, diffRangesMs.map { SwappableMaster.framesCovering(it, next.sampleRate) })
    }

    override fun setDuck(target: DuckTarget) {
        duck = target
        pump?.setDuck(target)
    }

    override fun release() {
        sessionObservers.remove()
        stopPlayback()
        feeder.clear()
        if (graph.isCreated) graph.engine.stop()
        scope.cancel()
        master = null
        pump = null
    }

    internal fun renderOffline(frames: Int): FloatArray = graph.renderOffline(frames)

    private fun stopPlayback(seekTo: Long? = null) {
        feeder.stop(seekTo)
        if (playing) {
            playing = false
            _isPlaying.value = false
            positionPoll?.cancel()
            positionPoll = null
        }
    }

    internal companion object {
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DEFAULT_BUFFER_FRAMES = DEFAULT_SAMPLE_RATE / 20
        private const val POSITION_POLL_MS = 50L

        internal fun offline(
            format: AVAudioFormat,
            bufferFrames: Int = DEFAULT_BUFFER_FRAMES,
            queueDepth: Int = 3,
        ) = IosAudioEngine(bufferFrames, queueDepth, format)
    }
}

private fun MutableStateFlow<Long>.publish(
    master: SwappableMaster?,
    frame: Long,
) {
    val current = master?.current
    value = if (current == null) 0 else current.framesToMs(frame)
}

private fun MutableStateFlow<Int>.syncFrom(master: SwappableMaster?) {
    master?.let { value = it.swaps.value }
}

private fun AVAudioFormat.matches(audio: PcmAudio): Boolean =
    sampleRate == audio.sampleRate.toDouble() && channelCount.toInt() == audio.channels

private fun format(
    sampleRate: Int,
    channels: Int,
): AVAudioFormat = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = channels.toUInt())
