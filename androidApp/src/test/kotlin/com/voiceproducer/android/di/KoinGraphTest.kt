package com.voiceproducer.android.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.voiceproducer.AppConfig
import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.agent.ElevenLabsVoiceAgent
import com.voiceproducer.core.codec.AudioDecoder
import com.voiceproducer.core.codec.MediaCodecDecoder
import com.voiceproducer.core.common.AppScope
import com.voiceproducer.core.data.ProxyConfig
import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.database.AppDatabase
import com.voiceproducer.core.domain.agent.AgentTokenSource
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.edit.EditPipelineFactory
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.ApiLogFeed
import com.voiceproducer.core.domain.repository.ConversationHistory
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.core.domain.repository.TrackStore
import com.voiceproducer.core.domain.usecase.ComposeTrackUseCase
import com.voiceproducer.core.domain.usecase.EndAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.RestoreLatestTrackUseCase
import com.voiceproducer.core.domain.usecase.StartAgentSessionUseCase
import com.voiceproducer.core.domain.usecase.StartNewTrackUseCase
import com.voiceproducer.core.playback.Media3AudioEngine
import com.voiceproducer.di.initKoin
import com.voiceproducer.feature.access.AccessViewModel
import com.voiceproducer.feature.producer.ProducerViewModel
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class KoinGraphTest {
    private lateinit var koin: Koin

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        koin =
            initKoin(AppConfig(ProxyConfig("https://proxy.test"), AgentConfig())) {
                androidContext(app)
            }.koin
    }

    @After
    fun tearDown() {
        koin.get<AudioEngine>().release()
        koin.get<AppDatabase>().close()
        stopKoin()
    }

    @Test
    fun resolvesTheViewModelAndEveryPort() {
        assertNotNull(koin.get<ProducerViewModel>())
        assertNotNull(koin.get<AccessViewModel>())
        assertNotNull(koin.get<ProxyApi>())
        assertNotNull(koin.get<ApiLogFeed>())
        assertNotNull(koin.get<TrackLibrary>())
        assertNotNull(koin.get<TrackStore>())
        assertNotNull(koin.get<ConversationHistory>())
        assertNotNull(koin.get<AgentTokenSource>())
        assertIs<Media3AudioEngine>(koin.get<AudioEngine>())
        assertIs<ElevenLabsVoiceAgent>(koin.get<VoiceAgent>())
        assertIs<MediaCodecDecoder>(koin.get<AudioDecoder>())
        assertNotNull(koin.get<AppDatabase>())
        assertNotNull(koin.get<CoroutineScope>(AppScope))
    }

    @Test
    fun resolvesEveryUseCase() {
        assertNotNull(koin.get<ComposeTrackUseCase>())
        assertNotNull(koin.get<StartNewTrackUseCase>())
        assertNotNull(koin.get<RestoreLatestTrackUseCase>())
        assertNotNull(koin.get<StartAgentSessionUseCase>())
        assertNotNull(koin.get<EditPipelineFactory>())
        assertNotNull(koin.get<EndAgentSessionUseCase>())
    }
}
