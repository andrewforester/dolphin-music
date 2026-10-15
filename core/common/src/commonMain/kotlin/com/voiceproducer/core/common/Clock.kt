package com.voiceproducer.core.common

import kotlin.time.Clock

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
