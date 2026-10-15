plugins {
    alias(libs.plugins.template.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
        }
    }
}
