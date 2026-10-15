package com.voiceproducer.core.common.di

import com.voiceproducer.core.common.AppScope
import com.voiceproducer.core.common.appScope
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

val commonModule: Module =
    module {
        single<CoroutineScope>(AppScope) { appScope() }
    }
