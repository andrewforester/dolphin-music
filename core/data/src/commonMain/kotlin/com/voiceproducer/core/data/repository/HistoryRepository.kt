package com.voiceproducer.core.data.repository

import com.voiceproducer.core.database.MessageDao
import com.voiceproducer.core.database.MessageEntity
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.model.HistoryEntry
import com.voiceproducer.core.model.MessageRole
import com.voiceproducer.core.model.StoredMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class HistoryRepository(
    private val dao: MessageDao,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConversationHistory {
    override suspend fun append(
        trackId: Long?,
        message: StoredMessage,
    ): Long =
        withContext(ioDispatcher) {
            dao.insert(
                MessageEntity(
                    trackId = trackId,
                    createdAt = clock(),
                    role = message.role.name.lowercase(),
                    text = message.text,
                    toolKind = message.toolKind,
                    toolStatus = message.toolStatus,
                    toolSection = message.toolSection,
                    toolDetail = message.toolDetail,
                ),
            )
        }

    override suspend fun update(
        id: Long,
        message: StoredMessage,
    ) = withContext(ioDispatcher) {
        dao.update(id, message.text, message.toolStatus, message.toolSection, message.toolDetail)
    }

    override suspend fun recent(
        trackId: Long?,
        limit: Int,
    ): List<HistoryEntry> =
        withContext(ioDispatcher) {
            dao.latest(trackId, limit).asReversed().mapNotNull { row -> row.toStored()?.let { HistoryEntry(row.id, it) } }
        }

    override suspend fun assignPending(trackId: Long) = withContext(ioDispatcher) { dao.assignPending(trackId) }

    override suspend fun clear(trackId: Long?) = withContext(ioDispatcher) { dao.clear(trackId) }

    private fun MessageEntity.toStored(): StoredMessage? {
        val role = MessageRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) } ?: return null
        return StoredMessage(role, text, toolKind, toolStatus, toolSection, toolDetail)
    }
}
