package template

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("template.kmp.library")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.commonMain.dependencies {
                    implementation(lib("compose-runtime"))
                    implementation(lib("compose-foundation"))
                    implementation(lib("compose-ui"))
                    implementation(lib("compose-material3"))
                    implementation(lib("compose-components-resources"))
                    implementation(lib("compose-ui-tooling-preview"))
                }
            }
        }
}
