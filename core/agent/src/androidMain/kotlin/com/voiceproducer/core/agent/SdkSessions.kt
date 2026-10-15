package com.voiceproducer.core.agent

import android.content.Context
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SdkSessions(
    private val context: Context,
    private val sdkDispatcher: CoroutineDispatcher,
    private val audioMode: AudioModeRestorer,
) {
    suspend fun startOffMain(config: ConversationConfig): ConversationSession {
        var created: ConversationSession? = null
        try {
            withContext(sdkDispatcher) { created = ConversationClient.startSession(config, context) }
        } catch (e: CancellationException) {
            created?.let { endQuietly(it) }
            throw e
        }
        return checkNotNull(created)
    }

    suspend fun endQuietly(s: ConversationSession) {
        try {
            withContext(NonCancellable + sdkDispatcher) { s.endSession() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            audioMode.afterSessionEnded()
        }
    }
}
