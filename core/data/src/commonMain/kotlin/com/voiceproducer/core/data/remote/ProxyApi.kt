package com.voiceproducer.core.data.remote

import com.voiceproducer.core.domain.access.ProxyAccess
import com.voiceproducer.core.domain.agent.AgentToken
import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.PlanJson
import com.voiceproducer.core.model.ProxyError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

class ComposeResult(
    val songId: String,
    val format: AudioFormat,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ComposeResult && songId == other.songId && format == other.format && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * songId.hashCode() + format.hashCode()) + bytes.contentHashCode()

    override fun toString(): String = "ComposeResult(songId=$songId, format=$format, bytes=${bytes.size}B)"
}

private const val HTTP_OK = 200
private const val FIRST_SUCCESS_STATUS = HTTP_OK
private const val LAST_SUCCESS_STATUS = 299

interface ProxyCredentials {
    fun current(): ProxyAccess?

    suspend fun rejected()
}

class ProxyApi(
    private val client: HttpClient,
    baseUrl: String,
    private val credentials: ProxyCredentials,
    private val apiLog: ApiLog = ApiLog(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val base = baseUrl.trimEnd('/')
    private val prettyJson = Json(PlanJson.json) { prettyPrint = true }

    suspend fun createPlan(
        prompt: String,
        lengthMs: Long,
    ): CompositionPlan {
        val body =
            buildJsonObject {
                put("prompt", prompt)
                put("music_length_ms", lengthMs)
            }
        return recorded("music-plan", body.pretty()) { call ->
            val response =
                client.post("$base/music-plan") {
                    authorize(credentials.current())
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            val text = response.checked(call).bodyAsText()
            call.response = prettyOrRaw(text)
            PlanJson.decode(text)
        }
    }

    suspend fun compose(plan: CompositionPlan): ComposeResult {
        val body =
            buildJsonObject {
                put("composition_plan", PlanJson.json.encodeToJsonElement(CompositionPlan.serializer(), plan))
            }
        return recorded("music-compose", body.pretty()) { call ->
            val response =
                client
                    .post("$base/music-compose") {
                        authorize(credentials.current())
                        setBody(TextContent(body.toString(), ContentType.Application.Json))
                        timeout { requestTimeoutMillis = COMPOSE_TIMEOUT_MS }
                    }.checked(call)
            val songId = response.headers["song-id"] ?: throw ProxyError.Http(HTTP_OK, "missing header song-id")
            call.songId = songId
            val formatHeader =
                response.headers["x-audio-format"]
                    ?: throw ProxyError.Http(HTTP_OK, "missing header x-audio-format")
            val channels = response.headers["x-audio-channels"]?.toIntOrNull() ?: 1
            val format = parseAudioFormat(formatHeader, channels)
            val bytes = response.bodyAsBytes()
            call.response = "format=$formatHeader channels=$channels size=${bytes.size} bytes song-id=$songId"
            ComposeResult(songId, format, bytes)
        }
    }

    private fun parseAudioFormat(
        formatHeader: String,
        channels: Int,
    ): AudioFormat =
        try {
            AudioFormat.parse(formatHeader, channels)
        } catch (e: IllegalArgumentException) {
            throw ProxyError.Http(HTTP_OK, "bad header x-audio-format: ${e.message}", e)
        }

    suspend fun agentToken(): AgentToken =
        recorded("agent-token", "GET /agent-token", logErrorBody = false) { call ->
            val response = client.get("$base/agent-token") { authorize(credentials.current()) }
            val json: JsonObject = PlanJson.json.parseToJsonElement(response.checked(call).bodyAsText()).jsonObject
            val token =
                (json["token"] as? JsonPrimitive)?.contentOrNull
                    ?: throw ProxyError.Http(HTTP_OK, "missing token in response")
            val conversationId = conversationIdOf(token)
            call.response = "token received"
            AgentToken(token, conversationId)
        }

    suspend fun agentRefusal(conversationId: String): String? =
        recorded("agent-conversation", "GET /agent-conversation?id=$conversationId") { call ->
            val response =
                client.get("$base/agent-conversation") {
                    authorize(credentials.current())
                    parameter("id", conversationId)
                }
            val text = response.checked(call).bodyAsText()
            call.response = text
            (PlanJson.json.parseToJsonElement(text).jsonObject["reason"] as? JsonPrimitive)?.contentOrNull
        }

    suspend fun checkAccess(access: ProxyAccess) {
        recorded("access", "GET /access", logErrorBody = false) { call ->
            val response = client.get("$base/access") { authorize(access) }
            response.checked(call, revokeOnUnauthorized = false)
            call.response = "access granted"
        }
    }

    private class Call {
        var status: Int? = null
        var response: String? = null
        var songId: String? = null
    }

    private suspend fun <T> recorded(
        endpoint: String,
        request: String,
        logErrorBody: Boolean = true,
        block: suspend (Call) -> T,
    ): T =
        withContext(io) {
            val id = apiLog.start(endpoint, request)
            val startedAt = apiLog.now()
            val call = Call()
            val result =
                runCatching { block(call) }
                    .onFailure { e ->
                        if (!logErrorBody) call.response = null
                        val error =
                            when {
                                e is CancellationException -> {
                                    "cancelled"
                                }

                                e is ProxyError.Unauthorized -> {
                                    e.message
                                }

                                e is ProxyError.BadPrompt -> {
                                    "${e.message} (reason=${e.reason}, suggestion=${e.suggestion})"
                                }

                                e is ProxyError.Http -> {
                                    if (logErrorBody && call.response == null) call.response = e.body
                                    e.message
                                }

                                !logErrorBody -> {
                                    "invalid response"
                                }

                                else -> {
                                    e.message ?: e::class.simpleName
                                }
                            }
                        apiLog.finish(id, call.status, apiLog.now() - startedAt, call.response, call.songId, error)
                    }.getOrThrow()
            apiLog.finish(id, call.status, apiLog.now() - startedAt, call.response, call.songId)
            result
        }

    private fun prettyOrRaw(text: String): String =
        try {
            PlanJson.json.parseToJsonElement(text).pretty()
        } catch (_: SerializationException) {
            text
        }

    private fun JsonElement.pretty(): String = prettyJson.encodeToString(JsonElement.serializer(), this)

    private suspend fun HttpResponse.checked(
        call: Call,
        revokeOnUnauthorized: Boolean = true,
    ): HttpResponse {
        call.status = status.value
        if (status == HttpStatusCode.Unauthorized) {
            if (revokeOnUnauthorized) credentials.rejected()
            throw ProxyError.Unauthorized()
        }
        val error = failure(call)
        if (error != null) throw error
        return this
    }

    private suspend fun HttpResponse.failure(call: Call): ProxyError? =
        when {
            status == HttpStatusCode.TooManyRequests -> {
                ProxyError.RateLimited()
            }

            status == HttpStatusCode.UnprocessableEntity -> {
                val text = bodyAsText()
                call.response = text
                parseBadPrompt(text) ?: ProxyError.Http(status.value, text)
            }

            status == HttpStatusCode.PaymentRequired -> {
                val text = bodyAsText()
                call.response = text
                parseQuotaExceeded(text) ?: ProxyError.Http(status.value, text)
            }

            status.value !in FIRST_SUCCESS_STATUS..LAST_SUCCESS_STATUS -> {
                ProxyError.Http(status.value, bodyAsText().also { call.response = it })
            }

            else -> {
                null
            }
        }

    private companion object {
        const val COMPOSE_TIMEOUT_MS = 180_000L
    }
}

private const val ACCESS_CODE_HEADER = "x-access-code"

private fun HttpRequestBuilder.authorize(access: ProxyAccess?) {
    when (access) {
        is ProxyAccess.Code -> header(ACCESS_CODE_HEADER, access.value)
        null -> Unit
    }
}

private fun parseBadPrompt(text: String): ProxyError.BadPrompt? =
    try {
        val json = PlanJson.json.parseToJsonElement(text).jsonObject
        if ((json["error"] as? JsonPrimitive)?.contentOrNull != "bad_prompt") {
            null
        } else {
            ProxyError.BadPrompt(
                reason = (json["reason"] as? JsonPrimitive)?.contentOrNull,
                suggestion = (json["prompt_suggestion"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun parseQuotaExceeded(text: String): ProxyError.QuotaExceeded? =
    try {
        val json = PlanJson.json.parseToJsonElement(text).jsonObject
        if ((json["error"] as? JsonPrimitive)?.contentOrNull != "quota_exceeded") {
            null
        } else {
            ProxyError.QuotaExceeded(
                remaining = (json["credits_remaining"] as? JsonPrimitive)?.intOrNull,
                required = (json["credits_required"] as? JsonPrimitive)?.intOrNull,
            )
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
