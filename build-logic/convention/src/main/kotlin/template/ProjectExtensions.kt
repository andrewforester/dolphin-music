package template

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal const val BASE_PACKAGE = "com.voiceproducer"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> = libs.findLibrary(alias).get()

internal fun Project.intVersion(alias: String): Int =
    libs
        .findVersion(alias)
        .get()
        .requiredVersion
        .toInt()

internal val Project.namespaceFromPath: String
    get() = BASE_PACKAGE + path.replace(':', '.').replace('-', '_')
