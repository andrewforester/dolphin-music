package com.voiceproducer.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.voiceproducer.App
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerViewModel
import com.voiceproducer.feature.producer.ui.asksForMicFirst
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val viewModel = koinViewModel<ProducerViewModel>()
            val context = LocalContext.current
            val micLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    viewModel.onIntent(ProducerIntent.MicPermission(granted))
                    if (granted) viewModel.onIntent(ProducerIntent.ToggleAgent)
                }
            LifecycleResumeEffect(Unit) {
                viewModel.onIntent(ProducerIntent.MicPermission(context.hasMicPermission()))
                onPauseOrDispose {}
            }
            App(
                onIntent = { intent ->
                    if (asksForMicFirst(intent, viewModel.state.value, context.hasMicPermission())) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.onIntent(intent)
                    }
                },
                formatTime = ::localTime,
                backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
            )
        }
    }
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

private fun localTime(ms: Long): String = timeFormat.get()!!.format(Date(ms))
