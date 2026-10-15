package com.voiceproducer

import com.voiceproducer.core.data.access.SecureStore

interface KeychainBridge {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun delete(key: String)
}

internal class KeychainSecureStore(
    private val keychain: KeychainBridge,
) : SecureStore {
    override fun read(key: String): String? = keychain.read(key)

    override fun write(
        key: String,
        value: String,
    ) = keychain.write(key, value)

    override fun delete(key: String) = keychain.delete(key)
}
