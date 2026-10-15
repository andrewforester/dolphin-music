package com.voiceproducer.core.data.access

import com.voiceproducer.core.data.remote.ProxyCredentials
import com.voiceproducer.core.domain.access.AccessStore
import com.voiceproducer.core.domain.access.ProxyAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class AccessRepository(
    private val secure: SecureStore,
    initialCode: String,
    private val io: CoroutineDispatcher,
) : AccessStore,
    ProxyCredentials {
    private val state =
        MutableStateFlow<ProxyAccess?>(
            (secure.read(CODE_KEY) ?: initialCode.takeIf { it.isNotBlank() })?.let(ProxyAccess::Code),
        )
    override val access: StateFlow<ProxyAccess?> = state.asStateFlow()

    override suspend fun save(access: ProxyAccess) {
        withContext(io) {
            when (access) {
                is ProxyAccess.Code -> secure.write(CODE_KEY, access.value)
            }
        }
        state.value = access
    }

    override suspend fun clear() {
        withContext(io) { secure.delete(CODE_KEY) }
        state.value = null
    }

    override fun current(): ProxyAccess? = state.value

    override suspend fun rejected() = clear()

    private companion object {
        const val CODE_KEY = "access_code"
    }
}
