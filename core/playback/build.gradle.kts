plugins {
    alias(libs.plugins.template.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            implementation(projects.core.model)
            implementation(projects.core.dsp)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.media3.exoplayer)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.media3.test.utils)
            implementation(libs.media3.test.utils.robolectric)
        }
    }
}
