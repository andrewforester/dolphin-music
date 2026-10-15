package com.voiceproducer.core.model

data class ApiLogEntry(
    val id: Long,
    val timeMs: Long,
    val endpoint: String,
    val request: String,
    val status: Int? = null,
    val durationMs: Long? = null,
    val response: String? = null,
    val songId: String? = null,
    val error: String? = null,
)
