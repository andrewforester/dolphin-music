plugins {
    alias(libs.plugins.template.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.dsp)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
