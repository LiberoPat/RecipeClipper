# CLAUDE.md

Recipe Clipper: share a recipe link from any app and get just the recipe
(ingredients, steps, times, servings), with none of the story, ads or SEO
filler.

- **Android** (`app/`): Kotlin, Jetpack Compose, single Activity. MVVM +
  repository, Hilt, Room, Compose Navigation. minSdk 24, targetSdk 36,
  compileSdk 37.
- **iOS** (`ios/`): SwiftUI, iOS 17+, no third-party dependencies, at parity
  with Android. iOS specifics (XcodeGen, the Android→iOS type map, the share
  extension, simulator rules, test commands) are in `ios/README.md`.

**Everything here applies to both platforms.** A rule changed on one side
changes on the other. The pure logic (`data/model`, iOS `Data/Model`, and the parsers) is pinned to
the Kotlin by `ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift`,
whose expectations come from running the Kotlin: regenerate them, never
hand-edit them. The JVM `DifferentialCorpusTest` fails while that file is stale
and writes the regenerated one to `app/build/differential-corpus/`; a new row
needs only its input (`Ing("1,5 kg flour"),`).

**The word and density tables live once, in `shared/tables/`** (JSON: densities,
unit, timer, temperature, yield, range, amount, duration, detection and
ingredient-name words, condensed section names; tracking parameters), loaded by both apps (Android as
Java resources through `SharedTables`, iOS as a bundled `tables/` folder). Edit a
table there, never in code; the logic that reads it stays written twice. **Each
language has its own folder** (`shared/tables/en/`), read through
`LanguageWords`: the recipe's language picks it, never the phone's, and
languages are never merged.

**Keep this file short: it is loaded into every session.** Add only what an
agent needs almost every time. Rationale and history go in
`docs/decisions.md`, test and device detail in `docs/testing.md`, plans and
to-dos in GitHub issues (`gh issue list`). When something here goes stale,
fix it in place rather than appending an update.

## Status

Built on both platforms: share → parse → show; automatic history (capped at
50, searchable, delete with undo); lists and the save-to-list sheet; serving
scaling; unit and oven-temperature conversion; Settings; cook mode with step
timers, with cook progress and servings saved and background timer alerts;
sharing a recipe out as text; failure handling and offline; the microdata
fallback; a personal note per recipe; editing a recipe and typing one in by
hand, with "Update from source" (#29); export and import of everything as one
JSON file (Settings); the UI in English, Spanish, French, German, Italian and
Brazilian Portuguese (drafts awaiting a native speaker:
`docs/translations.md`). iOS also honours Dynamic Type.

Not built, all tracked as issues: Reddit (#11), reading recipes in other
languages (#12, #14–#16), release setup (#18–#22).

## Commands

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug               # APK in app/build/outputs/apk/debug/
./gradlew installDebug
./gradlew testDebugUnitTest           # one class: --tests "com.example.recipeclipper.data.model.IngredientScalerTest"
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # device tests: wipes the app's data, see below
cd ios && xcodegen generate           # after adding or removing iOS files
```

- **Device tests uninstall the app and wipe its database and settings.** Back
  them up first and restore after. The procedure (copy the `-wal` too) is in
  `docs/testing.md`, with the adb recipes.
- **Lint reports two warnings, both deliberate version advisories:**
  `OldTargetApi` (targetSdk 36 while 37 exists; raise it only after reading
  its behaviour changes) and `NewerVersionAvailable` for jsoup (held at
  1.17.2; the reason is beside it in `app/build.gradle.kts`). Don't baseline
  them; any other finding is real.
- **CI checks every PR** (`docs/testing.md`): merge only when green.
- **An emulator or simulator may be in use by a person.** Check before
  scripted taps, force-stops or settings changes, and ask. **Never run two iOS
  test sessions on one simulator**: one kills the other's test host.
- The Android toolchain versions are coupled; bump them together: Gradle,
  AGP, Kotlin (the Compose compiler plugin's version sets it), KSP, Hilt,
  Room, and the Compose BOM with `navigation-compose`. AGP 9 compiles Kotlin
  itself: there's no `kotlin-android` plugin and no legacy AGP flags.

## Where things are (Android)

```
MainActivity   share intent or timer notification → queued route → navigated once the NavHost exists
di/            DatabaseModule, RepositoryModule, SourceModule, ClockModule, PlatformModule
data/          RecipeRepository, ListRepository (interfaces; Default* are the Room-backed ones),
               Connectivity, ErrorLog, Clock (seams for tests)
  local/       RecipeDatabase (+ migrations), entities, RecipeDao, ListDao, AppPreferences
  remote/      BlogRecipeSource (+ JsonLdRecipeParser), MicrodataRecipeParser, RenderedPageSource
  model/       Recipe, ParseError, UrlCleaner, Servings, IngredientScaler, UnitConverter,
               Units, IngredientDensities, TemperatureConverter, StepTimers, RecipeShareText,
               SiteReportLink, SourceDomain, SharedTables (loads shared/tables),
               LanguageWords (one language's tables, chosen per recipe)
               IngredientName (a line's ingredient name), IngredientRendering (scale+convert)
ui/            navigation, home, history, recipe, savetolist, lists, listdetail, settings,
               theme, common
timers/        AlarmManager scheduler, alarm and boot receivers, the "time's up" notification
```

Routes: `home`, `history`, `settings`, `lists`, `lists/{listId}`,
`recipe/{recipeId}?cook={cook}` (`cook=true` from a timer notification opens
cook mode), `recipe/import?url={url}` (the share target: parse, then
upsert with no list membership), and `edit?recipeId={recipeId}` (no id: a new
recipe; saving replaces the edit screen, and the recipe screen under it, with
`recipe/{id}`).

## Conventions

- One `StateFlow<XUiState>` per ViewModel (iOS: an `@Observable` class with
  one `private(set) var uiState`). Screens observe and forward events: no
  coroutines, repository calls or business logic in composables or views.
- Never hold state in `remember` if it must survive rotation.
- **Edge-to-edge** (targetSdk 36 enforces it): a screen's root surface fills
  behind the system bars and pads its content with `safeDrawingPadding()`
  (History: its Scaffold's `contentWindowInsets = WindowInsets.safeDrawing`).
  Never set bar colours; the theme only flips the bar icons.
- ViewModels and repositories never import Compose, SwiftUI or UIKit, and
  never touch `Context`. Platform effects (alarm sound, keep-screen-on, the
  share sheet, opening a URL) live in the view layer.
- Domain `Recipe` is separate from `RecipeEntity`; map at the repository.
- Parsers are pure: text in, data out, no network, no Android APIs.
- **Causes, not copy.** Sources and repositories return a `ParseError`; the
  screen picks the words. Every UI string lives in `res/values/strings.xml`
  plus `values-{es,fr,de,it,pt-rBR}` (iOS: `Localizable.xcstrings`, read
  through `Strings.swift`); a new string needs all six languages on both
  platforms. `RecipeShareText` takes its words as `Labels` from the screen;
  `SiteReportLink` is a report body, English by design.
- Tests use hand-written fakes (`app/src/test/.../fake/`,
  `ios/RecipeClipperTests/Fakes`), never mocks. Screens take their ViewModel
  as a parameter defaulting to `hiltViewModel()`, so UI tests pass a real
  ViewModel over a fake repository.
- `stateIn(WhileSubscribed(...))`'s `.value` is the initial state while
  nothing collects. State an action reads (e.g. `ListDetailViewModel.onDelete`)
  must come from a flow the ViewModel collects itself.
- The bookmark icons are two hand-written drawables. Don't add
  `material-icons-extended` for a glyph or two.

## Product rules

Decisions, not suggestions. Don't relitigate them in code.

- **Capture is frictionless.** Sharing a link parses and shows it. No save
  prompt.
- **History is automatic,** newest first, capped at the 50 most recently
  viewed.
- **Lists are deliberate:** adding to one is an explicit second act.
  Favorites is a list like Lunch, Dinner, Desserts, Breakfast and Snacks, not
  a separate tier.
- **Only Favorites is permanent.** The other five seeded lists delete like a
  user's own. `isBuiltIn` means only "seeded, sorts first"; the delete guard
  is `isFavorites`, in the SQL, and a device test fails if it regresses to
  `isBuiltIn`.
- **"Saved" means "in at least one list."** It's derived from the cross-ref
  table; there's no column. A recipe in any list is never culled.
- **Leaving a list is a demotion, not a deletion.** The recipe stays in
  history and becomes cullable. Deleting is a separate, explicit action with
  its own confirmation.
- **Never guess a recipe from prose, and never show a confident wrong
  number.** Anything the app doesn't understand stays as written.

## UI decisions

Settled; don't reintroduce what they removed. The history behind each is in
`docs/decisions.md`.

- **The reading view opens on the recipe:** photo, title, times, one
  servings-and-units row, ingredients. Under the title, quietly, the source's
  domain and "Open original" (reading view only, not cook mode). No segmented
  pickers, filled chips or radio lists above the ingredients. Times are plain
  labelled numbers, not chips.
- **Servings and units: one always-visible row, adjusted in place.**
  `Serves − 6 +` (per recipe) on the left, the unit dropdown (As written,
  Metric, Ounces: a global default for "every recipe", exclusive choices
  only) on the right. Don't
  bring back the old "Adjust" bottom sheet without asking.
- **Cook mode is a highlighted scroll, not a pager,** because steps overlap,
  cooks scroll back to re-check amounts, and source steps range from 12 clean
  lines to 3 paragraph blobs. Rows are done (struck, dimmed), current (ringed
  card, ~21px type, timer inside) or upcoming (readable, muted). It's a
  boolean on the recipe screen, not a destination; it holds
  `FLAG_KEEP_SCREEN_ON`; ingredients collapse to a tap-to-expand bar. Tapping
  a step makes it current; only "Done — next step" advances (leaning that
  way; confirm when cooking with it).
- **Theme:** Fraunces (display) over Karla (body); ground `#FBF9F6`, ink
  `#1C1917`, muted `#6B6259`, hairline `#E7E1D9`, paprika `#BF4A2B`. Dark mode
  is the system's call (no in-app toggle) and uses the on-ink tokens
  (`MutedOnInk`, `HairlineOnInk`, `PaprikaTextOnInk`). No Material purple.
  Cook mode follows the system theme; "Dark while cooking" (off by default)
  opts into dark. Don't restore an always-dark cook mode without asking.
- **iPad (iOS only, #20):** every screen's content sits in a centred ~680pt
  column (`readableColumn()`, `UI/Common/Components.swift`) so text never
  runs edge to edge on a wide screen; History, a `List`, sets the same width
  through row insets instead, since a `List` can't take a frame. iPhone
  portrait is unchanged.
- **Settings:** exclusive choices are radio rows, independent toggles are
  switches, never a bare ✓. Sections: Units (with "Also convert liquids" for
  Ounces only), Oven temperature (independent of units, default As
  written), Appearance ("Dark while cooking"). Reached from the gear beside
  the Home title. It could now open from elsewhere too (the recipe screen
  follows `AppPreferences.settings`), but adding an entry point is the
  owner's call.
- **Home:** link field, "Continue cooking" (the most recent), "Recently
  viewed" (the five before it), History and Lists rows (always shown), the
  Settings gear, and a small "+ New recipe" text action under the link field.
  Empty sections hide. **No "Saved" section**: it duplicated
  Recently viewed. Search is on History only.
- **Save-to-list sheet** (Spotify's add-to-playlist): checkboxes, not radios;
  each tick writes immediately, with no Save/Cancel; "+ New list" expands
  inline (no dialog on a sheet) and ticks the current recipe into the new
  list; built-ins first, then user lists by `sortOrder`. Opened from the
  bookmark icon (filled = in a list). Membership is edited only here, not on
  list detail. A list is renamed and deleted on its own screen.
- **Deleting a recipe** is a hard delete: a History swipe with an undo
  snackbar (a burst of swipes shares one snackbar and one all-or-nothing
  undo), or the recipe screen's overflow menu with a confirmation dialog (no
  undo).
- **Editing** (#29) is its own screen, from the recipe overflow menu (Edit,
  then "Update from source" for an edited or clipped recipe with a link,
  behind a warning, then Delete): name, yield, three times, ingredients and
  steps one per line, a photo link. Saving needs a name plus ingredients or
  steps (the parsers' rule); nothing typed is converted or guessed.
- **Sharing a recipe out** sends plain text (no Markdown), as shown on
  screen, scaled and converted, without the source link. The share icon sits
  beside Back in the reading view, not in cook mode.

## Data rules

- Room database `recipe_clipper.db`, **version 7** (iOS `user_version` 6):
  `recipes` (with nullable `notes`, `language`, `cookState`,
  `servingsTarget` and `editedAt`, and `contentOrigin`), `lists` and `recipe_list_cross_ref` (cascading). Recipes
  and lists carry a unique, never-changing `uid`: what an export file calls
  them. The schema is exported to `app/schemas/`: commit it. **Never use
  destructive migration**, and give every migration a `MigrationTest`.
  iOS mirrors the schema in SQLite, with `PRAGMA user_version` migrations.
- `recipes.sourceUrl` is unique, and always cleaned first by `UrlCleaner`. It
  strips only `utm_*`, known click ids (`fbclid`, `gclid`, …) and the
  `#fragment`, lowercases the scheme and host, upgrades `http` to `https`,
  and keeps every other parameter in order. Add a name only when you're sure
  it's tracking.
- **Re-sharing upserts:** same id, list membership, note and chosen servings,
  refreshed content, bumped `lastViewedAt`, ticked ingredients kept only if
  the ingredient list is unchanged, cook progress only if the steps are. In
  the same transaction, recipes in no list beyond the 50 most recently viewed
  are deleted. Opening from history counts as a view.
- **The user's version is never refreshed** (#29, #37).
  `contentOrigin` (`PARSED` | `EDITED` | `CLIPPED` | `MANUAL`, by name; an
  unknown name reads as `EDITED`) and `editedAt` (the last saved edit). Anything
  but `PARSED` is the user's: a re-share opens it without fetching and only
  counts as a view. "Update from source" is the one way back: it fetches,
  replaces the content, keeps the id, note and lists, and sets `PARSED` and
  no `editedAt`; a failure changes nothing. An edit makes `PARSED` into
  `EDITED`; the other values stay. A typed-in recipe is `MANUAL` with a
  synthetic `sourceUrl` of `manual:<uuid>`: never fetched or cleaned, and with
  no host there is no source credit, Open original or Report. Both fields go
  into the export file.
- `isFavorites` is a column, never a name match: names change on rename and
  translation. Built-in lists are seeded in `onCreate`, so adding one later
  needs a migration (as `MIGRATION_1_2` did).
- `addToList` is `@Insert(IGNORE)`, never REPLACE: re-adding must not
  rewrite `addedAt`, which orders list detail. `ListDao.create` with a recipe
  inserts both in one transaction.
- `observeLists(recipeId)` is one query with derived `recipeCount` and
  `containsRecipe`; pass `ListDao.NO_RECIPE` when there's no recipe.
  `observeHistory(query)` uses `instr(lower(…))`, **never `LIKE`** (a literal
  `%` must match), with the empty query as an `OR` branch of the same SQL. It
  matches ingredients as stored, not as converted.
- Settings live in the SharedPreferences file `unit_preferences` (never
  rename it, or users' choices are stranded) and in `UserDefaults` on iOS,
  under the same keys: `unit_system`, `convert_liquids`, `temperature_unit`,
  `dark_while_cooking`, each enum stored by name. `AppPreferences.settings`
  (a Flow over the change listener; iOS a publisher over
  `UserDefaults.didChangeNotification`) emits them; ViewModels that show a
  preference collect it rather than reading once.
- **Backup is an include list** (`res/xml/data_extraction_rules.xml` and
  `backup_rules.xml`): the database with its `-wal`/`-shm`, and
  `unit_preferences.xml`. Anything else, a new file or a renamed one, is not
  backed up until it's added to both. That excludes the export/import temp
  file below, which lives in `cacheDir`, never backed up anyway. iOS keeps the
  database in Application Support, which backups include. Proof and the adb
  recipe: `docs/testing.md`.
- **Export/import** (#26) is one versioned JSON file
  (`shared/fixtures/backup/backup-v1.json`; unknown keys ignored). Import
  merges, never replaces or deletes: recipes by cleaned `sourceUrl`,
  Favorites by `isFavorites`, other lists by uid then trimmed
  case-insensitive name; unlisted recipes only fill free history slots.
  Rules in `BackupMerger`, rationale in `docs/decisions.md`.
- Ticked ingredients are written as they change; the note once typing pauses
  (500 ms), or on leaving the screen. History search ignores notes. Cook
  progress (`cookState`, JSON: step, done steps, timers) and the chosen
  servings are written on every action, in order, through one queue; a
  running timer is saved by its deadline, so ticks never write.

## Failure handling

- The source returns a cause:
  - `Blocked(httpStatus)` for 403, 404, 429 and 5xx: usually a bot block
    that lifts, and some sites send 404 as a disguise.
  - `Offline`: on Android, an `IOException` while `Connectivity.isOnline()`
    is false; on iOS, the no-connection `URLError` codes.
  - `FetchFailed(detail, timedOut)` for anything else.
  - `NoRecipeFound`.
- The repository retries **once**, after an injectable 2 s pause, and only
  for `Blocked` or a `FetchFailed` that wasn't a timeout. Never for `Offline`
  (it fails at once), a timeout (a dead Wi-Fi costs one 15 s timeout, not
  two) or `NoRecipeFound`. A cancelled import writes nothing, even during the
  pause.
- **Then, only if still `Blocked` or `NoRecipeFound`, one rendered fetch:**
  the page loaded off screen (`RenderedPageSource`: Android `WebView`, iOS
  `WKWebView`, JavaScript on, a short settle, capped at 20 s, cancelled with
  the import), its HTML through the same parsers. Never after `Offline` or a
  timeout; nothing is shown. No recipe there keeps the original cause.
- After any failure, a link saved before opens from the saved copy. Photos
  are cached (Coil; iOS `ImageLoader`), so they show offline too.
- **Every error screen offers Try again, `NoRecipeFound` included**: a
  captive portal's login page parses as a page with no recipe. While
  `Offline` or `FetchFailed` shows, the screen reloads once on a real
  offline→online transition.
- **`NoRecipeFound` from a shared link also offers "Report this site"**
  (never `Blocked`, `Offline` or `FetchFailed`): a prefilled GitHub issue
  (`SiteReportLink`, label `site-report`) opened in the browser. Nothing is sent
  unless the user submits it.
- Database errors degrade instead of crashing. The Android repositories run
  every DAO call through `ErrorLog.guard`, which returns a safe fallback
  (`SaveFailed`, null, a no-op, or `CREATE_FAILED` = -1), and every Flow
  through `orEmptyOnError`. **`CancellationException` is always rethrown**,
  everywhere.
- **Fetches are blocked on and off:** the same site 403s, then answers
  minutes later. A failure often means "try again", not "unsupported". Don't
  chase a user-agent that "works"; there isn't one.

## Parsing rules

- **JSON-LD first:** a `schema.org/Recipe`, found through `@graph`, arrays
  and nesting.
- **Microdata only when JSON-LD finds no recipe** (`MicrodataRecipeParser`):
  - Properties belong to their nearest `itemscope`.
  - Values follow the microdata spec: `content`, an absolute `href`/`src`,
    `<time datetime>`, else the text.
  - Steps split at block boundaries, not one per `<p>`.
  - Jetpack's `.jetpack-recipe-directions` supplies the steps when there's no
    `recipeInstructions`.
  - The photo falls back to `og:image`.
- **A recipe needs a name, plus ingredients or steps.**
- **Pages behind a login, or rendered by JavaScript,** expose no recipe data
  to the direct fetch. Only the rendered fetch can see the latter.
- Every extracted string except `sourceUrl` goes through `stripHtml` (Jsoup's
  `text()`; iOS has a Jsoup-compatible port). A plain-string instructions
  block is split on `\n` **before** stripping, so `<br>`-separated steps stay
  separate.
- **Depth guards.** `findRecipeNode` stops past 50 levels. The
  `JSONTokener(...).nextValue()` parse is wrapped in `catch (e: Throwable)`,
  because deep nesting overflows the stack there. That's the only `Throwable`
  catch: `BlogRecipeSource.fetch` catches `Exception`, so cancellation
  propagates.
- **The recipe's language** (`Recipe.language`, stored): JSON-LD `inLanguage`,
  else `<html lang>`, else English; but words (name and ingredients) that
  clearly say another language win, and fill in when nothing is declared. A language with no tables stays entirely as written: no scaling,
  conversion, temperature rewrite, timer, stepper or phrase times.
- **Times:**
  - An ISO duration totalling zero ("PT0S") is absent.
  - A whole-string phrase in the recipe's words ("1 hour 30 minutes") renders
    like ISO ("1h 30m").
  - Anything else ("Overnight", "20 to 25 minutes") stays as written.
- **Condensed duplicates are skipped:** a `HowToSection` named as a condensed
  copy of the recipe ("Abbreviated Recipe", "Summary", "TL;DR", …; an exact
  set in the parser). Only when there are two or more sections and another
  still has steps.
- **Only ingredient lines are scaled,** never numbers inside instructions. An
  unparseable line stays as written, and a yield with no number shows no
  stepper.

## Unit conversion rules

Each one exists to avoid showing a confident wrong number.

- `UnitSystem` is As written (the default), Metric or Ounces. Grams was
  dropped (#17): a stored `GRAMS` reads as Metric on both platforms, never As
  written. Oven temperatures follow the separate `TemperatureUnit` (As
  written, Celsius, Fahrenheit). Each ingredient is scaled first, then
  converted.
- Weight to weight (oz, lb, g, kg) is exact. Volume to weight needs a density
  from `IngredientDensities`; an ingredient not in it stays as written.
- **The density table matches the end of the ingredient name:** "unsalted
  butter" matches, "butter beans" doesn't. Compound names that would wrongly
  match a shorter alias ("apple butter", "rice flour", "condensed milk") are
  `skip` entries, which win by being longer. Add one whenever a new alias
  could swallow another ingredient.
- **Only ingredients that weigh about the same every time belong in the
  table.** Salt, chopped produce, shredded cheese, nuts, rolled oats and rice
  are out on purpose. Dry goods follow King Arthur's weight chart (it was
  read through a summarising fetch, so spot-check values); liquids and fats
  use physical densities.
- **A line that already carries the target unit uses the site's figure**
  ("1 cup (120 g) flour", "1 cup/120 grams flour", "250 - 300 g / 8 - 10 oz
  pasta"), and `IngredientScaler`
  scales those figures too. Package sizes ("1 can (14 oz)") are never scaled.
- **A compound amount converts as a whole or not at all**
  ("1½ cups plus 1 Tbsp. (200 g) flour"). A site figure after the second part
  stands for the whole; otherwise both parts are converted and summed;
  otherwise the line stays as written.
- **A unit's trailing period ("tsp.", "oz.") belongs to the unit.** The
  `UnitPatterns` alternation is wrapped so `\.?` applies to every
  alternative.
- **Liquids.** Ounces leaves pourable liquids as written unless "Also
  convert liquids" is on. Metric ignores that flag: liquids, spoons and
  cups become ml (a cup is 240 ml, a tbsp 15 ml, a tsp 5 ml), and known
  solids become g.
  - In Metric, a spooned or cupped non-liquid with a site weight keeps that
    weight ("1 tsp (4 g) salt" becomes "4 g salt").
  - Known liquids stay in ml, even beside a gram figure.
- **A bare "oz" is a weight,** unless the ingredient is a known liquid (then
  it's fl oz).
- **Decimal commas** (in scaling, conversion and step timers): a comma
  between digits followed by 1–2 digits is a decimal ("1,5 kg"), and the
  line's output keeps the comma, as decimals rather than fractions
  ("1,5 kg" ×1.5 is "2,25 kg"). Followed by 3 digits ("1,500 g") it may be a
  thousands separator, so the whole line stays as written.
- **Temperatures:**
  - They need 2–3 digits, then F or C.
  - Without a degree sign, "degrees" or a full word, the number must also be
    a plausible cooking temperature: "2 C flour" is cups.
  - Ovens round to the numbers recipes use (350°F ↔ 180°C, 200°C ↔ 400°F);
    food-safety temperatures keep degree precision.
  - "350°F (180°C)" collapses to the half that matches the target.
  - Nothing else in the instructions is rewritten.

## Gotchas that have cost real time

- **Never call `removeLast()` on a Kotlin list.** It resolves to a JDK 21
  method that crashes on older Android.
- **LazyColumn keys must be unique across the whole list,** not per section.
  Home prefixes each key with its section; a duplicate key once crashed the
  app.
- **Espresso 3.6.x is broken on API 36+.** Every Compose test dies in
  `Espresso.onIdle()`, so `espresso-core` is pinned to 3.7.0.
- **Unquoted string resources collapse runs of spaces:** `+  New list` renders
  as `+ New list`, so tests must match one space. When a Compose test can't
  find a node, dump the semantics tree before touching production code.
- **`MigrationTest` reads the schemas from the test APK's assets**
  (`androidTest` `assets.directories += "$projectDir/schemas"`). A
  `FileNotFoundException` there means a missing file, not a broken migration.
- **`org.json` is an Android framework class,** so JVM tests need
  `org.json:json` as a test dependency.
- **Timer alerts:** Android uses `setAlarmClock()` only when exact alarms are
  allowed (API 31+ needs `SCHEDULE_EXACT_ALARM`, which Android 14 denies by
  default), else `setAndAllowWhileIdle()`, which can be minutes late. No
  Settings prompt, by decision. The receiver re-checks the database, so a
  reset or deleted timer never rings. Details in `docs/decisions.md`.
- **The iOS share extension opens the app through an unsupported
  workaround** (#19).

## Deliberately deferred

- **On-device OCR** (ML Kit) as a fourth Reddit step. It adds a dependency,
  and OCR is weakest on handwriting, the case that motivates it. Revisit once
  Reddit (#11) shows how often the comment fallback hits.
