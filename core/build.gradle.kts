import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The pure logic (#238): data/model, the parsers in data/remote, and what the differential
// corpus pins (RecipeRenderer). A plain Kotlin/JVM library, not Android and not Kotlin
// Multiplatform (the owner's decision, 2026-10-01): with no Android on its classpath, "no
// Android in the model" is a compile error here rather than a convention. Package names are
// the app's, unchanged. iOS keeps its own Swift copy, pinned by the differential corpus.
plugins {
    id("org.jetbrains.kotlin.jvm")
    // RedditFixtures, shared with :app's RedditRecipeSourceTest.
    `java-test-fixtures`
}

// The same bytecode level as :app (its compileOptions), which D8 desugars for minSdk 26.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

sourceSets {
    // The word and density tables shared with iOS (#9), the flags, the tour's sample and the
    // clip scripts live in shared/ at the repo root. As this module's Java resources they land
    // in the APK (through :app's dependency on this jar) and on every JVM test classpath alike,
    // so the model reads them with getResourceAsStream and no Context. The fixtures are test
    // data, not something to ship.
    main {
        resources.srcDir("$rootDir/shared")
        resources.exclude("fixtures/**")
    }
    // Fixtures both platforms test against (the iOS tests copy the same folder into their bundle).
    test {
        resources.srcDir("$rootDir/shared/fixtures")
    }
}

dependencies {
    // Parses the shared page and the HTML inside JSON-LD. `api`: Document and Element are in
    // the parsers' signatures. Keep the version in step with :app's, which fetches with it and
    // says why it is held at 1.17.2.
    api("org.jsoup:jsoup:1.17.2")
    // Jsoup's nullness annotations, which its pom leaves optional: Kotlin needs them to read
    // its types. In :app, AndroidX brings the same 1.0.0.
    compileOnly("org.jspecify:jspecify:1.0.0")
    // org.json is an Android framework class: on the device the framework's copy is the one
    // that runs, and bundling another trips lint's DuplicatePlatformClasses. So compile against
    // it only, and give the JVM tests a real implementation. Keep in step with :app's.
    compileOnly("org.json:json:20260814")
    testImplementation("org.json:json:20260814")
    testImplementation("junit:junit:4.13.2")
}

// DifferentialCorpusTest reads the iOS corpus and checks it against the Kotlin, so an edit to
// that Swift file alone must rerun the tests rather than leave them "up to date".
tasks.withType<Test>().configureEach {
    inputs.files("../ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift")
        .withPropertyName("differentialCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// `./gradlew testDebugUnitTest`, the command everyone runs, runs these tests too. Not under
// -PsiteCheck, which runs :app's LiveSiteCheck and nothing else.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs :core's tests (an alias of test), so testDebugUnitTest covers every module."
    if (!providers.gradleProperty("siteCheck").isPresent) dependsOn(tasks.test)
}
