package com.voiceproducer.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

class LayerDependencyTest {
    private val production = Konsist.scopeFromProduction()

    @Test
    fun modelImportsNoProjectPackage() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.core.model") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.core.model") }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("com.voiceproducer.") && !import.name.startsWith("com.voiceproducer.core.model")
                }
            }
    }

    @Test
    fun dspImportsOnlyModel() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.core.dsp") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.core.dsp") }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("com.voiceproducer.") &&
                        !import.name.startsWith("com.voiceproducer.core.dsp") &&
                        !import.name.startsWith("com.voiceproducer.core.model")
                }
            }
    }

    @Test
    fun domainImportsOnlyModelAndDsp() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.core.domain") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.core.domain") }
            .assertFalse { file ->
                file.imports.any { import ->
                    val name = import.name
                    name.startsWith("com.voiceproducer.") &&
                        !name.startsWith("com.voiceproducer.core.domain") &&
                        !name.startsWith("com.voiceproducer.core.model") &&
                        !name.startsWith("com.voiceproducer.core.dsp")
                }
            }
    }

    @Test
    fun codecImportsOnlyModelAndDsp() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.core.codec") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.core.codec") }
            .assertFalse { file ->
                file.imports.any { import ->
                    val name = import.name
                    name.startsWith("com.voiceproducer.") &&
                        !name.startsWith("com.voiceproducer.core.codec") &&
                        !name.startsWith("com.voiceproducer.core.model") &&
                        !name.startsWith("com.voiceproducer.core.dsp")
                }
            }
    }

    @Test
    fun playbackImportsOnlyDomainDspAndModel() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.core.playback") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.core.playback") }
            .assertFalse { file ->
                file.imports.any { import ->
                    val name = import.name
                    name.startsWith("com.voiceproducer.") &&
                        !name.startsWith("com.voiceproducer.core.playback") &&
                        !name.startsWith("com.voiceproducer.core.domain") &&
                        !name.startsWith("com.voiceproducer.core.model") &&
                        !name.startsWith("com.voiceproducer.core.dsp")
                }
            }
    }

    @Test
    fun agentImportsOnlyDomainModelAndCommon() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.core.agent") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.core.agent") }
            .assertFalse { file ->
                file.imports.any { import ->
                    val name = import.name
                    name.startsWith("com.voiceproducer.") &&
                        !name.startsWith("com.voiceproducer.core.agent") &&
                        !name.startsWith("com.voiceproducer.core.domain") &&
                        !name.startsWith("com.voiceproducer.core.model") &&
                        !name.startsWith("com.voiceproducer.core.common")
                }
            }
    }

    @Test
    fun commonCodeDoesNotImportPlatformApis() {
        production
            .files
            .filter { it.sourceSetName == "commonMain" }
            .assertFalse { file ->
                file.imports.any { it.name.startsWith("android.") || it.name.startsWith("platform.") }
            }
    }

    @Test
    fun featuresDoNotImportOtherFeaturesOrData() {
        production
            .files
            .filter { it.packagee?.name?.startsWith("com.voiceproducer.feature.") == true }
            .also { assertTrue(it.isNotEmpty(), "no files in com.voiceproducer.feature") }
            .assertFalse { file ->
                val ownFeature =
                    file.packagee!!
                        .name
                        .split('.')
                        .take(FEATURE_PACKAGE_DEPTH)
                        .joinToString(".")
                file.imports.any { import ->
                    val name = import.name
                    (name.startsWith("com.voiceproducer.feature.") && !name.startsWith(ownFeature)) ||
                        FORBIDDEN_IN_FEATURES.any { name.startsWith("com.voiceproducer.core.$it.") }
                }
            }
    }

    private companion object {
        const val FEATURE_PACKAGE_DEPTH = 4
        val FORBIDDEN_IN_FEATURES = listOf("data", "database", "playback", "codec", "agent", "common")
    }
}
