package com.voiceproducer.core.domain.di

import com.voiceproducer.core.domain.edit.EditPipelineFactory
import com.voiceproducer.core.domain.usecase.ComposeTrackUseCase
import com.voiceproducer.core.domain.usecase.ExplainAgentRefusalUseCase
import com.voiceproducer.core.domain.usecase.RestoreLatestTrackUseCase
import com.voiceproducer.core.domain.usecase.StartAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.StartNewTrackUseCase
import com.voiceproducer.core.domain.usecase.SubmitAccessCodeUseCase
import org.koin.dsl.module

val domainModule =
    module {
        factory { ComposeTrackUseCase(get(), get()) }
        factory { StartNewTrackUseCase(get(), get(), get()) }
        factory { RestoreLatestTrackUseCase(get(), get()) }
        factory { StartAgentSessionUseCase(get(), get()) }
        factory { ExplainAgentRefusalUseCase(get()) }
        factory { EditPipelineFactory(get(), get()) }
        factory { SubmitAccessCodeUseCase(get(), get()) }
    }
