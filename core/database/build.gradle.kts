plugins {
    alias(libs.plugins.template.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
