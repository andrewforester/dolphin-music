import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "template.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id =
                libs.plugins.template.kmp.library
                    .get()
                    .pluginId
            implementationClass = "template.KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id =
                libs.plugins.template.kmp.compose
                    .get()
                    .pluginId
            implementationClass = "template.KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id =
                libs.plugins.template.kmp.feature
                    .get()
                    .pluginId
            implementationClass = "template.KmpFeatureConventionPlugin"
        }
        register("androidApplication") {
            id =
                libs.plugins.template.android.application
                    .get()
                    .pluginId
            implementationClass = "template.AndroidApplicationConventionPlugin"
        }
        register("detekt") {
            id =
                libs.plugins.template.detekt
                    .get()
                    .pluginId
            implementationClass = "template.DetektConventionPlugin"
        }
    }
}
