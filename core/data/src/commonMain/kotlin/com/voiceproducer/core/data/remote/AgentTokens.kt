package com.voiceproducer.core.data.remote

import com.voiceproducer.core.model.PlanJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

private val CONVERSATION_ID = Regex("conv_[A-Za-z0-9]+")
private val JWT_PAYLOAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

internal fun conversationIdOf(token: String): String? {
    val payload = token.split('.').getOrNull(1) ?: return null
    val claims =
        try {
            PlanJson.json.parseToJsonElement(JWT_PAYLOAD.decode(payload).decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SerializationException) {
            null
        }
    val room = ((claims?.get("video") as? JsonObject)?.get("room") as? JsonPrimitive)?.contentOrNull
    return room?.let { CONVERSATION_ID.find(it)?.value }
}
