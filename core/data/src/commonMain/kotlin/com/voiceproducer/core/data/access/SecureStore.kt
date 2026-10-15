package com.voiceproducer.core.data.access

interface SecureStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun delete(key: String)
}
