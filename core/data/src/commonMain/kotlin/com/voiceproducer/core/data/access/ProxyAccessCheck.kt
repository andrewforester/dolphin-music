package com.voiceproducer.core.data.access

import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.domain.access.AccessCheck
import com.voiceproducer.core.domain.access.ProxyAccess

internal class ProxyAccessCheck(
    private val api: ProxyApi,
) : AccessCheck {
    override suspend fun check(access: ProxyAccess) = api.checkAccess(access)
}
