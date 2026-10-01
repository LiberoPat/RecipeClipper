plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin): there is no kotlin-android plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // :core (#238) is plain Kotlin/JVM. The same Kotlin as above: AGP already puts this
    // version's Kotlin Gradle plugin on the classpath, so bump the two together.
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
}
