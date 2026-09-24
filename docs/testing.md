# Testing, devices and emulators

Detail for running and writing tests. `CLAUDE.md` has the short version and
the commands; iOS test commands and the simulator rules are in
`ios/README.md`. Moved out of `CLAUDE.md` in September 2026, word for word;
"above" and "below" refer to the old layout.

## Test suites

Tests: `app/src/test/` has the JVM ones (`IngredientScalerTest` scaling,
servings and yield parsing; `UnitConverterTest`; `TemperatureConverterTest`;
`StepTimersTest`; `ConvertersTest`; `TimeAgoAndUrlInputTest`; `UrlCleanerTest`,
now also covering the http→https upgrade; `SourceDomainTest`, the domain the
reading view credits (one leading "www." dropped, other subdomains kept);
`JsonLdRecipeParserTest`, covering
entity/tag stripping, blank-after-strip lines being dropped, `<br>`-split
steps staying separate, and the deep-JSON depth guard; `RecipeShareTextTest`,
covering the labelled-vs-unlabelled serves line (built from a `ServingsScale?`),
the times line omitting absent values, the no-yield recipe omitting the serves
line, and the source URL being left out); and
`RecipeViewModelTest`, `HistoryViewModelTest`, `HomeViewModelTest`,
`SettingsViewModelTest` (see the Settings screen bullet above — each setter
writes through to a `FakeAppPreferences` and updates `SettingsUiState`, and
state is seeded from preferences on construction; `SettingsUiState` is a
plain `MutableStateFlow`, not `stateIn(WhileSubscribed(...))`, so unlike
`HomeViewModelTest`/`HistoryViewModelTest` it needs no `collectEagerly`).
`FakeAppPreferences` keeps its values in one `MutableStateFlow` (iOS: a
`CurrentValueSubject`), so writing a value on the fake directly stands for
Settings changing a default while another screen is open; the
`RecipeViewModelTest` cases under "A settings change arriving while the
recipe is open" use that (#24).
and the three list suites — `SaveToListViewModelTest`, `ListsViewModelTest`
and `ListDetailViewModelTest`. Those run against `FakeListRepository`, which
deliberately models membership as real state rather than only recording calls:
the behaviour worth proving is a round trip (ticking a list writes, and the
flow the sheet collects re-emits with the box now checked), and a
call-recording fake would prove only half of it. `recipeCount` and
`containsRecipe` are derived from that state exactly as the SQL derives them,
so a `recipeCount` staged on a list literal is ignored — stage membership
instead. The error handling has its own: `DefaultRecipeRepositoryRetryTest`
(the retry rule, the saved-copy fallback, cancelling during the pause, and
the rendered fallback after it over a `FakeRenderedPageSource`: rendered once
and only after `Blocked` or `NoRecipeFound`, never for `Offline` or a
timeout, the 20 s cap, a rendered page with no recipe keeping the cause, and
cancelling mid-render writing nothing; iOS has the same cases in
`DataRepositoryTests`),
`BlogRecipeSourceStatusTest` (which statuses and exceptions become which
cause, against a fake `Connectivity`), `DatabaseErrorTest` (every repository
call degrades and logs instead of throwing, and cancellation is never
swallowed), and the reconnect cases in `RecipeViewModelTest`.
`MicrodataRecipeParserTest` covers the microdata fallback on hand-written pages
shaped like Smitten Kitchen's (the iOS suite uses the same pages).
`DifferentialCorpusTest` recomputes every ingredient and instruction row of
the iOS `DifferentialCorpusTests.swift` from its input, fails if the file is
stale, and writes the regenerated file to
`app/build/differential-corpus/DifferentialCorpusTests.swift` to copy over it
(`app/build.gradle.kts` declares the Swift file as a test input, so editing
it alone reruns the tests). `SiteReportTest` covers the weekly site check's
report and URL list offline (see CI below).

`app/src/androidTest/` has `RecipeDaoTest` and `ListDaoTest`, which run the
database rules against real SQLite on a device, because they live in SQL and a
fake would prove nothing. `RecipeDaoTest` covers search (title match,
ingredient match, empty query returns everything, case-insensitivity, and a
literal `%` in the query, which is what catches a regression to `LIKE`) and
delete/restore (row and cross-refs gone; restore brings back the row and its
list membership under the same id). `ListDaoTest` covers built-ins sorting
before user lists whatever they're named, derived counts, `containsRecipe`
being true only for the recipe asked about, the IGNORE-not-REPLACE `addedAt`
rule, deleting a user list cascading its membership while leaving its recipes,
deleting a built-in being refused, renaming a built-in keeping `isFavorites`,
and a recipe out of its last list staying in history. It also pins the rule
that a seeded list which isn't Favorites *can* be deleted, so a guard that
regressed to `isBuiltIn = 0` would fail rather than quietly return.

`WebViewRenderedPageSourceTest` runs the rendered fallback's real `WebView`:
a `data:` URL page (no network) whose script adds its recipe JSON-LD 300 ms
after the load event must come back from `render` with that JSON-LD in the
HTML, and parse through `BlogRecipeSource.parse`. It proves the settle wait
and the `outerHTML` decoding; a JVM test can't host a WebView.

`MigrationTest` uses Room's `MigrationTestHelper` (hence
`androidTestImplementation("androidx.room:room-testing")`) to open a real
version-1 database from the exported schema and run `MIGRATION_1_2` against
it: Breakfast and Snacks arrive, an existing recipe keeps its title,
ingredients, `lastViewedAt` and list membership, and a user-created list keeps
its place after the seeded block. This is what makes "never use destructive
migration" checkable rather than an intention.

**All 89 device tests have been run on an emulator and pass**: 26
`RecipeDaoTest`, 22 `ListDaoTest`, 3 `MigrationTest`, and 38 Compose UI tests
(see below). `RecipeSourceCreditTest` (the source credit under the recipe
title) was added after that run and has so far only been compiled.

`MigrationTest` needs `app/schemas` packaged into the instrumentation APK:
`MigrationTestHelper` reads the exported JSON from the test APK's **assets**,
not from the project directory. That is what
`sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")` in
`app/build.gradle.kts` is for. Without it every migration test fails with
`FileNotFoundException: Cannot find the schema file in the assets folder`,
which reads like a broken migration and is really a missing file — don't go
hunting in the migration when that appears.

**Compose UI tests** (`androidx.compose.ui:ui-test-junit4`, plus
`debugImplementation("androidx.compose.ui:ui-test-manifest")` for the empty
Activity `createComposeRule` launches): `HomeScreenTest` (12),
`SaveToListBottomSheetTest` (12) and `ListDetailScreenTest` (14). They exist
because every ViewModel behind Home was already covered and the whole suite
stayed green through a duplicate-key crash that made the app unusable — that
bug lived entirely in the view.

No Hilt in these. Every screen takes its ViewModel as a parameter defaulting
to `hiltViewModel()`, so a test builds a real ViewModel over a fake repository
and passes it in; what runs is the real repository-to-ViewModel-to-pixels
wiring. The fakes are shared with the JVM tests through
`sourceSets.getByName("androidTest").java.srcDir(".../test/java/.../fake")` —
only `fake/`, since the helpers beside it need kotlinx-coroutines-test and
have no business on a device.

Three things that cost real time and will again:

- **Espresso 3.6.x is broken on API 36+.** It reflects into
  `android.hardware.input.InputManager#getInstance`, which no longer exists,
  so every Compose test dies in `Espresso.onIdle()` with
  `NoSuchMethodException` before any assertion runs. `espresso-core` is pinned
  to 3.7.0 in `androidTestImplementation` ahead of what compose-ui-test pulls
  in. The emulator in use is API 37.
- **Android collapses runs of whitespace in unquoted string resources.**
  `action_new_list` is written `+  New list` with two spaces and renders as
  `+ New list` with one. A test matching the XML spelling finds nothing, and
  the failure looks like a layout problem: "not displayed", "failed to inject
  touch input", scroll actions failing. Several strings here use double spaces
  for optical padding (`action_back`, `action_exit`, `timer_start`) — **none
  of that extra space has ever rendered.** Match on one space, or quote the
  resource if the spacing is actually wanted.
- Chasing those two symptoms produced two plausible, wrong diagnoses
  (`verticalScroll` fighting the sheet; the sheet sitting partially expanded).
  When a Compose test says a node isn't there, print what *is* there — dump
  every `SemanticsProperties.Text` in the tree — before changing production
  code.

Also `RecipeScreenTest` (reading view, servings and units, bookmark, delete,
and the share text after scaling and converting through the UI; the source
credit has its own `RecipeSourceCreditTest`), `CookModeTest` (step states,
tap to jump, "Done — next step", timers), `HistoryScreenTest` (search,
swipe-to-dismiss, the batched undo snackbar) and `ListsScreenTest`.
`RecipeScreenFixture` gives the recipe tests a `Clock` the test moves
forward, so a 20-minute timer finishes as soon as the test says so; the
ViewModel's 250 ms tick is real time, so wait with `compose.waitUntil`, not a
bare assert. Done steps are only drawn struck through, not exposed to
semantics, so `isStruckThrough()` reads the text's layout style. Share itself
opens the system chooser, so the tests stop at `RecipeViewModel.shareText()`.

Still without Android UI tests: the Settings screen. The iOS UI tests
(`ios/RecipeClipperUITests`) cover Home, History (search, swipe-to-delete, the
batched undo), Settings, list detail, the save-to-list sheet with the bookmark
it fills, the source credit, the import error screens and cook mode
(`CookModeUITests`, on the `cook` seed scenario). XCUITest drives the app
from outside and can't move its clock, so the one timer that has to finish
there is a real 3-second step. The share sheet is left to the hosted
`RecipeViewModelTests`, which pin the exact share text.

Two dependency versions are pinned on purpose: `navigation-compose` 2.7.7 and
`hilt-navigation-compose` 1.2.0. The newest releases need a newer Compose than
the BOM in use and would pull in a mix of Compose versions. Bump them together
with the BOM.

`JsonLdRecipeParser` uses `org.json`, which is an Android framework class.
Plain JUnit tests will need `testImplementation("org.json:json:<version>")`
or the calls will fail as "not mocked".

## CI

GitHub Actions, in `.github/workflows/`:

- **Android** (`android.yml`, check `Android unit tests and lint`), on every
  pull request and push to `main`, on `ubuntu-latest` with JetBrains Runtime
  25 (Android Studio's bundled JDK): `./gradlew testDebugUnitTest lintDebug
  compileDebugAndroidTestKotlin`. A lint error fails the build;
  `.github/scripts/check_lint.py` then fails on any finding, at any severity,
  that isn't one of the four version-advisory ids. It checks ids, not the
  count, because `NewerVersionAvailable` drifts as libraries release. Reports
  are uploaded as the `android-reports` artifact on failure. Device tests
  don't run in CI yet.
- **iOS** (`ios.yml`, check `iOS unit tests`), same triggers, on the
  `xcode-27` runner image (arm64, macOS 27, Xcode 27 only; in public preview
  as of September 2026). `DEVELOPER_DIR` selects Xcode 27 explicitly. It runs
  `RecipeClipperTests` on the image's iPhone 17 / iOS 27.0 simulator and
  uploads the `.xcresult` on failure.
- **iOS UI tests** (`ios-ui-tests.yml`), about 18 minutes: nightly at 03:00
  UTC and on demand (Actions → iOS UI tests → Run workflow).
- **Recipe site check** (`site-check.yml`, #32): Mondays at 06:00 UTC and on
  demand, and on a pull request that changes the check or its URL list. It
  runs the real `BlogRecipeSource` (JSON-LD, then microdata) over
  the ~20 pages in `app/src/test/resources/site-check-urls.txt`, applying the
  repository's one retry, and writes a table to the job summary: per site,
  parsed or the `ParseError` cause, and for a success the ingredient and step
  counts and whether yield, total time and photo came through. Each run
  uploads `results.md` and `results.json` as the `site-check-<run>` artifact
  (kept 90 days): compare runs, since blocking flips run to run. A blocked
  site never fails the job; a broken harness does, and "no site parsed" raises
  a warning. Only outcomes are recorded, never the pages or recipe text.
  Locally: `./gradlew testDebugUnitTest -PsiteCheck` (results in
  `app/build/site-check/`). Without `-PsiteCheck`, `LiveSiteCheck` is excluded
  in `app/build.gradle.kts`, so the normal runs never touch the network.
  Replace a URL only when its page is gone in a browser too.

When a new Xcode major comes out, GitHub ships it as a new image label
(`xcode-28`), so the label, `DEVELOPER_DIR`, the simulator `OS=` and
`.github/actionlint.yaml` move together. Check workflow edits with
`actionlint`.

## Lint

`./gradlew lintDebug` reports no errors and 16 warnings, all of them version advisories:
`GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion` and
`OldTargetApi`. They are left deliberately. `navigation-compose` 2.7.7 and
`hilt-navigation-compose` 1.2.0 are pinned to the Compose BOM in use, bumping
`targetSdk` past 34 is a behaviour change needing device testing, and the rest
are a coupled toolchain that moves together or not at all. Do not silence them
with a baseline: the day one of them matters, it should still be visible.

Every code-level flag has been fixed, so a new one is a real finding rather than
noise. Adding the launcher icon raised `MonochromeLauncherIcon` in turn, which is
why the adaptive icon carries a `<monochrome>` layer.

## Commands, device tests and the emulator

Run from the repo root with the wrapper. The shell needs a JDK; Android
Studio's bundled one works:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug        # build; APK lands in app/build/outputs/apk/debug/
./gradlew installDebug         # install on a connected device or emulator
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew connectedDebugAndroidTest  # database tests; needs a running emulator
./gradlew lintDebug            # report at app/build/reports/lint-results-debug.html
```

`connectedDebugAndroidTest` uninstalls the app from the emulator when it
finishes, so run `installDebug` again afterwards. It also **destroys whatever
is in the database**, which matters if the emulator holds recipes someone
cares about or a database at an older version you wanted to migrate for real.
The uninstall takes the unit settings (`shared_prefs`) with it too. Back both
up first and put them back after (this round-trip has been used and works):

```
B=/tmp/recipe-backup && mkdir -p $B && P=com.liberopat.recipeclipper
for f in recipe_clipper.db recipe_clipper.db-wal recipe_clipper.db-shm; do
  adb exec-out run-as $P cat databases/$f > $B/$f
done
adb exec-out run-as $P cat shared_prefs/unit_preferences.xml > $B/unit_preferences.xml
# Merge the -wal into the copy, leaving one self-contained file.
sqlite3 $B/recipe_clipper.db "PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;"
# ... run the tests, then installDebug ...
adb push $B/recipe_clipper.db /data/local/tmp/db
adb push $B/unit_preferences.xml /data/local/tmp/prefs.xml
adb shell "run-as $P sh -c 'mkdir -p databases shared_prefs && cat /data/local/tmp/db > databases/recipe_clipper.db && rm -f databases/recipe_clipper.db-wal databases/recipe_clipper.db-shm && cat /data/local/tmp/prefs.xml > shared_prefs/unit_preferences.xml'"
adb shell rm -f /data/local/tmp/db /data/local/tmp/prefs.xml
```

**Copy the `-wal` as well as the `.db`.** Room writes go to the `-wal` first,
and the main file can lag hours behind: the most recent view of a recipe
once existed only in the `-wal`, and copying the `.db` alone would have
quietly lost it. Merge on the Mac and push the one merged file; deleting the
device's `-wal`/`-shm` lets SQLite recreate them cleanly. Check the copies'
sizes against `adb shell run-as $P ls -l databases` before trusting them:
straight after an emulator restores from a snapshot, `run-as` can fail with
`couldn't stat /data/user/0/...`, and the "backup" is then that error text
(88 bytes). A retry a few seconds later works.

### The application ID changed

The installed ID is `com.liberopat.recipeclipper` (`applicationId`); the
Kotlin package and `namespace` are still `com.example.recipeclipper`, which is
why test class names and `am start` use the old spelling. Until September 2026
the app installed as `com.example.recipeclipper`. To Android that is a
different app: `installDebug` now puts a second copy beside it, starting
empty, and the old one keeps its recipes, lists and settings.

To carry them across, run the backup half of the round-trip above with
`P=com.example.recipeclipper` (all three database files, then the merge), then
`installDebug` the new build without opening it (or force-stop it), and run
the restore half with `P=com.liberopat.recipeclipper`. The database is the same
version, so nothing migrates. Check the recipes are there, then
`adb uninstall com.example.recipeclipper`.

A single test:
`./gradlew testDebugUnitTest --tests "com.example.recipeclipper.data.model.IngredientScalerTest"`
(append `.` and a backticked method name to run one case).

The emulator may be in use by a person while you work. Scripted taps, force-stops
and settings changes land in their session, so check for activity first (a
device clock that jumps, or state you didn't set) and ask before automating.
Reading the database is gentler: `adb exec-out run-as com.liberopat.recipeclipper
cat databases/recipe_clipper.db` (plus the `-wal` and `-shm` files) gives a copy
to open read-only with sqlite3.

To try a recipe on a running emulator without the share sheet (adb lives in
`~/Library/Android/sdk/platform-tools/`):

```
adb shell am start -n com.liberopat.recipeclipper/com.example.recipeclipper.MainActivity \
  -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<recipe url>"
adb exec-out screencap -p > shot.png
```

The activity needs its full class name: the `.MainActivity` shorthand expands
against the application ID, which no longer matches the package.

The unit default persists in the app's SharedPreferences and recipes persist in
the database, so a test run leaves both behind (`adb shell pm clear
com.liberopat.recipeclipper` resets them). To test rotation from the shell:
`adb shell settings put system accelerometer_rotation 0` then
`settings put system user_rotation 1` (0 is portrait); put
`accelerometer_rotation` back to 1 afterwards.

Gradle 9.6.0, AGP 9.4.0, Kotlin 2.2.10 (with the Compose compiler plugin), KSP
2.3.12, Hilt 2.60.1, Room 2.8.5. Those are coupled; bump them together. The
project still uses AGP's legacy DSL and separate Kotlin plugin
(`android.newDsl=false`, `android.builtInKotlin=false` in `gradle.properties`),
which AGP 9 prints deprecation warnings for on every build. `local.properties`
holds the machine-specific SDK path and is git-ignored.
