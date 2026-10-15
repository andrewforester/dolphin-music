package com.voiceproducer.core.data.access

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class CipherSecureStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("secure-test", Context.MODE_PRIVATE)
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = CipherSecureStore(prefs) { key }

    @Test
    fun aWrittenValueReadsBack() {
        store.write("access_code", "river-copper-violin-dawn")

        assertEquals("river-copper-violin-dawn", store.read("access_code"))
    }

    @Test
    fun theValueIsNotStoredInPlainText() {
        store.write("access_code", "river-copper-violin-dawn")

        assertFalse(prefs.all.values.any { it.toString().contains("river-copper-violin-dawn") })
    }

    @Test
    fun aDeletedOrMissingValueReadsAsNull() {
        store.write("access_code", "river-copper-violin-dawn")
        store.delete("access_code")

        assertNull(store.read("access_code"))
        assertNull(store.read("never_written"))
    }

    @Test
    fun aValueThatNoLongerDecryptsReadsAsNull() {
        store.write("access_code", "river-copper-violin-dawn")
        val otherKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(CipherSecureStore(prefs) { otherKey }.read("access_code"))
    }
}
