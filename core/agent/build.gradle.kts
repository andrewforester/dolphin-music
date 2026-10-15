plugins {
    alias(libs.plugins.template.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            implementation(projects.core.model)
            implementation(projects.core.common)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.elevenlabs.android)
            compileOnly(libs.livekit.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
    }
}
