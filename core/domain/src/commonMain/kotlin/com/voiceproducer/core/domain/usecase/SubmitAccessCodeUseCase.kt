package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.access.AccessCheck
import com.voiceproducer.core.domain.access.AccessResult
import com.voiceproducer.core.domain.access.AccessStore
import com.voiceproducer.core.domain.access.ProxyAccess
import com.voiceproducer.core.domain.reason
import com.voiceproducer.core.domain.runCatchingExceptions
import com.voiceproducer.core.model.ProxyError

class SubmitAccessCodeUseCase(
    private val check: AccessCheck,
    private val store: AccessStore,
) {
    suspend operator fun invoke(code: String): AccessResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return AccessResult.Rejected
        val access = ProxyAccess.Code(trimmed)
        return runCatchingExceptions { check.check(access) }
            .fold(
                onSuccess = {
                    store.save(access)
                    AccessResult.Granted
                },
                onFailure = { e -> if (e is ProxyError.Unauthorized) AccessResult.Rejected else AccessResult.Failed(e.reason()) },
            )
    }
}
