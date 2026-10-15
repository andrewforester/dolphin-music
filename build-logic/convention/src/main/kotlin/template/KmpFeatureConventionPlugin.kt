package template

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("template.kmp.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.commonMain.dependencies {
                    implementation(project(":core:model"))
                    implementation(project(":core:domain"))
                    implementation(project(":core:designsystem"))
                    implementation(lib("lifecycle-viewmodel"))
                    implementation(lib("lifecycle-viewmodel-compose"))
                    implementation(lib("lifecycle-runtime-compose"))
                    implementation(lib("navigation-compose"))
                    implementation(lib("kotlinx-serialization-core"))
                    implementation(lib("koin-compose"))
                    implementation(lib("koin-compose-viewmodel"))
                }
                sourceSets.commonTest.dependencies {
                    implementation(project(":core:testing"))
                }
            }
        }
}
