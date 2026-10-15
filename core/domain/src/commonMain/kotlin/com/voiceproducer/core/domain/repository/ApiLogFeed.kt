package com.voiceproducer.core.domain.repository

import com.voiceproducer.core.model.ApiLogEntry
import kotlinx.coroutines.flow.StateFlow

interface ApiLogFeed {
    val entries: StateFlow<List<ApiLogEntry>>
}
