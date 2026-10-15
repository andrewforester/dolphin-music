package com.voiceproducer.core.agent

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class AudioModeRestorer(
    context: Context,
    private val scope: CoroutineScope,
    private val lifecycle: Mutex,
    private val sessionLive: () -> Boolean,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val pending = AtomicReference<Job?>(null)

    fun afterSessionEnded() {
        val am = audio ?: return
        val job =
            scope.launch {
                val modeChanged = Channel<Unit>(Channel.CONFLATED)
                val listener =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        listenForModeChanges(am) { modeChanged.trySend(Unit) }
                    } else {
                        null
                    }
                try {
                    val start = TimeSource.Monotonic.markNow()
                    for (at in CHECK_DELAYS_MS) {
                        while (true) {
                            val left = at.milliseconds - start.elapsedNow()
                            val changed = if (left.isPositive()) withTimeoutOrNull(left) { modeChanged.receive() } else null
                            if (changed == null) break
                            checkWithRetry(am)
                        }
                        checkWithRetry(am)
                    }
                } finally {
                    if (listener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.removeOnModeChangedListener(listener)
                    }
                }
            }
        pending.getAndSet(job)?.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenForModeChanges(
        am: AudioManager,
        onChange: () -> Unit,
    ): AudioManager.OnModeChangedListener {
        val listener = AudioManager.OnModeChangedListener { onChange() }
        am.addOnModeChangedListener({ it.run() }, listener)
        return listener
    }

    private suspend fun checkWithRetry(am: AudioManager) {
        if (check(am) == AudioModeDecision.SKIP_BUSY) {
            delay(BUSY_RETRY_MS)
            check(am)
        }
    }

    private fun check(am: AudioManager): AudioModeDecision {
        if (!lifecycle.tryLock()) {
            val mode = am.mode
            return AudioModeDecision.SKIP_BUSY.also { log(mode, it, mode) }
        }
        try {
            val before = am.mode
            val sdk = Build.VERSION.SDK_INT
            val callState = if (sdk < Build.VERSION_CODES.S) readCallState() else null
            val decision = decideAudioModeRestore(sdk, before, callState, sessionLive())
            if (decision == AudioModeDecision.RESTORE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
                am.mode = AudioManager.MODE_NORMAL
            }
            log(before, decision, if (decision == AudioModeDecision.RESTORE) am.mode else before)
            return decision
        } finally {
            lifecycle.unlock()
        }
    }

    @Suppress("DEPRECATION")
    private fun readCallState(): Int? =
        try {
            telephony?.callState
        } catch (_: SecurityException) {
            null
        }

    private fun log(
        before: Int,
        decision: AudioModeDecision,
        after: Int,
    ) = Log.i(TAG, "audio mode after session: $decision (mode $before -> $after)")

    companion object {
        private const val TAG = "VP-Audio"

        val CHECK_DELAYS_MS = listOf(1_000L, 3_000L)
        const val BUSY_RETRY_MS = 500L
    }
}

internal enum class AudioModeDecision {
    RESTORE,
    SKIP_SESSION_ACTIVE,

    SKIP_BUSY,

    SKIP_NOT_IN_COMMUNICATION,

    SKIP_CALL_ACTIVE,

    SKIP_CALL_STATE_UNKNOWN,
}

internal fun decideAudioModeRestore(
    sdkInt: Int,
    currentMode: Int,
    callState: Int?,
    sessionActive: Boolean,
): AudioModeDecision =
    when {
        sessionActive -> AudioModeDecision.SKIP_SESSION_ACTIVE
        currentMode != AudioManager.MODE_IN_COMMUNICATION -> AudioModeDecision.SKIP_NOT_IN_COMMUNICATION
        sdkInt >= Build.VERSION_CODES.S -> AudioModeDecision.RESTORE
        callState == null -> AudioModeDecision.SKIP_CALL_STATE_UNKNOWN
        callState != TelephonyManager.CALL_STATE_IDLE -> AudioModeDecision.SKIP_CALL_ACTIVE
        else -> AudioModeDecision.RESTORE
    }
