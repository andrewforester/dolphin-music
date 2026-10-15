pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "voice-producer"

include(":androidApp")
include(":composeApp")
include(":feature:producer")
include(":feature:access")

include(":core:model")
include(":core:dsp")
include(":core:common")
include(":core:codec")
include(":core:playback")
include(":core:agent")
include(":core:domain")
include(":core:database")
include(":core:data")
include(":core:testing")
include(":core:designsystem")
include(":konsist-test")
