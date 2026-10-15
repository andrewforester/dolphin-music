package com.voiceproducer.core.data.remote

import com.voiceproducer.core.domain.access.ProxyAccess

internal class FixedCredentials(
    private val access: ProxyAccess?,
) : ProxyCredentials {
    var rejections = 0

    override fun current(): ProxyAccess? = access

    override suspend fun rejected() {
        rejections++
    }
}
