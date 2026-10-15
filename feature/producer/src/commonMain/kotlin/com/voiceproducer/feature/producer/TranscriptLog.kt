package com.voiceproducer.feature.producer

import com.voiceproducer.core.domain.repository.ConversationHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class TranscriptLog(
    private val state: MutableStateFlow<ProducerUiState>,
    private val history: ConversationHistory,
) {
    var lastItemId = 0L

    private var storeTrackId: Long? = null

    private var failureNotice: FailureNotice? = null

    private val storedIds = mutableMapOf<Long, Long>()

    private val historyWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val historyRestored = CompletableDeferred<Unit>()

    var storing = true

    suspend fun restore(
        trackId: Long?,
        trackLoaded: () -> Boolean,
    ) {
        try {
            if (trackLoaded()) return
            storeTrackId = trackId
            val entries = history.recent(trackId, TRANSCRIPT_LIMIT)
            if (trackLoaded()) return
            val restored =
                entries.mapNotNull { entry ->
                    transcriptItem(entry.message, lastItemId + 1)?.also {
                        lastItemId = it.id
                        storedIds[it.id] = entry.id
                    }
                }
            if (restored.isNotEmpty()) {
                state.update { it.copy(transcript = (restored + it.transcript).takeLast(TRANSCRIPT_LIMIT)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            historyRestored.complete(Unit)
        }
    }

    suspend fun runWrites() {
        historyRestored.await()
        for (write in historyWrites) {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun startHistory(trackId: Long) {
        historyWrites.trySend {
            storeTrackId = trackId
            history.assignPending(trackId)
        }
    }

    fun startPending(clearPending: suspend () -> Unit) {
        historyWrites.trySend {
            storeTrackId = null
            clearPending()
        }
    }

    fun add(item: TranscriptItem) {
        state.update { it.copy(transcript = it.transcript.append(item)) }
        store(state.value.transcript.last())
    }

    fun addTool(kind: ToolKind): Long {
        val id = ++lastItemId
        add(TranscriptItem.Tool(id, kind, section = null, detail = null, status = ToolStatus.Running))
        return id
    }

    fun noticeFailure(
        section: String,
        reason: String,
    ) {
        val open =
            failureNotice?.takeIf {
                it.id ==
                    state.value.transcript
                        .lastOrNull()
                        ?.id && it.reason == reason
            }
        val sections = open?.sections.orEmpty() + section
        val notice = TranscriptItem.Notice(open?.id ?: ++lastItemId, "Couldn't regenerate ${listed(sections)}: $reason")
        failureNotice = FailureNotice(notice.id, reason, sections)
        state.update {
            it.copy(
                transcript =
                    if (open == null) {
                        it.transcript.append(notice)
                    } else {
                        it.transcript.map { item -> if (item.id == notice.id) notice else item }
                    },
            )
        }
        store(notice)
    }

    fun updateTool(
        id: Long,
        change: (TranscriptItem.Tool) -> TranscriptItem.Tool,
    ) {
        state.update { it.copy(transcript = it.transcript.updateTool(id, change)) }
        state.value.transcript
            .firstOrNull { it.id == id }
            ?.let(::store)
    }

    fun finishTool(
        id: Long,
        landed: Boolean,
    ) = updateTool(id) { it.copy(status = if (landed) ToolStatus.Done else ToolStatus.Failed) }

    fun store(item: TranscriptItem) {
        if (!storing) return
        val message = storedMessage(item)
        historyWrites.trySend {
            val id = storedIds[item.id]
            if (id == null) storedIds[item.id] = history.append(storeTrackId, message) else history.update(id, message)
        }
    }

    private class FailureNotice(
        val id: Long,
        val reason: String,
        val sections: List<String>,
    )
}

private fun listed(names: List<String>): String =
    if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
