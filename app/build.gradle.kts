plugins {
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself (built-in Kotlin).
    id("com.android.application")
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
    // compileSdk is ahead of targetSdk because the current AndroidX releases (Compose 1.12,
    // navigation 2.10, core 1.19) require compiling against 37. targetSdk is what changes
    // runtime behaviour: 36 is what Google Play requires, and its behaviour changes
    // (edge-to-edge, predictive back) are handled. Raise it only after reading the next
    // release's behaviour changes.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.liberopat.recipeclipper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // Kotlin's jvmTarget follows targetCompatibility under built-in Kotlin. D8 desugars
        // Java 17 bytecode for minSdk 26; Java 8 made javac (Hilt's generated code) warn
        // that source/target 8 is obsolete.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    // Features that ship dark are flags in shared/flags.json (#87), with a default per build
    // type there, not buildConfigFields here.
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
            // shared/ is a main resource dir for its tables (#9); its fixtures/ are test data
            // (see the test source set below), not something to ship.
            excludes += "/fixtures/**"
        }
    }

    // The word and density tables shared with iOS (#9) live in shared/tables/ at the repo
    // root. As Java resources they land in the APK and on the JVM test classpath alike, so
    // the pure model code reads them with getResourceAsStream and no Context.
    sourceSets.getByName("main").resources.directories += "$rootDir/shared"

    // MigrationTestHelper reads the exported schema JSON from the instrumentation APK's
    // assets, not from the project directory, so the schemas have to be packaged into the
    // androidTest APK. Without this every migration test fails with
    // "Cannot find the schema file in the assets folder" — which looks like a broken
    // migration and is really a missing file.
    sourceSets.getByName("androidTest").assets.directories += "$projectDir/schemas"

    // The hand-written fakes live in the JVM test source set, and the device tests that remain
    // (ClipScreenTest's real WebView) want the same ones. Sharing the one directory beats
    // keeping two copies of FakeRecipeRepository in step. Only `fake/` is shared — the
    // JVM-only helpers next to it (MainDispatcherRule, collectEagerly) depend on
    // kotlinx-coroutines-test and have no business on a device.
    // (`kotlin`, not `java`: built-in Kotlin compiles only the kotlin source directories.)
    sourceSets.getByName("androidTest").kotlin.directories += "src/test/java/com/example/recipeclipper/fake"

    // The Compose screen tests and the Room DAO tests run on the JVM under Robolectric (#91):
    // they need the merged manifest (ui-test-manifest's empty Activity) and the app's
    // resources. The SDK Robolectric emulates is pinned in src/test/resources/robolectric.properties.
    testOptions.unitTests.isIncludeAndroidResources = true
    // JDK 25 (Android Studio's JBR, and CI's) closes the internals Robolectric reaches into for
    // file descriptors; without these every Robolectric test dies before it starts.
    testOptions.unitTests.all {
        it.jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED"
        )
    }

    // Fixtures both platforms test against (the iOS tests copy the same folder into their
    // bundle): the export file format is proven interchangeable by reading the same files.
    sourceSets.getByName("test").resources.directories += "$rootDir/shared/fixtures"
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
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Icons.Default.*: material3 1.4 stopped pulling this in. The core set only; see
    // CLAUDE.md on material-icons-extended.
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // org.json is an Android framework class; plain JVM tests need a real implementation.
    testImplementation("org.json:json:20260814")
    // viewModelScope posts to Dispatchers.Main, which doesn't exist on the JVM; this lets a
    // test install a StandardTestDispatcher/UnconfinedTestDispatcher in its place.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // Robolectric (#91): the Compose screen tests and the Room DAO tests run in
    // testDebugUnitTest, on the JVM, with the same AndroidX test APIs as on a device.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")

    // Kept ahead of what compose-ui-test may drag in. Espresso 3.6.x reflects into
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

    // Navigation and Hilt. navigation-compose has no BOM of its own; bump it with the Compose
    // BOM so both ask for the same Compose (2.10 is built against Compose 1.12).
    // hiltViewModel() now lives in hilt-lifecycle-viewmodel-compose; the copy in
    // hilt-navigation-compose is deprecated, and nothing here needs the navigation-scoped one.
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.4.0")
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
    // navigation 2.10 puts kotlinx-serialization-core 1.7.3 on the app's runtime classpath,
    // and AGP pins the test APK to the app's versions, which drags room-testing's
    // kotlinx-serialization-json 1.8.1 onto core 1.7.3: every MigrationTest then dies in an
    // AbstractMethodError (GeneratedSerializer.typeParametersSerializers). Raise core past 1.8.1.
    constraints {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    }

    // Fetch + parse the shared page (also reads embedded JSON-LD recipe data).
    // Held at 1.17.2 on purpose; it is not part of the toolchain. Newer releases need core
    // library desugaring on Android (1.19.1), fetch through java.net.http.HttpClient where it
    // exists (1.21.1: on the JVM, so unit tests no longer exercise the device's
    // HttpURLConnection path, and a timeout stops being a SocketTimeoutException), and
    // change Element.text() boundaries (1.22.2), which the iOS stripHtml port mirrors.
    implementation("org.jsoup:jsoup:1.17.2")

    // Recipe photo
    implementation("io.coil-kt:coil-compose:2.7.0")

    // The one-time unlock (#107): Google Play Billing, only behind PlayBillingEntitlements.
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Background thread for the network fetch
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Chef mode (#100): short steps from Gemini Nano on the phone, through AICore. Its minSdk of
    // 26 is why ours is 26: overriding it (<uses-sdk tools:overrideLibrary>) makes lint read the
    // app's targetSdk as 1, which silences targetSdk-based checks (docs/decisions.md).
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")
}

// DifferentialCorpusTest reads the iOS corpus and checks it against the Kotlin, so an edit to
// that Swift file alone must rerun the unit tests rather than leave them "up to date".
tasks.withType<Test>().configureEach {
    inputs.files("../ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift")
        .withPropertyName("differentialCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
