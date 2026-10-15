plugins {
    alias(libs.plugins.template.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.domain)
            implementation(projects.core.dsp)
            implementation(projects.core.database)
            implementation(projects.core.codec)
            implementation(projects.core.common)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqlite.bundled)
            implementation(libs.okio)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
    }
}
