pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal() // For testing the published artifact locally before Maven Central release
    }
}

rootProject.name = "atlantis-android"
include(":atlantis")
include(":sample")
