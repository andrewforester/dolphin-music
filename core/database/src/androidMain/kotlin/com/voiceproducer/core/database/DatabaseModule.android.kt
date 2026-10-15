package com.voiceproducer.core.database

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val databaseModule: Module =
    module {
        single { buildAppDatabase(androidContext()) }
    }
