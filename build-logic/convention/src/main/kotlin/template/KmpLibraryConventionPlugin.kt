package template

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            pluginManager.apply("template.detekt")

            tasks.withType<KotlinJvmCompile>().configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }

            tasks.withType<Test>().configureEach {
                if (name == "testAndroidHostTest") {
                    jvmArgs(
                        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                        "--add-opens=java.base/java.io=ALL-UNNAMED",
                    )
                }
            }

            extensions.configure<KotlinMultiplatformExtension> {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }

                (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension>("android") {
                    namespace = namespaceFromPath
                    compileSdk = intVersion("android-compileSdk")
                    minSdk = intVersion("android-minSdk")
                    withHostTest { isIncludeAndroidResources = true }
                }

                if (findProperty("kmp.jvmTests") == "true") {
                    jvm()
                }
                iosArm64()
                iosSimulatorArm64()

                sourceSets.commonMain.dependencies {
                    implementation(lib("kotlinx-coroutines-core"))
                    implementation(lib("koin-core"))
                }
                sourceSets.commonTest.dependencies {
                    implementation(kotlin("test"))
                    implementation(lib("kotlinx-coroutines-test"))
                    implementation(lib("turbine"))
                }
            }
        }
}
