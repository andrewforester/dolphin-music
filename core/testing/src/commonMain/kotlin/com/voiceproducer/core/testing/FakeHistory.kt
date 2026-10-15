package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.model.HistoryEntry
import com.voiceproducer.core.model.StoredMessage
import kotlinx.coroutines.CompletableDeferred

class FakeHistory : ConversationHistory {
    class Row(
        val id: Long,
        var trackId: Long?,
        var message: StoredMessage,
    )

    val rows = mutableListOf<Row>()
    val cleared = mutableListOf<Long?>()

    override suspend fun append(
        trackId: Long?,
        message: StoredMessage,
    ): Long {
        val id = rows.size + 1L
        rows += Row(id, trackId, message)
        return id
    }

    override suspend fun update(
        id: Long,
        message: StoredMessage,
    ) {
        rows.first { it.id == id }.message = message
    }

    var recentGate: CompletableDeferred<Unit>? = null

    override suspend fun recent(
        trackId: Long?,
        limit: Int,
    ): List<HistoryEntry> {
        recentGate?.await()
        return rows.filter { it.trackId == trackId }.takeLast(limit).map { HistoryEntry(it.id, it.message) }
    }

    override suspend fun assignPending(trackId: Long) {
        rows.filter { it.trackId == null }.forEach { it.trackId = trackId }
    }

    override suspend fun clear(trackId: Long?) {
        cleared += trackId
        rows.removeAll { it.trackId == trackId }
    }

    fun of(trackId: Long?): List<StoredMessage> = rows.filter { it.trackId == trackId }.map { it.message }
}
