package com.voiceproducer.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named

val AppScope = named("appScope")

fun appScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    log: (String) -> Unit = ::println,
): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log("AppScope job failed: $e") },
    )
