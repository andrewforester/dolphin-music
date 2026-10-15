plugins {
    alias(libs.plugins.template.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core.viewmodel)
        }
    }
}
