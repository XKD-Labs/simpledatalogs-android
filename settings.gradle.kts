// Plugins resolve from Google's and Gradle's repositories only.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Libraries resolve from Google and Maven Central only, never from a module's own block.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "simpledatalogs-android"

// The pure Kotlin/JVM core, usable on servers and by the Android library.
include(":simpledatalogs-core")

// The Android library needs an Android SDK; pass -PjvmOnly=true to build only the core without one.
val isJvmOnlyBuild = providers.gradleProperty("jvmOnly").orNull == "true"
if (!isJvmOnlyBuild) {
    include(":simpledatalogs")
}
