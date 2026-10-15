package com.voiceproducer.core.data.di

import com.voiceproducer.core.common.nowMs
import com.voiceproducer.core.data.AudioStore
import com.voiceproducer.core.data.ProxyConfig
import com.voiceproducer.core.data.access.AccessRepository
import com.voiceproducer.core.data.access.ProxyAccessCheck
import com.voiceproducer.core.data.remote.ApiLog
import com.voiceproducer.core.data.remote.ProxyAgentTokenSource
import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.data.remote.ProxyCredentials
import com.voiceproducer.core.data.repository.HistoryRepository
import com.voiceproducer.core.data.repository.TrackRepository
import com.voiceproducer.core.database.AppDatabase
import com.voiceproducer.core.domain.access.AccessCheck
import com.voiceproducer.core.domain.access.AccessStore
import com.voiceproducer.core.domain.agent.AgentTokenSource
import com.voiceproducer.core.domain.repository.ApiLogFeed
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.domain.repository.TrackStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

fun dataModule(config: ProxyConfig): Module =
    module {
        single {
            HttpClient(get<HttpClientEngine>()) {
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        }
        single { AccessRepository(get(), config.initialAccessCode, Dispatchers.IO) } binds
            arrayOf(AccessStore::class, ProxyCredentials::class)
        single { ProxyApi(get(), config.baseUrl, get(), get()) }
        single<AccessCheck> { ProxyAccessCheck(get()) }
        single { get<AppDatabase>().trackDao() }
        single { TrackRepository(get(), get(), get(), get(), clock = ::nowMs) } binds arrayOf(TrackLibrary::class, TrackStore::class)
        single { get<AppDatabase>().messageDao() }
        single { HistoryRepository(get(), clock = ::nowMs, ioDispatcher = Dispatchers.IO) } bind ConversationHistory::class
        single<AgentTokenSource> { ProxyAgentTokenSource(get()) }
        single<ApiLogFeed> { get<ApiLog>() }
    }

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val SOCKET_TIMEOUT_MS = 180_000L
private const val REQUEST_TIMEOUT_MS = 60_000L
