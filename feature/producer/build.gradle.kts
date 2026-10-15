plugins {
    alias(libs.plugins.template.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core.viewmodel)
            implementation(libs.kotlinx.collections.immutable)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.compose.ui.test.manifest)
        }
    }
}
