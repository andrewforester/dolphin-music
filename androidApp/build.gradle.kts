import java.util.Properties

plugins {
    alias(libs.plugins.template.android.application)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.moduleGraphAssertion)
}

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun buildConfigString(
    key: String,
    default: String = "",
): String = quoted(localProps.getProperty(key) ?: System.getenv(key) ?: default)

fun signingValue(
    property: String,
    env: String,
): String? = (localProps.getProperty(property) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val signingStoreFile = signingValue("signing.storeFile", "ANDROID_KEYSTORE_PATH")
val signingStorePassword = signingValue("signing.storePassword", "ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("signing.keyAlias", "ANDROID_KEY_ALIAS")
val signingKeyPassword = signingValue("signing.keyPassword", "ANDROID_KEY_PASSWORD")

android {
    namespace = "com.voiceproducer.android"

    defaultConfig {
        applicationId = "com.voiceproducer.android"
        providers.gradleProperty("applicationIdSuffix").orNull?.let { applicationIdSuffix = it }
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "PROXY_BASE_URL",
            buildConfigString("PROXY_BASE_URL", providers.gradleProperty("voiceproducer.proxyBaseUrl").getOrElse("")),
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingStoreFile != null && signingStorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "ACCESS_CODE", buildConfigString("ACCESS_CODE"))
        }
        release {
            buildConfigField("String", "ACCESS_CODE", quoted(""))
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

moduleGraphAssert {
    configurations =
        setOf(
            "api",
            "implementation",
            "commonMainApi",
            "commonMainImplementation",
            "androidMainApi",
            "androidMainImplementation",
            "iosMainApi",
            "iosMainImplementation",
        )
    restricted =
        arrayOf(
            ":core:model -X> :.*",
            ":core:designsystem -X> :.*",
            ":core:dsp -X> :(?!core:model$).*",
            ":core:common -X> :.*",
            ":core:domain -X> :(?!core:(model|dsp)$).*",
            ":core:codec -X> :(?!core:(model|dsp)$).*",
            ":core:database -X> :(?!core:model$).*",
            ":core:playback -X> :(?!core:(domain|dsp|model)$).*",
            ":core:data -X> :(?!core:(domain|model|dsp|database|codec|common)$).*",
            ":core:agent -X> :(?!core:(domain|model|common)$).*",
            ":feature:.* -X> :feature:.*",
            ":feature:.* -X> :core:(data|database|playback|codec|agent|common)",
        )
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.feature.producer)
    implementation(projects.core.model)
    implementation(projects.core.domain)
    compileOnly(libs.livekit.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(projects.core.common)
    testImplementation(projects.feature.access)
    testImplementation(projects.core.data)
    testImplementation(projects.core.database)
    testImplementation(projects.core.codec)
    testImplementation(projects.core.playback)
    testImplementation(projects.core.agent)
    testImplementation(libs.koin.test)
    testImplementation(libs.okio)
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.exoplayer)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.composable.preview.scanner)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
