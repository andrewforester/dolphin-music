package com.voiceproducer.core.testing

import com.voiceproducer.core.domain.repository.ApiLogFeed
import com.voiceproducer.core.model.ApiLogEntry
import kotlinx.coroutines.flow.MutableStateFlow

class FakeApiLogFeed : ApiLogFeed {
    override val entries = MutableStateFlow<List<ApiLogEntry>>(emptyList())
}
