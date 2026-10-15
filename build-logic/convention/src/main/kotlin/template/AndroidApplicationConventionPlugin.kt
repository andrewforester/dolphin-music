package template

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("template.detekt")

            extensions.configure<ApplicationExtension> {
                compileSdk = intVersion("android-compileSdk")
                defaultConfig {
                    minSdk = intVersion("android-minSdk")
                    targetSdk = intVersion("android-targetSdk")
                    providers
                        .gradleProperty("abiFilters")
                        .orNull
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?.let { ndk { abiFilters += it } }
                }
                buildFeatures { compose = true }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                lint {
                    checkDependencies = true
                    warningsAsErrors = true
                    abortOnError = true
                    lintConfig = rootProject.file("config/lint/lint.xml")
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                    unitTests.all {
                        it.jvmArgs(
                            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                            "--add-opens=java.base/java.io=ALL-UNNAMED",
                        )
                    }
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
}
