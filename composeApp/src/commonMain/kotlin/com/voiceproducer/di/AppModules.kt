package com.voiceproducer.di

import com.voiceproducer.AppConfig
import com.voiceproducer.core.agent.di.agentModule
import com.voiceproducer.core.codec.di.codecModule
import com.voiceproducer.core.common.AppScope
import com.voiceproducer.core.common.di.commonModule
import com.voiceproducer.core.data.di.dataModule
import com.voiceproducer.core.database.databaseModule
import com.voiceproducer.core.domain.di.domainModule
import com.voiceproducer.core.domain.usecase.EndAgentSessionUseCase
import com.voiceproducer.core.playback.di.playbackModule
import com.voiceproducer.feature.access.di.accessModule
import com.voiceproducer.feature.producer.di.producerModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

fun appModules(config: AppConfig): List<Module> =
    listOf(
        commonModule,
        codecModule,
        playbackModule,
        agentModule,
        databaseModule,
        dataModule(config.proxy),
        domainModule,
        producerModule,
        accessModule,
        platformModule,
        module {
            single { config.agent }
            factory { EndAgentSessionUseCase(get(), get(AppScope)) }
        },
    )

fun initKoin(
    config: AppConfig,
    platform: KoinAppDeclaration = {},
): KoinApplication =
    startKoin {
        platform()
        modules(appModules(config))
    }
