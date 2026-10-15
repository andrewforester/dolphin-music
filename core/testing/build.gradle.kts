plugins {
    alias(libs.plugins.template.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            api(projects.core.model)
            api(libs.kotlinx.coroutines.test)
            api(kotlin("test"))
        }
    }
}
