package com.voiceproducer.core.data.access

import com.voiceproducer.core.domain.access.ProxyAccess
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessRepositoryTest {
    private val secure = MapSecureStore()

    private fun TestScope.repository(initialCode: String = "") =
        AccessRepository(secure, initialCode, StandardTestDispatcher(testScheduler))

    @Test
    fun aSavedCodeIsStoredAndOffered() =
        runTest {
            val repo = repository()
            repo.save(ProxyAccess.Code("river-copper-violin-dawn"))

            assertEquals(ProxyAccess.Code("river-copper-violin-dawn"), repo.access.value)
            assertEquals(ProxyAccess.Code("river-copper-violin-dawn"), repo.current())
            assertEquals(ProxyAccess.Code("river-copper-violin-dawn"), repository().access.value)
        }

    @Test
    fun aRejectedCodeIsForgotten() =
        runTest {
            secure.values["access_code"] = "river-copper-violin-dawn"
            val repo = repository()

            repo.rejected()

            assertNull(repo.access.value)
            assertNull(secure.values["access_code"])
        }

    @Test
    fun theInitialCodeIsOfferedOnlyWhenNothingIsStored() =
        runTest {
            assertEquals(ProxyAccess.Code("debug-code"), repository(initialCode = "debug-code").access.value)
            secure.values["access_code"] = "stored-code"
            assertEquals(ProxyAccess.Code("stored-code"), repository(initialCode = "debug-code").access.value)
        }

    @Test
    fun withNothingStoredAndNoInitialCodeThereIsNoAccess() =
        runTest {
            assertNull(repository().access.value)
        }
}

private class MapSecureStore : SecureStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
