package com.voiceproducer.core.data

data class ProxyConfig(
    val baseUrl: String,
    val initialAccessCode: String = "",
) {
    override fun toString(): String = "ProxyConfig(baseUrl=$baseUrl)"
}
