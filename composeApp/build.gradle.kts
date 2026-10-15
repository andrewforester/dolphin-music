plugins {
    alias(libs.plugins.template.kmp.compose)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "com.voiceproducer.ui")
            export(projects.core.agent)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.agent)
            api(projects.core.data)
            implementation(projects.core.model)
            implementation(projects.core.common)
            implementation(projects.core.domain)
            implementation(projects.core.codec)
            implementation(projects.core.playback)
            implementation(projects.core.database)
            implementation(projects.feature.producer)
            implementation(projects.feature.access)
            implementation(projects.core.designsystem)
            implementation(libs.okio)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
