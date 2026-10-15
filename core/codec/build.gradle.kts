plugins {
    alias(libs.plugins.template.kmp.library)
}

kotlin {
    android {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.dsp)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
