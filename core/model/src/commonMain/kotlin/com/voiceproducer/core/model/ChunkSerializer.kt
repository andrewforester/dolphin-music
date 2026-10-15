package com.voiceproducer.core.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

object ChunkSerializer : JsonContentPolymorphicSerializer<Chunk>(Chunk::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Chunk> =
        if ("song_id" in element.jsonObject) ReferenceChunk.serializer() else GenerationChunk.serializer()
}
