plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.moduleGraphAssertion) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:${libs.versions.composeRules.get()}"))
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Runs every check CI runs on Linux: format, static analysis, tests and screenshots."
    dependsOn("spotlessCheck")
    dependsOn(":androidApp:lintDebug")
    dependsOn(":androidApp:testDebugUnitTest")
    dependsOn(":androidApp:assertModuleGraph")
    dependsOn(":androidApp:verifyRoborazziDebug")
    dependsOn(":konsist-test:test")
    subprojects.forEach { p ->
        p.plugins.withId("dev.detekt") { dependsOn("${p.path}:detekt") }
        p.plugins.withId("com.android.kotlin.multiplatform.library") { dependsOn("${p.path}:testAndroidHostTest") }
        p.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            if (p.findProperty("kmp.jvmTests") == "true") dependsOn("${p.path}:jvmTest")
        }
    }
}
