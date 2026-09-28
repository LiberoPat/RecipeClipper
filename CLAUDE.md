# CLAUDE.md

Recipe Clipper: share a recipe link from any app and get just the recipe
(ingredients, steps, times, servings), with none of the story, ads or SEO
filler.

- **Android** (`app/`): Kotlin, Jetpack Compose, single Activity. MVVM +
  repository, Hilt, Room, Compose Navigation. minSdk 26 (for ML Kit GenAI,
  #100), targetSdk 36, compileSdk 37.
- **iOS** (`ios/`): SwiftUI, iOS 17+, no third-party dependencies, at parity
  with Android. iOS specifics (XcodeGen, the Android→iOS type map, the share
  extension, simulator rules, test commands, iPad) are in `ios/README.md`.

**Everything here applies to both platforms.** A rule changed on one side
changes on the other. The pure logic (`data/model`, iOS `Data/Model`, and the
parsers) is pinned to the Kotlin by
`ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift`, whose
expectations come from running the Kotlin: regenerate them (the JVM
`DifferentialCorpusTest` writes the file to `app/build/differential-corpus/`;
a new row needs only its input), never hand-edit them.

**The word and density tables live once, in `shared/tables/`** (JSON, read
by both apps through `SharedTables`). Edit a table there, never in code; the
logic that reads it stays written twice. **Each language has its own
folder**, read through `LanguageWords`: the recipe's language picks it, never
the phone's, and languages are never merged.

**Keep this file short: it is loaded into every session.** Add only what an
agent needs almost every time. Rationale, history and each feature's full
behaviour go in `docs/decisions.md` (read the section for the area you're
changing), test and device detail in `docs/testing.md`, plans and to-dos in
GitHub issues (`gh issue list`). When something here goes stale, fix it in
place rather than appending an update.

## Status

Built on both platforms: share → parse → show; the Recipes library (#102);
the free tier and its unlock (#107); lists; scaling and unit conversion;
Settings; cook mode with saved progress and background timer alerts; sharing
out as text or a file (#149); offline handling; notes; editing and typing in
recipes (#29); "Clip it yourself" (#37); export/import and an automatic
backup copy (#150); Week, Groceries and Pantry (#49–#52, #146, #147); Chef
mode (#100), recipes picked from page text (#103) and typed decisions (#104)
by the on-device model; "I made this" photos and "Mark as cooked" (#116, #173); the first-run tour
(#151, #190: a sample recipe and tooltips); Reddit posts, from the body or a comment
(`reddit` flag, #11), or the photo read on the device and checked by the cook (`photoText`,
#198); the UI in six languages (drafts awaiting a native speaker:
`docs/translations.md`). iOS also honours Dynamic Type.

Not built, all tracked as issues: other recipe languages,
release setup (#18, #20–#22).

## Commands

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug               # APK in app/build/outputs/apk/debug/
./gradlew installDebug
./gradlew testDebugUnitTest           # one class: --tests "com.example.recipeclipper.data.model.IngredientScalerTest"
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # the few device-only tests (docs/testing.md): wipes the app's data
cd ios && xcodegen generate           # after adding or removing iOS files
```

- **Device tests uninstall the app and wipe its database and settings.** Back
  them up first and restore after. The procedure (copy the `-wal` too) is in
  `docs/testing.md`, with the adb recipes.
- **Lint reports two warnings, both deliberate version advisories:**
  `OldTargetApi` and `NewerVersionAvailable` for jsoup (`docs/testing.md`).
  Don't baseline them; any other finding is real.
- **CI checks every PR** (`docs/testing.md`): merge only when green.
- **An emulator or simulator may be in use by a person.** Check before
  scripted taps, force-stops or settings changes, and ask. **Never run two iOS
  test sessions on one simulator**: one kills the other's test host.
- Bump the coupled Android toolchain together: Gradle, AGP, Kotlin, KSP,
  Hilt, Room, the Compose BOM with `navigation-compose`. AGP 9 compiles
  Kotlin itself (no `kotlin-android` plugin).

## Where things are

Android code is in `app/src/main/java/com/example/recipeclipper/` (`data/`
with `local/`, `remote/` and the pure `model/`; `ui/` a package per screen;
`di/`, `timers/`, `reminders/`); `shared/` holds the tables, flags and
fixtures. The annotated map, and what each route does, are in
`docs/decisions.md` ("Code map and routes").

Routes: `home`, `recipes`, `settings` (+ hidden `settings/developer`),
`lists`, `lists/{listId}`, `recipe/{recipeId}?cook={cook}`,
`recipe/import?url={url}` (the share target: parse, then upsert with no list
membership), `edit?recipeId={recipeId}`, `edit/photo?url=…` (#198), `clip?url={url}`; with the tab
shell (#47) they sit under Recipes, beside `week/…`, `groceries` and
`pantry`. A route from an intent always lands in Recipes.

## Conventions

- One `StateFlow<XUiState>` per ViewModel (iOS: an `@Observable` class with
  one `private(set) var uiState`). Screens observe and forward events: no
  coroutines, repository calls or business logic in composables or views.
- Never hold state in `remember` if it must survive rotation.
- **Edge-to-edge:** a screen's root fills behind the system bars and pads its
  content with `safeDrawingPadding()`. Never set bar colours.
- ViewModels and repositories never import Compose, SwiftUI or UIKit, and
  never touch `Context`. Platform effects (alarm sound, keep-screen-on, the
  share sheet, opening a URL) live in the view layer.
- Domain `Recipe` is separate from `RecipeEntity`; map at the repository.
- Parsers are pure: text in, data out, no network, no Android APIs.
- **Causes, not copy.** Sources and repositories return a `ParseError`; the
  screen picks the words. Every UI string lives in `res/values/strings.xml`
  plus `values-{es,fr,de,it,pt-rBR}` (iOS: `Localizable.xcstrings`, through
  `Strings.swift`): a new string needs all six languages on both platforms.
  `RecipeShareText` takes its words from the screen; `SiteReportLink` is
  English by design.
- **Features sit behind feature flags** (#87), **on by default in debug and
  release**; one goes off only for a known bug (so `freeTier` and
  `aiCountBrackets` are off). One entry in `shared/flags.json` plus the
  `Flag` enum on each platform; read `FeatureFlags.isOn(...)`, never a build
  constant. Overrides: hidden Developer settings (7 taps on the version).
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

- **Capture is frictionless.** Sharing a link parses and shows it (iOS: the
  share extension saves it). No save prompt.
- **History is automatic,** newest first, in the Recipes library. With
  `freeTier` off, capped at the 50 most recently viewed unprotected recipes.
- **The free tier keeps 20 recipes** (#107). Every recipe counts (not the
  tour's sample). Sharing always opens the recipe; adding one at 20 or more
  first removes the oldest-viewed unprotected one, so the limit never
  shrinks a library. Unlocked, nothing is ever removed automatically.
- **Lists are deliberate:** adding to one is an explicit second act.
  Favorites is a list like Lunch, Dinner, Desserts, Breakfast and Snacks, not
  a separate tier.
- **Only Favorites is permanent.** The other five seeded lists delete like a
  user's own. `isBuiltIn` means only "seeded, sorts first"; the delete guard
  is `isFavorites`, in the SQL, and `ListDaoTest` fails if it regresses to
  `isBuiltIn`.
- **"Saved" means "in at least one list,"** derived from the cross-ref
  table; there's no column. **Never culled, and not counted toward the 50:**
  a recipe in a list, planned for today or later, in a saved menu, typed in
  by hand, or with the user's own photos.
- **Leaving a list is a demotion, not a deletion.** The recipe stays in
  history and becomes cullable. Deleting is a separate, explicit action with
  its own confirmation.
- **Never invent a recipe, and never show a confident wrong number.** The
  on-device model only picks text that's on the page (#103) and never writes
  a number the cook sees (#100, #104). Anything the app doesn't understand
  stays as written.

## UI decisions

Settled; don't reintroduce what they removed. The reasons, and each
feature's full layout, are in `docs/decisions.md` under its issue.

- **The reading view opens on the recipe:** photo, title (the source's domain
  and "Open original" quietly under it), times as plain labelled numbers (not
  chips), one servings-and-units row, ingredients. No segmented pickers,
  filled chips or radio lists above the ingredients.
- **Servings and units: one always-visible row, adjusted in place.**
  `Serves − 6 +` (per recipe) on the left, the unit dropdown (a global
  default for "every recipe", exclusive choices only) on the right. Don't
  bring back the old "Adjust" bottom sheet without asking.
- **Cook mode is a highlighted scroll, not a pager.** Rows are done (struck,
  dimmed), current (ringed card, ~21px type, timer inside) or upcoming
  (readable, muted). A boolean on the recipe screen, not a destination; it
  holds `FLAG_KEEP_SCREEN_ON`; ingredients collapse to a tap-to-expand bar.
  Tapping a step makes it current; only "Done — next step" advances.
- **Theme:** Fraunces (display) over Karla (body); ground `#FBF9F6`, ink
  `#1C1917`, muted `#6B6259`, hairline `#E7E1D9`, paprika `#BF4A2B`. Dark mode
  is the system's call (no in-app toggle) and uses the on-ink tokens
  (`MutedOnInk`, `HairlineOnInk`, `PaprikaTextOnInk`). No Material purple.
  Cook mode follows the system theme unless "Dark while cooking" (off by
  default) is on. Don't restore an always-dark cook mode without asking.
- **Settings:** exclusive choices are radio rows, independent toggles are
  switches, never a bare ✓. Reached from the gear beside the Home title;
  another entry point is the owner's call.
- **Home:** link field, "Continue cooking" (the most recent), "Recently
  viewed" (the five before it), Recipes and Lists rows (always shown), the
  Settings gear. Empty sections hide. **No "Saved" section**: it duplicated
  Recently viewed. Search is on Recipes only.
- **Bottom tabs** (#47): Recipes · Week · Groceries · Pantry; Settings is not
  a tab. Each keeps its own back stack; the bar hides on the reading view and
  in cook mode.
- **A grocery tick only ticks** (#146): it restocks the pantry only through
  "Done shopping". The pantry is used up (#147) only through "Update the
  pantry" after cooking ends, never on a tick. Snackbars are only for undo.
- **Save-to-list sheet:** checkboxes, each tick writes at once (no
  Save/Cancel), "+ New list" expands inline and ticks the recipe into it.
  Opened from the bookmark icon (filled = in a list); membership is edited
  only here. A list is renamed and deleted on its own screen.
- **Deleting a recipe** is a hard delete: a Recipes swipe with an undo
  snackbar (a burst of swipes shares one all-or-nothing undo), or the recipe
  screen's overflow menu with a confirmation dialog (no undo). The user's
  photos go with it.
- **Sharing a recipe out** sends plain text (no Markdown), as shown on
  screen, scaled and converted, without the source link. The share icon sits
  beside Back in the reading view, not in cook mode.

## Data rules

- Room database `recipe_clipper.db`, **version 16** (iOS `user_version` 15),
  schema exported to `app/schemas/`: commit it. **Never use destructive
  migration**, and give every migration a `MigrationTest`. iOS mirrors the
  schema in SQLite, with `PRAGMA user_version` migrations, in the App Group
  container the share extension also writes to. User data rows carry a
  unique, never-changing `uid`, which an export file uses.
- `recipes.sourceUrl` is unique, and always cleaned first by `UrlCleaner`. It
  strips only `utm_*`, known click ids (`fbclid`, `gclid`, …) and the
  `#fragment`, lowercases the scheme and host, upgrades `http` to `https`,
  and keeps every other parameter in order. Add a name only when you're sure
  it's tracking.
- **Re-sharing upserts:** same id, list membership, note and chosen servings,
  refreshed content, bumped `lastViewedAt`, ticked ingredients kept only if
  the ingredient list is unchanged, cook progress only if the steps are; the
  `LibraryLimit` (#107) applies in the same transaction. Opening from
  history counts as a view.
- **The user's version is never refreshed** (#29): a `contentOrigin` other
  than `PARSED` or `EXTRACTED` (an unknown name reads as `EDITED`) opens on a
  re-share without fetching; "Update from source" is the one way back. A
  typed-in recipe's `sourceUrl` is `manual:<uuid>`, never fetched or
  cleaned.
- `isFavorites` is a column, never a name match: names change on rename and
  translation; seeded meal types go by `builtInKey` the same way. Built-in
  lists are seeded in `onCreate`, so adding one later needs a migration.
- **The cull's plan subquery must filter NULL recipe ids** (a planned note
  has none, and `NOT IN` a set holding a NULL matches nothing).
- `addToList` is `@Insert(IGNORE)`, never REPLACE: re-adding must not
  rewrite `addedAt`, which orders list detail.
- `observeHistory(query)` uses `instr(lower(…))`, **never `LIKE`** (a literal
  `%` must match), with the empty query as an `OR` branch of the same SQL. It
  matches ingredients as stored, not as converted.
- **Grocery lines add up only when exact** (`GroceryCombiner`); otherwise
  they sit together, each as written. **Pantry Have/Buy is presence only**
  (`PantryMatch`), never "enough", and "rice flour" is never "flour".
- Settings live in the SharedPreferences file `unit_preferences` (never
  rename it, or users' choices are stranded) and in `UserDefaults` on iOS,
  under the same keys (listed in `docs/decisions.md`), each enum stored by
  name, an unknown one read as the default. ViewModels collect
  `AppPreferences.settings` rather than reading once.
- **Backup is an include list** (`res/xml/data_extraction_rules.xml` and
  `backup_rules.xml`): the database with its `-wal`/`-shm`, and
  `unit_preferences.xml`. A new or renamed file isn't backed up until it's
  added to both (`docs/testing.md`).
- **Import merges, never replaces or deletes** (`BackupMerger`), for a
  backup and a file someone sent alike. The automatic backup copy only ever
  deletes files it wrote.
- Ticked ingredients are written as they change; the note once typing pauses
  (500 ms) or on leaving. Cook progress and the chosen servings are written
  on every action, in order, through one queue; a running timer is saved by
  its deadline, so ticks never write.

## Failure handling

- The source returns a cause:
  - `Blocked(httpStatus)` for 403, 404, 429 and 5xx: usually a bot block
    that lifts, and some sites send 404 as a disguise.
  - `Offline`: on Android, an `IOException` while `Connectivity.isOnline()`
    is false; on iOS, the no-connection `URLError` codes.
  - `FetchFailed(detail, timedOut)` for anything else.
  - `NoRecipeFound`.
  - `NoTranscription(title, imageUrl, imageUrls)`: a Reddit post with no
    recipe as text. An outcome, not a failure: the screen shows the post's
    photo and a muted note, with Try again and, for a post with a picture,
    "Read the photo" (#198: OCR into the editor, never saved unchecked).
    Never retried, never reloaded on reconnect.
- The repository retries **once**, after an injectable 2 s pause, and only
  for `Blocked` or a `FetchFailed` that wasn't a timeout. Never for `Offline`
  (it fails at once), a timeout (a dead Wi-Fi costs one 15 s timeout, not
  two) or `NoRecipeFound`. A cancelled import writes nothing, even during the
  pause.
- **Then, only if still `Blocked` or `NoRecipeFound`, one rendered fetch**
  (`RenderedPageSource`, an off-screen web view, capped at 20 s) through the
  same parsers: the only way to see a page rendered by JavaScript (a page
  behind a login stays out of reach). Never for a Reddit post (read only from
  its `.json`). No recipe there keeps the original cause.
- After any failure, a link saved before opens from the saved copy. Photos
  are cached (Coil; iOS `ImageLoader`), so they show offline too.
- **Every error screen offers Try again, `NoRecipeFound` included**: a
  captive portal's login page parses as a page with no recipe. While
  `Offline` or `FetchFailed` shows, the screen reloads once on a real
  offline→online transition. `NoRecipeFound` from a shared link also offers
  "Clip it yourself" (#37) and "Report this site".
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
  and nesting. **Microdata only when JSON-LD finds no recipe**
  (`MicrodataRecipeParser`).
- **Recipe cards only refine JSON-LD's lines,** and only when the card lines
  up one-to-one with `recipeIngredient` (#118–#120). A site quirk is an edit
  to `shared/tables/site-rules.json`, never code.
- **A recipe needs a name, plus ingredients or steps.**
- **Last, the on-device model may pick from the page's text** (#103), only
  after `NoRecipeFound` on a page that loaded; `PageRecipeCheck` keeps only
  what is on the page.
- Every extracted string except `sourceUrl` goes through `stripHtml` (Jsoup's
  `text()`; iOS has a Jsoup-compatible port). A plain-string instructions
  block is split on `\n` **before** stripping, so `<br>`-separated steps stay
  separate.
- **Depth guards.** `findRecipeNode` stops past 50 levels, and the
  `JSONTokener(...).nextValue()` parse is wrapped in `catch (e: Throwable)`
  (deep nesting overflows the stack). That's the only `Throwable` catch:
  `BlogRecipeSource.fetch` catches `Exception`, so cancellation propagates.
- **The recipe's language** (stored): JSON-LD `inLanguage`, else
  `<html lang>`, else English, but words that clearly say another language
  win. A language with no tables stays entirely as written.
- **Times:** an ISO duration totalling zero ("PT0S") is absent; a
  whole-string phrase ("1 hour 30 minutes") renders like ISO ("1h 30m");
  anything else ("Overnight") stays as written.
- **Reddit** (#11, `reddit` flag, routed by host): one `.json` fetch (a `/s/`
  share link is followed first). The post body if it splits, else the
  poster's comment, else the best other comment that splits, else
  `NoTranscription`. `RecipeTextSplitter` needs an ingredients block (a
  header, or quantity lines just above the steps) and steps (a header, or a
  numbered list from 1): never prose. `sourceType` is `REDDIT`.
- **Condensed duplicate sections are skipped** ("Abbreviated Recipe",
  "TL;DR", …; an exact set), only when another section still has steps.
- **Only ingredient lines are scaled,** never numbers inside instructions. An
  unparseable line stays as written, and a yield with no number shows no
  stepper.

## Unit conversion rules

Each one exists to avoid showing a confident wrong number. Other languages
(#15), Japanese (#16) and second amounts on a line (#61–#63) are in
`docs/decisions.md`.

- `UnitSystem` is As written (the default), Metric or Ounces (a stored
  `GRAMS` reads as Metric). Oven temperatures follow the separate
  `TemperatureUnit`. Each ingredient is scaled first, then converted.
- Weight to weight (oz, lb, g, kg) is exact. Volume to weight needs a density
  from `IngredientDensities`; an ingredient not in it stays as written.
- **The density table matches the end of the ingredient name:** "unsalted
  butter" matches, "butter beans" doesn't. A compound that would wrongly
  match a shorter alias ("rice flour", "condensed milk") is a `skip` entry,
  which wins by being longer. Add one whenever a new alias could swallow
  another ingredient.
- **Only ingredients that weigh about the same every time belong in the
  table:** salt, chopped produce, shredded cheese, nuts, rolled oats and rice
  are out on purpose. Dry goods follow King Arthur's chart (spot-check
  values); liquids and fats use physical densities.
- **A line that already carries the target unit uses the site's figure**
  ("1 cup (120 g) flour"), and `IngredientScaler` scales it too. Package
  sizes ("1 can (14 oz)") are never scaled.
- **Every amount on a line moves together, or the line stays as written:** a
  compound ("1½ cups plus 1 Tbsp. flour") converts as a whole or not at all;
  each side of "A or B" scales and converts, or none.
- **A unit's trailing period ("tsp.", "oz.") belongs to the unit:** the
  `UnitPatterns` alternation is wrapped so `\.?` applies to every
  alternative.
- **Liquids.** Ounces leaves pourable liquids as written unless "Also
  convert liquids" is on. Metric ignores that flag: liquids, spoons and cups
  become ml (a cup is 240 ml), known solids become g, and a spooned
  non-liquid with a site weight keeps it ("1 tsp (4 g) salt" is "4 g salt");
  known liquids stay in ml, even beside a gram figure.
- **A bare "oz" is a weight,** unless the ingredient is a known liquid.
- **Decimal commas:** "1,5 kg" is a decimal and keeps its comma when scaled;
  "1,500 g" may be thousands, so the line stays as written.
- **Temperatures** need 2–3 digits, then F or C; without a degree sign or a
  word, also a plausible cooking temperature ("2 C flour" is cups). Ovens round
  to recipe numbers (350°F ↔ 180°C); food-safety temperatures keep degree
  precision; "350°F (180°C)" collapses to the matching half. Nothing else in
  the instructions is rewritten.

## Gotchas that have cost real time

- **Never call `removeLast()` on a Kotlin list.** It resolves to a JDK 21
  method that crashes on older Android.
- **LazyColumn keys must be unique across the whole list,** not per section.
  Home prefixes each key with its section; a duplicate key once crashed the
  app. So must every `ForEach` id in a SwiftUI lazy stack or grid, nested or
  not: a repeat draws a blank row (#185).
- **Espresso 3.6.x is broken on API 36+.** Every Compose test dies in
  `Espresso.onIdle()`, so `espresso-core` is pinned to 3.7.0.
- **Unquoted string resources collapse runs of spaces:** `+  New list` renders
  as `+ New list`, so tests must match one space. When a Compose test can't
  find a node, dump the semantics tree before touching production code.
- **`MigrationTest` reads the schemas from the test APK's assets.** A
  `FileNotFoundException` there means a missing file, not a broken migration.
- **`org.json` is an Android framework class,** so JVM tests need
  `org.json:json` as a test dependency.
- **Timer alerts** are exact only when Android allows exact alarms (14 denies
  them by default), else they can be minutes late; no Settings prompt, by
  decision. The receiver re-checks the database, so a reset or deleted timer
  never rings.
- **The iOS share extension saves in its own process.** The app's observers
  never see those writes, so the app re-queries when it becomes active;
  anything new holding recipe data in memory must catch up the same way.
