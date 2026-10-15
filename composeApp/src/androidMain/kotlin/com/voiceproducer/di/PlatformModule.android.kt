package com.voiceproducer.di

import android.util.Log
import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.data.access.CipherSecureStore
import com.voiceproducer.core.data.access.SecureStore
import com.voiceproducer.core.data.remote.ApiLog
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single { ApiLog(logger = ::logApi) }
        single<HttpClientEngine> { OkHttp.create() }
        single<SecureStore> { CipherSecureStore.keystoreBacked(androidContext()) }
        single { AudioStore(FileSystem.SYSTEM, androidContext().filesDir.toOkioPath() / "audio") }
    }

private const val API_TAG = "VP-API"
private const val LOGCAT_CHUNK = 3_500

private fun logApi(message: String) {
    message.chunked(LOGCAT_CHUNK).forEach { Log.d(API_TAG, it) }
}
