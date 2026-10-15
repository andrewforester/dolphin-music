package com.voiceproducer.core.model

enum class MessageRole { User, Agent, Tool, System, Notice }

data class StoredMessage(
    val role: MessageRole,
    val text: String,
    val toolKind: String? = null,
    val toolStatus: String? = null,
    val toolSection: String? = null,
    val toolDetail: String? = null,
)

data class HistoryEntry(
    val id: Long,
    val message: StoredMessage,
)
