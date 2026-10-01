pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RecipeClipper"
include(":app")
// The pure logic (#238): the model, the parsers and what the differential corpus pins. Plain
// Kotlin/JVM, so nothing in it can reach Android; :app depends on it.
include(":core")
