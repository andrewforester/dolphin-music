package com.voiceproducer.di

import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.data.remote.ApiLog
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual val platformModule: Module =
    module {
        single { ApiLog(logger = ::logApi) }
        single<HttpClientEngine> { Darwin.create() }
        single { AudioStore(FileSystem.SYSTEM, documentsDir().toPath() / "audio") }
    }

private const val API_TAG = "VP-API"
private const val LOG_CHUNK = 900

private fun logApi(message: String) {
    message.chunked(LOG_CHUNK).forEach { NSLog("$API_TAG " + it.replace("%", "%%")) }
}

@OptIn(ExperimentalForeignApi::class)
private fun documentsDir(): String = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
