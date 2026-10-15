package com.voiceproducer.core.data.access

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

class CipherSecureStore(
    private val prefs: SharedPreferences,
    private val key: () -> SecretKey,
) : SecureStore {
    override fun read(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        prefs.edit().putString(key, Base64.encode(sealed)).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "voiceproducer.secure"
        private const val PREFS = "voiceproducer.secure"

        fun keystoreBacked(context: Context): CipherSecureStore =
            CipherSecureStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) { keystoreKey() }

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val spec =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
    }
}
