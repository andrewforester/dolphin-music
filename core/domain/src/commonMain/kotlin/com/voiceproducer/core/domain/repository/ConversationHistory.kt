package com.voiceproducer.core.domain.repository
import com.voiceproducer.core.model.HistoryEntry
import com.voiceproducer.core.model.StoredMessage

interface ConversationHistory {
    suspend fun append(
        trackId: Long?,
        message: StoredMessage,
    ): Long

    suspend fun update(
        id: Long,
        message: StoredMessage,
    )

    suspend fun recent(
        trackId: Long?,
        limit: Int,
    ): List<HistoryEntry>

    suspend fun assignPending(trackId: Long)

    suspend fun clear(trackId: Long?)
}
