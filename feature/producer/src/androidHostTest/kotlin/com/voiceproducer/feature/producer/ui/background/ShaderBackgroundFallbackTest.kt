package com.voiceproducer.feature.producer.ui.background

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.voiceproducer.feature.producer.ProducerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ShaderBackgroundFallbackTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersStaticBackgroundBelowApi33() {
        rule.setContent { ShaderBackground(ProducerUiState(), positionMs = { 0L }) }
        rule.onNodeWithTag("static-background").assertExists()
    }
}
