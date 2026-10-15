package com.voiceproducer.core.domain.access

import kotlinx.coroutines.flow.StateFlow

sealed interface ProxyAccess {
    data class Code(
        val value: String,
    ) : ProxyAccess {
        override fun toString(): String = "Code(***)"
    }
}

interface AccessStore {
    val access: StateFlow<ProxyAccess?>

    suspend fun save(access: ProxyAccess)

    suspend fun clear()
}

interface AccessCheck {
    suspend fun check(access: ProxyAccess)
}

sealed interface AccessResult {
    data object Granted : AccessResult

    data object Rejected : AccessResult

    data class Failed(
        val reason: String,
    ) : AccessResult
}
