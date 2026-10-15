package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.access.AccessCheck
import com.voiceproducer.core.domain.access.AccessStore
import com.voiceproducer.core.domain.access.ProxyAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAccessStore(
    initial: ProxyAccess? = null,
) : AccessStore {
    private val state = MutableStateFlow(initial)
    override val access: StateFlow<ProxyAccess?> = state

    override suspend fun save(access: ProxyAccess) {
        state.value = access
    }

    override suspend fun clear() {
        state.value = null
    }
}

class FakeAccessCheck(
    var answer: suspend (ProxyAccess) -> Unit = {},
) : AccessCheck {
    val checked = mutableListOf<ProxyAccess>()

    override suspend fun check(access: ProxyAccess) {
        checked += access
        answer(access)
    }
}
