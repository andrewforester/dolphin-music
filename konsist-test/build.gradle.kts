plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.template.detekt)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.konsist)
}

tasks.test {
    inputs
        .files(
            rootProject.fileTree(rootProject.projectDir) {
                include("**/src/**/*.kt")
                exclude("**/build/**", "konsist-test/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
}
