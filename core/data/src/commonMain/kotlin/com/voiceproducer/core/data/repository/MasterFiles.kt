package com.voiceproducer.core.data.repository

import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class MasterFiles(
    private val store: AudioStore,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun write(master: PcmAudio): String {
        val name = "master-${Uuid.random()}.wav"
        deletingOnFailure(name) { store.write(name, master) }
        return name
    }

    suspend fun <T> committing(block: suspend () -> T): T = withContext(NonCancellable) { block() }

    suspend fun <T> deletingOnFailure(
        name: String,
        block: suspend () -> T,
    ): T =
        runCatching { block() }
            .onFailure { withContext(NonCancellable) { store.delete(name) } }
            .getOrThrow()

    suspend fun normalizeLength(
        audio: PcmAudio,
        totalMs: Long,
    ): PcmAudio =
        withContext(Dispatchers.Default) {
            normalizeLengthBlocking(audio, totalMs)
        }

    private fun normalizeLengthBlocking(
        audio: PcmAudio,
        totalMs: Long,
    ): PcmAudio {
        val target = audio.msToFrames(totalMs)
        val diffFrames = target - audio.frames
        if (diffFrames == 0L) return audio
        val diffMs = audio.framesToMs(kotlin.math.abs(diffFrames))
        check(diffMs <= MAX_LENGTH_DRIFT_MS) {
            "master length mismatch: ${audio.framesToMs(audio.frames)} ms audio vs $totalMs ms plan"
        }
        val samples = audio.samples.copyOf((target * audio.channels).toInt())
        return PcmAudio(samples, audio.sampleRate, audio.channels)
    }

    private companion object {
        const val MAX_LENGTH_DRIFT_MS = 100L
    }
}
