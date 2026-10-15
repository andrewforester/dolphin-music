plugins {
    alias(libs.plugins.template.kmp.compose)
}

kotlin {
    android {
        androidResources { enable = true }
    }
}

compose.resources {
    packageOfResClass = "com.voiceproducer.core.designsystem.generated.resources"
}
