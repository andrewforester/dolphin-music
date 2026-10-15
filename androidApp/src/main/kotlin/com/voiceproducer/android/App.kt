package com.voiceproducer.android

import android.app.Application
import com.voiceproducer.AppConfig
import com.voiceproducer.core.agent.AgentConfig
import com.voiceproducer.core.data.ProxyConfig
import com.voiceproducer.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(
            AppConfig(
                ProxyConfig(BuildConfig.PROXY_BASE_URL, initialAccessCode = BuildConfig.ACCESS_CODE),
                AgentConfig(demo = BuildConfig.DEBUG),
            ),
        ) {
            androidLogger()
            androidContext(this@App)
        }
    }
}
