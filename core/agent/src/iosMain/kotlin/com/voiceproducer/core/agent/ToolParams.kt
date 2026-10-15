package com.voiceproducer.core.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

fun parseToolParams(json: String): Map<String, Any?> {
    val root =
        try {
            Json.parseToJsonElement(json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    return (root as? JsonObject)?.toPlainMap() ?: emptyMap()
}

private fun JsonObject.toPlainMap(): Map<String, Any?> = mapValues { (_, v) -> v.toPlain() }

private fun JsonElement.toPlain(): Any? =
    when (this) {
        JsonNull -> {
            null
        }

        is JsonObject -> {
            toPlainMap()
        }

        is JsonArray -> {
            map { it.toPlain() }
        }

        is JsonPrimitive -> {
            when {
                isString -> content
                else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
            }
        }
    }
