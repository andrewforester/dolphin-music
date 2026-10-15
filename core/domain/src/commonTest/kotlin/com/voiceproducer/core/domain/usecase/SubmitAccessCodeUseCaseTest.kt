package com.voiceproducer.core.domain.usecase

import com.voiceproducer.core.domain.access.AccessResult
import com.voiceproducer.core.domain.access.ProxyAccess
import com.voiceproducer.core.model.ProxyError
import com.voiceproducer.core.testing.FakeAccessCheck
import com.voiceproducer.core.testing.FakeAccessStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmitAccessCodeUseCaseTest {
    private val store = FakeAccessStore()
    private val check = FakeAccessCheck()
    private val submit = SubmitAccessCodeUseCase(check, store)

    @Test
    fun aCodeTheProxyAcceptsIsKept() =
        runTest {
            assertEquals(AccessResult.Granted, submit("  river-copper-violin-dawn \n"))

            assertEquals(listOf<ProxyAccess>(ProxyAccess.Code("river-copper-violin-dawn")), check.checked)
            assertEquals(ProxyAccess.Code("river-copper-violin-dawn"), store.access.value)
        }

    @Test
    fun aCodeTheProxyRejectsIsNotKept() =
        runTest {
            check.answer = { throw ProxyError.Unauthorized() }

            assertEquals(AccessResult.Rejected, submit("river-copper-violin"))
            assertNull(store.access.value)
        }

    @Test
    fun anUnreachableProxyIsAFailureWithItsReason() =
        runTest {
            check.answer = { throw ProxyError.Http(503, "") }

            assertEquals(AccessResult.Failed("The server had a problem (http 503), try again"), submit("river-copper-violin-dawn"))
            assertNull(store.access.value)
        }

    @Test
    fun aBlankCodeIsRejectedWithoutAskingTheProxy() =
        runTest {
            assertEquals(AccessResult.Rejected, submit("   "))
            assertTrue(check.checked.isEmpty())
        }
}
