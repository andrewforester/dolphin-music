package com.voiceproducer.di

import com.voiceproducer.AppConfig
import com.voiceproducer.IosConfig
import com.voiceproducer.KeychainBridge
import com.voiceproducer.KeychainSecureStore
import com.voiceproducer.core.agent.AgentBridge
import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.data.ProxyConfig
import com.voiceproducer.core.data.access.SecureStore
import org.koin.dsl.module

fun initKoin(
    config: IosConfig,
    bridge: AgentBridge,
    keychain: KeychainBridge,
) {
    initKoin(AppConfig(ProxyConfig(config.proxyBaseUrl), AgentConfig())) {
        modules(
            module {
                single<AgentBridge> { bridge }
                single<SecureStore> { KeychainSecureStore(keychain) }
            },
        )
    }
}
