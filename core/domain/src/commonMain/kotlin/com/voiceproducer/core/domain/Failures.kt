package com.voiceproducer.core.domain

import kotlin.coroutines.cancellation.CancellationException

inline fun <T> runCatchingExceptions(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it !is Exception || it is CancellationException) throw it }

inline fun <T> runCatchingIncludingCancellation(block: () -> T): Result<T> = runCatching(block).onFailure { if (it !is Exception) throw it }

fun Throwable.reason(): String = message ?: this::class.simpleName ?: "error"
