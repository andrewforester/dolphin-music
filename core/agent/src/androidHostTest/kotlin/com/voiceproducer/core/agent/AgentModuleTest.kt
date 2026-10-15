package com.voiceproducer.core.agent

import com.voiceproducer.core.agent.di.agentModule
import com.voiceproducer.core.common.AppScope
import com.voiceproducer.core.domain.agent.VoiceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentModuleTest {
    @After
    fun tearDown() = stopKoin()

    @Test
    fun androidAgentNeedsNoAgentConfig() {
        val koin =
            startKoin {
                androidContext(RuntimeEnvironment.getApplication())
                modules(agentModule, module { single<CoroutineScope>(AppScope) { CoroutineScope(Job()) } })
            }.koin
        assertNotNull(koin.get<VoiceAgent>())
    }
}
