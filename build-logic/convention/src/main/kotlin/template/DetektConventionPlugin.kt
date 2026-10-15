package template

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("dev.detekt")

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig.set(true)
                parallel.set(true)
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                source.setFrom(fileTree("src") { include("**/*.kt") })
                basePath.set(rootProject.layout.projectDirectory)
            }

            dependencies {
                add("detektPlugins", lib("detekt-compose-rules"))
            }

            tasks.withType<Detekt>().configureEach {
                jvmTarget.set("17")
                reports {
                    html.required.set(true)
                    sarif.required.set(true)
                    checkstyle.required.set(false)
                    markdown.required.set(false)
                }
            }

            tasks.matching { it.name == "check" }.configureEach { dependsOn("detekt") }
        }
}
