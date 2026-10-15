package com.voiceproducer.feature.access.di

import com.voiceproducer.feature.access.AccessViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val accessModule: Module =
    module {
        viewModel { AccessViewModel(get(), get()) }
    }
