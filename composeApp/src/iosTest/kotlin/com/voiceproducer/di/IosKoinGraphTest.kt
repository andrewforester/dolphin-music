package com.voiceproducer.di

import com.voiceproducer.AppConfig
import com.voiceproducer.core.agent.AgentBridge
import com.voiceproducer.core.agent.AgentBridgeListener
import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.agent.IosVoiceAgent
import com.voiceproducer.core.codec.AudioDecoder
import com.voiceproducer.core.data.ProxyConfig
import com.voiceproducer.core.data.access.SecureStore
import com.voiceproducer.core.data.remote.ProxyApi
import com.voiceproducer.core.domain.agent.VoiceAgent
import com.voiceproducer.core.domain.playback.AudioEngine
import com.voiceproducer.core.domain.repository.TrackLibrary
import com.voiceproducer.feature.access.AccessViewModel
import com.voiceproducer.feature.producer.ProducerViewModel
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class IosKoinGraphTest {
    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun resolvesTheViewModelAndEveryPort() {
        val koin =
            initKoin(AppConfig(ProxyConfig("https://x"), AgentConfig())) {
                modules(module { single<AgentBridge> { NoopAgentBridge } }, module { single<SecureStore> { NoSecureStore } })
            }.koin
        assertNotNull(koin.get<ProducerViewModel>())
        assertNotNull(koin.get<AccessViewModel>())
        assertNotNull(koin.get<ProxyApi>())
        assertNotNull(koin.get<TrackLibrary>())
        assertNotNull(koin.get<AudioEngine>())
        assertIs<IosVoiceAgent>(koin.get<VoiceAgent>())
        assertNotNull(koin.get<AudioDecoder>())
    }
}

private object NoopAgentBridge : AgentBridge {
    override fun start(
        token: String,
        plan: String,
        listener: AgentBridgeListener,
    ) = Unit

    override fun stop(done: () -> Unit) = done()

    override fun sendContext(text: String) = Unit

    override fun sendUserText(text: String) = Unit

    override fun setMicMuted(muted: Boolean) = Unit
}

private object NoSecureStore : SecureStore {
    override fun read(key: String): String? = null

    override fun write(
        key: String,
        value: String,
    ) = Unit

    override fun delete(key: String) = Unit
}
