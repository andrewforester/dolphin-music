package com.voiceproducer.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.voiceproducer.ui.SPLASH_HOLD_MS
import com.voiceproducer.ui.SplashHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SplashHostTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun splashCoversTheContentAtLaunchThenFadesAway() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SplashHost { Box(Modifier.fillMaxSize().testTag("content")) } }

        rule.onNodeWithTag("splash").assertIsDisplayed()
        rule.onNodeWithTag("content").assertExists()

        rule.mainClock.advanceTimeBy(SPLASH_HOLD_MS + 1_000)

        rule.onNodeWithTag("splash").assertDoesNotExist()
        rule.onNodeWithTag("content").assertIsDisplayed()
    }
}
