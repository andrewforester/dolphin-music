package com.voiceproducer.android

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w392dp-h820dp-xhdpi")
class PreviewScreenshotTest(
    private val capture: Capture,
) {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun snapshot() {
        rule.mainClock.autoAdvance = capture.advanceMs == 0L
        rule.setContent { capture.preview() }
        if (capture.advanceMs > 0) rule.mainClock.advanceTimeBy(capture.advanceMs)
        rule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/${capture.name}.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        )
    }

    class Capture(
        val preview: ComposablePreview<AndroidPreviewInfo>,
        val advanceMs: Long = 0,
        private val moment: String? = null,
    ) {
        val name: String
            get() = "${preview.declaringClass}.${preview.methodName}" + moment?.let { ".$it" }.orEmpty()

        override fun toString(): String = name
    }

    companion object {
        private val moments =
            mapOf(
                "GeneratingPreview" to listOf(25_000L to "slowingDown"),
                "PlayingPreview" to listOf(3_000L to "sectionRefilling", 875L to "composerSpeakingPulse"),
                "HearingYouPreview" to listOf(875L to "pulse"),
                "ListeningDuckedPreview" to listOf(875L to "silenceNoPulse"),
                "ConnectingPreview" to listOf(300L to "spinner"),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun captures(): List<Capture> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("com.voiceproducer")
                .includePrivatePreviews()
                .getPreviews()
                .flatMap { preview ->
                    listOf(Capture(preview)) +
                        moments[preview.methodName].orEmpty().map { (ms, moment) -> Capture(preview, ms, moment) }
                }
    }
}
