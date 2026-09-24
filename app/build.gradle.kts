plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Release signing, read from outside the repo: Gradle properties (in
// ~/.gradle/gradle.properties, never this project's gradle.properties) or environment
// variables, e.g. recipeClipper.storeFile or RECIPECLIPPER_STORE_FILE. When any of the four
// is missing, the release build is still produced, unsigned. See docs/release.md.
fun releaseSigning(name: String): String? {
    val env = "RECIPECLIPPER_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()
    return providers.gradleProperty("recipeClipper.$name")
        .orElse(providers.environmentVariable(env))
        .orNull
        ?.takeIf { it.isNotBlank() }
}
val releaseStoreFile = releaseSigning("storeFile")
val releaseStorePassword = releaseSigning("storePassword")
val releaseKeyAlias = releaseSigning("keyAlias")
val releaseKeyPassword = releaseSigning("keyPassword")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    // The Kotlin package stays com.example.recipeclipper; only the installed ID
    // (applicationId) is the real one. The two are independent.
    namespace = "com.example.recipeclipper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liberopat.recipeclipper"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Null, so unsigned, when the keystore properties are absent.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // MigrationTestHelper reads the exported schema JSON from the instrumentation APK's
    // assets, not from the project directory, so the schemas have to be packaged into the
    // androidTest APK. Without this every migration test fails with
    // "Cannot find the schema file in the assets folder" — which looks like a broken
    // migration and is really a missing file.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    // The hand-written fakes live in the JVM test source set, and the Compose UI tests want
    // the same ones: a screen test drives a real ViewModel over a fake repository, so the
    // behaviour under test is the actual wiring rather than a stub of it. Sharing the one
    // directory beats keeping two copies of FakeRecipeRepository in step. Only `fake/` is
    // shared — the JVM-only helpers next to it (MainDispatcherRule, collectEagerly) depend on
    // kotlinx-coroutines-test and have no business on a device.
    sourceSets.getByName("androidTest").java.srcDir("src/test/java/com/example/recipeclipper/fake")
}

// The weekly site check (#32, .github/workflows/site-check.yml) fetches real recipe pages, so
// it is excluded from every normal unit-test run and runs only when asked for:
// `./gradlew testDebugUnitTest -PsiteCheck`, which then runs nothing else. Results land in
// app/build/site-check/.
val siteCheck = providers.gradleProperty("siteCheck").isPresent
val siteCheckOut = layout.buildDirectory.dir("site-check")
tasks.withType<Test>().configureEach {
    if (siteCheck) {
        filter.includeTestsMatching("com.example.recipeclipper.sitecheck.LiveSiteCheck")
        systemProperty("siteCheck.out", siteCheckOut.get().asFile.absolutePath)
        outputs.upToDateWhen { false } // the sites change, the inputs don't
        testLogging.showStandardStreams = true
    } else {
        exclude("com/example/recipeclipper/sitecheck/LiveSiteCheck*")
    }
}

// Room writes its schema here on every build; commit the files, they are what future
// migrations are written and tested against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // org.json is an Android framework class; plain JVM tests need a real implementation.
    testImplementation("org.json:json:20240303")
    // viewModelScope posts to Dispatchers.Main, which doesn't exist on the JVM; this lets a
    // test install a StandardTestDispatcher/UnconfinedTestDispatcher in its place.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")

    // Pinned ahead of what compose-ui-test drags in. Espresso 3.6.x reflects into
    // android.hardware.input.InputManager#getInstance, which no longer exists on API 36+, so
    // every Compose test dies in Espresso.onIdle() with a NoSuchMethodException before a
    // single assertion runs. The emulator in use is API 37. 3.7.0 drops that reflection.
    // This is a test-only classpath bump and does not touch the app's Compose versions.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    // Compose UI tests. The BOM has to be applied to the androidTest classpath too, or
    // ui-test-junit4 resolves to a version that doesn't match the Compose in the app.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Provides the empty Activity that createComposeRule launches. debugImplementation, not
    // androidTestImplementation: it contributes an <activity> to the app under test's
    // manifest, which only the debug variant needs.
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation and Hilt. Pinned to versions built against the Compose BOM above: the
    // newest navigation-compose needs a newer Compose and would pull in mixed versions.
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    val hilt = "2.60.1"
    implementation("com.google.dagger:hilt-android:$hilt")
    ksp("com.google.dagger:hilt-compiler:$hilt")

    // Persistence
    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    // MigrationTestHelper, which opens a database at an old version from the exported schema
    // in app/schemas and runs a real migration against it. Device-only: it needs real SQLite.
    androidTestImplementation("androidx.room:room-testing:$room")

    // Fetch + parse the shared page (also reads embedded JSON-LD recipe data)
    implementation("org.jsoup:jsoup:1.17.2")

    // Recipe photo
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Background thread for the network fetch
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// DifferentialCorpusTest reads the iOS corpus and checks it against the Kotlin, so an edit to
// that Swift file alone must rerun the unit tests rather than leave them "up to date".
tasks.withType<Test>().configureEach {
    inputs.files("../ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift")
        .withPropertyName("differentialCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
