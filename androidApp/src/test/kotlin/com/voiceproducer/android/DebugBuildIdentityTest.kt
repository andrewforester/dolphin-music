package com.voiceproducer.android

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugBuildIdentityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theDebugBuildInstallsNextToTheRelease() {
        assertEquals("com.voiceproducer.android.debug", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun theDebugBuildIsLabelledDolphinDev() {
        val label =
            app.packageManager.getApplicationLabel(
                app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA),
            )
        assertEquals("Dolphin Dev", label.toString())
    }
}
