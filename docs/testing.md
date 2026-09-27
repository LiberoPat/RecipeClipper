# Testing, devices and emulators

Detail for running and writing tests. `CLAUDE.md` has the short version and
the commands; iOS test commands and the simulator rules are in
`ios/README.md`. Moved out of `CLAUDE.md` in September 2026, word for word;
"above" and "below" refer to the old layout.

## Test suites

### Which tests run where (#91)

- **JVM, `./gradlew testDebugUnitTest`** (and CI): the pure logic and
  ViewModel tests, and, under Robolectric, the Compose screen tests
  (`*ScreenTest`, `CookModeTest`, `AppShellTest`, `SaveToListBottomSheetTest`,
  the `Recipe*Test`s) and the Room DAO tests (`*DaoTest`, on an in-memory
  database, real SQLite through Robolectric's native build of it). No
  emulator. A screen test is `@RunWith(AndroidJUnit4::class)` with
  `createComposeRule()`, as on a device.
- **Device, `./gradlew connectedDebugAndroidTest`** (not in CI): only what
  needs a real device. `MigrationTest` (its schemas are read from the test
  APK's assets), `WebViewRenderedPageSourceTest` and `ClipScreenTest` (a real
  `WebView` running JavaScript; Robolectric's is a stub), and
  `MainActivitySmokeTest`, the end-to-end smoke set: the real app with its
  real Hilt graph, database and network stack, launched from the launcher and
  by a shared link (an `ACTION_SEND` intent to a `.invalid` host, which must
  reach the import screen and its Try again). It sets the first-run welcome
  pending (#151): the launcher opens it and Skip leaves for Home, while the
  shared link opens on the import screen with no welcome over it.

Waits that flaked under load (#91, and the nightly iOS UI tests), and what
not to undo:

- `ClipScreenTest` asks the page things (`evaluateJavascript`) inside a
  `waitUntil`. Each call gives up after 2 s and is asked again within one
  15 s wait; a call once allowed as long as the whole wait, so one slow answer
  from a busy renderer failed the test.
- iOS UI tests that delete and then tap Undo tap it as soon as the snackbar
  shows, and check the row went afterwards: the snackbar lasts four seconds of
  real time, and waiting for the row first could outlast it.
- `ClipUITests` gives anything on the web page 60 s, not the usual 10: WebKit
  draws it and answers accessibility queries from its own process, which on a
  busy machine took over 10 s to show the page, and on CI 9 s and then 28 s to
  answer one query. It also waits for each snackbar to go before the next
  tap: the snackbar leaves by itself after four seconds, sliding down across
  the field buttons, and a tap that met it there was lost.
- `ShareUITests` dismisses the share sheet by tapping in the free part of the
  screen beside it, never at the very top. On iOS 27 the iPhone's share sheet
  is a popover starting just under the status bar, and a tap in the status bar
  goes to the system, not the popover's dismiss region.

Robolectric's setup, all in `app/build.gradle.kts` and
`app/src/test/resources/robolectric.properties`:

- `sdk=36`, whatever `targetSdk` says: one Robolectric supports. The screen is
  `w320dp-h891dp`: Robolectric's default height leaves rows the tests assert
  on below the fold, and at 360dp wide or more a Material3 `AlertDialog`
  (List detail's Rename) never lets Compose go idle, so the test hangs.
- `application=android.app.Application`, so no test builds the Hilt graph.
  Screens take their ViewModel as a parameter, as on a device.
- `isIncludeAndroidResources`, for the strings and `ui-test-manifest`'s
  empty Activity; and two `--add-exports`/`--add-opens` JVM flags, without
  which JDK 25 stops every Robolectric test before it starts.
- **Robolectric's main looper has its own clock, which moves only when a test
  moves it.** A `delay` or `debounce` in `viewModelScope` (Recipes' and the
  Week sheet's 250 ms search debounce) never fires by itself: call
  `passTheSearchDebounce()` / `advanceMainLooperBy(ms)` (`MainLooper.kt`).
  Compose's own clock (animations, `LaunchedEffect` delays) moves by itself.
  `RecipeScreenFixture`'s timers run on the injected `Clock`, so
  `compose.waitUntil` still works for them.
- The JVM's `java.text.SimpleDateFormat` rejects ICU-only pattern letters
  (`ccc`) that Android's accepts; the plan's day labels use ICU's
  `android.icu.text.SimpleDateFormat`, which both take.
- A runtime permission is granted with
  `shadowOf(application).grantPermissions(...)` (`CookModeTest`), not
  `uiAutomation`.

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
`RecipeViewModelTest`, `RecipesViewModelTest`, `HomeViewModelTest`,
`SettingsViewModelTest` (see the Settings screen bullet above — each setter
writes through to a `FakeAppPreferences` and updates `SettingsUiState`, and
state is seeded from preferences on construction; `SettingsUiState` is a
plain `MutableStateFlow`, not `stateIn(WhileSubscribed(...))`, so unlike
`HomeViewModelTest`/`RecipesViewModelTest` it needs no `collectEagerly`).
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
`DataRepositoryTests`), `DefaultRecipeRepositoryExtractionTest` (iOS
`DataExtractionTests`: a recipe picked from a page's text, #103, over the
shared page fixture in `shared/fixtures/pages` and `FakePageRecipeExtractor`:
kept in the page's own words, invented lines dropped, nothing verifiable or an
unsupported language or the flag off staying `NoRecipeFound`, never after a
block; no real model runs in CI),
`BlogRecipeSourceStatusTest` (which statuses and exceptions become which
cause, against a fake `Connectivity`), `DatabaseErrorTest` (every repository
call degrades and logs instead of throwing, and cancellation is never
swallowed), and the reconnect cases in `RecipeViewModelTest`.
`MicrodataRecipeParserTest` covers the microdata fallback on hand-written pages
shaped like Smitten Kitchen's (the iOS suite uses the same pages).
Export and import (#26): `BackupJsonTest` and `BackupMergerTest` read the
shared fixtures in `shared/fixtures/backup/` (a test resource dir on Android,
bundled resources on iOS), so both platforms decode the same file and plan the
same merge; `DefaultBackupRepositoryTest` covers the causes and that a failed
import writes nothing; `SettingsViewModelTest` covers the Your recipes rows.
`BackupDaoTest` (Robolectric) runs the import transaction against real SQL
(IGNORE keeps `addedAt`, rollback on a bad file); iOS's `BackupDaoTests` do the
same on in-memory SQLite.
The automatic backup copy (#150): `AutoBackupPolicyTest` / `AutoBackupPolicyTests` pin the
rules on both platforms (due, nudge, names, which copies go, the fingerprint);
`AutoBackupTest` / `AutoBackupTests` run a copy against a fake export and folder; the
Settings rows and Home's restore and folder card are `SettingsAutoBackupTest` and
`HomeBackupTest` (Robolectric) and `SettingsAutoBackupTests` (iOS). The real Google Drive
folder, WorkManager with the app closed, and iCloud Drive need a device (`docs/decisions.md`).
`DifferentialCorpusTest` recomputes every ingredient and instruction row of
the iOS `DifferentialCorpusTests.swift` from its input, fails if the file is
stale, and writes the regenerated file to
`app/build/differential-corpus/DifferentialCorpusTests.swift` to copy over it
(`app/build.gradle.kts` declares the Swift file as a test input, so editing
it alone reruns the tests). A row read with another language's words names it
after the input (`Ing("2 EL Zucker", lang: "de"),`); a row without one is
English. Its `Dur` rows (#179) pin `Durations.format`, the prep, cook and total
times, per language; `SampleRecipeTest` / `SampleRecipeTests` check the tour's
sample shows its times formatted, and `DefaultRecipeRepositorySampleTest` /
`DataRepositoryTests` the launch-time fix for a sample saved with "PT10M".
`SiteReportTest` covers the weekly site check's
report and URL list offline (see CI below). `SiteReportLinkTest` pins the
"Report this site" issue link byte for byte (percent-encoding, the cleaned
link), and `RecipeViewModelTest` offers it only for `NoRecipeFound` on a
shared link. The corpus's ingredient rows end with real lines (#33),
taken from sites' JSON-LD `recipeIngredient` (US, UK, Australian, French,
Italian, Spanish, Portuguese, Brazilian, Dutch, German, Austrian and Swiss
sites; a comment names each site), so the scaler and converters are pinned on
what recipes actually write, not only on hand-written lines. A real line that
shows a wrong number isn't pinned: it's fixed, or left out with a `bug` issue.
To add some, fetch the page, copy only the ingredient lines (the repo is
public), and add them as input-only rows under their site's comment.

`RecipeDaoTest` and `ListDaoTest` run the database rules against real SQLite
(in-memory, under Robolectric), because they live in SQL and a fake would
prove nothing. `RecipeDaoTest` covers search (title match,
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
its place after the seeded block. `MIGRATION_2_3` (the `notes` column, #27)
is run against a real version-2 database the same way: the recipe keeps its
content, ticks and membership, has no note, and a note written afterwards
survives a re-share. `MIGRATION_3_4` (the `uid` columns, #26) backfills a
distinct UUID on every recipe and list and keeps the rest of each row.
`MIGRATION_4_5` (the `language` column, #14) is run against a real version-4
database: content, note and uid kept, no language, and a re-share fills it in;
a version-1 file also goes to 5 in one open. This is
what makes "never use destructive migration" checkable rather than an
intention.

The week meal plan (#49) adds `MIGRATION_7_8` (two new tables and the four
seeded meal types, nothing existing changed) to `MigrationTest`, and
`MealPlanDaoTest` for the rules that live in SQL: the cull keeps recipes planned
for today or later (and a planned note doesn't stop it), a recipe's meals
cascade and come back on undo, ordering, moving, and meal-type deletion (user
types only, meals moved to Dinner). `WeekScreenTest`, `RecipeAddToPlanTest`
and `AppShellTest` cover the screens. iOS
mirrors them in `MealPlanDaoTests` (with the user_version 6 → 7 step) and
`WeekUITests`.

The grocery list (#50) adds `MIGRATION_8_9` (one new table) to `MigrationTest`,
from a real version-8 file holding a recipe and a planned meal, and
`GroceryDaoTest`: order added, delete and undo restoring whole, a recipe's items
outliving it (SET NULL, also across an undo), and the week's planned recipes in
plan order without notes. `GroceriesScreenTest`, `RecipeAddToGroceriesTest`
and a `WeekScreenTest` case cover the screens. The combining rule is JVM-tested (`GroceryCombinerTest`,
`AislesTest`) and pinned for iOS by the corpus's `Groc` rows. iOS mirrors the
rest in `GroceryDaoTests` (with the user_version 7 → 8 step),
`GroceriesViewModelTests` and `GroceriesUITests`.

The pantry (#51) adds `MIGRATION_9_10` (one new table) to `MigrationTest`, from a
real version-9 file holding a recipe and a grocery item, and `PantryDaoTest`:
restock and running out, edits, a delete restored whole, unique uids.
`BackupDaoTest` round-trips the pantry, the grocery list and the meal plan (with a user's meal type) through an export; iOS's `BackupDaoTests` do the same.
`PantryScreenTest`, `WhatINeedScreenTest` and the updated `AppShellTest` cover
the screens. Have/Buy is JVM-tested (`PantryTest`, the ViewModel tests) and
pinned for iOS by the corpus's `Pant` rows. iOS mirrors the rest in
`PantryDaoTests` (with the user_version 8 → 9 step), `PantryTests`,
`PantryViewModelTests` and `PantryUITests`.

"Done shopping" and the pantry's "On list" tag (#146): `GroceriesPantryTest` (the sheet's
first ticks, put-away, one undo for the list and the pantry), `GroceriesScreenTest`,
`PantryViewModelTest` and `PantryScreenTest`; iOS mirrors them in `PantryViewModelTests`,
`GroceriesUITests` (Done shopping, Undo) and `PantryUITests` (the tag).

The Pantry's "Send list" and "Send as file" (#149, what's in stock): `SendListTextTest` (the
text, and its round trip through "Add this list"), `ShareFileTest` and
`ShareFileRepositoryTest` (the file), `PantryViewModelTest`, `ShareFileViewModelsTest`, and
`PantrySendTest` (Robolectric: the menu hands the share sheet the text or the file, both
disabled with nothing in stock). iOS mirrors them in `SendListTextTests`, `ShareFileTests`,
`ShareFileRepositoryTests`, `PantryViewModelTests` and `ShareFileViewModelsTests`; the system
share sheet isn't reachable from a UI test.

Using up the pantry at the end of cooking (#147): `PantryUseUpTest` (JVM) and
`PantryUseUpTests` (iOS) run the subtraction and every refusal (other kinds, no density,
unreadable text, a different name, parts and packages), and the corpus's `UseUp` rows pin the
Swift to the Kotlin (write only `UseUp("2 lb", "chicken", ["1 lb chicken"]),`). The sheet's
ViewModel over fakes: `PantryUseUpViewModelTest` / `PantryUseUpViewModelTests` (what it lists
and preselects, one confirm, one Undo; "I made this" with ticked lines or none; the 12-hour
guard in both orders, cook mode then a photo and a photo then a photo, and after the window);
the hand-over at "Done — finish": `RecipeCookFinishedTest` (iOS: in
`PantryUseUpViewModelTests`); a photo just added saying the recipe was cooked once it closes:
`CookedPhotosViewModelTest(s)`; the `pantry_use_up` key: `SharedPrefsAppPreferencesTest` /
`UserDefaultsAppPreferencesTests`; the sheet on screen, from "Done — finish" and from a photo
added, once: `PantryUseUpScreenTest` (Robolectric). iOS has no UI test for it: a UI test can't
add a photo (see "I made this" photos, #116, below).

The recipe screen's collaborators (#169), each without a ViewModel: `RecipeRendererTest` /
`RecipeRendererTests` (servings, units, temperatures, short steps, amounts, decisions), with the
corpus's `Render` rows pinning the Swift to the Kotlin (write only
`Render("4 servings", 6, ["2 cups flour"], ["Bake at 350°F."]),`); `CookSessionTest` /
`CookSessionTests` (restore, start, done, the end of cooking, timers over a clock the test
moves, what is saved); `ChefModeTest` / `ChefModeTests` (the flag and setting, a recipe not
kept, an unsupported language, count brackets). The ViewModel suites still cover them together.

Amounts inside steps (#101): `StepAmountsTest` (JVM) and `StepAmountsTests` (iOS) run
the rules on steps modelled on real pages, and the corpus's `Step` rows pin the Swift to the
Kotlin (each step against its lines as given and doubled in Metric; write only
`Step("Add the eggs.", ["2 eggs"]),`). `RecipeStepAmountsTest` (and iOS
`RecipeStepAmountsTests`) shows the amount following servings, units and the switch;
`RecipeStepAmountsScreenTest` and `AmountsInStepsSettingsTest` (Robolectric) and
`AmountsInStepsUITests` (iOS, the `cook` scenario's "1 lb beef") cover the screens.

Pantry expiry reminders (#52): when they fall and what they list is JVM-tested
(`ExpiryRemindersTest`, `ExpiryReminderCoordinatorTest`,
`SettingsExpiryRemindersTest`) and mirrored on iOS (`ExpiryRemindersTests`,
`ExpiryReminderCoordinatorTests`, `SettingsExpiryRemindersTests`,
`ExpiryRemindersUITests`). `ExpiryRemindersSettingsTest` (the switch, with the
permission granted up front) runs on the JVM; on the agents' emulator
`ExpiryReminderAlarmsTest` (the alarm is pending, then cancelled; 9:00 local;
the posted notification's text and channel) pass. Still to check on a device:
the alarm firing at 9:00 through Doze and after a reboot, the system permission
dialog and a refusal, a tap opening the Pantry tab, and the iOS notification.

The cook-persistence tests (#10) are the cook-state migration in
`MigrationTest` (device) and the cook-state cases in `RecipeDaoTest` (JVM). A timer alarm was also checked end to end there: a
recipe seeded with a running timer, opened through the notification's
`OPEN_COOK` intent, came back in cook mode on the saved step with the timer
recomputed from its deadline. `AlarmManager` held the alarm at that deadline,
and with the app in the background the "Time's up" notification posted. It
came 44 s late, which is the expected inexact alarm on Android 14+ without
"Alarms & reminders", under battery saver. Still to check on a device: a
process kill mid-timer, a reboot, Doze, denying the notification permission,
and the iOS notification (background, lock screen, tap).

`MigrationTest` needs `app/schemas` packaged into the instrumentation APK:
`MigrationTestHelper` reads the exported JSON from the test APK's **assets**,
not from the project directory. That is what
`sourceSets.getByName("androidTest").assets.directories += "$projectDir/schemas"` in
`app/build.gradle.kts` is for. Without it every migration test fails with
`FileNotFoundException: Cannot find the schema file in the assets folder`,
which reads like a broken migration and is really a missing file — don't go
hunting in the migration when that appears.

**Compose UI tests**, on the JVM under Robolectric since #91 (`androidx.compose.ui:ui-test-junit4`, plus
`debugImplementation("androidx.compose.ui:ui-test-manifest")` for the empty
Activity `createComposeRule` launches): `HomeScreenTest`,
`SaveToListBottomSheetTest`, `ListDetailScreenTest`, `SettingsScreenTest` and
`RecipeErrorScreenTest` ("Report this site" on the no-recipe error only). They exist
because every ViewModel behind Home was already covered and the whole suite
stayed green through a duplicate-key crash that made the app unusable — that
bug lived entirely in the view.

No Hilt in these. Every screen takes its ViewModel as a parameter defaulting
to `hiltViewModel()`, so a test builds a real ViewModel over a fake repository
and passes it in; what runs is the real repository-to-ViewModel-to-pixels
wiring. The fakes live in `app/src/test/.../fake/`; the device tests that
remain (`ClipScreenTest`) reach them through
`sourceSets.getByName("androidTest").kotlin.directories += ".../test/java/.../fake"` —
only `fake/`, since the helpers beside it need kotlinx-coroutines-test and
Robolectric.

Three things that cost real time and will again:

- **Espresso 3.6.x is broken on API 36+.** It reflects into
  `android.hardware.input.InputManager#getInstance`, which no longer exists,
  so every Compose test dies in `Espresso.onIdle()` with
  `NoSuchMethodException` before any assertion runs. `espresso-core` is pinned
  to 3.7.0 in `androidTestImplementation` and `testImplementation` ahead of
  what compose-ui-test pulls in. The emulator in use is API 37.
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
credit has its own `RecipeSourceCreditTest`, and the import error screen,
including "Report this site", has `RecipeErrorScreenTest`), `CookModeTest`
(step states, tap to jump, "Done — next step", timers), `RecipesScreenTest`
(search, swipe-to-dismiss, the batched undo snackbar) and `ListsScreenTest`.
`RecipeScreenFixture` gives the recipe tests a `Clock` the test moves
forward, so a 20-minute timer finishes as soon as the test says so; the
ViewModel's 250 ms tick is real time, so wait with `compose.waitUntil`, not a
bare assert. Done steps are only drawn struck through, not exposed to
semantics, so `isStruckThrough()` reads the text's layout style. Colour isn't
either, so `assertInAppTheme()` (`ui/ThemeAssertions.kt`) reads a TextButton
label's colour the same way and fails on Material's baseline purple; each
dialog once composed outside its screen's theme (#142, #186) has a test that
calls it. Share itself
opens the system chooser, so the tests stop at `RecipeViewModel.shareText()`.

Still without Android UI tests: the Settings screen. The iOS UI tests
(`ios/RecipeClipperUITests`) cover Home, Recipes (+ → Type a recipe, search, swipe-to-delete, the
batched undo), Settings, list detail, the save-to-list sheet with the bookmark
it fills, the source credit, the import error screens (including "Report this
site" opening Safari) and cook mode (`CookModeUITests`, on the `cook` seed
scenario). XCUITest drives the app from outside and can't move its clock, so
the one timer that has to finish there is a real 3-second step. The share
sheet is left to the hosted `RecipeViewModelTests`, which pin the exact share
text. Sharing into the app has one more iOS suite, `ShareExtensionUITests`,
which runs only on request (see "iOS share extension: end to end and memory"
below).

`navigation-compose` has no BOM of its own and is built against a particular
Compose: bump it with the Compose BOM, or the app pulls in a mix of Compose
versions. `hiltViewModel()` comes from `hilt-lifecycle-viewmodel-compose`
(the copy in `hilt-navigation-compose` is deprecated).

The Compose UI tests still use the v1 `createComposeRule`, which Compose 1.12
deprecates (the one compiler warning left). The v2 rule runs on a
`StandardTestDispatcher` instead of an unconfined one, so moving to it can
change timing; do it with a device run to check.

`JsonLdRecipeParser` uses `org.json`, which is an Android framework class.
Plain JUnit tests will need `testImplementation("org.json:json:<version>")`
or the calls will fail as "not mocked".

## The free tier and the unlock (#107)

- **Turning it on:** Developer settings (7 taps on the version) → `freeTier`.
  "Unlocked" beside it counts the purchase as bought, with no store; Reset
  clears both.
- **The rules** are tested against real SQLite (`FreeTierDaoTest` /
  `FreeTierDaoTests`: one for one at 20, protections, a library over 20 keeps
  everything, all protected is not kept, unlocked is never culled), through
  the repository over an in-memory DAO (`DefaultRecipeRepositoryLimitTest`),
  in the backup merger, and in the ViewModels over a fake `Entitlements`.
- **iOS StoreKit:** `StoreKitEntitlementsTests` runs `StoreKitEntitlements`
  against `ios/RecipeClipper.storekit` with `SKTestSession` (no dialogs): the
  price, a purchase made by the session (`buyProduct`) found and cached, and
  locked again once it's gone. `buyProduct` returns before the app's own
  `Transaction.currentEntitlements` has the purchase (it lands up to ~300 ms
  later on a just-booted simulator, which made the test flaky), so the test
  waits for StoreKit to list it before calling `refresh()`. A
  `clearTransactions()` now and then doesn't take (the purchase stayed listed
  15 s, or for good, in a few runs in a hundred), so the test clears until
  StoreKit drops it; a second clear always has. `Product.purchase()` itself
  waits forever in a hosted unit test (no window scene for its sheet), so the
  sheet is checked by
  hand: running the app from Xcode uses the same file (the scheme's StoreKit
  configuration), so Unlock, Ask to Buy and Restore work in the simulator;
  Debug → StoreKit → Manage Transactions refunds or deletes the purchase.
- **Android Play Billing can't be exercised here:** it needs the app on a
  Play test track with the `unlimited_recipes` product and a license tester
  (#22). Until then Unlock answers "Couldn't reach the store", and the
  override is the way to test the unlocked library.

## "I made this" photos (#116)

- **Turning it on:** Developer settings → `cookedPhotos`.
- **Automated:**
  - Android, against real SQLite and real files: `CookedPhotoDaoTest` (order, edits, cull and
    free-tier protection, cascade with Undo, the sweep) and `CookedPhotoBackupTest` (the zip
    round trip past a full history, and a plain JSON export).
  - Android, JVM: `BackupArchiveTest` (the shared `backup-v1-photos.zip`, path checks),
    `CookedPhotosViewModelTest`, the Recently cooked sort in `RecipesViewModelTest`, and
    `RecipeCookedPhotosScreenTest` (Robolectric). `MigrationTest.migration13To14…` on the
    device.
  - iOS: `CookedPhotoTests` (real SQLite, ImageIO downscaling, the zip round trip),
    `BackupArchiveTests` and `CookedPhotosViewModelTests`, and `CookedPhotosUITests` (the
    section behind its flag; the camera, or the no-camera alert, opens and closes without
    adding a photo, since the simulator's virtual camera never captures). A photo from the
    library, one of the simulator's own samples picked in the real picker, opens full screen;
    with the software keyboard up for its note, the note and × are hittable and × closes it
    (#180). The software keyboard shows unless Simulator connects a hardware one (I/O menu).
  - "Mark as cooked" (#173): a cooking with no photo in `CookedPhotoDaoTest` / `CookedPhotoTests`
    (no file, the sort, the cull, the cascade, the sweep; the plain-JSON round trip in
    `CookedPhotoBackupTest` / `CookedPhotoTests`), `CookedPhotosViewModelTest(s)` (it opens,
    its note, the `madeThis` signal, a delete at once), the shared `backup-v1-cooked.json` in
    `BackupJsonTest(s)` (its own section, and the file an older app reads), `ShareFileTest(s)`,
    `RecipeCookedPhotosScreenTest` (the menu item, the entry, its labels and delete, the recipe
    delete counting photos only) and `PantryUseUpScreenTest` (the sheet once, then a photo not
    offered it again). `MigrationTest.migration14To15…` on the device. iOS's
    `CookedPhotosUITests` marks one, types its note and puts the keyboard away with Done.
- **By hand, on a phone:**
  - Take a photo in portrait and landscape; it should stay upright in the gallery and full
    screen. On iOS the new photo's viewer opens only once the camera has gone, with × below
    the status bar (#180; the simulator can't try the camera path).
  - Pick several photos from the library, including a HEIC on iOS.
  - On iOS, in the full-screen viewer: write a note, change the date, close and reopen (both
    kept), then Delete and Undo.
  - Share one: the photo arrives with the recipe name.
  - Export with photos (a `.zip`), then import it on the other platform.
  - On Android, the first camera use asks nothing (the app declares no `CAMERA`). On iOS it
    asks once, in the phone's language.
  - "Mark as cooked" (#173), on a phone upgraded from a build with photos (the migration): the
    old photos are all there; mark a recipe with some pantry lines as cooked, write a note,
    close: "Update the pantry" opens once; add a photo of it straight after: nothing more. Then
    export and import on the other platform: the marked cooking comes across with its note.

## iOS share extension: end to end and memory

The extension's logic is unit-tested (`RecipeClipperTests/Share`). What
needs the real share sheet is `ShareExtensionUITests`. It drives Safari to a
page, shares it to Recipe Clipper, checks the card, then checks that the app
shows the recipe. It is skipped unless `RC_SHARE_E2E_BASE` is set, so CI
never runs it. It writes to the app's real App Group database, so use a
simulator of your own:

```
# A certificate for localhost, trusted by the simulator
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 30 \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "extendedKeyUsage=serverAuth"
xcrun simctl keychain <device> add-root-cert cert.pem
# Serve small.html (a JSON-LD recipe titled "E2E Guacamole Small") over HTTPS
# on localhost:8443, answering 403 at /blocked (Python's http.server wrapped
# in an ssl context will do), then:
TEST_RUNNER_RC_SHARE_E2E_BASE=https://localhost:8443 xcodebuild ... test \
  -only-testing:RecipeClipperUITests/ShareExtensionUITests
```

Plain `http://` pages won't do: `UrlCleaner` upgrades them to `https`, and
real sites block the Mac's fetches often enough to make them useless as
fixtures.

**Memory.** Apple doesn't document a share extension's limit; it's commonly
about 120 MB on a device, and the simulator enforces none. Debug builds log
the extension's footprint and its peak at launch, after reading the shared
items, and after the import. On a device, open Console.app, pick the phone,
and filter on subsystem `com.liberopat.recipeclipper`, category `share`. On
a simulator: `xcrun simctl spawn <device> log show --last 10m --predicate
'subsystem == "com.liberopat.recipeclipper" AND category == "share"'`.

Measured on a simulator (debug build, no team; the simulator enforces no
ceiling, so treat these as a rough guide and re-check on a device before
release): peak footprint at "import finished" was 41.8 MB for a 250 KB page,
44.3 MB for a 2 MB page, 51.0 MB for the `/blocked` (403) page, and 67.8 MB
for a 10 MB page — all comfortably under the ~120 MB estimate, and run to
run this varies by several MB on the simulator.

## Walkthrough videos (#106)

Scripted walkthroughs of the new features, one short video per feature per platform, for
people to watch rather than to catch regressions (the feature suites do that). They seed
twenty realistic recipes, each in a list (iOS `UITestWalkthroughSeed`, Android
`WalkthroughSeed`), turn flags on as Developer settings would, and pause between steps.
Where a feature needs the on-device model, a stub answers: Chef mode's short steps and the
grocery merging's typed decisions ("AI answers simulated": close names "same", trailing
text a "note", or "junk" if it holds "dfsafs", and the name of a line ending in "dfsafs"
with no separator: the Banana Bread's "2 eggs dfsafs", for the junk-hiding clip). The
page-extraction recipe is stored as `EXTRACTED`, so no model runs for it.

- **iOS:** `WalkthroughUITests` (`ios/RecipeClipperUITests/WalkthroughUITests*.swift`),
  skipped unless `TEST_RUNNER_RC_WALKTHROUGH=1`, so CI and the nightly UI run skip them.
  `scripts/record-walkthroughs-ios.sh <sim-udid> [out-dir] [test …]` builds, records each
  test with `simctl io recordVideo --codec h264`, trims to the test's
  `WALKTHROUGH-START`/`END` marks and re-encodes with `avconvert` (1280 high). Use your own
  simulator; it is switched to light mode.
- **Android:** `MealPlanWalkthroughTest` and `RecipesWalkthroughTest` in
  `app/src/androidTest/.../walkthrough`, run only under `-Pwalkthrough`, which swaps in
  `WalkthroughRunner` (Hilt's test Application, so `@UninstallModules(OnDeviceModelModule)`
  can bind the stubs); under the plain runner they skip. Each test records itself with
  `screenrecord`, which writes a frame only when the screen changes, so a still ending would
  have no length: `WalkthroughBase.finish()` redraws the last screen before stopping it.
  `scripts/record-walkthroughs-android.sh <serial> [out-dir] [Class#test …]`
  installs both APKs, clears the app before each test (`pm clear`), pulls and re-encodes the
  video. It wipes the app's data: use the agents' emulator (emulator-5580, under the lock),
  never a device someone uses.
- Output defaults to `~/Downloads/RecipeClipper-walkthroughs/` (`ios-NN-name.mp4`,
  `android-NN-name.mp4`), never the repo, with an `index.md` of what each shows. On a miss, the
  Android script saves the screen at `/tmp/android-NN-name-miss.png`; the iOS log is under
  `$DERIVED_DATA/raw/`.

The clips, the same number on both platforms: 01 tabs and Week, 02 Groceries, 03 Pantry and
What I need, 04 weekly menus, 05 expiry reminders, 06 the Recipes screen, 07 amounts in steps,
08 Chef mode (stub model), 09 the free tier, 10 the page-extraction line, 11 grocery merging (AI
answers simulated), 12 junk hidden in Groceries (simulated); 13 "I made this", 14 the automatic
backup copy and "Restore from a backup file", 15 Send list and Paste a list, 16 Send as file and
a received file, 17 the first-run tour, 18 Done shopping and the On list tag, 19 the Pantry's
Send list, 20 using up the pantry after cooking, 21 Chef mode on an unsupported phone
(simulated), 22 junk hidden in a recipe's own lines (simulated), 23 "Mark as cooked". Android's
13–21 and 23 are in `CookingWalkthroughTest` and `SharingWalkthroughTest` (22 beside 12, in
`MealPlanWalkthroughTest`), iOS's in `WalkthroughUITests+Cooking.swift` and `+Sharing.swift` (22
in `+MealPlan.swift`). What they need from outside the app:

- **The photo** "I made this" adds is a macOS sample picture (`/Library/User Pictures/Fun/Gingerbread
  Man.heic`), which each script converts: Android pushes it to `/data/local/tmp` and the test hands
  it back as the Photo Picker's answer (an `ActivityMonitor`; the picker itself can't be driven);
  iOS adds it to the simulator's Photos (`simctl addmedia`, through BMP so it has no capture date
  and sorts first) and the test picks it in the real picker.
- **The received file** is `shared/fixtures/backup/share-v1.recipeclipper`, pushed to
  `/data/local/tmp` and opened with a VIEW intent through the app's FileProvider; iOS opens its
  canned file with `-uiTestReceiveFile`. A pasted list is put on the clipboard by the test
  (iOS: `-uiTestPasteboard`).
- **The kitchen** (a stocked pantry, the Adobo's lines on the grocery list) for 15, 18–20 and 23:
  `start(kitchen = true)` from `WalkthroughSeed.pantry`, iOS's `walkthroughPantry` scenario.
- **The share sheet and the system pickers** show, then close with Back (iOS: a tap outside the
  sheet, the picker's Cancel); Android waits for them to take the screen first
  (`waitForSystemScreen`), which is slow on a busy emulator. Android's backup folder can only be
  picked there, so its rows read "Not chosen yet" and "Not backed up yet"; a simulator has no
  iCloud Drive, so iOS's copy goes to a throwaway local folder (`-uiTestBackupFolder`) and
  "Back up now" shows "Last backed up".
- **Android records at 720×1616**, two-thirds size (the clips end up 1280 high anyway): at full
  size the emulator's encoder fell behind on a busy machine and lost the ends of clips.
- **Chef mode unsupported** is the stub model's answer: Android's classes bind
  `ChefSupport.Unsupported` (and a decision model that supports nothing, so no AI answer shows);
  iOS passes `-uiTestChefUnsupported`.

## CI

GitHub Actions, in `.github/workflows/`. On a pull request each platform's job
runs only when the PR touches its files (Android: `app/`, `shared/`, the Gradle
files, its workflow and `.github/scripts/`; iOS: `ios/`, `shared/`, its
workflow); otherwise its check reports as skipped. Pushes to `main` always run
both.

- **Android** (`android.yml`, check `Android unit tests and lint`), on every
  pull request and push to `main`, on `ubuntu-latest` with JetBrains Runtime
  25 (Android Studio's bundled JDK): `./gradlew testDebugUnitTest lintDebug
  compileDebugAndroidTestKotlin`. A lint error fails the build;
  `.github/scripts/check_lint.py` then fails on any finding, at any severity,
  that isn't one of the four version-advisory ids. It checks ids, not the
  count, because `NewerVersionAvailable` drifts as libraries release. Reports
  are uploaded as the `android-reports` artifact on failure. The screen and
  DAO tests run here, under Robolectric; the few device tests (see "Which
  tests run where") don't run in CI yet.
- **iOS** (`ios.yml`, check `iOS unit tests`), same triggers, on the
  `xcode-27` runner image (arm64, macOS 27, Xcode 27 only; in public preview
  as of September 2026). `DEVELOPER_DIR` selects Xcode 27 explicitly. It runs
  `RecipeClipperTests` on the image's iPhone 17 / iOS 27.0 simulator and
  uploads the `.xcresult` on failure.
- **iOS UI tests** (`ios-ui-tests.yml`), about 18 minutes: nightly at 03:00
  UTC and on demand (Actions → iOS UI tests → Run workflow).
- **Recipe site check** (`site-check.yml`, #32): Mondays at 06:00 UTC and on
  demand, and on a pull request that changes the check, its URL list or
  `shared/tables/site-rules.json`. It
  runs the real `BlogRecipeSource` (JSON-LD, then microdata) over
  the ~20 pages in `app/src/test/resources/site-check-urls.txt`, applying the
  repository's one retry, and writes a table to the job summary: per site,
  parsed or the `ParseError` cause, and for a success the ingredient and step
  counts and whether yield, total time and photo came through. Each run
  uploads `results.md` and `results.json` as the `site-check-<run>` artifact
  (kept 90 days): compare runs, since blocking flips run to run. A blocked
  site never fails the job; a broken harness does, and "no site parsed" raises
  a warning. A "Site rules" section lists each rule of a site with rules
  (#120) as Matched (on at least one of the site's pages) or **Stopped
  matching** (on none), and the latter raises a warning.
  Only outcomes are recorded, never the pages or recipe text.
  Locally: `./gradlew testDebugUnitTest -PsiteCheck` (results in
  `app/build/site-check/`). Without `-PsiteCheck`, `LiveSiteCheck` is excluded
  in `app/build.gradle.kts`, so the normal runs never touch the network.
  Replace a URL only when its page is gone in a browser too.

When a new Xcode major comes out, GitHub ships it as a new image label
(`xcode-28`), so the label, `DEVELOPER_DIR`, the simulator `OS=` and
`.github/actionlint.yaml` move together. Check workflow edits with
`actionlint`.

## Lint

`./gradlew lintDebug` reports no errors and two warnings, both version
advisories left deliberately. `OldTargetApi`: targetSdk is 36, what Google Play
requires, while API 37 exists; raising it is a behaviour change to read up on
and test on a device. `NewerVersionAvailable` for jsoup: it is held at 1.17.2
because newer releases need core library desugaring on Android, fetch through
`java.net.http.HttpClient` on the JVM (so unit tests stop exercising the
device's code path), and change `Element.text()`, which the iOS port mirrors.
The toolchain upgrade of September 2026 (#23) cleared the other 14. Do not
silence them with a baseline: the day one of them matters, it should still be
visible. CI (`check_lint.py`) allows only the version-advisory ids.

Compose 1.12's lint checks found three real issues in that upgrade, all fixed:
the date format now reads `LocalLocale` (`NonObservableLocale`), cook mode's
keep-screen-on uses `LocalActivity` (`ContextCastToActivity`), and
`HomeScreenTest` builds its ViewModel outside `setContent`
(`ViewModelConstructorInComposable`).

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
./gradlew testDebugUnitTest    # JVM tests, screen and DAO tests included (Robolectric)
./gradlew connectedDebugAndroidTest  # migrations, WebView, smoke; needs a running emulator
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
the restore half with `P=com.liberopat.recipeclipper`. If the old install's
database is an older version, Room migrates it on first open. Check the recipes are there, then
`adb uninstall com.example.recipeclipper`.

### Backup and restore to a new phone (#25)

What goes is an include list: `app/src/main/res/xml/data_extraction_rules.xml`
(API 31+, both `cloud-backup` and `device-transfer`) and `backup_rules.xml`
(API 23–30, Auto Backup). Both name `recipe_clipper.db`, `-wal`, `-shm` and
`unit_preferences.xml`, so recipes, lists, ticked ingredients and settings
travel, and nothing else does. Coil's image cache is in `cacheDir`, which is
never backed up; photos refill from the network. The user's own "I made this"
photos (`filesDir/cooked_photos`, #116) stay out too: their rows come back
without the files and say "Photo not on this phone" (docs/decisions.md).

Proven on an API 37 emulator (September 2026) with the local transport. The
app was seeded through its UI (a shared recipe, two ingredients ticked, the
recipe in Favorites and Breakfast, Metric / Celsius / Dark while cooking), with
canary files outside the include list, then backed up, uninstalled and
reinstalled. The database, all in the `-wal` at the time (the `.db` was 4 KB),
and the settings came back and showed in the app; the canaries didn't:

```
A="adb -s <serial>"; P=com.liberopat.recipeclipper
# Canaries that must NOT survive:
$A shell "run-as $P sh -c 'mkdir -p files cache && echo x > files/canary.txt && echo x > cache/canary && echo \"<map/>\" > shared_prefs/other_prefs.xml'"
$A shell bmgr enable true
$A shell bmgr transport com.android.localtransport/.LocalTransport
# A force-stopped app is ineligible: backupnow then reports "Backup is not
# allowed". Start it and leave it in the background (backupnow kills it).
$A shell am start -W -n $P/com.example.recipeclipper.MainActivity
$A shell input keyevent HOME
$A shell bmgr backupnow $P            # "... with result: Success"
$A uninstall $P
$A install app/build/outputs/apk/debug/app-debug.apk   # restores on install
$A shell run-as $P ls -lR databases shared_prefs files cache
# (or, with the app installed: bmgr list sets; bmgr restore <token> $P)
# Then pull the three database files and the prefs as in the round-trip above
# and query: recipes.checkedIngredients, recipe_list_cross_ref, the prefs XML.
# Put things back:
$A shell bmgr wipe com.android.localtransport/.LocalTransport $P
$A shell bmgr transport com.google.android.gms/.backup.BackupTransportService
$A shell bmgr enable false
```

Not exercised on the emulator: Google's cloud transport (it needs a signed-in
account and uploads on Google's schedule) and a real device-to-device transfer.
Both read the same rules; the local transport runs the same file selection.
Android 12+ reads `dataExtractionRules` because `targetSdk` is 31 or more;
API 23–30 devices read `fullBackupContent`, which wasn't run here.

iOS: the database is `Application Support/recipe_clipper.sqlite`
(`AppDatabase.defaultPath()`), settings are in `UserDefaults.standard`, and
both are in iCloud and Finder backups. `BackupLocationTests` pins the location
and that neither the directory nor the database and its WAL are flagged
`isExcludedFromBackup`. If the database moves to an App Group container (the
share extension, #19), that is backed up too; move the test with it. Photos are
in Caches (`ImageLoader`), which isn't backed up, as intended.

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

## Regex vs on-device LLM evaluation (#105)

`tools/eval/` is a dev-only harness (never built into the apps or run by CI) that compiles the
iOS `Data/Model` sources with a Swift command-line driver and scores the parsers against local
models (Ollama, llama-server). How to run it, the gold sets and the results:
`docs/eval/llm-vs-regex.md`.
