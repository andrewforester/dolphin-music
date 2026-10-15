package com.voiceproducer.core.data.remote

import com.voiceproducer.core.domain.repository.ApiLogFeed
import com.voiceproducer.core.model.ApiLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

class ApiLog(
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val logger: (String) -> Unit = {},
) : ApiLogFeed {
    private val _entries = MutableStateFlow<List<ApiLogEntry>>(emptyList())
    override val entries: StateFlow<List<ApiLogEntry>> = _entries.asStateFlow()

    fun now(): Long = clock()

    fun start(
        endpoint: String,
        request: String,
    ): Long {
        var id = 0L
        val time = clock()
        _entries.update { list ->
            id = (list.lastOrNull()?.id ?: 0L) + 1
            (list + ApiLogEntry(id, time, endpoint, request.truncated())).takeLast(CAPACITY)
        }
        return id
    }

    fun finish(
        id: Long,
        status: Int?,
        durationMs: Long,
        response: String? = null,
        songId: String? = null,
        error: String? = null,
    ) {
        var done: ApiLogEntry? = null
        _entries.update { list ->
            done = null
            list.map { e ->
                if (e.id != id) {
                    e
                } else {
                    e
                        .copy(
                            status = status,
                            durationMs = durationMs,
                            response = response?.truncated(),
                            songId = songId,
                            error = error?.truncated(),
                        ).also { done = it }
                }
            }
        }
        done?.let { runCatching { logger(it.logMessage()) } }
    }

    companion object {
        const val CAPACITY = 50
        const val MAX_CHARS = 20_000
        const val TRUNCATED_MARKER = "…(truncated)"
    }
}

private fun String.truncated(): String = if (length <= ApiLog.MAX_CHARS) this else take(ApiLog.MAX_CHARS) + ApiLog.TRUNCATED_MARKER

private fun ApiLogEntry.logMessage(): String =
    buildString {
        append("#$id $endpoint status=${status ?: "-"} ${durationMs ?: "-"}ms")
        songId?.let { append(" song=$it") }
        error?.let { append(" error=$it") }
        append("\nREQUEST:\n").append(request)
        response?.let { append("\nRESPONSE:\n").append(it) }
    }
