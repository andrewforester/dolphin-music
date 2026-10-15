package com.voiceproducer.core.model

sealed class ProxyError(
    msg: String,
    cause: Throwable? = null,
) : Exception(msg, cause) {
    class RateLimited : ProxyError("Rate limit reached, try again in a moment")

    class Unauthorized : ProxyError("The access code is not valid")

    class Http(
        val status: Int,
        val body: String,
        cause: Throwable? = null,
    ) : ProxyError(httpMessage(status), cause)

    class BadPrompt(
        val reason: String?,
        val suggestion: String?,
    ) : ProxyError("Prompt rejected: it names artists or copyrighted material")

    class QuotaExceeded(
        val remaining: Int?,
        val required: Int?,
    ) : ProxyError(
            if (remaining != null && required != null) {
                "Not enough ElevenLabs credits: $remaining left, $required needed"
            } else {
                "Not enough ElevenLabs credits"
            },
        )
}

private const val FIRST_SERVER_ERROR_STATUS = 500

private fun httpMessage(status: Int): String =
    when {
        status >= FIRST_SERVER_ERROR_STATUS -> "The server had a problem (http $status), try again"
        else -> "The request failed (http $status)"
    }
