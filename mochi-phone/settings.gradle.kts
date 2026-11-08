pluginManagement {
    repositories {
        // Google's mirror of Maven Central first: serves the jars itself (Maven Central sent some, like the Kotlin
        // compiler, on to GitHub release files, which returned 504s on 2026-11-08)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google's mirror of Maven Central first: serves the jars itself (Maven Central sent some, like the Kotlin
        // compiler, on to GitHub release files, which returned 504s on 2026-11-08)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
    }
}
rootProject.name = "mochi-phone"
include(":app")
