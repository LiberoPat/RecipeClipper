# Decisions and history

Why things are the way they are. `CLAUDE.md` holds the rules; this file holds
the reasoning and history behind them. It is not loaded automatically: read
the section for the area you are about to change.

These passages were moved out of `CLAUDE.md` in September 2026, word for
word, to keep that file small. Phrases like "above", "below", "Current state"
or "Known constraints" refer to the old single-file layout. Plans that used
to live here are now GitHub issues (`gh issue list`).

## Implementation notes by area (as of September 2026)

Exists today:

- `RecipeApp` (`@HiltAndroidApp`) and `MainActivity` (`@AndroidEntryPoint`,
  hosts the `NavHost`). A shared link is queued on a channel and navigated to
  once the NavHost is composed. `shareHandled` is saved across recreation so a
  rotation after navigating doesn't open the link twice, while a rotation
  before it (which would lose the queued link) re-delivers it. Rotating
  0.4 s after a share is the case that broke this once.
- `ui/navigation/RecipeNavHost` — routes `home`, `history`, `recipe/{recipeId}`
  and `recipe/import?url={url}`. The import route is the share target and is
  told apart from `recipe/{recipeId}` correctly (checked on a device).
- `ui/home/`, `ui/history/` — Home (link field, "Continue cooking" = the most
  recent recipe, "Recently viewed" = the five before it, then a
  History / Lists block, plus a Settings gear beside the title) and History
  (newest first). Sections with nothing in them don't appear; the nav block
  and the gear always do. `ui/common/` holds
  `RecipeRow` and `TimeAgo`.
- `ui/recipe/` — the recipe screen, redesigned to the UI decisions below.
  `RecipeViewModel` (`@HiltViewModel`) holds a single `StateFlow<RecipeUiState>`
  and loads either by id or by URL from its navigation arguments;
  `RecipeScreen` is the entry point and picks between `ReadingView`,
  `CookView` and a small loading/error view; `ServesUnitsRow` is the inline
  servings stepper and unit menu; `RecipeComponents` holds shared pieces.
- `ui/theme/Theme.kt` — palette, typography, and `RecipeClipperTheme(cookMode)`.
  Fraunces and Karla are bundled as variable fonts in `res/font` (SIL OFL;
  licences in `assets/licenses`). Weight variation needs API 26+; below that
  the default weight is used. `secondary`, `tertiary` and the dark scheme's
  muted/hairline/paprika-text colours are derived, since the spec only names
  the light-side tokens. `tertiary` is the accent-for-text slot; `primary` is
  the filled-button paprika. Two schemes: `RecipeClipperTheme(forceDark)`
  takes `DarkScheme` when `forceDark` or `isSystemInDarkTheme()`, else
  `LightScheme`. There used to be a third, `CookScheme`, forced whenever cook
  mode was active; it was deleted once cook mode began following the system
  theme, and it was byte-for-byte identical to `DarkScheme` anyway, so nothing
  was lost. `forceDark` is now passed only as `cooking && darkWhileCooking`.
  Status/nav bar icon appearance is light-appearance only when `LightScheme`
  is actually showing. `res/values-night/` gives the
  window background/status/nav bar colour (ink `#1C1917`) so there's no
  light flash before Compose draws. Not verified on a device — no emulator
  run was done for this change; only build + lint were checked.
- `data/local/AppPreferences` — the global defaults, in SharedPreferences
  (deliberately not Room: settings, not recipe data). Named for the app, not
  for units, since `darkWhileCooking` is a display choice. The backing file is
  still called `unit_preferences`: renaming it would strand every existing
  user's saved unit choice for no gain. Holds `unitSystem`, `convertLiquids`,
  `temperatureUnit` and `darkWhileCooking`; `SharedPrefsAppPreferences` stores
  each enum by name under its own key (`temperature_unit` for the newest one),
  same pattern as `unitSystem`.
- Room (`data/local/`): database `recipe_clipper.db`, **version 6**, with the
  three tables from the schema section (named `recipes`, `lists`,
  `recipe_list_cross_ref`). The schema is exported to `app/schemas/`; commit
  it, it is what future migrations are written against, and never use
  destructive migration. The six built-in lists are seeded by
  `RecipeDatabase.SeedBuiltInLists` on first create. `Converters` stores the
  step and ingredient lists and the ticked-ingredient set as JSON.
- `RecipeDatabase.MIGRATION_1_2` adds Breakfast and Snacks to databases made
  before they existed. It is a **data-only migration**: versions 1 and 2 have
  identical table definitions, and the two exported schemas have the same
  `identityHash`, which is the quickest way to confirm nothing structural
  moved. It exists purely because `SeedBuiltInLists` runs in `onCreate` and
  therefore never runs again — without it only fresh installs would get the
  two new lists. The rows are appended after the existing seeded block
  (`MAX(sortOrder) + 1` among `isBuiltIn = 1` rows), so nothing is renumbered
  and any list the user had already made keeps its place. Registered in
  `di/DatabaseModule` via `.addMigrations(...)`.
- Database behaviour, all in `RecipeDao` and tested on a device: `upsert` keeps
  the id and list membership of a re-shared link, refreshes its content, bumps
  `lastViewedAt`, and keeps ticked ingredients only if the ingredient list is
  unchanged (otherwise the indexes mean different things). It also culls, in
  the same transaction: recipes in no list beyond the 50 most recently viewed
  are deleted, and a recipe in any list never is. "Saved" is computed from list
  membership (`isSaved` in the summary rows); there is no column for it.
  Opening a recipe from history counts as a view.
- Links are cleaned by `data/model/UrlCleaner` before they are fetched or saved,
  so one recipe reached through different tracking tags is one row. It removes
  only what cannot change the page: `utm_*` and well-known click ids (`fbclid`,
  `gclid`, `msclkid`, `igshid`, `mc_cid`, ...), the `#fragment`, and capitals in
  the scheme and host. Every other parameter is kept in its original order,
  because some sites use them to say which recipe you mean; add a name to the
  list only when you're sure it is tracking. Rows saved before this existed keep
  their old links (there is no migration; the app had no real data then).
- `RecipeRepository` fetches, parses and persists. If the fetch fails but that
  link was saved before, it shows the saved copy, so anything you've opened
  once still opens offline (checked with the network off). Ticked ingredients
  are written as they change.
- **Failed imports, offline, and database errors (both platforms).** Every
  layer hands over a cause, never copy. The source turns a failure into
  `Blocked(httpStatus)` (403, 404, 429, 5xx: usually a bot block that lifts on
  its own, see Known constraints), `Offline` (no usable network),
  `FetchFailed(detail, timedOut)` (anything else, including other statuses) or
  `NoRecipeFound`. Android decides `Offline` by asking `Connectivity.isOnline()`
  after an `IOException`, so a refused connection with the network up stays
  `FetchFailed`; iOS maps the no-connection `URLError` codes. The repository
  retries **once**, after an injectable 2 s pause, and only for `Blocked` or a
  `FetchFailed` that wasn't a timeout: `Offline` fails at once, a timeout is
  not repeated (a dead Wi-Fi costs one 15 s timeout, not two), `NoRecipeFound`
  never. The saved-copy fallback still applies after any failure, and a
  cancelled import writes nothing, even during the pause. **Every error screen
  offers Try again, `NoRecipeFound` included**, because a café or hotel captive
  portal serves a login page that parses as a page with no recipe. While
  `Offline` or `FetchFailed` is on screen, `RecipeViewModel` watches
  `Connectivity` (a `ConnectivityManager` callback flow bound in
  `di/PlatformModule`; `NWPathMonitor` on iOS) and reloads once on a real
  offline→online transition. Being online already is not one, so a failure
  while connected never reloads in a loop. Database failures degrade instead
  of crashing: the Android repositories run every DAO call through
  `ErrorLog.guard`, which logs and returns a safe fallback (`SaveFailed` from
  an import, null from `open`/`delete`, a no-op write,
  `DefaultListRepository.CREATE_FAILED` = -1 from `createList`), and every
  Flow through `orEmptyOnError`; `CancellationException` is always rethrown.
  iOS already worked this way. iOS photos load through `ImageLoader`, a
  disk-backed `URLCache` of their own (50 MB memory, 200 MB disk), so a photo
  seen once shows offline, as Coil's do on Android.
- `di/` — `DatabaseModule` (Room and the DAOs) and `SourceModule` (remote
  sources; the Reddit one joins in phase 4).
- Cook progress, timers and the chosen servings are saved as they change and
  survive the app being closed or killed (#10; see "Background timers and
  saved cook progress").
  `data/model/StepTimers` finds the duration a step states (lower bound of a
  range, null if none); the ViewModel runs the countdowns against wall-clock
  deadlines, several at once, and `TimerAlerts` plays three beeps on the alarm
  stream when one ends.
- `data/remote/BlogRecipeSource` — Jsoup fetch, plus `JsonLdRecipeParser`
  (pure: takes the JSON-LD script text, no network), and
  `MicrodataRecipeParser` as the fallback for a page with no JSON-LD recipe
  (see "Microdata fallback" under Parsing). Every extracted string
  (name, ingredients, instruction steps, yield — not `sourceUrl`) is run
  through a shared `stripHtml` helper (`Jsoup.parse(raw).text()`) so HTML
  tags and entities embedded in JSON-LD (`<p>`, `&amp;`, `&#39;`, ...) never
  reach the screen; a plain-string instructions block is split on `\n`
  *before* stripping each line, so `<br>`-separated steps don't collapse into
  one. `findRecipeNode` takes a `depth` param and gives up past 50 (nested
  `@graph`/object/array walks); separately, the `JSONTokener(...).nextValue()`
  parse that runs before that cap ever applies is wrapped in
  `catch (e: Throwable)`, not `Exception`, since a hostile deeply-nested
  block can overflow the stack there (`StackOverflowError` is an `Error`).
  The outer `catch (e: Exception)` in `BlogRecipeSource.fetch` is untouched,
  so `CancellationException` still propagates normally.
- `data/model/Recipe` — `Recipe` and `ParseResult`
- `data/model/UrlCleaner` upgrades `http://` to `https://` (case-insensitively)
  as part of the existing lowercasing step, instead of re-enabling cleartext
  for targetSdk 34's default block. `UrlInput.normalize` is unchanged.
- Serving scaling (not in the original build order): `data/model/Servings`
  reads the count from `recipeYield`, `data/model/IngredientScaler` rescales
  the leading quantity of each ingredient line, and `RecipeViewModel` holds
  the chosen target and the scaled list. Only ingredient lines are scaled,
  not numbers inside instruction text. Unparseable lines are left as written,
  and a recipe with no number in its yield shows no stepper.
- Sharing a recipe out of the app (not in the original build order):
  `data/model/RecipeShareText` is a pure object that formats the recipe as
  currently on screen — scaled servings, converted units — into plain text
  (no Markdown: it lands in SMS/WhatsApp/Mail, which don't render it). The
  source link is deliberately left out, on both platforms: what's shared is
  the recipe as clipped, not a pointer back to the page it came from. It
  takes the already-rendered ingredient/instruction strings plus a
  `ServingsScale?` (a `data/model` type; see the layering-cleanup notes
  below), so no scaling/conversion logic is duplicated. `RecipeViewModel.shareText()`
  returns the formatted string only; `RecipeScreen` builds and fires the
  `ACTION_SEND` Intent via `ShareCompat.IntentBuilder`, since the ViewModel
  may not touch `Context`. The share icon sits beside Back in the reading
  view's top row — deliberately not in cook mode, where the top bar is
  already Exit + step counter.
- History search (not in the original build order): `RecipeDao.observeHistory(query)`
  filters titles and ingredients with SQLite `instr(lower(...), lower(:query)) > 0`,
  never `LIKE` (which treats `%`/`_` as wildcards and would mishandle a query
  like "100%"). An empty query is a first `OR` branch in the same SQL, so
  there is one code path, not two queries picked in Kotlin. It matches
  against the ingredients column as stored (parsed, not scaled or
  unit-converted) — a search for "grams" won't find a recipe that merely
  displays in grams; that's correct, not a bug. `RecipesViewModel` keeps the
  query in a `MutableStateFlow` (survives rotation), debounces it ~250ms,
  and `flatMapLatest`s onto `repository.observeHistory(query)`. "History is
  empty" and "no results" are different UI states. Search is on History
  only, deliberately not added to Home.
- Deleting a recipe (not in the original build order): a manual delete is a
  **hard** delete — the row and its `recipe_list_cross_ref` rows (which
  cascade) are gone outright, list membership included. This is not the same
  question as the open one below about a recipe falling out of its last
  list; a manual delete answers a different question (an explicit user
  action) and this settles it. Two entry points, two confirmations: a
  History row swipes away (`SwipeToDismissBox`, red-paprika background) with
  an undo `Snackbar`; the recipe screen deletes via an overflow menu → an
  `AlertDialog` naming the recipe → `onBack()` (no undo there — the screen
  showing the recipe is already gone by the time a snackbar would appear).
  `RecipeDao.crossRefsFor` is read *before* `delete` so undo has something to
  restore; `RecipeDao.restore` re-inserts the captured `RecipeEntity` with
  its original id (`@Insert` honours a non-zero primary key) then its
  cross-refs. `RecipeRepository.delete` returns the captured
  entity+cross-refs pair (`RecipeRepository.DeletedRecipe`, opaque to
  callers) or null; `restore` takes it back. The captures live in
  `RecipesViewModel` as a plain field, not in the repository — the
  repository stays stateless everywhere else, the same reason
  `RecipeViewModel` keeps its timer deadlines in a plain field rather than
  in `StateFlow`. That field is a `LinkedHashMap` keyed by recipe id, not a
  single slot: swiping a second row within the snackbar's few seconds would
  otherwise overwrite the first capture and strand it, deleted with no way
  back. One snackbar covers the batch (`Deleted "X"`, or `2 recipes
  deleted`) and its Undo restores all of them, so undo is all-or-nothing by
  design — the alternative, a queue, plays a separate snackbar per delete
  and feels slow. The `LaunchedEffect` is keyed on the whole pending list so
  a new delete replaces the snackbar; that restart cancels `showSnackbar`,
  which is why neither the undo nor the dismissed branch runs and the
  earlier capture survives. Deliberately a hard delete, not a soft flag: a `deleted`
  column would need `cullHistory`, `observeHistory`, `observeRecent`,
  `observeRecentlySaved` and the `upsert` dedupe to all understand it.
  Re-sharing a deleted link after the fact creates a new row with a new id;
  correct, since the old one was actually deleted.
- Unit conversion (also not in the original build order): `UnitSystem` is
  AS_WRITTEN (default), OUNCES or METRIC. It was four options until #17
  dropped GRAMS, which differed from METRIC only in leaving liquids, spoons
  and cups of liquids as written; a stored GRAMS reads as METRIC
  (`UnitSystem.fromStoredName`, iOS `UnitSystem(storedName:)`), since its
  users wanted weights. `data/model/UnitConverter`
  does the work, `IngredientDensities` holds the weights, and `Units.kt` holds
  the unit table and regex fragments. The ViewModel renders each ingredient as
  scale first, then convert, so amounts always match the chosen servings.
  `data/model/TemperatureConverter` does the same for oven temperatures in the
  instruction text, but takes a `data/model/TemperatureUnit`
  (AS_WRITTEN/CELSIUS/FAHRENHEIT), not a `UnitSystem` — see the Settings
  screen bullet below for why the two were split apart.
- **Settings screen** (not in the original build order): `ui/settings/` —
  `SettingsScreen` + `SettingsViewModel` (`@HiltViewModel`, injects
  `AppPreferences` directly rather than through the repository, since these
  are app-wide defaults, not any one recipe's). Three sections, each behind a
  `SectionHeading`: Units (the three `UnitSystem` options as `RadioButton`
  rows, then "Also convert liquids" as a `Switch`, shown only for Ounces), Oven temperature (`TemperatureUnit`'s three options as
  `RadioButton` rows), Appearance ("Dark while cooking" as a `Switch`).
  Exclusive choices are always `RadioButton`s and independent toggles are
  always `Switch`es — never a bare ✓ for either, which is the reason this
  screen exists: the old units dropdown wore an identical ✓ on four exclusive
  options and two unrelated toggles, with oven temperature about to become a
  third exclusive choice crammed into the same menu. The units dropdown
  (`ui/recipe/ServesUnitsRow.kt`'s `UnitsMenu`) is exclusive-choice only now
  — just the four `UnitSystem` rows; "Also convert liquids" and "Dark while
  cooking" moved out to Settings. `RecipeViewModel.onConvertLiquidsChange` and
  `onDarkWhileCookingChange` stayed on `RecipeViewModel` for a while with no
  callers; #24 removed them on both platforms, since the recipe screen now
  picks up those values from `AppPreferences.settings`.
  **Oven temperature is now independent of the ingredient unit system**,
  defaulting to AS_WRITTEN: choosing Metric no longer converts a recipe's
  350°F to 180°C unless Oven temperature is separately set to Celsius. This
  is an intentional behaviour change from the single-dropdown design.
  `Routes.SETTINGS = "settings"`; reachable from the **gear icon beside the
  Home title** (`Icons.Default.Settings`, tinted `onSurfaceVariant`, nudged
  8.dp right so it optically aligns to the screen edge past `IconButton`'s
  own padding) and **only** from there — not from the recipe screen. It was
  briefly a labelled row in Home's nav block; it moved because Settings is
  visited rarely and on purpose, while History and Lists are daily and earn
  named rows. Reason for Home-only (also a comment in `HomeScreen.kt`):
  `RecipeViewModel` reads `AppPreferences` once in its initializer. Reaching
  Settings from Home means any Recipe destination has already been popped off
  the back stack, so the next recipe opened builds a fresh `RecipeViewModel`
  that reads current preferences. A Settings entry point on the recipe screen
  would leave that screen's `RecipeViewModel` alive underneath and showing
  stale settings on return — fixing that would need `AppPreferences` to
  expose Flows instead of plain `var`s, which it deliberately doesn't yet.
  **Superseded by #24:** `AppPreferences.settings` is now a Flow (iOS: a
  Combine publisher) that `RecipeViewModel` and `SettingsViewModel` collect,
  so a recipe left underneath Settings follows a change as it is made. The
  Home gear is still the only entry point; whether the recipe screen gets one
  is a product decision, no longer a technical constraint.

## Layering cleanup and the first ViewModel tests

- `data/model/ParseError` is a sealed class (`NoRecipeFound`, `Blocked(httpStatus)`,
  `Offline`, `FetchFailed(detail, timedOut)`, `SaveFailed`, `NotSaved`,
  `NothingToShow`; see "Failed imports, offline, and database errors" above)
  carried by `ParseResult.Error` and `RecipeContent.Error`. `BlogRecipeSource` and `RecipeRepository` hand over a cause,
  never a sentence; `RecipeScreen` resolves it to copy via `stringResource`.
  `FetchFailed.detail` stays a raw string — the platform exception message, diagnostic
  not copy, shown in parentheses.
- Every UI string (screen text, button labels, snackbar messages, every
  `contentDescription`) lives in `res/values/strings.xml`, English only. The one
  deliberate exception is `data/model/RecipeShareText`, which keeps its own English
  wording by design (see its KDoc) — it builds a message body, not UI, and has no
  `Context` to read a resource from. `Servings.describe` (English pluralisation
  hardcoded in a pure file) is now `Servings.bareCount(recipeYield): Int?`, which only
  decides *whether* the yield is a bare number; the UI supplies the word via
  `pluralStringResource(R.plurals.servings, n, n)`.
- `data/model/ServingsScale` (moved out of `ui/recipe/RecipeViewModel.kt`, which is
  where it used to live) is the one genuinely domain type of the three that were
  declared inside the ViewModel; `RecipeShareText.format` takes the real type now
  instead of two loose `Int?`. `StepTimer` and `CookState` stayed screen state and
  moved to `ui/recipe/RecipeUiState.kt` alongside `RecipeUiState`/`RecipeContent`,
  not to `data/model/`.
- `ui/recipe/TimerAlarm.kt` holds `playAlarm()`/`ToneGenerator` and the `TimerAlerts`
  composable, moved out of `RecipeScreen.kt` for tidiness (it was already correct
  MVVM — a view firing an effect off observed state — so this is not a layering fix
  and it deliberately isn't wrapped in an interface).
- `data/Clock` (`fun interface Clock { fun now(): Long }`) replaces the inline
  `System.currentTimeMillis()` calls in `RecipeRepository` and the private clock
  field in `RecipeViewModel`; provided for real via `di/ClockModule`. This is what
  lets a ViewModel test's virtual time and a timer's wall-clock deadline agree.
- `RecipeRepository` is now an interface (`data/RecipeRepository.kt`); the real,
  Room-backed implementation is `DefaultRecipeRepository`, bound with `@Binds` in
  `di/RepositoryModule`. `AppPreferences` is likewise an interface, with
  `SharedPrefsAppPreferences` (the one class holding a `Context`) as the real
  implementation, bound in the same module. `app/src/test/.../fake/` holds
  `FakeRecipeRepository` (history and recent as in-memory
  `MutableStateFlow`s; `importFromUrl`/`open` return whatever a test stages;
  `setChecked`/`delete`/`restore` calls are recorded for assertions),
  `FakeAppPreferences` (plain `var`s), `FakeListRepository` and
  `FakeConnectivity`. Hand-written, not mocks — CLAUDE.md's
  now-satisfied condition for this was "when ViewModel unit tests are actually
  being written".
- `RecipeViewModelTest`, `RecipesViewModelTest` and `HomeViewModelTest` are the
  first ViewModel test suites (`app/src/test/.../ui/...`). `MainDispatcherRule`
  (`app/src/test/.../MainDispatcherRule.kt`) installs a `StandardTestDispatcher` as
  `Dispatchers.Main`; tests run via `runTest(mainDispatcherRule.dispatcher) { }` so
  the test body's virtual clock and `viewModelScope`'s are the same scheduler —
  needed for the timer tests, which back `Clock` with `testScheduler.currentTime`.
  `collectEagerly` (`app/src/test/.../CollectUiState.kt`) starts a background
  collector on a `stateIn(WhileSubscribed(...))` flow before `advanceUntilIdle()`,
  since `HomeViewModel.uiState` and `RecipesViewModel.uiState` emit nothing without
  one.

## Lists

**Lists (phase 3's first half).** List membership lives behind its own
`data/ListRepository` interface, with `DefaultListRepository` as the Room-backed
implementation, bound in `di/RepositoryModule` alongside the recipe one. It is
deliberately *not* bolted onto `RecipeRepository`: the two cover different
things, and the three list ViewModels need nothing from the recipe side, so
`FakeListRepository` stays small. Details worth knowing:

- `ListDao.observeLists(recipeId)` is one query, not two. It returns each list
  with a derived `recipeCount` and a `containsRecipe` flag saying whether it
  holds that recipe. Callers with no recipe in hand (the Lists screen) pass
  `ListDao.NO_RECIPE`, an id no row can have, so every `containsRecipe` comes
  back false. Same spirit as `observeHistory`'s empty-query branch: one code
  path to keep correct instead of two that can drift.
- `addToList` is `@Insert(onConflict = IGNORE)`, never REPLACE. Re-adding a
  recipe already in a list must not rewrite `addedAt`, which is what orders
  the list detail screen. There is a device test for exactly that.
- `ListDao.delete` carries `AND isFavorites = 0` **in the SQL**, not in Kotlin,
  so no path can get around it. **Favorites is the only list that cannot be
  deleted.** Deleting a list never deletes the recipes in it — they fall back
  to ordinary history.
- `ListDao.create` is a transaction: it inserts the list and, when given a
  recipe, puts that recipe straight in, so a list created from the sheet can
  never exist without the recipe that prompted it.
- `ui/savetolist/` — `SaveToListBottomSheet` + `SaveToListViewModel`. The
  ViewModel backs **both** the sheet and the recipe screen's bookmark icon,
  which is why the recipe screen holds one whether or not the sheet is open:
  the icon has to know whether the recipe is in any list before anything is
  tapped. `isSaved` is a computed `lists.any { it.containsRecipe }`, so the
  icon reads the same derived thing the database does. The recipe id arrives
  via `setRecipe(id)` rather than `SavedStateHandle`, because the sheet is not
  a navigation destination and because the import route has no id until the
  parse finishes.
- `ui/lists/` and `ui/listdetail/`. Renaming and deleting a list are on the
  list's own screen (overflow menu → dialog), not on the Lists screen, so a
  list is managed from one place — the same shape as deleting a recipe from
  the recipe screen. Removing a recipe *from* a list is deliberately not
  offered on the detail screen: membership is edited in the sheet only.
- `ListDetailViewModel` collects the list into its own `MutableStateFlow` in
  `init` rather than reading it back off `uiState`. `uiState` is
  `stateIn(WhileSubscribed(...))`, so its `value` is the initial state
  whenever nothing is collecting, and `onDelete` reading a null list there
  would silently refuse a delete that should have gone through. There is a
  test that runs the delete with no collector at all.
- The bookmark glyphs are two hand-written vector drawables
  (`res/drawable/ic_bookmark*.xml`), not
  `androidx.compose.material:material-icons-extended`. The core icon set
  material3 already brings in has no bookmark, and its only outline/filled
  pair is the heart — wrong here, since Favorites is one built-in list among
  several rather than what "saved" means. A large dependency for two glyphs
  wasn't worth it. They carry no `android:tint`: that would want
  `?attr/colorControlNormal`, an AppCompat theme attribute this app doesn't
  define, and the `Icon` composable tints them anyway.
- **Home has no "Saved" section, deliberately.** It used to, fed by a
  `RecipeDao.observeRecentlySaved` query. In use it showed the same recipes as
  "Recently viewed" — saving something almost always means you just opened it
  — and Lists answers "what have I kept?" properly, by list rather than as an
  undifferentiated recent-three. The section, the query, the repository
  method, the fake's flow, `SAVED_COUNT` and the `section_saved` string were
  all removed rather than left unused. `RecipeSummary.isSaved` stays: it is
  what draws the "Saved" tag on a History row.
- **A LazyColumn's item keys must be unique across the whole list, not within
  a section.** Home's `section()` takes a `sectionKey` and emits
  `"$sectionKey-${recipe.id}"`. Keying on the recipe id alone crashed with
  `IllegalArgumentException: Key "3" was already used` once one recipe could
  appear in both "Recently viewed" and "Saved". That was latent from the day
  Home was written and only fired when lists shipped, because nothing could
  put a recipe in a list before then, so "Saved" was always empty. The prefix
  is kept even though only one section remains — it is what stops the next
  section from bringing the crash back.
- Home's bottom nav block is History / Lists, both unconditional. **Settings
  is a gear icon beside the Home title**, not a row in that block: it is a
  place you go rarely and deliberately, where History and Lists are part of
  the daily path and earn named rows. It is still reachable from Home *only*
  — the reason has nothing to do with where the control sits and everything
  to do with `RecipeViewModel` reading preferences once in its initializer;
  see the Settings screen bullet. History used to appear only once there was a "continue
  cooking" recipe while Settings was always there, so the block changed shape
  depending on what was in the database. With three entries a fixed block is
  easier to aim at. This settles the open inconsistency noted when Settings
  was added.

## Product rules and UI decisions: the original wording

The condensed versions in CLAUDE.md are the ones to follow; this is the fuller wording, with the reasons and the history of each revision.

### Product rules

These are decisions, not suggestions. Don't relitigate them in code.

- **Capture is frictionless.** Sharing a link parses and displays it. No save
  prompt at share time.
- **History is automatic.** Everything shared lands in history, newest first,
  capped at the 50 most recent.
- **Lists are deliberate.** Adding a recipe to a list is an explicit second
  act. Favorites is NOT a separate tier — it's a list alongside Lunch,
  Dinner, Desserts, Breakfast and Snacks. One mechanism, not two.
- **Only Favorites is permanent.** The six seeded lists are *starting
  suggestions, not fixtures*: Lunch, Dinner, Desserts, Breakfast and Snacks
  delete exactly like a list the user made. Favorites stays because "saved"
  is defined in terms of it and a missing one would strand what is in it.
  `isBuiltIn` therefore means only "seeded, and sorts before user-created
  lists" — it stopped meaning "undeletable". Don't re-conflate them: the
  delete guard is `isFavorites`, and there is a device test that fails if it
  goes back to `isBuiltIn`.
- **"Saved" means "in at least one list."** There is no `isSaved` column;
  it's derived from the cross-ref table. Anything in a list is never culled
  by the history cap.
- **Coming out of a list is a demotion, not a deletion.** A recipe removed
  from its last list stays in history and becomes cullable by the cap like
  anything else; the recipe row is untouched. This was an open question and
  is now settled. Deleting is the explicit, separate action with its own
  confirmation — unticking a checkbox in a sheet is too light a gesture to
  destroy something with, and it would be unrecoverable. The removal path
  needs no code for this: `removeFromList` only touches the cross-ref table,
  so the behaviour falls out of the schema. A device test pins it.

### UI decisions

Mockups live in the "Recipe Clipper — Recipe Screen Redesign" design canvas.
These are settled; don't reintroduce what they removed.

**The reading view opens on the recipe.** No segmented pickers, no filled
chips, no radio lists above the ingredients. The first screenful is photo,
title, times, one servings-and-units row, and ingredients.

**Times are plain labeled numbers, not chips.** Chips imply tappable; these
aren't.

**Servings and units are one always-visible row, adjusted in place.**
`Serves − 6 +` on the left, the unit choice (`Metric ▾`) on the right. Servings
is per-recipe: one tap on − or +, nothing to open. Units are a **global
default** — the user is a metric person or they aren't, so it's set once and
persists across recipes, not re-picked per recipe; the menu says "every
recipe". The unit menu is a small dropdown (two taps: the label, then the
option). "Cook" stays a named feature behind one bottom button, not in the
reading view.

*Revised.* The dropdown used to also hold "Also convert liquids" and "Dark
while cooking" alongside the four exclusive unit options, every row wearing
an identical ✓ with no way to tell which rows were exclusive and which
weren't. Both toggles moved to the Settings screen; the dropdown is
exclusive-choice only now. See "Settings screen" below.

*Revised.* This used to be an "Adjust" bottom sheet behind a "Serves N · Adjust"
row, on the reasoning that scaling and units shouldn't occupy the reading view.
In use it hid the two things that are most often adjusted and cost about three
taps per change, so the controls now sit in one compact row instead. That
reversed the earlier decision at the user's request; don't bring the sheet back
without asking.

**Cook mode is a highlighted scroll, not a pager.** Decided against
one-step-at-a-time. Steps stay in a scrolling list with three visual states:
done (struck, dimmed), current (ringed card, ~21px type, timer inside),
upcoming (readable, muted). Reasons:

- Cooking isn't linear reading. Steps overlap ("while it simmers, heat the
  oven"), so the next step must be glanceable before it's current.
- Users constantly scroll back to re-check an amount.
- **Source step quality is out of our control.** Blogs give anywhere from 12
  clean steps to 3 paragraph-blobs; Reddit gives whatever someone typed. A
  scroll degrades gracefully; a pager over 3 blobs is a wall of text with a
  Next button.

Implementation: a `LazyColumn` with three row states, a `currentStepIndex` in
the ViewModel, and `FLAG_KEEP_SCREEN_ON`. Cook mode is a **boolean on the
recipe screen**, not its own navigation destination. Ingredients collapse to
a tap-to-expand bar at the top.

Open within cook mode: tapping a step sets it current (forgiving — you can
jump back without losing progress) and only the "Done — next step" button
advances. Leaning that way; confirm when cooking with it.

**Design language:** Fraunces (display) over Karla (body); warm off-white
ground `#FBF9F6`, ink `#1C1917`, muted `#6B6259`, hairline `#E7E1D9`,
paprika accent `#BF4A2B`. System dark mode inverts to `#1C1917`, using the
on-ink token set (`MutedOnInk`, `HairlineOnInk`, `PaprikaTextOnInk`) — the
spec's own muted and paprika are too dim to read there. No manual light/dark
toggle for the app as a whole: the system setting decides. Avoid the default
Material purple.

**Android: every dialog, sheet and menu is composed inside its screen's
`RecipeClipperTheme` block** (#142, #186). The theme is per screen, so one
called before or after the block gets Material's baseline purple; only hosts
over any screen (the tab bar, the received-file sheet) carry their own. The
#186 sweep moved Recipes' Paste a link, List detail's Rename, the full-screen
cooked photo with its date picker, and Edit's library-full prompt inside
(`LibraryFullDialog` no longer wraps itself); a Robolectric test on each checks
its button is paprika.

*Revised.* Cook mode used to invert to ink unconditionally, on the reasoning
that a screen propped up across a kitchen reads better dark. In practice a
bright room made that the wrong call as often as the right one, so cook mode
now follows the system theme like every other screen, and a **Dark while
cooking** toggle opts back in. Off by default. Don't restore the
unconditional inversion without asking.

**Settings screen.** The toggle used to sit in the units menu because that
dropdown was the app's only settings surface; it now lives on its own
Settings screen (`ui/settings/`), reachable from the gear beside the Home title,
alongside "Also convert liquids" and the new "Oven temperature" choice. See
"Settings screen" in the Current state section above for the full shape and
why it's Home-only. Radio rows for exclusive choices, Switch rows for
independent toggles — never a bare ✓ for either.

## Room schema, navigation and the save-to-list sheet (original spec)

### Room schema

```kotlin
@Entity(indices = [Index("sourceUrl", unique = true)])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,          // unique — dedupes re-shares
    val title: String,
    val imageUrl: String?,
    val ingredients: List<String>,  // TypeConverter (JSON)
    val instructions: List<String>,
    val prepTime: String?, val cookTime: String?,
    val totalTime: String?, val servings: String?,
    val sourceType: String,         // BLOG | REDDIT — for re-fetch
    val lastViewedAt: Long,
    val checkedIngredients: Set<Int> = emptySet()
)

@Entity
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isBuiltIn: Boolean,         // seeded + sorts first; NOT "undeletable"
    val isFavorites: Boolean,       // exactly one row
    val sortOrder: Int,
    val createdAt: Long
)

@Entity(primaryKeys = ["recipeId", "listId"], foreignKeys = [/* cascade */])
data class RecipeListCrossRef(
    val recipeId: Long,
    val listId: Long,
    val addedAt: Long
)
```

Schema notes that are easy to get wrong:

- `sourceUrl` is unique. Re-sharing a seen link **upserts** — bump
  `lastViewedAt`, don't insert a duplicate. List membership and ticked
  ingredients survive.
- `isFavorites` is a column, not a name match on `"Favorites"`. Name matching
  breaks on rename and on translation.
- `checkedIngredients` is persisted so a user can close the app mid-cook and
  return to it.
- Built-in lists are seeded via `RoomDatabase.Callback` on first create, which
  means adding one later needs a migration — `onCreate` never runs again.
  All six are renameable. All but Favorites are deletable.
- History cap: after each insert, delete where the recipe is in no list,
  ordered by `lastViewedAt`, offset 50.

### Navigation

```
home
recipe/{recipeId}            from history, lists, or home
recipe/import?url={url}      share-target entry: parse, then persist
history
settings                     from Home's gear — see "Settings screen" above
lists
lists/{listId}
```

Share intent → `MainActivity` extracts URL → navigate to `recipe/import`.
On successful parse: upsert with `lastViewedAt = now`, no list membership.

Home shows: URL input (kept for testing without the share sheet), "continue
cooking" (last viewed, hidden when empty), last 5 viewed, rows into History
and Lists, and a Settings gear beside the title. No "Saved" section — see the
Lists notes in Current state for why it was removed.

### Save-to-list bottom sheet

Spotify's add-to-playlist model. Built; `ui/savetolist/`.

- Checkboxes, not radio buttons — multiple lists per recipe.
- Toggling writes/deletes a `RecipeListCrossRef` immediately. No Save/Cancel;
  the sheet is dismissed, not submitted.
- "+ New list" expands inline to a text field — no dialog stacked on a sheet.
- Creating a list from the sheet auto-ticks the current recipe into it.
- Built-ins sort first, then user lists by `sortOrder`.
- Reachable from the recipe screen's bookmark icon (filled = in ≥1 list).

## Unit conversion rules (original wording)

Unit conversion rules (each one exists to avoid showing a confident wrong number):

- Weight to weight (oz, lb, g, kg) is exact. Volume to weight needs a density,
  and an ingredient not in `IngredientDensities` is left as written.
- The density table matches on the END of the ingredient name, so "unsalted
  butter" matches but "butter beans" does not. Compound names that would
  wrongly match a shorter alias ("apple butter", "rice flour", "condensed milk")
  are listed as `skip` entries, which win by being longer. Add one whenever a
  new alias could swallow a different ingredient.
- Only ingredients that weigh about the same every time belong in the table.
  Salt, chopped produce, shredded cheese, nuts, rolled oats and rice are left
  out on purpose. Dry goods follow King Arthur's published weight chart;
  liquids and fats use physical densities. The chart was read through a
  summarising fetch, so spot-check any value before relying on it.
- If a line already carries the target unit ("1 cup (120 g) flour" or
  "1 cup/120 grams flour"), the site's own figure is used. This is also why
  `IngredientScaler` rescales those parenthetical and slash measures along
  with the leading amount; package sizes ("1 can (14 oz)") are never scaled.
- A compound amount ("1½ cups plus 1 Tbsp. (200 g) flour"; `plus`, `and` or
  `+` followed straight away by a second quantity and unit) is converted as a
  whole or not at all: a site figure after the second part stands for the
  whole amount; else both parts are converted and summed; else — unknown
  density, a range, a liquid with liquids off — the line stays as written.
  Converting only the leading part was a confident wrong number.
  `IngredientScaler` scales the second part and its figure too.
- A unit abbreviation's trailing period ("tsp.", "oz.", "lb.") belongs to the
  unit: the `UnitPatterns` alternation is wrapped so `\.?` applies to every
  alternative, not just the last. Bon Appétit, Epicurious, Delish and Budget
  Bytes all write units this way.
- Old-style abbreviations (#135): "c", "c.", "C" and "C." after an amount are a
  cup (Delish: "1 1/2 c. cherry tomatoes", "1/2 c. heavy cream"), and "T"/"T."
  a tablespoon and "t"/"t." a teaspoon, as old recipe cards write them. T and t
  differ only by case, so `units.json` holds them as `(?-i:[Tt])` in `patterns`
  (every caller matches case-insensitively otherwise) and as `caseSensitive`
  `names` rules that run first; `MeasureUnit.fromText` lowercases for every
  other rule. "Tbs" was already a tablespoon in any case. A one-letter unit
  refuses a hyphen or apostrophe after it, so "2 T-bone steaks" stays a count.
  "C" is also Celsius: a whole 2–3 digit number in `TemperatureConverter`'s
  plausible Celsius range (40–320) followed by a C is no amount at all
  (`IngredientScaler.Patterns.temperature`), so "180 C water" stays as written
  in scaling, conversion and grocery totals, like a size ("1-inch"). "2 C
  flour" is cups, as the temperature rule already said, and instructions are
  still read only by `TemperatureConverter`. Because the scaler, converter,
  `GroceryCombiner`, `IngredientName` and amounts in steps all read units
  through `UnitPatterns` and `fromText`, they agree: "1 c. heavy cream" and
  "1 cup heavy cream" add up to "2 c. heavy cream" (the total keeps a unit as a
  line wrote it, as "3 cup milk" always has). "c" left `names.json`
  `leadingWords`, which lists only unit words the converter doesn't read.
- OUNCES leaves pourable liquids as written unless `convertLiquids` is on. METRIC ignores that flag: liquids, spoons and cups become ml (a volume
  to volume conversion, exact and density-free), and known solids become g.
  METRIC treats 1 cup as 240 ml, 1 tbsp as 15 ml and 1 tsp as 5 ml.
  Exception: in METRIC a spooned or cupped amount of anything that is not a
  known liquid keeps the site's own weight figure when the line has one
  ("1 tsp (4 g) salt" is "4 g salt", not "5 ml salt") — the site's number is
  more accurate than a volume. Known liquids stay in ml even with a gram
  figure beside them, since METRIC shows liquids by volume.
- Temperatures need 2-3 digits followed by F or C. Without a degree sign,
  "degrees" or a full word, the number must also be a plausible cooking
  temperature, so "2 C flour" (cups) is never touched. Ovens round to the
  numbers recipes use (350°F is 180°C, 200°C is 400°F); food-safety
  temperatures keep degree precision. A pair like "350°F (180°C)" collapses to
  the half that matches the target instead of printing the same value twice.
  Instructions are otherwise never scaled or rewritten. The target scale comes
  from `TemperatureUnit` (Settings screen), not `UnitSystem` — the two are
  independent (see the Settings screen bullet above).
- A bare "oz" is a weight, unless the ingredient is a known liquid, where it is
  fl oz. Never call `removeLast()` on a list: Kotlin resolves it to a JDK 21
  method that crashes on older Android versions.

## Parsing: blogs and field rules (original wording)

Repository routes by URL host.

**Blogs** — fetch page, find `schema.org/Recipe` JSON-LD, read fields. This
is the data sites publish for Google's rich snippets, so it's structured by
construction. Working today.

Field rules that real sites forced (same on both platforms, pinned by tests):

- Times: an ISO duration totalling zero ("PT0S", Delish) is absent, not
  shown raw. A time written as a plain English phrase ("1 hour 30 minutes",
  Condé Nast sites) is rendered like an ISO one ("1h 30m") — only when the
  whole string is such a phrase; "Overnight", "20 to 25 minutes" stay as
  written.
- `HowToSection`s named as a condensed duplicate of the recipe ("Abbreviated
  Recipe" on RecipeTin Eats, "Summary", "Quick version", "TL;DR" …; an exact,
  commented set in the parser) are skipped — only when there are two or more
  sections and another still has steps, so nothing is ever lost. Without this
  the summary paragraph became step 1 of cook mode with a bogus timer. Section
  names are otherwise still dropped; headers in the reading view would be a
  feature, not a fix.

## Architecture and conventions (original)

MVVM + Repository, Hilt for DI, Room for persistence, Compose Navigation.

```
com.example.recipeclipper/
├── RecipeApp.kt                    @HiltAndroidApp
├── MainActivity.kt                 @AndroidEntryPoint, NavHost, ACTION_SEND
├── di/
│   ├── DatabaseModule.kt           Room db + DAOs (@Singleton)
│   ├── RepositoryModule.kt         @Binds the repositories and AppPreferences
│   ├── SourceModule.kt             remote sources
│   ├── ClockModule.kt              the real Clock
│   └── PlatformModule.kt           Connectivity, ErrorLog, TimerAlarmScheduler
├── data/
│   ├── Connectivity.kt             online state; AndroidConnectivity is the real one
│   ├── ErrorLog.kt                 where swallowed database errors are logged
│   ├── local/
│   │   ├── RecipeDatabase.kt
│   │   ├── entity/                 RecipeEntity, ListEntity, RecipeListCrossRef
│   │   └── dao/                    RecipeDao, ListDao
│   ├── remote/
│   │   ├── BlogRecipeSource.kt     JSON-LD extraction
│   │   └── RedditRecipeSource.kt   Reddit .json + comment fallback
│   ├── ListRepository.kt           list membership; DefaultListRepository is the
│   │                               Room-backed impl. Separate from RecipeRepository
│   │                               on purpose — see the Lists notes above.
│   ├── RecipeRepository.kt         routes sources, writes Room, exposes Flows,
│   │                               enforces history cap
│   └── model/Recipe.kt             domain model, distinct from entity
└── ui/
    ├── navigation/RecipeNavHost.kt
    ├── home/                       HomeScreen + HomeViewModel
    ├── recipe/                     RecipeScreen + RecipeViewModel
    ├── settings/                   SettingsScreen + SettingsViewModel (Home-only entry)
    ├── savetolist/                 SaveToListBottomSheet + ViewModel
    ├── recipes/                    RecipesScreen + RecipesViewModel (was history/, #102)
    ├── lists/                      ListsScreen + ViewModel
    └── listdetail/                 ListDetailScreen + ViewModel
```

### Conventions

- ViewModels expose a single `StateFlow<XUiState>`; UI state is a sealed
  class or data class in the same package. Composables observe and forward
  events up — no coroutines, no repository calls, no business logic in
  Composables.
- Never hold screen state in `remember` if it must survive rotation. That
  bug (losing an in-flight parse on rotate) is the reason for the ViewModel
  layer; don't reintroduce it.
- ViewModels and the repository must not import Compose or touch `Context`.
  They stay plain-JUnit testable.
- Domain `Recipe` (ui/data boundary) is separate from `RecipeEntity` (Room).
  Map at the repository.
- Parsers are pure where possible. Extraction helpers take a String and
  return data — no network, no Android APIs. That's what makes them testable.

## Microdata fallback

**Microdata fallback** (`MicrodataRecipeParser`, both platforms). It is tried
only when JSON-LD finds no recipe, so no site that works changes. It reads
the `itemscope`/`itemprop` attributes on the page's markup, and it was built
for Smitten Kitchen. That site is WordPress with Jetpack's recipe block:
name, ingredients, yield and total time as microdata, no JSON-LD at all. Its
steps have no `recipeInstructions` itemprop; they sit in
`<div class="jetpack-recipe-directions">`, which is read when the microdata
has no steps. Rules, pinned by tests on the same pages on both platforms:

- **Properties belong to their nearest item.** An author's Person `name`
  inside the Recipe is not the recipe's name.
- **Value per the microdata spec:** a `content` attribute when there is one,
  otherwise `href`/`src` made absolute, `<time datetime>`, or the text.
- **Steps split at block boundaries, not one per `<p>`.** Jetpack's first
  step is bare text before any `<p>`, followed by a stray `</p>`, and a
  per-`<p>` reading silently drops it. The notes block is not read.
- **The photo is `og:image`** when there's no `image` itemprop (Jetpack has
  none).

iOS has no HTML parser, so its twin brings a small, forgiving element tree
(`HtmlTree`: void and raw-text elements, comments, and implied `</p>` and
`</li>`), with elements in a flat array so a pathologically deep page can't
overflow the stack. Text still goes through the Jsoup-compatible
`stripHtml`. On the real Smitten Kitchen page (September 2026) both
platforms produce identical output: 12 ingredients, 6 steps, the yield, 50
minutes and the photo. The iOS parse took 48 ms.

Background: a probe of 15 mainstream recipe sites once found **zero**
carrying schema.org microdata, so this was deferred. A later 16-site pass
found Smitten Kitchen, which failed with "no recipe found" on both
platforms. Its steps turned out to be recoverable through the Jetpack
markup, which an earlier note here had missed.

## Build order (history)

The remaining work in phase 4 is tracked as issue #11.

1. **MVVM refactor, no new features.** Move current logic into ViewModel +
   repository. Done when: share flow behaves identically AND rotating
   mid-fetch no longer loses state.
2. **Hilt + Room.** Entities, DAOs, DI modules, history cap, built-in
   seeding. Home + History screens.
3. **Lists + cook mode.** Cross-ref, save-to-list sheet, Lists + List detail,
   bookmark icon — **all done**; see the Lists notes in Current state. Cook
   mode lands here too — it's a state on the recipe screen, and both it and
   list membership depend on the same persisted recipe. (An in-memory version
   already exists; see Current state. What phase 3 adds is persisting it.)
   Persisted cook-mode progress, persisted servings and background timers
   are done too (#10).
   **Background timers land here too, deliberately**, not before. An alarm
   that fires after the process has been killed would otherwise notify about
   a timer the app has no record of, so a background alert needs running
   deadlines persisted — which is the same work as persisting cook-mode
   progress. Doing them separately means writing that twice. See the
   background-timer note under Known constraints for the shape.
4. **Reddit comment transcription.** With unit tests on the scorer.

Do 1 before 2. If the refactor and persistence land together and something
breaks, you can't tell which half did it. Phase 1 has a hand-runnable test:
share a link, rotate the phone.

Phase 1's done-check (share a link, rotate mid-fetch, the recipe still loads)
has been run on an emulator and passes.

## Fetch blocking, as measured

- **Fetches are blocked intermittently, and that is the real coverage gap.**
  A probe of 15 mainstream recipe sites using the app's exact `Jsoup.connect`
  call and user-agent got 7 clean fetches and 8 failures (403s and 404s).
  The failures are *not* stable per-site blocking: The Kitchn 403'd in one
  run and returned 200 minutes later to the same user-agent, while
  Allrecipes did the reverse, and a browser user-agent did *worse* on The
  Kitchn (403) than the app's own (200). Some sites also return 404 as a
  disguise for bot-blocking, flipping between 403 and 404 across repeats.
  So a failed import often means "try again", not "this site is
  unsupported". That is what the error handling is built around: Try again
  on every error screen, a `Blocked` cause whose copy says to try again in a
  minute, and one automatic retry after a short pause (see "Failed imports,
  offline, and database errors" in Current state). Don't chase a
  user-agent that "works" — there isn't one.

## Rendered fallback: an off-screen browser after the retry (#36)

When the direct fetch and its one retry still end `Blocked`, or the page
loaded with `NoRecipeFound`, the repository loads the page once in an
off-screen browser (Android `WebView`, iOS `WKWebView`), takes
`document.documentElement.outerHTML`, and runs it through the same pure
parsers (JSON-LD, then microdata).

- **Why.** Bot protection targets plain HTTP clients, and a real browser
  engine running the site's JavaScript passes many of its checks. Pages that
  build their recipe data in JavaScript have none in the fetched HTML. This
  is not the user-agent chase warned against above: the web view keeps its
  engine's own user agent. Paprika extracts from pages loaded in its own
  browser the same way.
- **Why after the retry, and only for those two causes.** The direct fetch
  is cheap and usually works; the web view costs seconds and memory. `Offline`
  would fail the same way, a timeout means the network is dead (the rule that
  a dead Wi-Fi costs one timeout still holds), and other `FetchFailed` causes
  (DNS, TLS) aren't what a browser fixes.
- **Why a rendered page with no recipe keeps the original cause.** A block
  that the browser also can't pass is still a block, and its copy ("try
  again in a minute") is the right advice. Showing `NoRecipeFound` instead
  would hide it.
- **Shape.** `RenderedPageSource` returns HTML, nothing more, so the
  repository stays `Context`-free and testable with a fake, and parsing stays
  pure. The implementations need the main thread (and on Android a
  `Context`), so they sit beside `AndroidConnectivity` / `PathConnectivity`,
  bound in Hilt and `AppContainer`. The repository caps the whole render at
  20 s (`RENDER_TIMEOUT_MS` / `renderTimeout`); the implementation waits a
  1.5 s settle after the last page load (a navigation restarts it) before
  reading the HTML, runs JavaScript with DOM storage, skips images, and
  destroys the web view on every way out, cancellation included. Nothing is
  shown to the user: capture stays frictionless, just slower.
- **On Android, load errors aren't handled early:** a failed page still ends
  in `onPageFinished` on the WebView's error page, which parses as no recipe,
  and finishing on `onReceivedError` would also end a load whose first
  navigation a script redirect aborted. iOS has no such page, so
  `didFail`/`didFailProvisionalNavigation` finish at once, except for the
  cancelled navigation a redirect causes. A crashed renderer
  (`onRenderProcessGone`, `webViewWebContentProcessDidTerminate`) ends the
  render without taking the app down.
- **Limits.** Not a guarantee: some checks detect web views, and still end
  `Blocked`. Cloudflare's check is waited out, or shown to the cook when it
  wants a click (#220, below). The web view has the app's cookies, not the user's browser
  logins, so paywalls still fail. Not inside the iOS share extension, for
  memory (#19); Safari shares could use Safari's own page instead (#35).
- **Checked only by tests so far.** The repository rules are pinned by fakes
  on both platforms; the web views themselves need a device check on a page
  whose recipe data appears only after JavaScript runs, and on one that
  blocks the plain fetch.

## Cloudflare's check (#220)

Sites behind Cloudflare answer the plain fetch (Jsoup, URLSession) with a 403
and Cloudflare's challenge; the one render (#36) then often landed on "Just a
moment..." or "Verify you are human" and gave up. Owner's decision
(2026-09-29): build three steps, on both platforms.

- **Recognising it** (`CloudflareChallenge`, pure, both platforms, pinned by
  the pages in `shared/fixtures/cloudflare`): only Cloudflare's own markers,
  `window._cf_chl_opt`, a `__cf_chl_` token, the challenge's `orchestrate`
  script under `/cdn-cgi/challenge-platform/`, or the title "Just a moment..."
  on a page naming Cloudflare. **Not** the `scripts/jsd/` script Cloudflare's
  bot detection adds to ordinary pages (same folder), a Turnstile widget on a
  comment form, or the firewall block ("Sorry, you have been blocked", which
  no one can pass: it stays `Blocked`). A refused plain fetch is a challenge
  when it carries `cf-mitigated: challenge` or a challenge page as the body
  of a 403, 429 or 503 (`FetchedPage.challenge`; Android now lets HTTP errors
  through Jsoup to read them, and checks the content type itself). A real
  challenge page fetched in September 2026 carried all four markers.
- **Waiting it out.** A challenged plain fetch skips the 2 s retry (it would
  be refused the same way). The render never hands back a challenge page: it
  reads the page again after each settle, reports `onChallenge`, and waits
  for the page the check moves on to. The repository's cap grows from 20 s to
  30 s in all (`CHALLENGE_TIMEOUT_MS` / `challengeTimeout`) once a challenge
  has shown by the 20 s mark. A render that got past the check is the page
  itself, so its answer stands: its recipe, or `NoRecipeFound` (not the
  403), which offers "Clip it yourself" as usual.
- **Keeping the clearance.** The `cf_clearance` cookie lives in the web
  view's own persistent store (Android's `CookieManager`, flushed after each
  render; iOS's default `WKWebsiteDataStore`), shared by the render and the
  clip view. It is never handed to the plain fetch: that would be the
  fingerprint trick ruled out below. Instead a host that got past the check
  is remembered for a day (`ClearedHosts`) and its next import renders
  first; a render there that doesn't load falls back to the plain fetch, and
  one that meets the check again goes on as below. A day, because sites set
  how long a clearance lasts (often 30 minutes, up to a year) and guessing
  long only costs a slower first try. **Where:** Android's
  SharedPreferences file `cloudflare_clearances`, iOS's
  `cloudflare-clearances.json` in Caches. **Not backed up** on purpose (the
  backup rules are an include list; Caches is never backed up): the cookie
  it stands for doesn't come with a restore.
- **When it wants a person** (still on the check at 30 s): `ParseError.HumanCheck`,
  never retried or reloaded on reconnect. With no saved copy (the saved copy
  still wins), the import's ViewModel opens "Clip it yourself"'s web view on
  the page **in the import's place** (`clip?url=…&check=true`; iOS
  `.clip(url, check: true)`), with a note, "This site wants to check you're
  human. Tick the box and the recipe will open.", no clip toolbar and Done
  off. The view reads the page's HTML after each load settles and every 2 s
  after, and hands it to `RecipeRepository.importPage`: the same parsers,
  saved like an import (a full free library shows the clip's prompt, whose
  Unlock keeps the recipe read). A recipe opens as an ordinary import,
  replacing the clip view; the check still showing keeps waiting. While
  waiting, the view lets the page move within its own site (the check posts
  back to the page with a token), which clipping otherwise blocks.
- **Past the check with no recipe:** the same view turns into "Clip it
  yourself" on that page, with a note, "The check passed, but this page has
  no recipe the app can read, so it's open here: select the recipe." Like
  Reddit's (#213) there's no Try again there.
- **Unchanged:** a saved copy opens first; `Offline`, timeouts and other
  `FetchFailed` keep their screens; a 403 that isn't Cloudflare's keeps the
  retry and today's flow; Reddit (#213). "Update from source" meeting the
  check says so in its snackbar (`error_human_check`). The iOS share
  extension has no render (#19), so there a Cloudflare block is the usual
  card; a Safari share carries Safari's page (#35), which is past the check.
- **Not doing** (owner): faked browser or TLS fingerprints, user-agent
  tricks, paid scraping proxies.
- **Tried for real** (September 2026): Serious Eats, Budget Bytes, Simply
  Recipes and Half Baked Harvest answered curl with `cf-mitigated:
  challenge`; the app's Jsoup fetch (from the development machine) got
  `Blocked(403)` marked as a challenge. From the iOS simulator and the
  Android emulator, the platforms' own fetches and web views got Budget
  Bytes' recipe without a check (Cloudflare judges the client, not just the
  site), so which sites check a phone, and whether its web view passes by
  itself, needs a real phone.

## Background timers and saved cook progress (#10)

Built on both platforms. Before this, cook progress, timers and the chosen
servings were in memory only, and a timer's alert depended on the process
surviving: under Doze or a low-memory kill the beep never came, and when it
did there was nothing to tap.

- **What is saved.** Two nullable columns on `recipes` (Room version 6, iOS
  `user_version` 5): `cookState`, a JSON `CookProgress` (cook mode on, current
  step, done steps, and each timer's total, remaining seconds and, while it
  runs, its wall-clock deadline `endsAt`), and `servingsTarget` (null = the
  recipe's own yield). One column rather than a timers table, so undo-delete
  and the re-share upsert carry it with the row. `ingredientsExpanded` and
  `alerted` are screen state and aren't saved.
- **Every cook action writes, in order.** Start, exit, select, done, and
  timer start, pause, resume and reset each save the whole `CookProgress`
  through one queue (Android: a queue drained by one job, each write
  `NonCancellable`, drained again in `onCleared`; iOS: a chain of tasks that
  hold the repository, not the ViewModel), so rapid taps can't land out of
  order and a tap just before leaving still lands. A running timer is saved
  by its deadline, so ticks never write. A `Channel` was tried first: it
  drops an element handed to a receiver that is cancelled before running,
  which is exactly the last tap before leaving.
- **Re-share.** Cook progress is kept only if the steps are unchanged (its
  indexes point into them, like ticked ingredients); the chosen servings are
  always kept.
- **Restoring.** On opening, a timer whose deadline is still ahead resumes
  from it (it kept counting while closed) and its alarm is rescheduled,
  which also recovers one lost to a force-stop. One whose deadline passed
  shows finished and already alerted: the background alert announced it, so
  no stale beep on reopening. Indexes past the last step are dropped.
- **Android alert.** One `AlarmManager` alarm per running timer
  (`timers/AndroidTimerAlarmScheduler`), to `TimerAlarmReceiver`, which posts
  a notification (channel "Cook timers", category alarm; a tap opens the
  recipe in cook mode through `recipe/{id}?cook=true`) only if the database
  still has that timer running with that deadline, so a reset timer, a
  deleted recipe or a re-share that changed the steps never rings, and only
  if that recipe isn't on screen, where the in-app beep sounds instead. A
  timer that reaches zero in the app keeps its alarm, since cancelling would
  only race it. `TimerBootReceiver` reschedules after a reboot or an app
  update.
- **Exact alarms: `setAlarmClock()` when allowed, else
  `setAndAllowWhileIdle()`. No Settings prompt (owner's decision).** Issue #10
  assumed `setAlarmClock()` needs no permission. It does: apps targeting API
  31+ need `SCHEDULE_EXACT_ALARM`, and for apps targeting 33+ Android 14
  denies it by default until the user allows "Alarms & reminders". So the
  alert is exact on Android 7–13, and on 14+ only if the user has allowed it
  (also the status-bar alarm icon then). Otherwise it is inexact, still fires
  in Doze, and can be minutes late. `USE_EXACT_ALARM` is Play-restricted to
  alarm and calendar apps and isn't used. A foreground service with a live
  countdown was the alternative; rejected as a permanent notification plus a
  `FOREGROUND_SERVICE_*` type that Play reviews.
- **Notification permission.** `POST_NOTIFICATIONS` (API 33+) is asked the
  first time a timer starts, once (remembered in its own preferences file,
  `permission_prompts`). Refused, the timer still runs and the in-app beep
  still sounds while the recipe is open. On iOS, notification authorisation
  is asked on the first timer start in the same way.
- **iOS alert.** A local notification per running timer at its deadline
  (`NotificationTimerScheduler`). With no receiver to re-check the database,
  opening a recipe replaces all its pending alerts with its running timers',
  and pause, reset and delete remove them. `NotificationRouter` opens cook
  mode on a tap and suppresses the banner while that recipe is on screen.
  Known gap: deleting a recipe from History with a timer running leaves its
  pending notification.

## iOS: importing inside the share extension (#19)

The extension used to find the link and open the app with
`recipeclipper://import?url=…`, reaching `UIApplication` through the
responder chain. That was an App Review risk (guideline 2.5.1), and iOS 18
had already broken it once. Now the extension does the import itself.

- **Same code, not a copy.** The extension compiles the app's `Data/` (minus
  `UserDefaultsAppPreferences`), `ShareImport/`, the theme and the button
  styles through dual target membership in `project.yml`. A framework target
  was the alternative. It would have meant `public` on most of the data layer
  for no gain at this size.
- **One database, two processes.** The SQLite file and the settings suite
  moved to the App Group container. Nothing was migrated: #40's new bundle
  IDs had already started everyone on an empty container. WAL plus
  `busy_timeout` let both processes write. Migrations re-check
  `user_version` under the write lock. The app re-queries on becoming active,
  because its observers only hear its own writes. A Darwin notification
  would also cover the iPad side-by-side case, but that wasn't worth it yet.
- **The card, not the recipe.** After saving, the extension shows a compact
  "Saved" card that dismisses itself after 2.5 s. Errors keep the app's
  causes and copy, with Try again, and reload on reconnect. Showing the whole
  recipe in the extension was the other option the issue named. It would
  have meant the reading view, scaling and conversion inside the extension's
  memory budget. The app doesn't jump to the recipe on its next launch
  either, since that would surprise someone who opens it hours later.
  "Continue cooking" already puts it one tap away. (The one hand-off, bounded
  to minutes, is a Reddit post Reddit won't let the app read, #213: see
  "Reddit posts (#11)".)
- **What only a device shows.** The memory ceiling (about 120 MB, which the
  simulator doesn't enforce). And iOS kills a suspended process that holds a
  file lock in a shared container (`0xdead10cc`). Writes are short
  transactions, so this shouldn't bite, but it has to be watched for on a
  device.

## iOS: reading the shared page from Safari instead of fetching it (#35)

Needed #19 (the extension importing itself) first: a rendered page (about
250 KB for a Smitten Kitchen recipe) is too big for the
`recipeclipper://import?url=…` deep link, so parsing it has to happen where
it's saved.

- **Safari's JavaScript preprocessing file**, not a second fetch. `Preprocessing.js`
  defines `ExtensionPreprocessingJS.run`, which hands `completionFunction`
  `{url: document.URL, html: document.documentElement.outerHTML}`; Safari runs
  it against the page it's sharing, before the extension launches. Info.plist
  (via `project.yml`) names it (`NSExtensionJavaScriptPreprocessingFile`) and
  adds `NSExtensionActivationSupportsWebPageWithMaxCount` to the activation
  rule alongside the existing URL and text rules; only Safari acts on it, so
  Chrome and other apps keep sharing just the URL.
- **The page never leaves the device.** It's parsed in the extension's own
  process with the same pure parsers the fetch uses
  (`BlogRecipeSource.parse(html:url:)`); nothing is sent anywhere for this.
- **Bypasses the fetch, not just the block-and-retry.** `SharedItems` prefers
  the preprocessing result (it carries both the rendered HTML and the URL
  JavaScript actually resolved) over the plain URL/text attachments every
  other app sends. `RecipeRepository.importFromUrl` grew a `renderedPage`
  parameter: given one, it parses it directly and skips the fetch, its retry
  and the off-screen-browser fallback (#36) entirely; only when that page
  holds no recipe does the ordinary fetch run, exactly as if nothing had been
  given. A default-argument extension method keeps every other caller
  (`RecipeViewModel`, the tests) at the one-argument call they already had.
- **Android has no equivalent.** Chrome's share sheet gives apps only the
  URL, never the rendered page; the off-screen-browser fallback (#36) is
  Android's route to a page that needs JavaScript to reveal its recipe data.
- **Checked so far:** the plumbing (SharedItems reading a real property-list
  item provider shaped exactly as Apple delivers preprocessing results; the
  repository using the page's HTML and cleaned URL without fetching; a page
  with no recipe falling back to fetch; the view model passing the page
  through). Safari end to end on a device, against a site that blocks the
  plain fetch, is still owed.

## Export and import (#26)

The owner's decision: import **merges, never replaces**, and deletes nothing.

- **One file, versioned.** `format: "recipe-clipper-backup"`, `formatVersion: 1`,
  then `recipes`, `lists` and `memberships`. The canonical example is
  `shared/fixtures/backup/backup-v1.json`; both platforms' tests decode it and
  plan the same merge from it. Readers ignore keys they don't know, so a later
  feature (the meal plan, #46; sync, #53) adds a section or a field without a
  version bump. Bump only when an older app would *misread* a newer file; an
  older app refuses a newer version (`NewerVersion`) rather than half-import it.
- **Stable ids.** Recipes and lists got a `uid` column (Room 4 / iOS
  `user_version` 3, backfilled with random UUIDs), and the file names records by
  it; memberships refer to uids, never row ids. A re-share keeps a recipe's uid
  and a rename keeps a list's, so a list renamed on one phone still finds itself
  on the other, and sync can build on the same identity.
- **What's left out:** cached photos (only `imageUrl`), and cook progress
  (current step, timers, chosen servings), which is a moment in one kitchen
  rather than part of the recipe, even once #10 persists it.
- **Merge rules** (`BackupMerger`, pure, the same on both platforms):
  recipes match by the cleaned `sourceUrl`; a recipe already here keeps its
  content, ticks and last view, gains the imported memberships, and gains the
  imported note only if it has none. Favorites maps to Favorites by
  `isFavorites`, never by name, and a user list called "Favorites" stays a user
  list. Other lists join the same uid, else the same trimmed, case-insensitive
  name, else they're created after the existing lists. Memberships are
  insert-or-ignore, so an existing `addedAt` stands.
- **History cap: free slots, not a cull.** The issue suggested running the
  normal cull after import, but that could delete the user's own older history,
  which "never delete" forbids. So listed recipes always come in, and unlisted
  ones fill only the places free under 50 (most recently viewed first); the rest
  are skipped and counted in the summary.
- **One transaction.** Any failure (a bad file, a database error) writes
  nothing, and the Settings screen shows the cause.
- **Every merge key, in one place** (CLAUDE.md's summary until September 2026,
  covering the sections added by later issues): one versioned JSON file
  (`shared/fixtures/backup/backup-v1.json`; unknown keys ignored); with photos
  (#116) a `.zip` of that JSON (`backup.json`) and `photos/*.jpg`, STORED
  (`BackupArchive`, `backup-v1-photos.zip`). Either imports. Import merges,
  never replaces or deletes: recipes by cleaned `sourceUrl`, Favorites by
  `isFavorites`, other lists by uid then trimmed case-insensitive name;
  unlisted recipes only fill free history slots (free tier: places under 20,
  counting every recipe; unlocked: all come in); pantry items by uid then name
  and language (what's here stands); grocery items by uid; meal types by
  `builtInKey`, else uid, else user-type name; planned meals by uid, a recipe's
  only if its recipe is here after the import; menus by uid, whole, their meals
  by the plan's rules; photos by uid, only with their picture, their recipe
  coming in like a listed one (a cooking marked with no photo, #173, the same,
  needing no picture). Rules in `BackupMerger`.

## Shared tables, native logic (#9)

Every feature was built twice and kept at parity by hand, and the language
work (#12–#16) would have added a word table per language, written twice. The
owner's decision on #9: stay native on both platforms (no Kotlin
Multiplatform, which would cost iOS its no-dependency property and need
multiplatform replacements for Jsoup and org.json), and move the data, not the
code. The tables are JSON under `shared/tables/`: `url.json` (tracking
parameters) and, per language, `en/densities.json`, `units.json`,
`timers.json`, `temperature.json`, `yield.json`, `ranges.json`,
`sections.json` and (since #48) `names.json`. Each has a `schemaVersion` and an `about` saying how the code
reads it.

- Android adds `shared/` as a `main` Java resource directory (`:core`'s since #238), so the pure model
  code reads the tables with `getResourceAsStream`: no `Context`, and the JVM
  tests read exactly what the APK ships. iOS bundles the folder as a folder
  reference (`project.yml`) and reads it from `Bundle.main`.
- Word lists are regex fragments where the code builds a regex from them, so
  the patterns come out character for character as before; symbols (dashes,
  degree signs, the F and C letters) stay in the code, being no language's.
- A missing or malformed table is a build mistake, so both loaders fail loudly.
  `SharedTablesTest(s)` load every table and check each on-disk file is
  covered; `DifferentialCorpusTest(s)` passed unchanged across the move.
- Left in code as English by #9, then moved to the tables by #14:
  `IngredientScaler`'s "plus"/"and" continuation and the words the app writes
  out (`StepTimers.label`'s "hr" and "min", the "h"/"m" of times).

## The recipe's language picks the words (#14)

Every piece of text understanding was English, and merging languages into one
set of words would collide ("C" is a cup in English and Celsius elsewhere). So
each language has its own folder, `shared/tables/<language>/`, and a recipe is
read with its own language's tables only.

- **The language is the recipe's, never the phone's:** JSON-LD `inLanguage`
  (a tag, or a schema.org Language's `alternateName`), else the page's
  `<html lang>`, else English. Detection from the recipe's name and ingredient
  lines (`language.json`'s `detect` words: a language needs at least 3 hits and
  more than twice the runner-up's) fills in when nothing is declared, and **the
  owner's decision on #14: when the words clearly say another language, they
  beat the declared one; ambiguous words keep it.** #15's survey found
  `inLanguage` on 1 site in 25 and `<html lang>` on nearly all, but wrong on
  one (mulherportuguesa.com says `en` on Portuguese pages).
- **Detection knows more languages than the app reads.** de, es, fr, it and pt
  have a `language.json` only (`LanguageWords.DETECTED` vs `SHIPPED`), so a
  German page labelled `en` is recognised as German and shown as written,
  rather than read with English rules. The words avoid ones the languages
  share ("de", "sal", "sopa"), and German's `EL`/`TL` are case-sensitive
  (Spanish "el").
- **A language with no tables leaves everything as written:** no scaling,
  conversion, temperature rewrite, timer or servings stepper, no phrase times
  (ISO times still read), no condensed-section skipping. English rules on a
  German line would scale "2 bis 3 Eier" to "4 bis 3 Eier".
- **Stored:** `recipes.language`, the normalised tag ("en-us"), because the
  declared language can't be rebuilt from what was stored (Room version 5, iOS
  `user_version` 4, after #26's uids took 4 / 3). Recipes stored before are NULL and are detected from their
  words when shown; a re-share fills it in. Lookup is by primary subtag, so a
  regional table (fr-CA's 250 ml `tasse`) can come later without a migration.
- **API:** `LanguageWords` (both platforms) loads a language's tables and
  caches each parser's compiled patterns per language. Every parser takes a
  `words` argument defaulting to English, so the differential corpus and every
  English caller are byte-for-byte unchanged; nil means "no words".
  `IngredientName` and `IngredientRendering` (#48) take the recipe's words too,
  and `names.json` is a per-language table; no words means no name, so a line
  is never matched to the pantry by another language's rules.
- New tables: `amounts.json` (mixed-number joiners, "2 and 1/2", which make
  the quantity pattern itself per language; compound joiners; size words),
  `durations.json`
  (phrase times and the "h"/"m" written back), `language.json` (detection
  words); `timers.json` gained each unit's button label. Symbols (dashes,
  degree signs, `%`, `cm`/`mm`, `+`, the fraction slash `⁄`) stay in the code.
  `IngredientScaler.parse` holds no words: it drops whatever word the language's
  quantity pattern already allowed between the whole number and the fraction.
- An empty word list never matches (`SharedTables.alternation` gives `(?!)`),
  so a language that lacks, say, range words can't turn an empty alternative
  into a match everywhere.

## Ingredient names and shared rendering (#48)

The first building blocks of the meal plan, pantry and groceries (#46), pure
and on both platforms.

- `IngredientName.of(line)` gives the ingredient's name in a line
  ("2 large eggs, beaten" is "eggs"), or null when it can't tell. It reuses
  the parsing that already reads amounts: `IngredientScaler.LEADING` and
  `NOT_AN_AMOUNT`, the converter's unit, continuation ("plus 2 tbsp") and
  slash-measure regexes, and the density table's `stripParentheses` and
  `headPhrase`, made `internal` with no change in behaviour. Its own English
  words (sizes and containers dropped from the front, preparation words from
  the end, the phrases a name ends before, and the conjunctions) are in
  `shared/tables/en/names.json`.
- It answers "which ingredient", never "how much", and prefers no name to a
  wrong one: a heading, a leftover digit ("juice of 1 lemon") or two
  ingredients ("salt and pepper", "butter or margarine") give null, unless the
  conjunction is inside a density-table alias ("half and half"). There's no
  singulariser, only listed pairs: "eggs" and "egg" are one name because
  `names.json` lists the pair (#191, below); a word that isn't listed is only
  itself.
- `IngredientName.matches(a, b)` is the density table's end-of-name rule
  (`IngredientDensities.endsWithName`, now shared with `find`): "unsalted
  butter" matches "butter", "butter beans" doesn't. Both sides go through
  `headPhrase`, so a typed "Butter" works.
- `IngredientRendering.render(lines, factor, system, convertLiquids)` is
  `RecipeViewModel`'s old private `render`, moved unchanged (scale, then
  convert with the original line's decimal separator), so the week's
  shopping view (#46) shows lines exactly as the reading view does.
- The differential corpus pins both: every `Ing` row now ends with the
  Kotlin's `IngredientName.of`, and its rendered columns are computed through
  `IngredientRendering`.

## Reading recipes in de, es, fr, it and pt (#15)

The five languages #14 could only detect now ship every table, filled from
real lines on 25 sites (September 2026). Each rule below is pinned by real
lines in the differential corpus, with a `lang:` argument.

- **Dot thousands** (#76) are an `amounts.json` flag, on for de, es, it and
  pt and off for fr (which writes a space) and en. Only a dot before exactly
  three digits is a separator, so generator output like "0.5 TL" stays a
  decimal; a number that fits neither ("1.500,5") leaves the line alone.
- **Mixed numbers** (#75): each language's "and" is in `mixedJoiners`
  ("2 e 1/2 xícaras"). A half in words ("1 taza y media", "2 e meia") can't
  be read by the pattern, so `spelledHalves` keeps those lines as written.
- **Units without one size** are `MeasureUnit.VARIES`: French "tasse" (a
  Québec cup, a vague French one), German "Tasse", Italian "tazza",
  Portuguese "colher (café)" and bare "colheres". They scale, so Ricardo's
  "250 ml (1 tasse)" doubles as a whole, but never convert. Spanish "taza"
  and Brazilian "xícara (chá)" are the 240 ml cup their sites mean. `cl` and
  `dl` are metric units (French and Italian write them constantly); Metric
  leaves them as written.
- **Compounds and head-first names.** The density table still matches whole
  trailing words. German compounds are listed whole where safe
  ("weizenmehl", "puderzucker"); anything else ("Mandelmehl") stays as
  written rather than matching "mehl". In the Romance languages the head
  noun comes first, so "farine de riz" ends in "riz" and matches nothing,
  which is the safe side. Elided articles ("d'huile d'olive") aren't
  undone, so those lines keep their units in Ounces.
- **Spanish "o"** is a range word: "1 o 2 minutos" and "160 o 165 °C" are
  alternatives read like a range (both ends scale and convert), which
  fixed "160 o 325°F".
- **Whole words by letters**, not `\b`: unit words end with `(?!\p{L})` and
  yield words use letter lookarounds, because the JDK, Android's ICU and
  NSRegularExpression disagree on `\b` beside accented letters.
- **Timers.** A number after a colon is a clock time ("1:30 Stunden" gave
  a 30-hour timer), so it gets none. Bare degrees ("180 Grad", "165°") stay
  as written: German turns trays "um 180 Grad", French writes alcohol
  strength in degrees.
- **Left as written, on purpose:** GialloZafferano's trailing amounts
  ("Burro 100 g"), which can't be read without a guess.
- **Not handled yet:** French space thousands ("1 500 g", not seen on a site
  yet, would scale as "1"). Totals in parentheses after the name ("¾ de taza
  de queso crema (180 g.)") were fixed by #63, below.

## Editing a recipe, and typing one in (#29)

- **Owner's decision:** an edited recipe is never auto-refreshed. Re-sharing
  its link opens the user's version; an explicit "Update from source" in the
  overflow menu fetches the site's, after a warning that the edits will be
  lost. The same rule covers #37's hand-clipped recipes.
- **One schema step shared with #37** (Room 6 → 7, iOS `user_version` 5 → 6):
  `contentOrigin` (`PARSED` | `EDITED` | `CLIPPED` | `MANUAL`, stored by name,
  default `PARSED`) plus a nullable `editedAt`. `EDITED` is its own value, as
  the owner chose on #37, so "is this the user's version" is one column
  (`contentOrigin != PARSED`); `editedAt` records when, and an edited clip
  stays `CLIPPED`. An unknown name, from a newer app, reads as `EDITED` so it
  is never overwritten.
- **The re-share check comes before the fetch,** not only in the upsert: the
  user's version is opened with no network at all, so it opens offline and
  never costs a timeout. The DAO's upsert also refuses to overwrite it unless
  told to (`replaceUsersVersion`), so a race can't lose an edit.
- **Manual recipes keep `sourceUrl` as the key** with a synthetic
  `manual:<uuid>`, rather than a nullable column and a second unique index.
  `UrlCleaner` leaves anything without `://` alone, `SourceDomain` finds no
  host, so the credit, Open original and Report hide themselves, and the
  export and import merge them by that key like any other recipe.
- **Saving an edit reopens the recipe** (the edit screen and the recipe screen
  under it are replaced by `recipe/{id}`), rather than the recipe screen
  reloading itself, so it can't show the copy it loaded before.
- **In short** (CLAUDE.md's summary until September 2026, #103's `EXTRACTED`
  included): Editing is its own screen, from the recipe overflow menu (Edit,
  then "Update from source" for an edited or clipped recipe with a link,
  behind a warning, then Delete): name, yield, three times, ingredients and
  steps one per line, a photo link. Saving needs a name plus ingredients or
  steps (the parsers' rule); nothing typed is converted or guessed.
  `contentOrigin` (`PARSED` | `EDITED` | `CLIPPED` | `MANUAL` | `EXTRACTED`, by
  name; an unknown name reads as `EDITED`) and `editedAt` (the last saved edit).
  Anything but `PARSED` or `EXTRACTED` (#103) is the user's: a re-share opens it
  without fetching and only counts as a view. "Update from source" is the one
  way back: it fetches, replaces the content, keeps the id, note and lists, and
  sets `PARSED` and no `editedAt`; a failure changes nothing. An edit makes
  `PARSED` into `EDITED`; the other values stay. A typed-in recipe is `MANUAL`
  with a synthetic `sourceUrl` of `manual:<uuid>`: never fetched or cleaned, and
  with no host there is no source credit, Open original or Report. Both fields
  go into the export file.

## Reading recipes in Japanese (#16)

Probed in September 2026: 6 of 7 big sites (Cookpad, Delish Kitchen, Rakuten
Recipe, Nadia, Orange Page, Ajinomoto Park) expose a schema.org Recipe in
JSON-LD to a plain fetch; Kurashiru is a client-rendered shell (only the
rendered fetch, #36, could see it). `ja` ships every table, and the real lines
and steps from those six sites are in the corpus with `lang: "ja"`.

- **Name first, amount last.** Every site writes "鶏もも肉 2枚（約700g）":
  the name, one space (Orange Page: an ideographic space and a space), the
  amount. `amounts.json` `amountAfterName` sends a language's lines to
  `TrailingAmount` instead of the leading-number scaler. The amount is the
  text after the last space, read only when it is: an optional `beforeNumber`
  word (各, 約, 大, 中, 小), an optional unit, a number or range, an optional
  unit, then text with no digit in it, which may hold one measure in brackets.
  Anything else stays as written. A name ending in a digit ("大さじ2 1/2")
  may have lost part of its amount to the space, so it stays too.
- **Units either side of the number:** 大さじ2 and 2カップ, and cookbooks'
  カップ1/2. 大さじ/小さじ are the 15/5 ml spoons; カップ is `CUP_200`
  (200 ml) and 合 `RICE_CUP` (180 ml), new `MeasureUnit`s, never the US cup.
- **A measure in brackets scales with the amount:** "1/2缶（200g）",
  "2個（240g）", "1/4個分(50g)". Unlike English "1 can (14 oz)", Japanese
  sites write the weight of the amount itself, so a package reading would
  halve it wrongly. Converting still needs a unit: a counter (個, 本, 枚, 缶)
  never converts, even with a weight beside it.
- **Cookpad's 大3 / 小1/2** (大さじ, 小さじ) scale but never convert: 大 and 小
  also mean "large" and "small" ("大1/6個"), and scaling the number is right
  either way.
- **Left as written:** 少々, 適量, お好みで and other amounts with no digit;
  kanji numerals ("一丁"); a half in words straight after the number ("1半丁",
  `spelledHalves`); sizes ("ねぎ 10cm"); headings ("肉だね", "A（混ぜる）").
- **No spaces between words** (`language.json` `spaced: false`). Timer units
  need no word boundary ("5分煮る"); 分 before の, 半, 目 or 割 ("2分の1",
  "1分半", "8分目", "5分割") and 時間 before 半 are not times. The density
  table matches the end of a name by character ("有塩バター" is バター), so
  compounds that would match wrongly are skip entries (ポン酢, 黒砂糖), and
  there is no bare 油 (醤油) or 粉 (片栗粉, パン粉). `IngredientName` takes the
  text before the amount, dropping group markers (☆ ★ A 【A】, glued to the
  name), asides in brackets and quote marks; a name with ・ 、 or "or" is two
  ingredients.
- **Full-width digits and letters** ("１００ＣＣ", "２０分", yields "４",
  "５〜６") are read as half-width. The mapping is one UTF-16 unit for one, so
  offsets found in the read text splice back into the line as written: the
  scaled number is half-width, the rest keeps its width. Temperatures read
  half-width digits only.
- **℃** is a Celsius word in `temperature.json`. A bare "180度" names no
  scale, so it stays as written, as bare degrees do in de, fr and it.
- **Yields:** "2人分", "2〜3人分" (the wave dash is a range word), and
  Ajinomoto Park's "2(servings)", which would otherwise read as "Makes".
- **Not handled:** "1時間半" gives no timer rather than a guess; a scaled
  amount converted in Metric is written "30 ml" with a space, as in other
  languages.

## Bottom tab shell (#47)

The navigation shell for #46 (weekly meal plan, groceries, pantry), landing
dark behind a flag so the shipped app is unchanged until the Week tab (#49)
has something in it.

- **One flag.** Now `mealPlan` in the feature-flag system (#87, below),
  default off in both build types. Android's `AppShellTest` calls `AppShell`
  directly with `tabsEnabled = true`; the iOS UI tests turn it on through the
  flag store.
- **Where the bar shows is an allow-list, not a deny-list**
  (`tabBarRoutes` on Android; `.toolbar(.hidden, for: .tabBar)` set only on
  the recipe destination on iOS). A new screen is bar-less by default, so
  forgetting to update the list fails safe (no bar) rather than leaking the
  bar onto the recipe reading view or cook mode.
- **Each tab is its own nested graph** (Android: `navigation(route =
  tab.route, ...)` under one `NavHost`, with `popUpTo`/`saveState`/
  `restoreState` on tab switch; iOS: one `NavigationStack` per `TabView`
  case). Recipes' graph is shared, byte-for-byte, between the flag-off
  `RecipeNavHost` and the flag-on shell's Recipes tab, so there are not two
  copies of the Home stack to keep in sync.
- **A share always lands in Recipes,** even mid-import from another tab:
  the router/nav controller switches tabs first, then pushes the import
  route on top of whatever the Recipes stack already held — so a share
  during, say, browsing Pantry doesn't lose the user's place there.
- **Choosing the open Recipes tab again goes back to Home**, matching both
  platforms' tab-bar convention, rather than a no-op.
- **Groceries/Pantry are `ComingSoonScreen` placeholders** (Week was one
  until #49, Groceries until #50), not simply absent tabs: they show the tab's name and one line on what it will hold,
  so the shape of the eventual app is visible to whoever flips the flag on,
  without implying anything is broken.

## Week meal plan (#49)

The first real tab of #46, still behind the #47 flag.

- **A day is a local epoch day** (`PlanDays`, both platforms, the same
  arithmetic): whole days since 1970-01-01 on the phone's calendar, stored in
  `meal_plan_entries.day`. A week is seven consecutive integers, "today or
  later" is one comparison in SQL, and a plan doesn't drift when the clock or
  zone moves. No `java.time` (API 26+; minSdk is 24): the arithmetic is plain
  integers, and labels are formatted at midnight UTC with a UTC formatter so
  the local zone can't show the day before.
- **The week starts today** (owner's call, 2026-10-01, #232, replacing #49's "the locale's
  first day"): "so if today is thursday the first date shown is today thursday". Weeks are
  seven-day blocks counted from today, today+7k … today+7k+6 (`PlanDays.blockStart`), so
  "This week" is Thursday to Wednesday when the app is opened on a Thursday. Today comes from
  the `PlanCalendar` seam, so ViewModel tests pin it; on a new day (the app back in front, or
  "Today" tapped) the blocks start from the new today. The locale's first day
  (`Calendar.getInstance().firstDayOfWeek`, iOS `Calendar.current.firstWeekday`, Sunday = 1)
  still lays out the month grid and anchors menus' weekdays.
- **One scroll of days** (#232): the Week is one vertical lazy list, a section per day (its
  meals and "+ Add"), opening with today at the top; plain scrolling moves freely. The
  header stays put: ‹ range › names the block holding the day at the top ("Thu, Oct 1 –
  Wed, Oct 7", both ends with their weekday), and the week's actions use that block. ‹ and ›
  scroll (animated) so the first day of the block before or after the one at the top is at
  the top, so after free scrolling an arrow lands on a block boundary; a second press during
  the scroll counts from where it's going. Away from this week, "Today" (the old "This
  week" place; the owner can veto) scrolls back.
- **Bounded days, a window of meals** (#232). The list's days are fixed at today − 365 to
  today + 730, so indices are stable and a jump is instant; a month tap beyond them re-centres
  the same span on that day. Only the meals of a 63-day window (four weeks either side of the
  block at the top) are held, read with the existing `observeDays` range query and replaced,
  never added to, when the block next to the top's would leave it: one query per four weeks
  scrolled, and memory stays flat however far the list goes. The scroll position is the
  view's own state; the ViewModel owns the days, the window, the block math and one-shot
  `scrollTo` requests (an arrow, "Today", a new day, a month tap), and ignores the days a
  requested scroll passes until it lands.
- **Meal types are a table** (`meal_types`, owner's call): Breakfast, Lunch,
  Dinner and Snack are seeded with a `builtInKey` that survives a rename, as
  `isFavorites` does for lists. Any type can be renamed and reordered; only
  the user's own (`builtInKey IS NULL`, a guard in the SQL) can be deleted.
  Deleting one moves its meals to Dinner in the same transaction, never
  deleting them. Entries reference a type by id. Dinner is the default for a
  new meal.
- **Stable ids for #53:** both new tables carry a unique `uid` (#26's
  convention) and an `updatedAt`, set on every write. The integer `id` stays
  the key that the foreign keys use.
- **A recipe's meals cascade with it.** Deleting a recipe removes its planned
  meals, and undo restores them (with its lists). A planned meal whose meal
  type went meanwhile is skipped rather than failing the whole restore.
- **The cull rule** (product rule change): a recipe planned for today or
  later is never culled and doesn't count toward the 50, like a recipe in a
  list. One planned only for past days is ordinary history again. "Saved"
  still means "in a list". In the SQL the plan subquery filters out NULL
  recipe ids (notes), because `NOT IN` a set holding a NULL is never true and
  would silently stop the cull. `today` is passed in by the repository; other
  callers default to protecting nothing.
- **Moving is long-press → Move** on both platforms: the same day strip (today
  and the next 13 days, #232) and meal types as "Add to plan". Drag and drop
  across day sections was left out, because it needs experimental Compose
  APIs and gives no parity with iOS for the same result.
- **Opening a planned recipe uses the planned servings for that visit only.**
  It isn't saved as the recipe's chosen servings unless the cook changes
  them there. A planned recipe opens on the Week's own stack
  (`week/recipe/{id}?servings=`), so Back returns to the week.
- **"Add to plan"** is in the recipe menu (first, above Edit) only while the
  flag is on. It's a deliberate act with a button, unlike save-to-list's
  instant ticks: a day and a meal type have to be chosen first.
- **"+ Add" on a day** is one sheet: a meal type, then a recipe from history
  (searchable, the same query as History) or, once something is typed, that
  text as a note. Planned servings start as the recipe's own yield.
- **In the export file** (#26) since the plan joined it after groceries and the
  pantry, with no `formatVersion` bump: see the Pantry section's Export note.
- **The screen in short** (CLAUDE.md's summary until September 2026, #52's
  extras included; rolling since #232): a fixed header, ‹ range › naming the
  seven days from today (or the block at the top), and "Today" when away from
  them; under it one scroll of day sections, opening on today, from a year back
  to two ahead; ‹ › snap to the previous or next block. Meal rows (type,
  thumbnail, title, servings, or a note), "+ Add" per day (a meal type, then a
  recipe from history or the typed text as a note). Long-press: Move (the day
  strip and meal types) or Remove (undo snackbar). A tapped recipe opens at its
  planned servings, for that visit only. "Month" beside the title swaps in a
  month grid (locale weeks, a dot on planned days; a tapped day goes to the top
  of the days). "Save week as menu…" / "Apply a menu…" (Week menu): a named copy
  of the block at the top; applying adds its meals on the same weekdays within
  that block, never replacing what's planned; rename and delete in the menus
  sheet. "Share as calendar file" (Week menu): the block at the top as an .ics
  of all-day events, never invented times. "Meal types" from the Week menu: add,
  rename, reorder any, delete the user's own. "Add to plan" (recipe menu, first
  item, flag on only): today and the next 13 days, a meal type (Dinner first),
  servings (the yield first), one button.

## Groceries (#50)

The third tab of #46, still behind the #47 flag.

- **One table, room for more lists.** `grocery_items` (Room 9, iOS
  `user_version` 8, a new table so nothing existing changes) holds the text as
  written, a `language` tag, an `aisle` key, `checked`, `sortOrder`, and an
  optional `recipeId` and `plannedDay`, plus #26's `uid` and #53's
  `updatedAt`. `listId` is 1 for now: several lists would add a
  `grocery_lists` table keyed by it, without touching the items. A recipe's
  items outlive it (`ON DELETE SET NULL`): what's on the list is what to buy,
  whatever happened to the recipe. Undo after the recipe went restores the
  items without a source rather than failing on the foreign key.
- **The text is stored as shown, not re-rendered.** A line goes on the list as
  the reading view showed it (scaled, converted), and the week's at each
  meal's planned servings, through `IngredientRendering`. Changing units later
  doesn't rewrite a shopping list someone is already holding.
- **Aisles are a per-language table** (`shared/tables/<lang>/aisles.json`,
  every shipped language, the Japanese one smaller), matched on the end of
  `IngredientName.of`, longest alias first, exactly like the density table:
  "peanut butter" beats "butter", "butter beans" isn't butter. A word in
  `names.json`'s `pluralPairs` matches in either number (#191), so "onion" also
  files "onions"; a plural not listed there is listed here, and two aliases
  that are one once pairs are read as one must share an aisle. The aisle is chosen once when the item
  is added and stored; "Move to aisle…" overwrites it, and nothing reassigns it
  after that. A line with no name (a heading, "salt and pepper") or no words is
  Other. The aisle keys and their order are fixed in code (`Aisle`), since
  their names are UI strings.
- **The item's language.** A recipe's line keeps the recipe's language (#14's
  words read it). A typed item has no recipe, so it takes the phone's language
  when the app ships words for it, else English: the one place the phone's
  language picks the words, because the person typing is the only source.
- **Combining is the "never a confident wrong number" rule** (`GroceryCombiner`,
  pure, both platforms, pinned by the differential corpus's `Groc` rows). Lines
  group by exact `IngredientName` and language (and checked state, so a ticked
  line never hides in an unticked total). A group adds up into one row only if
  every line is one exact amount (no range, no "plus", no second measure in
  brackets or after a slash, no package size) and all are in one family whose
  units convert by exact ratios: g/kg, oz/lb, ml/cl/dl/l (with the 200 ml and
  180 ml Japanese cups), tsp/tbsp/fl oz/cup (3, 6 and 48 teaspoons), sticks,
  or counts whose words after the number are identical, a listed pair's number
  aside ("2 eggs" + "3 eggs", "1 onion" + "2 onions" = "3 onions", #191; not
  "2 large eggs" + "3 eggs"). Grams never meet ounces, cups never meet
  grams, and a bare "oz" is a weight even for milk. The total is written in a
  unit the lines already used, the largest that shows it exactly under the
  scaler's own formatting ("1 cup" + "2 tbsp" is "1 1/8 cup"; 1.25 kg shows as
  "1250 g" because "1.3 kg" would round), followed by the shortest wording any
  line used ("300 g butter" from "200 g butter, softened" and "100 g
  butter"). If no unit shows it exactly, nothing is combined. Otherwise the
  lines sit together under the name, each as written. Japanese lines (amount
  after the name) are never combined. The combined row shows its lines under
  it, so the sum can always be checked.
- **Adding the same recipe again** (the owner's "2 corn, 2 corn, 2 corn"):
  - A note after the amount doesn't stop a total. What follows a bracket or
    slash counts as a note only with no digit, fraction or unit word in it, so
    "(, minced)" (WP Recipe Maker's notes) or an unclosed "(see note" add up,
    while "(about 1 lb)", "(or 2 teaspoon dried)" or "(about a pound)" never
    do: the figure beside the total would be wrong.
  - The same line added more than once that can't be summed is one row,
    "1 lb / 500 g zucchinis × 3": exact, whatever the line says. That holds
    for a line with no name ("salt and pepper") or no language, which groups
    only with the very same line.
  - Lines that sit together are still one row with one tick (the name, its
    lines listed under it without ticks), and a line added more than once is
    listed once, "× 3". Before, each line had its own box, so a repeated
    recipe looked like separate items.
- **Checked and shared.** Ticking a row ticks all its lines; checked
  rows are struck through and dimmed, and sort after unchecked ones in each aisle, though only
  from the next visit: a tick never moves a row while Groceries is shown (#219, in the #146
  section below). Share ("Send list" since #149)
  sends the unchecked rows as plain text by aisle. Delete and clearing the
  checked rows are undoable from one snackbar (one undo at a time, as on the
  Week). "Clear checked" became "Done shopping" (#146, below), and came back in the menu as
  "Clear ticked items" beside "Clear the whole list" (#219).
- **Adding.** "Add to groceries" in the recipe menu and "Add this week's
  ingredients" in the Week menu open the same sheet (Paprika's basket): every
  line ticked, headings (a line ending in ":") and blanks left out, one
  button. It's a deliberate act, like "Add to plan", since the point is to
  untick what's in the cupboard first. "Checking off offers Add to pantry"
  waits for the pantry (#51).
- **In the export file** (#26) with the pantry and the plan: see the Pantry
  section's Export note.
- **The screen in short** (CLAUDE.md's summary until September 2026, with
  #146 and #149): "Add an item", then the list by aisle (unchecked first, as of the visit's
  start: a tick never moves a row until Groceries is opened again, #219); tap
  ticks, and a tick only ticks (#146); long-press offers "Move to aisle…" and
  Delete (undo snackbar); the menu sends the list ("Send list": every unticked
  item as plain text, each naming its recipes in brackets; "Send as file": the
  same items and their recipes as a file) and pastes one. "Paste a list", or
  text with no link shared into the app (Android: the Groceries tab; iOS: the
  share extension's card), opens "Add this list": its lines, all ticked, then
  Add to groceries or Add to pantry, as written. "Add to groceries" (recipe
  menu, after Add to plan) and "Add this week's ingredients" (Week menu) open
  one sheet: the lines as the reading view renders them (the week's at each
  meal's planned servings), headings left out, all ticked except what the
  pantry has (#51), one button. The menu also clears without putting away (#219):
  "Clear ticked items" at once, "Clear the whole list" after asking, each with Undo.
  **"Done shopping"** (#146, shown while anything is ticked): a sheet of the ticked items with
  checkboxes, pantry-tracked ones ticked, the rest not; one confirm restocks or
  adds the ticked ones (only here, never on the tick) and clears every ticked
  line, with one Undo for both. Snackbars only for undo.

## Pantry (#51)

The fourth tab of #46, still behind the #47 flag, with the week's Have/Buy.

- **One table** (`pantry_items`, Room 10, iOS `user_version` 9, new, so nothing
  existing changes): `name` as typed, `language`, optional `quantity` as written,
  an `aisle` key (from the aisle table when added, like a grocery's), `inStock`,
  `alwaysHave`, and optional `purchasedDay` and `expiresDay`, plus #26's `uid` and
  #53's `updatedAt`, and #194's `runningLow` (Room 16, iOS `user_version` 15; see
  its section below). The dates are **epoch days** (named `…Day`, like
  `plannedDay`), not the `…At` millis the brief suggested: a use-by date is a
  calendar day, and the plan already counts days that way.
- **The quantity is never read.** It's a note for the cook ("half a bag"). Matching
  is by name only, so Have means "you have flour", never "you have enough flour":
  the screen says so under "In your pantry". This keeps the pantry inside "never a
  confident wrong number".
- **Have/Buy** (`PantryMatch`, pure, both platforms, pinned by the corpus's `Pant`
  rows): a line's `IngredientName.of` against the item's name by
  `IngredientName.matches`, and only in the same language. "What I need" shows
  which pantry item it matched ("You have butter"), so the cook can check. A staple (`alwaysHave`)
  is never on Buy, in stock or not. A line the app can't name ("salt and pepper",
  a heading) stands alone and is always Buy: nothing is guessed. Lines group by
  exact name (a listed pair's number aside, #191) and language, each shown as written with its recipe and day; nothing
  is added up here (the grocery list does that, when it's exact).
- **"What I need"** is its own screen on the Week's stack (`week/need/{weekStart}`),
  from the Week menu, for the week shown. Its lines come from the same
  `GrocerySources.fromPlan` as "Add this week's ingredients", so both show the
  same text at the same servings. It follows the pantry live. "Add to groceries"
  puts every Buy line on the list through #50's add path, once.
- **iOS rows are keyed `buy-0`, `have-0`, as Android's** (#185): a lazy stack pools every
  `ForEach`'s ids, so bare offsets left the first pantry rows blank (and, the same way, the
  week sheet's later recipes and the month grid's first row).
- **The grocery sheet starts with what the pantry covers unticked** (in stock or a
  staple), so "untick what's in the cupboard" is done for the cook, who still
  sees and can re-tick every line.
- **Ticking a grocery off fed the pantry** at first (a restock with Undo, or an
  "Add to pantry" offer, in a snackbar per tick). #146 replaced that with "Done
  shopping": see its section below. What stays: an untracked item goes in only
  when the cook ticks it, since the pantry holds what the cook chose to track.
- **A pantry item never matches a different ingredient** (owner's decision,
  after #51 shipped a pantry "rice flour" as Have for a recipe's "flour").
  `matches` was the density table's end-of-name rule in both directions, which
  let a compound name meet its head noun ("rice flour"/"flour", "peanut
  butter"/"butter", "coconut milk"/"milk", French "farine de riz"/"riz"). Now two
  names match only when they are equal, or when the longer ends with the shorter
  and **every word before it is a plain modifier**: `matchModifiers` or
  `leadingWords` in `names.json`. Any other word makes it a different ingredient,
  in either direction, and the line is Buy.
  - **Why a list of modifiers, not the density table's `skip` entries.** Skip
    entries only cover compounds someone thought to list ("rice flour" is there,
    "coconut milk" isn't), so an unlisted compound would still read as Have: a
    confident wrong answer. A short list of words known to leave the ingredient
    the same fails the other way: an unknown word is Buy, which the cook can
    re-tick. That's the "never a confident wrong number" side to fail on.
  - **What counts as a plain modifier (English):** salted/unsalted, fresh,
    organic, free range, all purpose, plain, extra virgin, and states of the same
    thing (softened, melted, cold, chilled, sifted), plus the sizes, containers
    and cuts already dropped from a line's name (`leadingWords`: large, can,
    cloves…). So "unsalted butter"/"butter", "all-purpose flour"/"flour", "extra
    virgin olive oil"/"olive oil" and "large eggs"/"eggs" still match.
  - **Deliberately not modifiers:** anything that names a different product on
    the shelf: fat levels ("whole milk" is not "milk", nor "heavy cream"
    "cream"), colours and varieties ("brown sugar", "red onion"), and processing
    that makes a different product ("ground", "dried", "crushed", "minced",
    "smoked"). "salted butter" and "unsalted butter" don't match each other
    either (neither ends with the other). These are conservative calls: widen
    the list only when a real pantry shows a miss.
  - **Other languages:** German gets its leading adjectives (ungesalzene, frische,
    bio…), Japanese its prefixes (無塩, 有塩), since both put the modifier first.
    French, Spanish, Italian and Portuguese put modifiers after the noun
    ("beurre doux"), which the end-of-name rule never matched anyway, so their
    lists are empty: there only equal names, or a size or container word
    before the name, match.
  - Grocery combining and aisles never used `matches`: they compare
    `IngredientName.of` by exact name or the aisle table, so this rule left them
    unchanged. (Since #191 every one of these compares names through
    `IngredientName.key`, so a listed pair's number never matters.)
- **Running low or out puts it on the list** (#146, #194; it was a snackbar offer
  before): "Ran out" or "Running low" adds its name as a typed item, silently,
  and the row shows the basket Groceries tag. Typing a name already in the pantry puts it back
  in stock rather than adding a twin.
- **Expiry**: a badge only (#52 later added an opt-in morning reminder) (Expired before today; the date in paprika from today
  to 3 days ahead), no notifications, as the epic says. Sort by aisle (the
  default) or by expiry (soonest first, undated last), from the menu as radio
  choices.
- **Export (#26)**: `pantry` and `groceries` are new top-level sections, with no
  `formatVersion` bump: older readers ignore them. Pantry items merge by uid, then
  by trimmed case-insensitive name in the same language; what's already here
  keeps its stock, dates and quantity. Grocery items merge by uid only (two "2
  eggs" can be two recipes' eggs), after the list's own items, and keep their
  recipe only if it is on the phone after the import.
- **The meal plan in the export file (#26, #49)**: two more top-level sections,
  `mealTypes` and `mealPlan`, still `formatVersion` 1 (older readers ignore
  them). The plan needs its meal types, so they travel with it.
  - **Meal types:** a seeded one maps to the phone's type with the same
    `builtInKey`, never by name, like Favorites: the file's "Dinner" finds this
    phone's Dinner even if it was renamed "Supper". A user's own type joins one
    here with its uid, else a *user* type here with the same trimmed,
    case-insensitive name, else one earlier in the file, else it's created after
    the types here. A user type called "Dinner" never joins the seeded one.
  - **Entries merge by uid** and go at the end of their day and meal type. A
    note always comes in. A recipe's meal comes in only if its recipe is on the
    phone after the import (matched by link, or written), exactly as a grocery
    keeps its recipe; otherwise the meal is **dropped**, because a meal is a
    recipe or a note, a recipe-less, note-less row would be an empty line on the
    Week, and deleting a recipe already removes its meals. An entry whose meal
    type the file doesn't name goes to Dinner.
  - **A recipe the file plans for today or later comes in like a listed one**,
    the cull's own rule, so a full history can't make the import drop next
    week's dinner. A recipe planned only in the past is ordinary history: it
    takes a free place or is skipped, and its meal is dropped with it.
- **The screen in short** (CLAUDE.md's summary until September 2026, with
  #52, #146, #147, #149 and #194): "Add to the pantry", search, then items by
  aisle (menu: by expiry, radio glyphs), with what has run out in a dimmed "Run
  out" section last; each row shows its quantity as written on the right (no
  button since 2026-09-29) and a "Low" tag while running low; tap for the edit
  sheet (the stock, quantity as written, "Always have", a use-by date, Delete
  with undo), with swipes and a touch-and-hold menu as shortcuts. Expired or within 3 days shows a paprika
  badge; an opt-in 9:00 notification lists what expires today or tomorrow
  (Settings → Pantry). Running low or out puts its name on the grocery list
  silently; the row shows the Groceries tab's basket tag whenever a grocery line names the
  item, in any stock, and tapping that takes those lines off (with Undo). The menu's "Send list"
  and "Send as file" send what's in stock (running low included), never what's out (that is on
  the grocery list already), and "Clear run-out items" removes what has run out, after asking,
  with Undo. **Using up:** cook mode's "Done — finish"
  with ingredients ticked, or a photo added with "I made this" (or "Mark as
  cooked", #173) once its viewer closes (the ticked lines, else all), opens
  "Update the pantry" (never on a
  tick, never on Exit; a recipe's is offered again only 12 h after it was
  confirmed or dismissed, `pantry_use_up`): per matched item, the worked-out
  change ("2 lb → 1 lb", ticked; used up goes out and onto the list) or, when
  it can't be worked out, the lines as written with Keep / Running low (the
  Running low state, onto the list) / Out, Keep chosen. One confirm, one Undo. "What I
  need" (Week menu): the shown week's lines at planned servings, grouped by
  ingredient, "To buy" then "In your pantry", with a note that having some
  isn't having enough; "Add to groceries" adds the To buy lines.

## Clip it yourself (#37)

A page with no recipe data (`NoRecipeFound` from a shared link, never Blocked, Offline or
FetchFailed) offers **Clip it yourself** (and a Reddit post Reddit won't let the app read opens in
it by itself, #213: see "Reddit posts (#11)"): the page opens live in a web view, the user selects
the name, ingredients and steps and taps where each goes, then reviews and saves. The design was
approved as a mock-up (six frames); the owner's nine decisions are in the issue's comments.

- **Error screen:** Try again stays first, outlined; under a hairline, one line of explanation,
  then Clip it yourself (the one filled button), then Report this site (#30) as a text button.
- **Assigning replaces** what a field held, for every field. The toolbar count shows the
  replacement ("Ingredients 4", never "8 + 4").
- **Undo, both ways:** a snackbar Undo after each assignment or clear, and tapping a field's tag
  on the page clears that field (with Undo).
- **Nothing is guessed.** No field is suggested for a selection. A selection splits one item per
  line (`getSelection().toString()` breaks between blocks); a name joins its lines. Serves and
  Total time are typed in Review, optional, never read from the page.
- **Photo:** a Photo button, then **one tap** on the page. An image with a readable `http(s)`
  address becomes the photo (kept as `https`, and checked natively: `WebImageUrl`, #235):
  lazy-loading placeholders (`data:` URIs) are skipped for the real
  address (`data-src`, a `srcset`, the `<picture>`'s sources, inside open shadow roots too).
  Anything else ends the step and says "Couldn't read a picture there. The photo is optional."
  Selecting new text, tapping a tag, or **Skip** (beside "Tap the picture…") ends it too. No
  long-press. Picking used to last until a readable image was tapped: on a Reddit post the
  owner found every other tap swallowed, and the fields disabled again after each assignment
  ("stuck in the photo section"), so now neither the page nor the ViewModel keeps it past one
  tap. A tag is kept inside the page's width (Reddit's backdrop image, drawn wider than the
  page, put its tag outside and pushed the page's own controls off the screen). Android's clip
  snackbars are `Long`, not the Indefinite an action gets by default: "Photo added" that never
  went read as the clip being stuck there.
- **Session draft per URL:** Cancel keeps the draft in memory (`ClipDraftStore`, keyed by the
  cleaned URL; Android also mirrors it into `SavedStateHandle`); reopening restores it with a
  "Draft restored" snackbar offering Discard. Save or Discard drops it. Never on disk.
- **One script, `shared/web/clipper.js`,** injected by both apps (Android as a Java resource, iOS
  from the bundled `web/` folder). The page only reports (selection, tag tapped, image tapped,
  no readable image), and the apps hear its main frame only (#235, "Security hardening");
  native code pushes the draft's marks back with one declarative
  `RC.sync(...)`, so replace, undo and clear all redraw from state. Mark ids come from the draft, so an undo can show a mark
  again. Marks don't survive a page reload (rotation on Android, a restored draft); the draft does.
- **Links to other pages are blocked** in the clip view (redirects and fragment jumps load, and
  so does a page sending itself back to its own path when no one tapped, as Reddit's check does,
  #213; an app's own `intent:` or `reddit:` link never loads), so a clip is always saved under
  the page it came from.
- **Save** upserts on the cleaned URL like an import (same id, note and list membership) with
  `contentOrigin` CLIPPED (#29's column; no schema change), replacing whatever the row held,
  then the recipe replaces both the clip and the error screen in the back stack.
- **Done and Save always answer: they save, or say what's missing** (the owner's S23 on a Reddit
  post, 2026-09-30: "save doesn't work"). With ingredients and steps but no name, Done was greyed
  out with nothing saying why (a Reddit title is hard to select: the selection jumps into the
  header), and in Review the top bar's Done stayed tappable but did nothing, the real Save being
  at the foot of a long list. Now Done is always tappable (bar Cloudflare's check): with lines
  but no name it opens Review, where the name can be typed, and says so; with no lines it stays
  on the page and says what to select. In Review the top bar's button is Save. Save with too
  little says what's missing (`ClipMessage.Missing`); a failed save, a full library (#107) and a
  save under way already said or showed so.
- **A clip is the user's version** (#29's rule): a re-share opens it without a fetch. "Clipped
  by you · host" replaces the domain under the title (Open original stays), and History rows
  say "Clipped by you" (a derived `isClipped` in the summary queries). Update from source is
  always offered for a clip, warning "Replace your clip?"; on failure the clip is kept and the
  snackbar says why. On success it becomes PARSED and the line goes.
- **UI tests** use a fixed local page, never the network: Android's `ClipScreenTest` makes the
  selection by script in a real WebView; iOS's `ClipUITests` taps buttons on the fixture page
  (`UITestSeeding.clipFixtureHTML`) that select by script, since XCUITest can't drag a web
  selection reliably. Either way the app hears it through the page's `selectionchange`.

## Alternatives, second parts and totals after the name (#61, #62, #63)

The #33 collection of real lines found three shapes where the leading amount
scaled and a second amount on the same line didn't, so the line showed two
figures that disagreed. Each is now scaled with the line, or the whole line is
left as written; never half.

- **Sides.** `IngredientScaler` reads a line as sides: the leading amount, then
  any amount after a joining word (`amounts.json` `alternatives`, `additions`,
  `subtractions`, plus the "+" symbol). A joining word counts only outside
  brackets or right after one opens, so "(or 1/2 cup oil)" is an alternative
  and the "or" in "1 can (14 oz or 400 g)" is not. Every side scales, or the
  line stays as written.
- **Alternatives (#61).** The amount after "or" must have a unit: "or 1 tsp
  vanilla extract" scales, "or 2 small onions" and German "(alternativ: 1
  Pck. …)" keep the line as written, since a bare count after "or" is as often
  a size as an amount. "use" is deliberately not an alternative word; King
  Arthur's "(use 1/2 teaspoon salt if you use salted butter)" stays as written
  through the bracket rule below instead. The converter converts each side or
  none; a side with no unit or already in the target units is fine as it is,
  and each side finds its density in its own name, so "melted butter or 1/4
  cup (50g) vegetable oil" is weighed as butter and measured as oil.
- **Second parts (#62).** A part later in the line ("2 large eggs plus 3 large
  egg yolks", "+ 1 cucchiaio") scales, counts included. "minus" straight after
  the unit is a compound whose second part is subtracted when converting; a
  negative or unconvertible result leaves the line as written.
- **Totals after the name (#63).** A bracket after the name is a total only
  when the side is a measure (it has a unit) and the bracket holds nothing but
  an amount: an optional "about" word (`approximately`; "~" and "≈" in code),
  a quantity or range and unit, optionally "/" and a second one, optionally a
  trailing period ("(180 g.)"). A total scales, and when converting it is the
  site's figure, dropped from the line once it has become the amount.
  A count's bracket ("4 Apfel (ca. 800g)", "1 patate douce (300-400 g)") may
  be each item's weight, so it is never a total. Package sizes never scale:
  a bracket straight after the count ("2 (15-ounce) cans") or a container word
  ("1 can (14 oz)"), a count of 1 that starts with a container ("1 lata leite
  condensado (397 g)"), or a per-item word ("each", "per"). Any other bracket
  holding an amount with a unit is unsure, and the whole line stays as written
  when scaled; brackets with no unit ("(Note 2)", "(2 medium)") are ignored as
  before.
- **Words are per language and only where confident:** en or/plus/minus;
  de oder, alternativ; es o, más; fr ou; it o, oppure; pt ou; each language's
  "about", per-item and container words. A missing word only costs scaling:
  the bracket it would have explained leaves the line as written. Spanish
  `unitPrefixes` ("de") makes "¾ de taza" a measure for the bracket rule only;
  the converter still leaves "de taza" lines as written.
- **Corpus rows that changed:** only "100 gr di yogurt greco … + 1 cucchiaio"
  (it): the "+ 1 cucchiaio" now scales with the grams, and in Metric becomes
  "+ 15 ml"; Ounces, which can't weigh a nameless spoonful, now leaves the
  line as written instead of converting only the grams.

## Feature flags (#87)

Features sit behind local flags, with no server (remote flags need
accounts and a backend the app deliberately doesn't have; revisit only if #53
brings one).

- **On by default, in debug and release (the owner's call, September 2026).**
  Features used to ship dark and be switched on one by one; now every flag
  defaults on, and one is switched off only when its feature has a known bug
  or can't work yet. `freeTier` stays off until the store product
  `unlimited_recipes` exists (on without it, users would hit the 20-recipe
  limit with an Unlock that can't complete), and `aiCountBrackets` stays off
  because the #105 evaluation found it confidently wrong on 4 of 22 lines.
  The flags stay as kill switches rather than being retired.
- **Tests don't lean on the defaults.** A unit test that needs a flag on or
  off sets it through the fake store. iOS UI tests start with every flag off
  and turn on only the ones `launch(flags:)` names (`UITestSeeding`).

- **One registry, `shared/flags.json`:** key, a one-line description, the
  default per build type (`debug`, `release`) and the issue. Both apps read
  it (Android as a Java resource, iOS as a bundled file), so the list can't
  drift. Each platform also has a typed `Flag` enum with the same keys, and a
  unit test (`FeatureFlagsTest` / `FeatureFlagsTests`) fails if the enum and
  the file differ, or if a flag is no longer referenced by the app's code.
- **Typed access over an injectable store:** `FeatureFlags.isOn(Flag.MEAL_PLAN)`
  / `isOn(.mealPlan)`. `FeatureFlagStore` holds only overrides; choosing a
  flag's default removes its override, so a later change of default still
  reaches everyone. ViewModels see `FeatureFlags`, never prefs; tests use
  `FakeFeatureFlagStore` / `MemoryFeatureFlagStore`.
- **Overrides live apart from the user's settings:** the SharedPreferences
  file `feature_flags` and the UserDefaults suite `RecipeClipperFeatureFlags`
  (not the App Group: the share extension never reads flags). Never
  `unit_preferences`, so a reset can't touch a user's choices. Neither is in
  the backup include list, deliberately.
- **Developer settings is hidden, in release builds too** (the owner's call:
  handy on the owner's own phone, harmless when hidden). Seven taps on the
  version at the foot of Settings; the count is the ViewModel's, so a
  rotation mid-way keeps it. A switch per flag (key, description, issue,
  "Changed from the default"), then "Reset to defaults". The descriptions
  come from flags.json and stay English: developer text, not UI. The
  screen's own words are translated.
- **Changes apply without a restart.** Android: MainActivity collects
  `FeatureFlags.values` and provides them as `LocalFlagValues`; the tab shell
  and the recipe screen read them. Turning the tab shell on or off swaps the
  navigation graph, so the NavController is keyed by the flag and the app
  reopens on Home (the screen says so). iOS: `FeatureFlags` is `@Observable`
  and `RootView` reads it, so the root switches in place and the stack stays.
- **UI tests set flags through the store,** not a special launch argument:
  `launch(flags: ["mealPlan"])` passes `-uiTestFlags`, which the UI-test
  container writes as overrides into its own throwaway suite.
- **Retiring a flag:** a flag stays while it's useful as a kill switch. When
  one is no longer wanted, delete it from flags.json and the enums, with its
  branches, in one PR. flags.json keeps no history.

## Pantry expiry reminders (#52)

Owner-approved extra of #52: a morning notification when something in the
pantry is about to be used up by its date. #51's badge stays; this adds the
nudge for a cook who isn't looking at the pantry.

- **Opt-in, in Settings.** A Pantry section with one switch, "Expiry
  reminders", off by default, shown only while the `mealPlan` flag is on (the
  pantry is behind it). With the flag off nothing is scheduled even if the
  setting is on. Stored as `expiry_reminders` in `unit_preferences` /
  UserDefaults, like the other settings.
- **The permission is asked when the switch goes on, never at launch.**
  Android: `POST_NOTIFICATIONS` from the Settings screen (it holds the
  Activity), no prompt below API 33; a refusal, or notifications switched off
  for the app, leaves the switch off with a line saying to allow them in the
  system settings. iOS: `requestAuthorization` through a `NotificationPermission`
  seam on the ViewModel (the system asks once; afterwards it answers at once).
  This doesn't touch #10's "asked once" flag for timers: turning the switch on
  is an explicit request, so it always asks.
- **One notification per morning, 9:00 local, never one per item.** It lists
  what expires that day and the next ("Milk and yogurt expire tomorrow.",
  both sentences when both apply), A–Z, each name as typed. So an item is
  mentioned twice: the morning before and the morning of. Only items in
  stock, not "Always have", and with a date count; an item already past its
  date gets nothing (the badge says Expired). Turned on after 9:00, today's is
  skipped. Sentences come from one/many strings rather than plurals (the six
  languages only need the two), names joined with commas and a translated
  "and".
- **Pure planning** (`ExpiryReminders`, both platforms): items + today +
  minute of day → the reminders to come, each (day, today's names, tomorrow's
  names), capped at 30 (iOS keeps 64 pending notifications, which timers
  share). The coordinator (`ExpiryReminderCoordinator`) replans on every
  change to the pantry, the setting or the flag, and on app start.
- **Android: one inexact `AlarmManager` alarm** (`setAndAllowWhileIdle`, #10's
  plumbing, no exact-alarm permission: a morning note may come a few minutes
  late) for the first planned morning. The receiver re-reads the pantry, posts
  that morning's reminder as it is now (nothing if it was all used up, the
  setting went off, or the alarm is a day late), then arms the next morning.
  `TimerBootReceiver` re-arms it after a reboot or update; the app process
  starting replans too. Channel "Pantry reminders" (default importance); a tap
  opens the Pantry tab through MainActivity's route queue.
- **iOS: pending `UNCalendarNotificationTrigger` requests, replaced
  wholesale** (`expiry.<day>`), each carrying its text as planned, since
  nothing runs when it fires. Any pantry change replans, and so does coming
  back to the foreground, so the list starts from today. It never asks for
  permission itself. A tap selects the Pantry tab (`NotificationRouter`).
  Known gap: an item changed from another device (#53) or by the share
  extension is only seen at the next foreground.

## Meal plan extras (#52)

The optional extras of #46, one PR each, still behind the `mealPlan` flag.

### Month view

- **A switch, not a destination.** "Month" beside the Week title swaps the week for a month
  grid in place ("Week" swaps back), so the tab keeps one stack and Back never has to learn
  about it. It's ViewModel state, so it survives rotation; it isn't remembered across launches
  (the Week tab opens on today, #232). No new schema: the grid reads the same
  `observeDays` range query as the week, over the grid's first to last day.
- **The grid is whole weeks from the locale's first day** (`PlanDays.monthGrid`, both
  platforms), four to six rows, the days before and after the month muted but tappable. The
  month arithmetic is plain integers (Hinnant's civil-from-days), like the rest of `PlanDays`:
  no `java.time` on minSdk 24.
- **A day with meals shows a paprika dot, not a count or titles.** The month answers "which
  days are planned"; the week answers "what". Screen readers hear "…, meals planned".
- **Tapping a day scrolls the days to it** (#232: that day at the top, the header naming its
  block from today; before, it opened the day's locale week). Which month opens: today's for
  this week, otherwise the month holding most of the shown week (its fourth day), so Sep 30 –
  Oct 6 opens October.
- The week's own menu actions (What I need, Add this week's ingredients) act on the week shown,
  so they're hidden while the month shows; Meal types stays.

### Calendar file (.ics)

- **"Share as calendar file"** in the Week menu (week view only, disabled for an empty week)
  shares the shown week as `meal-plan-YYYY-MM-DD.ics` (the week's first day) through the
  platform share sheet, so any calendar app, mail or Files can take it. Nothing is subscribed
  or synced: it's a snapshot, and sharing again is how it's updated.
- **All-day events, not timed ones.** Meal types have no times (and the user's own types can
  be anything), so giving Dinner 19:00 would invent a fact. Each meal is one all-day event on
  its day (`DTSTART;VALUE=DATE`, `DTEND` the next day), `TRANSP:TRANSPARENT` so the day doesn't
  show as busy.
- **Summary: the meal type, then the recipe title or the note** ("Dinner · Chicken Adobo",
  "Lunch · Leftovers"): note entries are events like recipes. The middle dot avoids a locale's
  colon spacing. No servings, photo or link: the event says what's planned, the app holds the
  recipe.
- **The UID is the entry's stable `uid`** (`<uid>@recipe-clipper`), so a calendar that honours
  UIDs updates an event on re-import instead of adding a twin. PlannedMeal now carries it.
- **Pure generator, view-layer share.** `MealPlanIcs` (both platforms) turns meals into RFC 5545
  text: CRLF lines, TEXT escaping (backslash, `;`, `,`, newline), folding at 75 UTF-8 octets
  without splitting a code point, ASCII digits whatever the locale. The corpus's `Ics` rows pin
  the escaping and folding to the Kotlin. The ViewModel makes the text (`PlanCalendar.now()`
  stamps it); the screen writes it (Android: `cacheDir/exports/` through the existing
  FileProvider, `text/calendar`; iOS: the temporary directory) and opens the share sheet.

### Reusable weekly menus

- **A menu is a named copy of a week, not a template the plan follows** (Paprika's Menus).
  "Save week as menu…" (Week menu, week view only, disabled for an empty week) copies the
  shown week's meals into a new menu: each keeps its weekday (`dayOffset` 0–6 from the locale's
  first day of the week), meal type, recipe with planned servings, or note. Nothing links a planned meal
  to a menu afterwards, so editing the plan never changes a menu, and vice versa.
- **Applying only adds.** "Apply a menu…" opens the menus sheet (name, meal count); tapping one
  copies its meals into the week shown on the same weekdays, each at the end of its day and
  meal type. What is planned stays, so applying twice doubles up (visibly, and each meal
  can be removed) rather than silently replacing a week the user built. The snackbar says how many
  meals came in.
- **Weekdays across rolling weeks** (#232, owner's call 2026-10-01). The shown week is now
  the seven days from today, so it can start on any weekday, but a menu still means "same
  weekdays": Monday's meals land on the Monday among the seven days at the top. The schema
  is unchanged (no migration): `dayOffset` stays what it always was, days after the locale's
  first day of the week, and is converted at save (`PlanDays.menuOffset`) and apply
  (`PlanDays.menuDay`) time, so menus saved before keep working (`MenuDaoTest` /
  `MenuDaoTests` insert one as the old code wrote it). A locale whose first day differs from
  the one a menu was saved under still shifts it, as before.
- **Names are free text,** trimmed; blank does nothing. Duplicate names are allowed; the sheet sorts
  by name, case-insensitively.
  Rename and Delete sit on each row's menu in the sheet (iOS: their alerts show over the
  sheet). Deleting a menu removes it and its meals only, never the plan or a recipe.
- **Schema:** `menus` (name, `uid`, `updatedAt`) and `menu_entries` (shaped like
  `meal_plan_entries` with `menuId` and `dayOffset` in place of `day`), Room 11 / iOS
  `user_version` 10. Entries cascade with their menu and with their recipe; a recipe's menu
  meals come back with it when a delete is undone. Meal types don't cascade: deleting one
  moves its menu meals to Dinner, as it does planned meals.
- **A recipe in a menu is never culled** and doesn't count toward the 50, like a listed one:
  otherwise a menu saved months ago would quietly lose its recipes to history's cap.
- **Backup:** menus travel in the export file (`menus`, `menuEntries`). A menu comes in whole
  unless its uid is already here (then it's left as it is, never merged or renamed); its meals
  follow the plan's rules (a recipe meal needs its recipe, a note always comes in, no meal type
  means Dinner), its recipes come in like listed ones, and a menu left with no meals is dropped.

## Chef mode: short steps written on the device (#100)

Part of #99: the model writes words, code owns every number.

**The on-device APIs, as checked on 2026-09-25** (official docs, and the iOS 27 SDK's
`FoundationModels.swiftinterface`):

- **iOS: Apple's Foundation Models framework** (`import FoundationModels`, iOS 26+, Apple
  Intelligence devices). `SystemLanguageModel.default.availability` is `.available` or
  `.unavailable(reason)`, the reason one of `.deviceNotEligible`, `.appleIntelligenceNotEnabled`
  and `.modelNotReady` (still downloading). `supportedLanguages` (a `Set<Locale.Language>`) and
  `supportsLocale(_:)` say which languages it writes. `contextSize` is 4,096 tokens on 26.0 and
  8,192 on 27.0 (newer devices). A `LanguageModelSession(instructions:)` is stateful, so each
  step gets a fresh one; `respond(to:options:)` returns `Response<String>.content`;
  `GenerationOptions(temperature:)`. It throws on guardrail violations and unsupported
  languages. The app targets iOS 17, so everything sits behind `#available(iOS 26, *)` and
  `#if canImport(FoundationModels)`; on an older phone Chef mode is unsupported.
- **Android: ML Kit GenAI on Gemini Nano, through AICore.** Three candidates:
  - *Rewriting* (`com.google.mlkit:genai-rewriting:1.0.0-beta1`): `Rewriting.getClient(
    RewriterOptions.builder(context).setOutputType(SHORTEN).setLanguage(…))`. Input under
    256 tokens (a step is well under). Languages: English, Japanese, French, German, Italian,
    Spanish, Korean; **no Portuguese**. Returns suggestions sorted by confidence.
  - *Summarization* (`genai-summarization:1.0.0-beta1`): bulleted summaries of articles and
    chats; the wrong shape for one step.
  - *Prompt API* (`genai-prompt:1.0.0-beta4`): free prompts to Gemini Nano, under 4,000 tokens,
    on fewer phones (nano-v2 to v4 lists).
  All three: `checkFeatureStatus()` (Prompt: `checkStatus()`) returns `UNAVAILABLE`,
  `DOWNLOADABLE`, `DOWNLOADING` or `AVAILABLE`; `downloadFeature(callback)` fetches the model;
  minSdk 26 (the app's is 24, so the manifest overrides the library's and code checks the
  API level); not on unlocked bootloaders; inference only while the app is the top foreground
  app (`BACKGROUND_USE_BLOCKED`), with short-term (`BUSY`) and daily battery quotas
  (`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`). Supported phones: Pixel 9 and later, Galaxy
  S25/S26, OnePlus 13–15 and others on Google's list.
  **Chosen: Rewriting with `SHORTEN`**, the API built for exactly this, on the most phones.
- **iOS: Foundation Models, `LanguageModelSession(instructions:)`**, a fresh session per step
  and `GenerationOptions(temperature: 0)`. The instructions ask for the step shortened in its own
  language with every number, time, temperature and unit kept as written. The languages offered
  are `supportedLanguages` that the app also reads recipes in.

**Design.**

- **The seam:** `StepShortener` (`support()`, `shorten(step, language)`), implemented once per
  platform (`MlKitStepShortener`, `FoundationModelsStepShortener`) and faked in tests; nothing
  else imports ML Kit or FoundationModels. `ShortStepRepository` sits on top: it caches, checks
  and prunes, and is what the ViewModels see.
- **The gate, `ShortStepCheck`** (pure, both platforms, pinned by the corpus's `Short` rows): a
  short version shows only if it is shorter than the step, every number in it appears in the
  step as written ("1,5", "1 1/2", "½", each end of a range; "1.5" for "1,5" fails), and it
  states exactly the step's durations (amount and unit length, via `StepTimers.durations`) and
  temperatures (value and scale, via `TemperatureConverter.temperatures`): none changed, added or
  dropped. Dropping a time or an oven temperature fails too, beyond the issue's wording, since
  "Bake until golden" loses what the cook needs; the other half of "350°F (180°C)" may go.
  A step under 40 characters is never sent. Anything else shows as written.
- **The gate also keeps the words (#129),** because the #105 evaluation found that about half of
  the short steps it let through dropped or invented something without a number moving ("Press
  tofu for an hour.", a dropped "grease two baking sheets"). `ShortStepCheck.keepsWords`, by
  `shared/tables/<language>/chef.json`: every word of the step that is an **ingredient** (the
  words of each of the recipe's lines' `IngredientName`, or of the line when it has none, less
  sizes, modifiers and units), an **action** ("preheat", "grease", "fold"), a piece of
  **equipment** ("bowl", "thermometer"), a **qualifier** ("not", "if", "until", "alternatively")
  or a **time word** ("a few minutes") must still be in the short step, and the short step may
  add only function words ("the", "then", "with"). An action word right after "the"/"a" is a
  noun ("the rest of the flour") and may go. Words match through an ending ("stirring", "stir";
  "baking", "bake"), an abbreviation ("temp") or the same unit ("mins", "minutes"); nothing
  else, so a faithful synonym ("set oven" for "preheat") is rejected, by design. Japanese has no
  word boundaries, so it compares characters: every kanji and katakana of the short step is in
  the step, and each table entry (a stem, "混ぜ", "鍋") and ingredient name is in both or
  neither. The repositories pass `recipe.ingredients`.
  - **Measured** (the #105 harness, `run.sh chef`; qwen2.5:3b over the site-check pages, 103
    steps, the 78 shorter replies read by hand into `tools/eval/gold/chef.jsonl`: 21 faithful,
    57 not): shown 55 → 17, faithful among shown 38% → 76%, unfaithful shown 34 → 4, faithful
    rejected 0 → 8 of 21. The trade: fewer short steps, far fewer wrong ones.
  - **Still missed:** a dropped "re-cover" (the step's "cover" is still there), "according to
    the packet instructions" turned into its example's 1 minute, a dropped "the tray on the
    bottom might need a few extra minutes" tip, a dropped "place the other cardboard on top".
    **Wrongly rejected:** "if" dropped from an aside (2), a dropped thermometer, bowl or "coat",
    "set oven" for "preheat", an added "placing" or "adhesion".
  - Chef mode stays off (flag unchanged) until a phone model is measured with this gate.
- **Code still owns the numbers:** step timers come from the step as written, and a short step
  is rendered like the step (temperatures in the chosen unit), so the model never writes a
  number the cook sees that the step didn't state. Sharing a recipe sends the steps as written.
- **Cache:** `short_steps` (Room 12, iOS `user_version` 11), one row per recipe, step text
  (SHA-256 of it as stored) and language, with `uid` and `updatedAt` like the other tables. A
  changed step has no row and is written again; rows for steps the recipe no longer has, or in
  another language, are pruned when it opens. A version that failed the check is saved as
  failed (null) so it isn't asked for again; a model that couldn't answer ("not now": busy, in
  the background, downloading) saves nothing and is asked next time. Derived data: not in the
  export file, and it cascades with its recipe. A recipe the free tier didn't keep (#107) has no
  row to cache against, so it shows as written; Unlock keeps it and its short steps follow.
- **Settings → Steps → "Chef mode"** (a switch; the section only with the `chefMode` flag, off in
  both builds). Where the phone can't, the switch is disabled with one line saying why (an
  unsupported phone, Apple Intelligence off, model not ready); where it can, a line names the
  recipe languages it writes. The unsupported line names what the phone lacks (#144: "can't
  write short steps" read like a bug on a Galaxy S23): Google's on-device AI with examples
  (Pixel 9 or newer, Galaxy S25 or newer), or Apple Intelligence (iPhone 15 Pro or newer, iOS
  26 or later); Apple Intelligence off says where to turn it on (the iPhone's Settings).
  A recipe in another language keeps its steps as written, silently. Android offers
  Chef mode while the model is still downloadable: the first recipe starts the download and
  shows its steps as written meanwhile.
- **On screen:** while short steps are written, the steps show as written (no spinner). In the
  reading view a tap on a step with a short version shows it as written, and again short. In
  cook mode a tap already makes a step current, so the current step's card has a small
  "As written" / "Short version" button beside "STEP n" instead.
- **minSdk 26 (Android 8.0), was 24: the owner's decision (2026-09-25).** ML Kit GenAI's minSdk
  is 26. Keeping 24 needs `<uses-sdk tools:overrideLibrary>` in a manifest, and lint then reads
  the app's targetSdk from that element as 1 (lint 32.4 `Project.readManifest`): it flagged
  every "many" plural (`UnusedQuantity`), and it would quietly skip every check that depends on
  targetSdk. AGP 9 refuses SDK attributes on that element, so there is no clean override.
  Android 7.x (API 24–25) phones can no longer install the app; it was unreleased. The
  alternative, keeping 24 with Android's Chef mode unsupported, was turned down.
- **With "Amounts in steps" (#101):** a short step gets its amounts the same way, from its own
  text (`StepAmounts.annotate` over the short versions), so amounts follow whichever version is
  on screen. Both switches share the Settings Steps section; each row shows with its own flag.
- **Needs a real phone to judge:** the model's output quality, how often the gate rejects it,
  speed per step, and battery. CI and the tests run the fake model only.

## Ingredient amounts inside steps (#101)

Part of #99. "Add the carrots" reads "Add **2** carrots": a Settings switch, "Amounts in steps"
(Settings → Steps, off by default, key `amounts_in_steps`), behind the `amountsInSteps` flag.
Deterministic, no AI: `StepAmounts.annotate(steps, lines, words)`, pure, both platforms, pinned
by the differential corpus's `Step` rows. The lines are the ingredient lines **as the reading
view renders them** (scaled, then converted), so the amount follows the servings stepper and the
unit menu. It applies to the reading view and cook mode; sharing out keeps steps as written.

A mention gets an amount only when every rule holds; otherwise it stays exactly as written:

- **One line, strictly.** A run of words ending in a line name's last word (singular or plural,
  per `steps.json` `plurals`) names that line when `IngredientName.matches` says so: the
  pantry's strict rule (#51), so "rice flour" never takes "flour"'s amount and "the onion" never
  takes "1 red onion"'s. The longest run wins ("brown sugar" is the brown sugar line, not
  "sugar"). Two lines matching the mention ("unsalted butter" and "butter, for greasing"; sugar
  for the cake and for the frosting), or a line with no name that uses the word ("salt and
  pepper", "juice of 1 lemon"), make it ambiguous. Jev may later resolve those (#99).
- **First mention only**, once per line per step; later mentions stay as written, whatever
  became of the first. Each step is read on its own.
- **The line lends its amount only if the scaler reads it** (scaling it changes it), so a line
  that stays as written when scaled ("2 onions (about 300 g)") never lends a number. The amount
  is the rendered line's text before the name ("250 g", "2 large", "3 cloves", "200 g de",
  "1 cup (120 g)"), never with a comma in it. A line used in parts ("2 cups flour, divided",
  "1 tsp salt, plus more to taste": `splitWords` after the name) lends nothing.
- **The step doesn't already say how much.** A number, or a `partWords` word ("half", "the
  remaining", "rest", "of", "a", "some", "du"), right before the mention or before its article
  keeps it: "half the butter", "1 cup of the flour", "2 tablespoons butter", "a carrot".
- **The mention is the whole name.** After it: the step's end, punctuation, or a word in
  `after` ("and", "into", "until"…); anything else ("the flour mixture", "the lemon juice",
  "le beurre fondu") may be a longer name, so it stays. German writes compounds as one word,
  so any word may follow (`anyWordAfter`). Before it: its article (which the amount replaces),
  a list comma, a word in `before` (a preposition, a conjunction, a common verb: "Stir in flour",
  "Whisk flour"), or a sentence's first word (the imperative verb). Anything else ("Dust with
  rice flour" when only flour is listed) stays. Describing words between the article and the
  name stay after the amount: "the melted butter" → "115 g melted butter".
- **Japanese is a no-op** (no spaces, so no word boundaries), and so is a language without words.

Where it shows: the inserted amount is a separate run of the text, in the paprika text accent
and a heavier weight (Android SemiBold, iOS strongly emphasised); in a done cook-mode step it
keeps only the weight, so the dimmed row stays dim. The screen checks the flag; the ViewModel
computes the parts only while the switch is on, and again on every servings or units change.

Known limits, left as written on purpose: head-noun mentions of compound lines ("the milk" for
"whole milk", "the chocolate chips" for "semisweet chocolate chips", "the vanilla" for "vanilla
extract"); French names with an elided "d'" ("l'huile d'olive" against "3 c. à s. d'huile
d'olive"), because `IngredientName` keeps the elision in the name; and an ingredient also used
for greasing or dusting when the list has only one line for it ("grease the pan with butter"
then gets the batter's butter, since the step's words can't tell the two uses apart).

## The Recipes screen (#102)

- **Owner's decision:** a Paprika-style Recipes screen *replaces* History
  rather than sitting beside it: two lists of the same recipes, one searchable
  and one not, would only ask "which one is it in?". It keeps everything
  History did (newest viewed first, `instr(lower(…))` search, swipe to delete
  with one undo per burst), and the route is `recipes` (was `history`; only
  Home navigated to it, so no alias). Home's row says "Recipes"; the rest of
  Home is unchanged, "+ New recipe" included.
- **The + opens a two-item menu,** not a screen: "Type a recipe" is the #29
  editor as it already was; "Paste a link" is a small dialog whose Go is
  enabled only for what Home's link field would accept (`UrlInput`), then the
  usual import route. No clipboard is read unasked: Android shows a toast and
  iOS a permission prompt on every read.
- **A typed-in recipe is never culled,** like a listed one, and doesn't count
  toward the 50: a parsed recipe that falls out of history can come back by
  sharing its link again, a typed one can't. The rule is the column
  (`contentOrigin = 'MANUAL'` in the cull's SQL), not the `manual:` link; an
  import treats typed-in recipes as listed. No schema change: #29 already
  stored them with a synthetic `manual:<uuid>` `sourceUrl`. #107's free-tier
  limit will replace the 50 cap later; this rule is one more protected kind
  for it to count or exempt.
- **Sort** (Recently viewed, Name, Date added) is done in the
  ViewModel over what the query returns. Name uses
  the phone's collation; Date added is newest id first, which works because
  `recipes.id` is AUTOINCREMENT on both platforms (never reused), so no
  `createdAt` column or migration was needed.
- **The sort is remembered** (owner's decision, after #109 held it in
  memory and reset it on every visit): `recipe_sort` in `AppPreferences`,
  by name, default Recently viewed, an unknown name read as the default.
  It is a view preference, so it is not in the export file and not in
  Settings.

## The free tier and the unlock (#107)

Owner's decision (2026-09-25, rules approved as proposed): the free app keeps
20 recipes; a one-time purchase unlocks unlimited ones. Behind the `freeTier`
flag, off in debug and release until the store products exist; with it off
the app is exactly as before (the history cap of 50 unprotected recipes).

- **Every recipe counts toward 20:** shared, typed in, clipped, in a list,
  planned. What changes is only which one may be removed to make room.
- **Capture stays frictionless.** Sharing always opens the recipe. When a new
  recipe arrives and the library already holds 20 or more, the oldest-viewed
  recipe that is in no list, not planned for today or later, in no saved menu
  and not typed in is deleted first, in the upsert's transaction (the same
  protections as the old cull; menus were kept because a menu would lose the
  recipe). Re-sharing or opening a recipe already here removes nothing.
- **All protected: shown, not kept.** A shared recipe opens with a quiet
  "Not saved" snackbar (Android) or bar (iOS) offering Unlock; lists, notes,
  Edit, Delete and the plan actions are hidden, since there is no row. A
  successful unlock then saves it (`RecipeRepository.keep`). A typed-in
  recipe or a clip isn't shown unsaved, which would throw the typing away: the
  editor or clip stays open behind a "Your library is full" dialog with
  Unlock; after unlocking, Save runs again. On iOS the share extension, which
  can't sell anything, says so on its card and points to the app.
- **Grandfathering: the library never shrinks because of the limit.** The
  free tier removes at most one recipe per recipe added, and only when the
  count is already at or over 20, so a phone that holds 50 when the flag
  turns on keeps 50: each new one replaces its oldest unprotected recipe, and
  the count only falls below 50 when the user deletes. No "grandfathered"
  column was needed, and nothing is removed at the moment the limit arrives.
  The Recipes screen says "50 recipes, more than the free 20" rather than
  "50 of 20".
- **Unlocked means nothing is ever removed automatically**, not even the old
  history clean-up: the owner said "unlimited recipes", and quietly deleting
  unlisted ones would contradict that. Deleting stays the user's act.
- **Backup import** follows the same rules: unlocked, everything comes in; on
  the free tier, protected recipes (listed, planned, in a menu, typed in)
  always come in and the rest only fill free places under 20, counting every
  recipe already here. The summary says the free app keeps 20.
- **The unlock:** one non-consumable product, `unlimited_recipes`, on both
  stores (the owner creates it: App Store Connect in #18, Play Console in
  #22; price is the owner's call, the StoreKit file's 2.99 is a placeholder).
  Restorable: Settings' "Restore purchase" (iOS `AppStore.sync()`, Android a
  fresh `queryPurchasesAsync`); both also re-check at every launch, which
  picks up refunds and purchases made on another device.
  - Android: Google Play Billing Library 9.1.0 (`billing-ktx`, the one new
    dependency), in `PlayBillingEntitlements`. Purchases are acknowledged
    (Play refunds unacknowledged ones after three days); pending purchases
    (cash, slow cards) show as pending. Play's sheet needs the resumed
    Activity, tracked from the Application's lifecycle callbacks so no
    ViewModel holds one.
  - iOS: StoreKit 2 in `StoreKitEntitlements`, listening to
    `Transaction.updates` (Ask to Buy approvals, refunds). Locally the scheme
    runs against `ios/RecipeClipper.storekit`. `refresh()` after an update
    reads `currentEntitlements` without waiting: checked in #172, an update
    arrives only once `currentEntitlements` lists it (58 of 58 on just-booted
    simulators), and `purchase()` trusts the transaction it gets back. Only
    the tests' `SKTestSession.buyProduct` returns early.
  - Both cache the last answer (Android its own `entitlements` prefs file,
    not in the backup include list; iOS `UserDefaults`), so a share that
    cold-starts the app isn't judged "locked" while the store is still being
    asked.
- **Seams:** `Entitlements` (data layer; fakes in tests) and the limit as a
  value, `LibraryLimit` (`History(50)`, `Free(20)`, `Unlimited`), chosen by
  `LibraryPolicy` from the flag, the store and Developer settings' "Unlocked"
  override (stored beside the flag overrides as `override.unlocked`, so Reset
  clears it). ViewModels never import Billing or StoreKit. On iOS the
  repositories read the limit from the App Group suite (`library_limit`),
  which `LibraryPolicy` keeps current, because the share extension saves in
  its own process and sees neither the flags nor StoreKit.
- **What can't be tested here:** a real purchase needs the store products
  and accounts (#18, #22). Android's Billing code is only compiled and
  exercised through the fake; iOS runs `StoreKitEntitlementsTests` against the
  StoreKit file (`SKTestSession`: the price, an owned purchase found and
  cached, then lost), and the purchase sheet is tried by hand in the simulator. Play's own
  test tracks and license testers are the next step once the Play Console
  product exists.

## A recipe picked from the page's text (#103)

Part of #99. When a page loads but JSON-LD and microdata find no recipe (`NoRecipeFound`,
after the rendered fetch too), the on-device model may pick one out of the page's text. The
parsers stay first and unchanged; this runs only after them, behind the `llmExtraction` flag
(off in both builds).

**"Never guess a recipe from prose" becomes "never invent one": the model may only pick text
that's on the page** (the owner's direction on #99). What it returns is checked line by line,
and what shows is the page's own characters for each line found, never the model's. A line the
page doesn't have is dropped; a number is never cut into; code still owns every number
(scaling, conversion, timers and temperatures read the kept lines exactly as they read parsed
ones).

**The APIs, as checked on 2026-09-25:**

- **Android: ML Kit GenAI's Prompt API** (`com.google.mlkit:genai-prompt:1.0.0-beta4`,
  `Generation.getClient()`, `checkStatus()`, `download()`, `generateContent(generateContentRequest(
  TextPart(…)) { temperature = 0f; topK = 1; maxOutputTokens = 1024 })`). Input under 4,000
  tokens. Rewriting, which Chef mode uses, takes under 256 tokens and only rewrites, so it can't
  read a page; Summarization writes bullets. The Prompt API's structured output
  (`@Generable` data classes) is alpha and needs the `genai-schema-compiler` KSP processor, so
  instead the reply is one JSON object parsed strictly (`PageSelectionJson`: optionally in one
  code fence, nothing around it, every field the right type, else no answer at all). Offered for
  en, de, es, fr, it and ja (the languages Google lists for Gemini Nano's text features; no
  Portuguese). A model still downloadable starts its download and the page stays
  `NoRecipeFound` meanwhile.
- **iOS: Foundation Models with guided generation** (`@Generable struct PickedRecipe`, `@Guide`
  per field, `session.respond(to:generating:options:)`, a fresh session per page,
  `GenerationOptions(temperature: 0)`), iOS 26+ with Apple Intelligence on, for the recipe
  languages in `supportedLanguages`. No reply is parsed: the fields come back typed.
- **How much text:** Android 3,000 tokens of page; iOS `contextSize` (4,096 on 26, 8,192 on 27)
  minus 1,800 for instructions, schema and reply. Tokens become characters at 3 per token (1 in
  Japanese), a deliberate underestimate. A page over the limit fails the call (nil), never
  shows a partial recipe.
- **Seam:** `PageRecipeExtractor` (`windowChars(language)`, `extract(text, language)`) beside
  `StepShortener`; `MlKitPageRecipeExtractor` and `FoundationModelsPageRecipeExtractor` are the
  only files that import the model APIs; tests use `FakePageRecipeExtractor`. The share
  extension has no extractor (memory), so a shared link it imports behaves as before.

**Design.**

- **The source hands over the page's text** with `NoRecipeFound` (`RecipeSource.fetchPage`,
  `FetchedPage`); the repository prefers the rendered page's text when there is one. Only a page
  that loaded is read: never after a block, offline or a failed fetch.
- **Page text** (`PageTextReader`, pure, Jsoup / the iOS `HtmlTree`): one line per block, each
  as Jsoup's `text()` gives it; scripts, styles, `nav`, `footer`, buttons and the like left out.
  The title is the first `<h1>`, else `og:title`, else `<title>`; the photo is `og:image`.
- **The window** (`RecipeTextWindow`, pure, both platforms): the ingredients heading
  (`headings.json`, every shipped language at once) followed, before the next heading, by the
  most ingredient-looking lines (an amount first, or a short line holding a number), with a
  bonus when the steps heading follows; else the densest run of such lines (three in ten); else
  nothing, and the model isn't asked (a login page or an article costs no model call). Up to 12
  lines before the anchor (a fifth of the budget: the card's title, times and servings), then as
  many as fit, with the page title first if the window doesn't already hold it.
- **The verifier** (`PageRecipeCheck`, pure, both platforms, pinned by the corpus's `Pick`
  rows): each picked string is looked for in the window's text, both folded the same way (NFKC,
  so "½" is "1/2"; typographic quotes, dashes, "⁄" and "×" as ASCII; lowercase; whitespace runs,
  line breaks included, as one space). A match must not start or end inside a word, nor inside a
  number ("2 cups" in "12 cups", "25 minutes" in "20-25 minutes", "5 hours" in "1.5 hours"); an
  ingredient must start its line, after nothing but a bullet or checkbox, so "2 tbsp" can't be
  lifted out of "1 cup plus 2 tbsp"; a name, ingredient or step must hold a letter. The name is
  required; the recipe still needs ingredients or steps (the parsers' rule), otherwise it's
  `NoRecipeFound` as before. Times go through `Durations.format`, as the parsers' do.
- **Provenance:** `contentOrigin` `EXTRACTED` (no schema change: the column is text). It is the
  source's, like `PARSED`: a re-share fetches and refreshes it, and an edit makes it `EDITED`.
  An older app reads the unknown name as `EDITED`, the safe side. The reading view says, quietly
  under the source credit, "Picked from the page text — check against the source", with Open
  original as usual.
- **Needs a real phone to judge:** whether the models copy text faithfully enough for the
  verifier to keep most lines, how long a page takes, and whether 1,024 output tokens hold a long
  recipe on Android. CI and the tests run the fake model only.

## Typed decisions on the device (#104)

Part of #99. The owner turned down a paid Jev API (2026-09-25), so these decisions are made by
the same on-device models as Chef mode (#100) and page extraction (#103), behind the
`aiDecisions` flag (off in both builds). Each is a pick from a fixed list that includes
"unsure"; code acts only on a definite answer and otherwise keeps today's behaviour exactly.
The model never supplies a number: code owns every figure.

**The confidence rule (`DecisionRule`).** On-device models give no calibrated probabilities, so
a model's self-reported confidence alone can't be trusted, and asking twice at temperature 0
alone mostly repeats itself. The rule combines both: each question is asked **twice**, the
options listed in the declared order and then reversed (against a bias for the first or last
option; "unsure" always last), each reply giving an answer and a confidence (high, medium,
low). A decision counts only if **both replies pick the same definite option and both say
"high"**. A disagreement, "unsure", medium or low, an option not on the list, or an unreadable
reply is unsure. Android asks through ML Kit's Prompt API for one JSON object
(`{"answer", "confidence"}`, parsed strictly by `DecisionReplyJson`: anything else is unsure);
iOS uses Foundation Models guided generation, with one `@Generable` enum per kind, so the
model can only pick a listed option (the rule still checks it).

**What each answer changes.**

- **Count brackets** (#88's leftovers). A count's bracket that holds nothing but an amount
  ("4 Apfel (ca. 800g)", "1 patate douce (300-400 g)", "3 large apples, … (about 3 cups)") is a
  new `BracketKind.COUNT`. With no answer (or unsure) it is unsure, as before: scaling keeps the
  whole line as written. TOTAL treats it like a measure's total: it scales with the count ("8
  Apfel (ca. 1600g)" doubled). EACH treats it as a per-item size: the count scales, the figure
  stays ("2 patate douce (300-400 g)"). Only a line that stays as written *only* because of such
  a bracket is asked (`IngredientScaler.needsCountDecision`); prose in the bracket, a missing
  unit after "or", package sizes and measure totals are never asked and never change.
  Converting is unchanged: a count has no unit to convert, so the bracket keeps its units (it
  is the site's figure, scaled). Asked when a recipe with a servings stepper opens; applied in
  the reading view, and to the week's lines (grocery sheet, What I need) once cached.
  **Off since #127, behind a second flag, `aiCountBrackets`** (off in both builds; it acts only
  with `aiDecisions` on too). The #105 evaluation (`docs/eval/llm-vs-regex.md`) found a 3B
  model confidently wrong on 4 of 22 count brackets even through `DecisionRule` ("6 garlic
  cloves (30 g)" as each, so doubled it would read "12 garlic cloves (30 g)"), and every wrong
  answer is a wrong figure on screen. So under `aiDecisions` alone nothing is asked, and the
  repository leaves any cached count-bracket answer out of what it emits: these lines scale
  exactly as without the model. The prompt, the candidates and the scaling stay, so the
  decision can be re-measured on the phone models and re-enabled by the flag.
- **Pantry "same ingredient?"** Asked only for a line whose name no pantry item matches by
  `IngredientName.matches`, against in-stock or staple items whose names are close
  (`DecisionCandidates.close`: they share a word of 3+ letters, or one's last word ends with the
  other's, as "Weizenmehl"/"Mehl"; never in a language without spaces). Only a definite "same"
  counts, and only to turn Buy into Have (What I need, and the grocery sheet's unticking); it
  never overrides a real match and never uses an item that is out. The owner's decisions stay:
  "rice flour" is not "flour", "whole milk" is not "milk"; they are written into the question as
  examples of "different". With the fake model the tests pin that only "same" changes anything;
  whether the real models follow the examples is a device check. Ticking a grocery item off
  still restocks by the strict match only.
- **Aisles.** Asked for a grocery item the keyword table puts in Other and whose name is known
  (`DecisionCandidates.aisle`); the options are the aisle keys (with "other"). When an item is
  added, a cached aisle files it at once. Otherwise the Groceries screen asks in the background
  and, when a definite answer lands, files the items it asked about only if they are still in
  Other (`fileFromOther`); an item in Other whose aisle is already cached was put there by the
  user, so it is never moved again. Pantry items keep the keyword table's aisle.

**The cache.** `ai_decisions` (Room 13, iOS `user_version` 12): kind, the input normalised
(trimmed, whitespace collapsed, lowercased; a pair's two names sorted), language, answer (an
option or "unsure"), `uid` and `updatedAt`. Unique on kind + input + language, so each question
is asked once; unsure is cached too. A model that can't answer now (busy, in the background, not
downloaded, an error) caches nothing and is asked next time. Derived data: not in the export
file. Questions are asked lazily off the main thread; screens show today's result until an
answer lands. Unsupported phone or language (Android: en, de, es, fr, it, ja; iOS: the model's
supported languages), or the flag off: nothing is asked and everything is exactly as today.

**Needs a real phone to judge:** how often each model is definite and high-confidence (the rule
may leave most questions unsure), whether its answers are right (especially the owner's
"different" pairs), and the time per question (two asks each). CI and the tests use fakes only.

## WP Recipe Maker's ingredient parts (#118)

Most food blogs build their recipe card with a WordPress plugin, and WP Recipe Maker (RecipeTin
Eats, Minimalist Baker, Skinnytaste, Love and Lemons) marks each ingredient's parts in the HTML:
`wprm-recipe-ingredient-amount`, `-unit`, `-name` and `-notes`, inside named groups. Its JSON-LD
lines wrap the notes in brackets, whatever the notes already hold: "2 garlic cloves (, minced)",
"1 lb / 500 g zucchinis ((courgettes))". Love and Lemons' JSON-LD even drops a note ("for
garnish") that the card shows.

- **JSON-LD stays first.** The parts only refine its `recipeIngredient` lines, and only when a
  card's ingredients line up one-to-one: the same count, and each part's name found in its
  JSON-LD line (case and spacing ignored). Otherwise the JSON-LD lines stay as they were. A page
  can hold several cards; the first that lines up is used. Microdata recipes aren't refined.
- **Notes stay on the line, after the name, as the card shows them.** A recipe's ingredients are
  plain lines with no notes field; adding one would mean a schema change and teaching scaling,
  conversion, groceries, editing and export about it. The scaler reads the leading amount, and
  a bracket or second amount after the name follows the existing rules (#61, #63), so a note
  never changes what scales. The line is amount, unit and name joined by spaces; notes that
  start with a comma follow directly ("2 garlic cloves, minced"); otherwise a comma when the
  page puts one between name and notes ("kosher salt, *see notes"), else a space ("zucchinis
  (courgettes)").
- **Groups become heading lines** ("Batter:", "Minted Yoghurt (optional):"), the colon form the
  rest of the app already reads as a heading (groceries, ingredient names, step amounts). An
  unnamed group adds none. Headings change the ingredient list, so ticked ingredients reset once
  on the first re-share after this change, as for any changed list.
- **The owner's "2 corn (dfsafs -"** (#121) isn't on the Greek zucchini tots page; the page's
  real lines of that shape, "2 garlic cloves (, minced)", now read "2 garlic cloves, minced".
- Tests: trimmed real pages in `shared/fixtures/pages/wprm-*.html` on both platforms, and `Wprm`
  rows in the differential corpus. The weekly site check fetches the zucchini tots page.

## Tasty Recipes' and Mediavine Create's ingredient headings (#119)

The other two common WordPress recipe cards, read after WP Recipe Maker's (#118). **Neither
marks an ingredient's parts.** Tasty Recipes (Pinch of Yum, Joy the Baker, The Kitchen
Whisperer) prints each ingredient as a whole `<li>`; only the amount is wrapped, for its own
scaling (`data-amount`), and any bold name is the author's formatting. Mediavine Create
(TidyMom, Key to My Lime) prints each ingredient's `original_text` in an `<li>`. On every page
checked, those lines are JSON-LD's `recipeIngredient` lines word for word; the card's own only
differ in WordPress's curled dashes and quotes ("3–4 cups", "confectioners’"). So there are no
notes or parts to read, and **JSON-LD's lines stay exactly as they are**.

**What JSON-LD drops is the group headings**, and those are added (`CardHeadings`):
"For the chocolate cake:", "Oreo Crust:", "FOR APPLE FILLING:", "Chicken Marinade:".

- **Where headings come from.** Create names its groups: an `h3`/`h4` in
  `.mv-create-ingredient-group-header`, or, in its older markup, an `h3` straight before each
  list. Tasty's ingredients are free text around the lists, so a heading is what Tasty's own
  code leaves out of its JSON-LD as one: a heading element, or a paragraph that ends in a colon
  or is wholly bold. Any other paragraph is not a heading ("Use a big bowl"). The list's own
  title ("Ingredients", in `.tasty-recipes-ingredients-header` or
  `.mv-create-ingredients-title`) is never one, nor is a paragraph inside an item. A heading
  after the last ingredient heads nothing and is dropped.
- **Only when the card lines up one-to-one** with `recipeIngredient`: the same count, and each
  item's letters and digits (lowercased) found in its JSON-LD line, so curled punctuation and
  spacing don't count. Otherwise nothing changes. A Tasty card with no list items (plain
  paragraphs) already puts its headings in JSON-LD, so it's left alone.
- **One shared helper** (`CardIngredients`) now holds what the three adapters have in common:
  groups of items, the line-up check and the "Name:" heading lines. WP Recipe Maker keeps its
  own check (case and spacing ignored) and its refined lines; its behaviour is unchanged.
  Site-specific rules as data are #120.
- Tests: trimmed real pages in `shared/fixtures/pages/tasty-*.html` and `mv-create-*.html` on
  both platforms, and `Heads` rows in the differential corpus. The weekly site check fetches
  Pinch of Yum's blackout chocolate cake and TidyMom's apple pie bars.

## Site-specific parsing rules as data (#120)

The big sites were fetched with the app's user agent (2026-09-26). Every one that answered
already parsed from JSON-LD; what they lost was the same thing #119 found on plugin cards: **the
ingredient group headings** ("FOR THE FROSTING", "For the filling", "Cream cheese frosting and
assembly"). NYT Cooking, BBC Good Food, Bon Appétit, Epicurious and Delish drop them; Taste of
Home already puts them in JSON-LD, and King Arthur had none to lose. Bon Appétit and Epicurious
also end the last step with "Editor’s note: this recipe was first printed in … Head this way for
more … →". Serious Eats, AllRecipes, Simply Recipes and Food & Wine answer the direct fetch with
a bot check, so there is nothing there to write a rule for.

- **One table, `shared/tables/site-rules.json`, read by both apps** (`SiteRules`). Data only:
  CSS selectors and phrases, never code. `cards` are the plugin cards any site may have (Tasty
  Recipes and Mediavine Create, moved here from `CardHeadings`' hard-coded list); `sites` are
  keyed by host without "www." (`SourceDomain`), and a site's own cards are tried before the
  plugins'. A card is `list`, `item`, `heading` and optional `title` and `amount` selectors.
  Everything `CardHeadings` already required still holds: a heading must be a heading element,
  end in a colon or be wholly bold, and a card is used only when its items line up one-to-one
  with `recipeIngredient` (same count, each item's letters and digits in its line). A rule can
  only add headings or drop known noise; it can't change a line.
- **The selector subset is matched by hand** (`CardSelector`, both platforms): a tag, `.class`,
  `#id`, `[attr]`, `[attr=v]`, `[attr^=v]`, `[attr*=v]`, compounded, in comma lists, no
  combinators (a card's parts are only looked for inside its list). Jsoup's `select` on Android
  and the iOS `HtmlTree` would otherwise disagree at the edges. Anything else in the table is a
  mistake and fails loudly (Android throws, iOS traps). The hashed class names (NYT's
  `ingredientgroup_name__xNtpC`, Condé Nast's `SubHed-icPlCN`) are matched by their stable
  prefix with `*=`.
- **WP Recipe Maker stays code** (`WprmIngredients`): it rewrites lines from their parts, puts
  the notes' comma back and matches ignoring only case and spacing. None of that is a selector.
- **`amount`: parts of an item left out when matching it.** Delish's card writes "3 cups" and
  "6 Tbsp." where its JSON-LD writes "3 c." and "6 tbsp.", so its `<strong>` amount is removed
  before the line-up check. JSON-LD's line is still what shows. On iOS the removal cuts those
  elements' source out of the item's, then reads the text as any element's. An amount in the
  middle of a line ("4 (6- to **8-oz.**) chicken breasts") leaves a key that is no longer one
  run of the JSON-LD line, so that card doesn't line up and its page gets no headings: a safe
  miss, left as is.
- **`stepNoise`: phrases that start noise at the end of the last step.** The last step is cut
  where a phrase starts it or follows a space; a last step that was all noise goes, unless it
  was the only step. Only the last step, only on that site: an editor's note in the middle of a
  method is left alone.
- **`version`, raised on every edit**, beside `schemaVersion` (the format). A copy fetched later
  without an app release (the issue's "later, optionally") would be used only if newer than the
  bundled one; nothing fetches one yet.
- **The weekly site check flags a rule that stops matching.** Its report adds a "Site rules"
  section, judged per site over the site's pages in the run: Matched when a site's card lines up
  or its noise is found on at least one of them, else **Stopped matching**, and the workflow
  warns on that. Not per page, because a rule need not fit every page: not every Epicurious
  recipe has an editor's note, and a Delish card with an amount mid-line never lines up. The
  JSON keeps each page's result. It runs when the table changes too.
- Tests: trimmed real pages in `shared/fixtures/pages/site-*.html` (`SiteRulesTest` /
  `SiteRulesTests`), and `Site` rows in the differential corpus. The site check fetches one
  page per site with rules.

## Grocery lines merged with the model's help (#99)

Part of #99, on #104's typed decisions (same `DecisionRule`, `ai_decisions` cache and
`aiDecisions` flag). The owner asked for help with differently worded lines that are one
thing to buy ("2 ears of corn" + "2 corn", "corn on the cob" + "corn", "3 garlic cloves" + "2
cloves garlic") and with text after the ingredient ("(dfsafs -", ", shucked"). **The model never
does arithmetic or writes a number**: it answers two typed questions, and `GroceryCombiner`'s
exact rules still decide every total.

- **"Same thing to buy?"** (`sameGrocery`: same / different / unsure). Asked for two unchecked
  lines in one language whose names differ, are `DecisionCandidates.close` (the pantry's
  closeness test), and whose aisles could meet (the same aisle, or one in Other). Its own kind,
  not the pantry's, because the question differs (two list lines, not a recipe and a pantry
  item); the owner's "different" pairs are written into it, with "corn flour" is not "corn".
  A definite "same" puts the two groups in one row under the first group's name. **Adding up
  is unchanged:** only exact amounts in one unit family, and counts only with identical words
  after the number. So "200 g sweetcorn" + "100 g corn" is "300 g corn", but "2 ears of corn"
  + "2 corn" and "3 garlic cloves" + "2 cloves garlic" sit together in one row, each as written:
  whether an ear is one "corn" is exactly the kind of guess that makes a confident wrong number.
- **"What is this trailing text?"** (`trailingText`: note / second_amount / junk / unsure).
  `GroceryDecisions.split` cuts a line at the first comma, semicolon, bracket or spaced dash
  whose left side has an ingredient name ("2 eggs" | "(dfsafs -"); never text holding a digit
  (a figure is never ignored, whatever the model says), never Japanese or unspaced languages,
  never a package size before the name. Asked for every grocery line with trailing text, a
  lone line too (the owner's option 2), so a lone "2 eggs (dfsafs -" shows "2 eggs" once
  decided junk; once per text and language (the cache), in the background, once per visit.
  The recipe asks it too, about its own lines (#174, below). Note or junk: the line is grouped
  and added up as its core ("2 eggs, beaten" + "3 eggs" is "5 eggs"). A note still shows as written under
  the total; **junk is hidden in Groceries** (the owner's option 1): the row, the lines under
  a total or Together row and the shared text show the line without it ("2 eggs"). That is a
  display-time transform from the cached answer (`GroceryDecisions.shownText`, applied in
  `GroceryCombiner.sections`): the stored line is never rewritten, so turning `aiDecisions` off
  shows it again. Second amount or unsure: nothing changes. Pinned by the corpus's `Trail` rows.
- **"What is the ingredient's name?"** (`ingredientName`, free text) catches junk with no
  separator ("2 onions dfsafs"). Asked only for a line with no separator split whose name has
  words the aisle table doesn't match after words it does ("onions dfsafs": "onions" is
  produce, the whole isn't), once per line and language, never in unspaced languages. The
  answer counts only when both asks agree with high confidence (`DecisionRule`: any agreed
  non-empty text), it is in the line as whole words (`PageRecipeCheck.find`), the line up to it
  still reads as an ingredient line whose name ends with it, and what follows is two
  characters or more with no digit (`GroceryDecisions.nameSplit`). That rest then goes through
  the trailing-text question above. Unsure or a failed
  check: the line stays exactly as today. Pinned by the corpus's `NameCut` rows.
- **Aisles.** Grouping stays per aisle. When a fresh answer lands, a line in Other moves
  beside its "same" partner, or to its core's aisle once its trailing text is note or junk
  (`GroceryDecisions.filing`, then `fileFromOther`), exactly like #104's aisle answers: only
  lines still in Other, only on an answer that just landed, so an aisle the user chose stands.
  The core's aisle (`cutAisle`) follows whichever of its answers lands last, the name or the
  trailing text (#158: the junk answer is often cached already, from another line), and a
  line added once both are cached takes it at once, as it takes a cached aisle answer.
- **Lazily, in the background.** The Groceries screen asks after each change of the list,
  each question once per visit and once ever per text/pair and language (the cache); it
  shows today's grouping until an answer lands, then regroups from the decisions flow. Flag
  off, an unsupported phone or language: no question, and the list is exactly today's (a test
  on each platform compares it with `sections(items)`). Deleting, ticking and moving rows are
  as before. Sharing sends what the screen shows.
- **Junk is hidden in the recipe too** (#174). The owner's decision (2026-09-26): "Hide the
  junk, period, including in recipes." Until then the reading view never asked the model and
  always kept a line as the site wrote it; that is reversed. When a recipe opens (and after
  "Update from source"), `ChefMode` asks the same two questions about its ingredient lines
  (`GroceryDecisions.junkQuestions`: headings left out; a name that lands opens its
  trailing-text question), in the background, each once per visit and once ever through the
  same cache, so a line already decided in Groceries costs nothing. `IngredientRendering`
  shows a line decided junk as Groceries would (`GroceryDecisions.shownLine`, the same cut as
  `shownText`), then scales and converts the rest ("1 cup flour (dfsafs -" at double servings
  in Metric is "240 g flour"). The same safeguards, since it is the same code: never text
  holding a digit, never unspaced languages, never a package size before the name,
  `DecisionRule`. So the reading view, cook mode's ingredients bar, the shared text, "Add to
  groceries" and the pantry's use-up sheet all show "2 eggs"; the Week's "Add this week's
  ingredients" and "What I need", which render through `IngredientRendering` and ask nothing,
  do too once an answer is cached. A note, a second amount or unsure: as written. Display only:
  the editor shows the stored line, ticks stay at their index, and `aiDecisions` off or an
  unsupported phone or language shows every line as written. Pinned by the corpus's `Render`
  rows with `trailing:` and `names:` answers.

**Needs a real phone:** whether the models say "same" for the owner's corn and garlic pairs and
"different" for rice flour and whole milk with high confidence both times, whether they tell a
note from junk from a second amount, whether they copy "onions" out of "2 onions dfsafs"
verbatim and agree twice, how long the questions take on a long list, and how soon an opened
recipe's lines lose their junk (#174).

## A hyphenated mixed number is not a range (#125)

Taste of Home writes "1-1/2 cups sugar". The range reading ("1" to "1/2") scaled each end
and showed "2-1 cups" for ×2. Now a whole number, a dash (hyphen, en or em dash, the ones the
range code reads) and a fraction with no spaces ("1-1/2", "1-3/4", "2-½") is one quantity,
the first alternative of the shared quantity pattern, so every reader of the leading amount
agrees: the scaler, the unit converter, `GroceryCombiner`, `IngredientName`, amounts in
steps (#101), step timers ("Bake 1-1/2 hours" is 1 h 30) and the trailing-amount reader.
- Only a proper fraction: "1-3/2" is neither a mixed number nor a range anyone writes, so the
  line stays as written.
- Not after a slash or a decimal (lookbehinds), so "1/2-3/4" and "0.17-1/3" stay ranges.
- Spaces make a range: "1 - 2", "1-1 1/2" and "1-1/2 to 2" (a range from 1 1/2) are
  unchanged.
Pinned by the scaler tests on both platforms and the corpus's #125 rows.

## "I made this": your photos on a recipe (#116)

Owner's request: a private cooking journal on the phone, with no server and no accounts. It is
the groundwork for the backlog social feed (#117). Behind `cookedPhotos`, off by default. With
the flag off nothing shows, but the rules below (protection, deleting, backup) apply to any
photo that exists.

- **One photo per entry.** Each photo has its own `day` (an epoch day, like the plan's, so the
  date never slips across time zones) and an optional note of up to 280 characters. Picking
  several photos makes several entries. Adding is frictionless: a photo is saved at once,
  cooked today, and the first new one opens full screen so its note and date are right there.
  There is no "add" form to fill in first.
- **Where it shows.** "Your cooks" is the reading view's last section, after the steps and the
  note, so the reading view still opens on the recipe. Cook mode doesn't show it.
- **Storage.** Table `cooked_photos` (Room 14 / iOS `user_version` 13): `recipeId` CASCADE,
  `fileName` (nullable since Room 15 / iOS 14, for "Mark as cooked", #173), `day`, `note`,
  `createdAt`, `updatedAt`, `uid`. The picture is a JPEG in the
  app's own storage, named by the store and never by the user:
  - Android: `filesDir/cooked_photos`.
  - iOS: `CookedPhotos/` beside the database in the App Group container.

  Each picture is downscaled so its long edge is at most 2048 px, turned upright from its EXIF
  orientation, and saved at JPEG quality 85: a few hundred KB, sharp on any phone.
  - Android decodes with `BitmapFactory` (`inSampleSize`, then a `Matrix`) and reads EXIF with
    androidx `ExifInterface`. That library already came in through Coil and is now declared,
    because lint flags the framework copy.
  - iOS uses ImageIO's thumbnail API (`kCGImageSourceCreateThumbnailWithTransform`), which
    needs neither UIKit nor the main thread.
- **Files outlive rows until a delete stands.** Deleting a photo, or a recipe with photos,
  removes the rows at once and keeps the files, so Undo can restore them.
  - A delete stands when the snackbar goes away (Recipes swipe, a photo's own Delete), or at
    once for the recipe screen's confirmed delete. Then `RecipeRepository.forget` /
    `CookedPhotoRepository.forget` removes the files.
  - A launch sweep removes any file no row names and older than 10 minutes. That catches an
    app killed while its snackbar was up, and an import's unused copies. The grace period
    covers an add or an import still writing.
- **Protection.** A recipe with photos is never culled and never removed by the free tier's
  one-for-one, like a listed one: the photos would go with it. On import, a recipe with photos
  coming in comes in like a listed one.
- **Deleting a recipe deletes its photos.** The issue suggested asking whether to keep them;
  the owner decided the photos belong to the recipe. The confirmation says "with your 2 photos
  of it".
- **Recipes: "Recently cooked"** is a fourth sort (behind the flag): recipes with photos come
  first, ordered by their latest photo's day, and the rest follow in recency order. It is a
  sort, not a filter, so nothing disappears from the library. A stored Recently cooked reads
  as Recently viewed while the flag is off.
- **Sharing a photo** sends the JPEG through the share sheet with the recipe's name as plain
  text (Android `EXTRA_TEXT` through the FileProvider's new `cooked_photos` path; iOS
  `ShareLink` with a message).
- **Camera and library, and permissions.**
  - Android: the Photo Picker needs no permission. The camera is the camera app through
    `ACTION_IMAGE_CAPTURE`, writing to one reused file in the cache. The app doesn't declare
    `CAMERA`, so no permission is needed or asked for.
  - iOS: the system picker (`PHPickerViewController`, out of process) needs none. The camera is `UIImagePickerController`; iOS asks for
    access the first time the camera is chosen, with `NSCameraUsageDescription`, translated
    through `InfoPlist.xcstrings`. On a device without a camera (the simulator), choosing it
    says so.
- **Backup** (#26):
  - **Format.** An export with photos is a `.zip` holding `backup.json` (the same JSON, with a
    new `cookedPhotos` section: id, recipeId, day, note, createdAt, updatedAt, file) and each
    picture at `photos/<name>.jpg`. An export without photos is the same `.json` file as before,
    byte for byte, so with the flag off nothing changes. A file that doesn't start with a zip
    signature is read as JSON, so every older backup imports.
  - **Why a zip, not base64 in the JSON.** Base64 would mean a single string of tens of MB
    (+33%), held in memory while parsing. A zip keeps the JSON small and the pictures as files.
  - **How the zip is written.** Entries are STORED (JPEGs don't compress). iOS has no zip API,
    so `BackupArchive` writes and reads the format by hand: it reads through the central
    directory, and also takes DEFLATE entries (Compression's raw deflate), so a zip re-packed
    by another tool still imports. Android uses `java.util.zip`.
  - **Safety.** A picture's path must be `photos/` plus one plain name, and anything else in the
    zip is ignored. The JSON is capped at 20 MB, as before, and each picture at 30 MB.
  - **Merge.** Photos come in by uid, only with their picture, and only onto a recipe that is
    here after the import. `backup-v1-photos.zip` (written by Python's `zipfile`, a third
    writer) is read by both platforms' tests.
- **Android's cloud backup leaves the photos out.** The include list (database and settings)
  stays as it is: Auto Backup's 25 MB per-app quota would stop the whole app's backup, recipes
  included, once there are enough photos. A phone restored that way has the entries but not
  the pictures. Each entry keeps its day and note, shows "Photo not on this phone" in place of
  the picture, and offers no Share (`CookedPhoto.hasPicture`). The launch sweep only ever
  deletes files no row names, never a row whose file is missing. On Android, photos move to a
  new phone through the export `.zip`. On iOS, iCloud Backup carries them: `CookedPhotos/`
  sits beside the database in the App Group container, which nothing excludes from backup.
  Pinned by `CookedPhotoDaoTest` and `RecipeCookedPhotosScreenTest` (Android) and
  `CookedPhotoTests` (iOS).
- **The screen in short** (CLAUDE.md's summary until September 2026): "Your
  cooks" is the reading view's last section (after the note), a row of dated
  thumbnails and "I made this" (camera, library, or "Mark as cooked" with no
  photo, #173, below). Each photo is an entry
  cooked today; the new one opens full screen for its note (short, saved as
  typing pauses) and date; Share sends the photo plus the recipe name; Delete
  has an undo snackbar.
- **The viewer while its note is typed (#180).** The picture gives up its height to the keyboard,
  so the date and the note stay above it and × stays below the status bar, on both platforms.
  - iOS: a full-screen cover presented while the photo picker (or the camera) is still being
    dismissed came up with no safe area at all: × under the status bar, where no tap reaches
    it, and the note behind the keyboard, since keyboard avoidance is a safe-area inset too.
    It depended on timing (a photo opened later from its thumbnail was fine), so it was
    intermittent. The picked pictures are now handed to the ViewModel only from the picker's
    `onDismiss`, once it has gone. That is why the library is a `PHPickerViewController` in a
    `.sheet` rather than SwiftUI's `.photosPicker`, which says nothing when it has gone.
  - iOS: the note takes a return as a new line and nothing on the screen scrolls, so "Done"
    above the keyboard puts it away. The keyboard toolbar needs a navigation stack to show in,
    so the viewer sits in one whose bar is hidden.
  - Android: the dialog was `decorFitsSystemWindows = true`, so its window panned for the
    keyboard and `safeDrawingPadding()` then lifted the column by the keyboard's height again,
    pushing × off the top with a gap above the keyboard. It is now edge to edge
    (`decorFitsSystemWindows = false`), so the padding alone does it. Back puts the keyboard away.

**Needs a real phone:** the camera itself (the Android emulator's virtual scene and the iOS
simulator's missing camera prove only the wiring), EXIF orientation from a real portrait shot,
HEIC pictures from the iOS library, and an export with many photos shared and imported on the
other platform.

## The automatic backup copy (#150)

The owner's question: if someone loses their phone, how do they get everything back? The phone's
own backup (Google's Auto Backup, iCloud Backup) covers only a restore onto the same platform,
only if the user has it on, and on Android without photos. Export (#26) works across platforms
but is only as fresh as the last time the user remembered. So the app now keeps an export
itself, in a cloud folder that belongs to the user. There is no server, no account, and nothing
is sent anywhere the user didn't choose.

Owner's decisions: **on by default, and photos included.**

- **What is written.** The export exactly as Settings' Export makes it (`BackupRepository.export`),
  always in the `.zip` form of #116 (`backup.json` plus `photos/`, STORED), even with no photos,
  so every copy has the same kind of name. Import reads it like any export, on either platform.
- **Where.**
  - **iOS:** the `Documents` folder of the app's iCloud container
    (`iCloud.com.liberopat.recipeclipper`). With `NSUbiquitousContainers` set to public, it
    shows in Files as iCloud Drive → Recipe Clipper, with nothing for the user to set up. It
    needs the iCloud Documents capability and the container on the App ID, which only the owner
    can register (`docs/release.md`). Without them, or signed out, or with iCloud Drive off, the
    container is nil: Settings says quietly that iCloud Drive isn't available, and nothing
    crashes or retries loudly.
  - **Android:** a folder the user picks once with the system's folder picker
    (`ACTION_OPEN_DOCUMENT_TREE`), usually Google Drive, kept through a persisted URI permission
    (`AndroidBackupFolder`, over `DocumentsContract`, with no extra library). "On by default"
    can't mean silently on here, since nothing can be written until a folder is chosen. So the
    switch starts on, and the app asks for the folder at two moments, never at launch and never
    in the way of a share:
    - Settings → Your recipes has a "Backup folder" row.
    - Home shows a one-time "Keep a backup copy?" card once the library has a recipe and there
      is no folder yet. "Choose a folder" or "Not now" both put it away for good
      (`folderPromptDone`); Settings still has the row.
- **Three copies, not one.** Each copy is a new file,
  `recipe-clipper-backup-YYYY-MM-DD-HHmm.zip`, and the app's own copies beyond the newest three
  are deleted only after the new one is written. A write that fails halfway (a full Drive, the
  app killed) then never leaves the user with nothing. A single rolling file couldn't promise
  that on Android, where a document can't be replaced atomically. Only names the app wrote
  are ever deleted; nothing else in the folder is touched.
- **When.** The platform only asks for a look; `AutoBackupPolicy.isDue` decides whether to
  write. It writes when there is no copy yet, when the last copy is gone from the folder, when
  the library changed and the last copy is at least an hour old, or when the last copy is a
  week old (so the date stays true). "Changed" is a SHA-256 of the export with its `exportedAt`
  blanked, plus the photos' names. So an unchanged library is never written again, and opening
  a recipe (which moves it up the history) counts as a change.
  - **Android:** WorkManager (plain `CoroutineWorker` reaching Hilt through an entry point, so
    WorkManager's default initialisation stands). Three looks: a daily one (battery not low), a
    minute after the app is left (`MainActivity.onStop`; kept, so quick returns share one), and
    one at once when a folder is chosen.
  - **iOS:** when the app goes to the background, inside a `beginBackgroundTask`. The write is
    atomic, so an expired background task leaves the old copies. There is no BGTaskScheduler
    job: a library changes only while the app or its share extension runs, and the next time
    the app is left catches both.
- **"Last backed up".** Settings → Your recipes shows the switch, the folder (Android), "Last
  backed up <date>" or "Not backed up yet", "Back up now" (works even with the switch off,
  whenever there is somewhere to write), and in the error colour: a folder whose permission
  went ("Choose it again"), a copy that couldn't be written, iCloud Drive unavailable, and the
  nudge. **The nudge** shows when the last copy is more than 30 days old and no automatic copy
  is working (off, or nowhere to write). It doesn't show before the first copy: then "Not
  backed up yet" and, on Android, the folder card are the prompt. A manual Export doesn't count
  as a backup, because the app can't know where the share sheet put it.
- **Where the record lives.** Android: its own SharedPreferences file `auto_backup`, deliberately
  *not* on the Auto Backup include list. A folder permission belongs to one phone, so a phone
  restored from Google's backup asks for its folder again instead of showing one it can't
  reach. iOS: the settings' App Group `UserDefaults` suite (`auto_backup_*` keys), which iCloud
  Backup restores. That is right there, because the folder belongs to the iCloud account.
- **Restore on a fresh install.** Home, with an empty library, offers "Restore from a backup
  file" under the empty hint. It is Settings' Import (the same picker, the same
  `importFile`/`BackupMerger` merge, the same outcome line), so it merges and never replaces.
  On iOS the picker opens on iCloud Drive, where the copies are.
- **Not a flag.** It ships on, and the switch turns it off. It needs no kill switch beyond that,
  since without iCloud or a folder it simply does nothing.
- **Pure and tested.** `AutoBackupPolicy` (Kotlin and Swift) holds the rules: due, nudge, the
  folder card, names, which copies go, the fingerprint. `AutoBackup` runs them against fakes in
  `AutoBackupTest` and `AutoBackupTests`; the Settings and Home rows are covered by
  `SettingsAutoBackupTest` and `HomeBackupTest` (Robolectric) and by `SettingsAutoBackupTests`
  (iOS).

**Needs a real phone:** Google Drive (and another provider) through the folder picker, a copy
written there by WorkManager while the app is closed, a revoked permission showing in Settings,
and a restore from the Drive copy on a second phone. On iOS: a device with the iCloud container
registered, the copy appearing in Files → Recipe Clipper, and the same copy restored on a new
iPhone and imported on Android.

## Sending a grocery list, and receiving one (#149, phase 1)

Two people shop for one household; one may not have the app. Phase 1 is plain text, which
works either way.

- **Owner's decisions:** "Send list" sends every unticked item (no picker); each item names
  the recipe it's for; no live shared list (phase 3, sync, is dropped). Phase 2 (a small
  export file for recipes and items) is its own PR.
- **The text** (`GroceryShareText`): the title, then each aisle's name and its unticked rows,
  "- " before each, as the screen shows them (a combined row is its total; lines kept
  together are each listed, a repeat as "× 3"). A line ends with its recipes in brackets,
  "- 2 lb chicken thighs (Sheet-pan chicken)": every recipe the row's lines came from, each
  once, in the order added. The titles come from the recipes table through `recipeId`
  (`observeRecipeTitles`), so a typed item, or one whose recipe was deleted (`SET NULL`),
  names none. No link and no Markdown: it reads as a message.
- **Reading a list back** (`ReceivedList`, pure, both platforms): when any line starts with a
  bullet ("- ", "• ", "* ", en or em dash…), only bulleted lines are items, so a sent list's
  title and aisle headings drop out; text with no bullets offers every line. Blank lines,
  headings (a line ending in ":") and lines with no letter or digit are never items. "-5" is
  not a bullet. Nothing else is read: "× 3" and "(Recipe)" stay in the line as written, since
  guessing what a stranger's text means is how a confident wrong list happens.
- **"Add this list"**: the lines with checkboxes, all ticked, then **Add to groceries** or
  **Add to pantry**, one tap, no second confirm. Lines are added as written and read like a
  typed item: the phone's language, unless the lines' words clearly say another the app
  has. On the grocery list they combine as any lines do (`GroceryCombiner`). In the pantry
  each line becomes its `IngredientName` (the whole line when there's none), once per name;
  a name the pantry already tracks is put back in stock instead of added twice (a staple is
  left alone), as ticking a grocery line off did before #146. Adding to the pantry shows the Pantry.
- **Where a list comes in.** "Paste a list" in the Groceries menu reads the clipboard when
  tapped (an empty one shows the sheet with a sentence saying so). On Android, shared text
  with a link still imports the link, exactly as before; text with no link but with lines
  opens the Groceries tab on the sheet (through `ReceivedListInbox`, in memory), only with
  the `mealPlan` flag. The iOS share extension can't open the app (#19), so it shows the same
  sheet in its card and writes to the App Group database itself; the app catches up when it
  becomes active, like a shared recipe. The extension never sees the flags, so the app
  mirrors `mealPlan` into the App Group suite as `groceries_on` (as #107's limit is); off, a
  list shared in is "no link", as before.
- **The Pantry's "Send list" sends what's in stock** (owner's decision, 2026-09-26): what's at
  home, so someone at the shops can check before buying twice. Items switched to out are not
  sent: running out already put them on the grocery list (#146), so Groceries' "Send list"
  carries them, and sending them twice would read as two lists. Staples are sent when in stock,
  like any item. The text (`PantryShareText`, pure, both platforms) mirrors Groceries': the
  title ("Pantry", the tab's name), then each section as the screen's sort arranges it (aisles
  by default; sorted by expiry it is one list with no heading) and its in-stock items, "- "
  before each, the name and then the quantity as written in brackets ("- basmati rice (half a
  bag)"). No use-by dates: it answers "is it there", like the pantry itself. A search on screen
  doesn't narrow it: the menu sends the pantry, not the search. It is disabled (Android) or
  hidden (iOS) while nothing is in stock, as Groceries' is with nothing to buy. The receiver
  needs nothing new: `ReceivedList` reads the bullets, and "Add to pantry" keeps each line's
  `IngredientName`, so the bracketed quantity drops and "basmati rice" arrives as itself.
- **Tests:** the iOS UI test can't drive the system share sheet or read another app's copy
  without the paste prompt, so the launch seeds the pasteboard (`-uiTestPasteboard`, debug
  only); the sent text itself is pinned by unit tests on both platforms. The Pantry's text,
  and its round trip back through `ReceivedList` and `IngredientName`, is in
  `SendListTextTest(s)` and `PantryViewModelTest(s)`; `PantrySendTest` (Robolectric) checks
  the menu hands the share sheet that text.

## Sending recipes and groceries as a file (#149, phase 2)

Plain text (phase 1) works for anyone, but a recipe sent as text arrives as words, and a list
as lines to re-read. When both people have the app, a small file can carry the recipe whole
(no fetch, so a site that blocks the fetch doesn't matter) and the grocery items as the app
stores them. No server, no account: the file goes through the user's own share sheet.

- **The format is #26's export, partial and marked.** `ShareFile` (pure, both platforms) makes
  a `Backup` with `"kind": "share"` at the top and only what was picked: no lists,
  memberships, plan, menus or photos. `formatVersion` stays 1. An older app never offers
  itself for the file (it has no intent filter or document type for it), and if someone picks
  it through an older app's Settings → Import anyway, that app ignores `kind` as an unknown key
  and merges it like any export, which only ever adds: nothing is replaced or lost, so no bump
  is needed. A backup never has `kind`, so every backup file is byte for byte as before.
  Settings' Import in this app also takes a share file, merged whole. The canonical example is
  `shared/fixtures/backup/share-v1.recipeclipper`, read by `ShareFileTest(s)` on both.
- **Its own type, so the other phone opens it in the app.** `<title>.recipeclipper` (the title
  made safe as a file name, 60 characters at most), MIME `application/vnd.recipeclipper+json`.
  Android: an intent filter for VIEW and SEND by that type, and VIEW by the name for apps that
  hand files over as `*/*`; the app's FileProvider (`ShareFileProvider`) reports the type, since
  a plain FileProvider calls an unknown extension `application/octet-stream` and a messaging
  app passes that on. iOS: an exported UTType, `com.liberopat.recipeclipper.share` (conforms to
  `public.json`), and a document type the app owns, opened as a copy through `onOpenURL`.
- **What is sent.** "Send as file" is in the recipe screen's overflow menu (the share icon
  stays one tap for text) and in the Groceries menu after "Send list", which stays the first
  and default. A recipe goes complete, as saved: not scaled or converted (the receiver scales
  it), with its origin (an edit stays the user's version on the other phone too), but without
  what is the sender's own: ticks, the note, the last view (it is set to the time sent).
  Groceries send every unticked item, as stored, with the recipes they came from, so each
  still names its recipe on the other side; no planned day (a day on someone else's plan).
  The Pantry's menu has "Send as file" after its "Send list", sending the same in-stock items
  (`ShareFile.pantry`, pure, both platforms), as stored: quantity, staple and dates included,
  since the receiver's "Add from this file" already takes them to the Pantry or, as their
  names, to Groceries. This one is a default for parity with Groceries, not the owner's
  decision: it is its own menu entry and `ShareFileRepository.pantryFile`, so it comes out
  cleanly if the owner says no.
- **What the receiver chooses.** "Add from this file" opens over whatever is on screen: the
  recipes, the grocery items (each with its recipe beneath) and the pantry items, every row
  ticked, then two radio rows for the pantry items (the Pantry, or Groceries as their names),
  then one Add. Groceries and Pantry rows show only with the `mealPlan` flag, like their tabs.
  Once added, the app shows where things went: Groceries, else the Pantry, else Recipes. A
  file that can't be read says why (the export's errors), with nothing to add.
- **How it merges: `BackupMerger`, with two differences from an import.** Recipes match by the
  cleaned `sourceUrl`; one already here keeps its content, ticks and note (never replaced).
  Unlike an import, every ticked recipe comes in, as the newest viewed, and one already here
  counts as viewed now, and then the history cap runs, as for a shared link: sending someone a
  recipe is sharing it into their app. The free tier (#107) keeps the import's rule (only free
  places; the sheet stays up to say how many were left out). Grocery items come in by uid, so
  the same file opened twice adds each once; one keeps its recipe only if that recipe was
  ticked too (or matched one here). Pantry items follow the import: an item already here by
  uid, or by name and language, stands as it is. Sent to Groceries instead, a pantry item
  becomes a grocery line of its name, keeping its uid for the same reason.
- **Tests.** Pure: `ShareFileTest` / `ShareFileTests` (the fixture, the round trip, what is
  sent, what is chosen, the file name). Against SQLite: `ShareFileRepositoryTest` (Robolectric)
  and `ShareFileRepositoryTests`. ViewModels over fakes: `ShareFileViewModelsTest(s)`. Screens:
  `SendReceiveFileScreenTest` (Robolectric: both menus hand the share sheet the file, only
  readable by the picked app; the sheet adds what is ticked), and `PantrySendTest` for the
  Pantry's menu (the text, the file, both disabled with nothing in stock). iOS UI: `ShareFileUITests`, where
  `-uiTestReceiveFile` (debug only) opens a canned file at launch, because a UI test can't open
  a file from Messages.

**Needs a real phone:** sending the file through Messages, WhatsApp, Mail and AirDrop, and
opening it from each on the other phone (Android: which apps pass the type or the name, so the
app is offered; iOS: "Open in Recipe Clipper" from Files and Messages), in both directions
between Android and iOS.

## The first-run tour (#151, #190)

Owner's decisions: first (2026-09-26, #151) welcome cards, a bundled sample recipe and one-time
tips in the screens' flow; then (2026-09-27, #190) **the welcome cards go** ("just blocks of text
without showing anything"), with their route, "Try it with a sample recipe" and the welcome
state, and **tooltips point at the major features instead**: a small bubble with an arrow at the
real control, the first time it's reached. **The sample recipe stays**, added quietly. **Everyone
sees the tooltips, existing users included**, which reverses #163's "existing users skip every
tip". Settings' "Show the tour again" became **"Show tips again"**.

- **The sample recipe** is written for the app (a tomato and white bean soup; no photo, so
  nothing to license), in each UI language, once, in `shared/sample/recipe.json`: the UI's
  language picks it, else English, and it is saved in that language so its lines scale and
  convert with that language's tables. It shows the features off: Serves 4, US measures in
  English (so Metric and Ounces change it), timers in steps, an oven temperature, and a
  "Meanwhile" step that overlaps the simmer. It is saved like a typed-in recipe: MANUAL
  under the fixed link `manual:sample`, so it is never fetched, has no Update from source or
  source credit, and is never culled.
- **It is added quietly, once, at a new user's first launch** (#190; before, when the welcome
  first showed): `FirstRunTour.onLaunch`, before any shared link's route opens, adds it to an
  empty library (counted without it) and sets `tour_sample_added`; someone who already has
  recipes then (an older version's user, or a restored backup: Android's Auto Backup and iOS's
  device backup put the database back before the first launch) never gets it, and it is decided
  either way. Deleted, it stays deleted. A launch from a shared link adds it too, under the
  shared recipe (capture stays frictionless: the link still opens on its recipe). iOS's share
  extension saves without opening the app, so it takes the same step before a new user's first
  share (`FirstRunTour.beforeShare`, with `shared/sample` bundled in the extension too).
- **Its times read like a parsed recipe's (#179).** The file keeps ISO durations, so one value
  serves every language, and `SampleRecipe.forLanguage` passes each through `Durations.format`,
  the parsers' own formatting, in the sample's language: "10m · 25m · 35m" in English, "10min"
  in the others, as a parsed recipe in that language shows. `Durations` lives in `data/model`
  (it was `JsonLdRecipeParser.formatDuration`), so the model never imports the parsers. At first it copied them as written and
  showed "PT10M". A sample saved then is fixed in the database rather than at display, because
  the times are shown in several places (the reading view, Recipes' rows, shared text, the
  edit screen, backups): `FirstRunTour.onLaunch` calls `RecipeRepository.formatSampleTimes`,
  which formats the stored sample's three times the same way and writes only if they change.
  Formatting a formatted time changes nothing, so after the first launch it is one read by
  link and no write, with no flag to store; it also tidies a sample restored from an old
  backup. A sample the user has edited (`editedAt` set) is left alone: its times are theirs.
- **It never counts toward the free tier (#107):** the library's count (`RecipeDao.count`:
  the Recipes screen's "12 of 20", the free tier's one-for-one) and an import's free places
  leave out `manual:sample`, and adding it applies no limit, so it never removes a recipe.
  Home treats a library holding only the sample as empty (#150): "Restore from a backup
  file" still shows, which matters most on a new phone, and the "Keep a backup copy?" card
  waits for a recipe of the user's own.
- **The tooltips** (`Tooltips` in `data/model` / `Data/Model`, pure, with the same ids on both
  platforms; `shared/tooltips.json` lists them, and `TooltipsTest` / `TooltipsTests` fail if
  either enum differs from it). In this order per screen; a flagged one shows only with its flag:
  - **Home:** the link field ("Share a recipe link to this app, or paste one here"); "+ New
    recipe".
  - **Recipe (reading view):** the units dropdown, explaining "As written" (every amount as
    the recipe gives it; tap for Metric or Ounces; what can't convert exactly stays as written);
    the bookmark; the share icon; the ⋮ menu (it names Add to plan and Add to groceries only with `mealPlan` on, as the menu
    has them only then); "Start cooking"; "I made this" (the button, or the + tile once there
    are photos; `cookedPhotos`).
  - **Cook mode** (its own screen, though it is the recipe screen's): "Done — next step"; the
    next step ("tap any step to make it the current one"); the current step's timer; the
    ingredients bar.
  - **Week:** the first day's "+ Add"; the Month switch; the ⋮ menu. **Groceries:** "Add an
    item"; the first row's tick; the first row (long-press); "Done shopping" (while anything is
    ticked); the ⋮ menu. **Pantry:** the add field; the first row's "Ran out" / "Restock"
    (#194; it names the long-press's "Running low" too); the ⋮ menu. All `mealPlan`.
  - **Settings:** the Units choice; "Show tips again".
- **No Serves tooltip** (owner, 2026-09-29: "write a tooltip [that] explains the 'As written'
  section; can remove the serving tooltip, it's straightforward"). `recipe_servings` is gone
  from the catalogue, so the units dropdown is the recipe screen's first; a stored
  `tooltip_recipe_servings` key is simply ignored. The units text used to be "Show amounts in
  metric or ounces, in every recipe", which didn't say what "As written", the label a new user
  sees, means. Its bubble is asked to go below the dropdown on both platforms: left to choose,
  iOS put the longer text beside it, over the title. Asking showed that iOS's side was
  inverted: `arrowEdge` is the popover's own edge, so `.bottom` put a bubble above its control
  (cook mode's "below" ones too). Fixed with it; `TooltipsUITests` now checks the side.
- **Which one, when** (`Tooltips.current`, the same rule on both platforms): a **visit** is one
  appearance of a screen, from when it shows until it's left (a rotation isn't a new one on
  Android: the visit's token is saved state, and leaving isn't reported while the activity is
  changing configurations). A visit shows **at most one** tooltip: the first in the catalogue's
  order that is unseen, has its flag on, and whose control is **wholly on screen** (not scrolled
  partly away, not under a bar or the keyboard). Once picked it stays the visit's: scrolled away
  it hides and comes back with its control, and nothing takes its place. Dismissed, the screen's
  next one waits for a **later visit**, never chained. None shows in a screen's **first second**,
  nor while anything covers it: a dialog, a sheet, a menu, the share sheet, the keyboard or a
  snackbar. The whole app has one `TooltipsViewModel` (Android: MainActivity's, through
  `LocalTooltips`; iOS: the container's, in the environment), so there is only ever one.
- **Dismissing:** "Got it", or a tap anywhere on the bubble (it is one button), marks it seen
  for good. On iOS a tap outside the popover also closes it, as popovers do; that is "not now",
  not "seen": it shows again on a later visit.
- **Never over cook mode's current step text:** "Done — next step", the timer and the next step
  put their bubble below themselves, and the ingredients bar puts its bubble above itself (over
  the top bar). Android places it itself; iOS 18 and later honour the side asked for, and iOS 17
  picks the side itself.
- **Android's bubble** is a `Popup` of its own, placed from the control's window bounds
  (`Modifier.tooltipAnchor`, a `Modifier.Node` reporting `onGloballyPositioned` to its screen's
  `TooltipHost`), in the theme's inverse colours (ink with ground text and "Got it" in paprika on
  ink; the reverse in dark mode) and Karla, with an arrow drawn at the control. It is **not
  focusable**, so a tap outside it and TalkBack reach the screen as before (no focus trap), and a
  live region announces it. "Something covers the screen" is the window losing focus (a dialog,
  a sheet, a menu and the share sheet are windows of their own), the keyboard, or the screen's
  own `blocked` (its snackbar). Material 3's `TooltipBox` was the alternative: it wraps each
  control in a box of its own, and its popup is focusable and dismissed by any tap outside,
  which a tooltip that waits for "Got it" doesn't want.
- **iOS's bubble** is SwiftUI's `.popover` kept a popover on iPhone
  (`presentationCompactAdaptation(.popover)`), shown from our state (`.tooltipAnchor` on the
  control, `.tooltipHost` on the screen), in the same inverse colours and Karla, wider at the
  accessibility text sizes; VoiceOver reads it as it appears, and its escape gesture or a tap
  outside closes it. **Not TipKit**, though it is first-party and has `popoverTip`: TipKit keeps
  its own datastore of which tips were shown and closed, which can only be reset before
  `Tips.configure` (so "Show tips again" would need a relaunch), and it decides eligibility itself,
  asynchronously, so it would be a second source of truth beside the settings keys, the visits,
  the first second and the anchors' visibility, which are ours on both platforms anyway. A popover
  can't show over another presentation, so each screen passes `blocked` for its own sheets,
  dialogs and snackbars, and the host blocks while the keyboard is up. Toolbar items (the recipe's
  bookmark, share and menu, and the tabs' menus) are anchors too (`inToolbar`: on screen while the
  bar shows), so `.tooltipHost` goes outside `.toolbar`. Anchors are measured in the host's own
  coordinate space against its own bounds (a background's geometry reaches under the bars, and a
  push animation moves everything). A NavigationStack's root doesn't always hear `onDisappear`
  when a screen is pushed over it, nor `onAppear` when that screen goes, so leaving the top screen
  makes the one under it the visit again, after its settling second; and a screen's reports are
  kept by its token, so one still alive underneath can't overwrite the one on top (Android too).
- **State** lives in `unit_preferences` / the settings suite, backed up with the settings, under
  the same keys on both platforms: `tooltip_<id>` (true once seen; "Show tips again" removes
  them) and `tour_sample_added`. #151's `tour_welcome` and `tour_tip_*` keys are **ignored**:
  never read or written again (left where an older build wrote them, which costs nothing), so
  everyone starts with every tooltip unseen.
- **"Show tips again"** is an action row in Settings' Help section: every tooltip once more, one
  at a time, as each screen is visited (the Settings screen's own first one can show at once).
- **Tests:** the rule and the catalogue, pure (`TooltipsTest` / `TooltipsTests`); the ViewModels
  over fakes (`TooltipsViewModelTest(s)`); Android's screens under Robolectric
  (`TooltipsScreenTest`: Home's first visit shows the first tooltip after its first second, Got
  it, the next visit the next; Settings' second shows only once scrolled to; the bubble's
  placement at its control), and iOS's recipe screen in `TooltipsUITests` (one per visit, at the
  units dropdown, then the bookmark on the next). Every other screen test, UI test and walkthrough
  starts with every tooltip seen: Android's screen tests provide no `LocalTooltips`, iOS's test
  containers use `MemoryTourPreferences`, and `-uiTestTooltips` (iOS) or `firstRun = true`
  (Android's walkthroughs) turns them on. Walkthrough 17 shows the sample on Home and a tooltip
  or two.
- **Needs a real phone:** how each bubble sits at its control on small and large screens, in
  both themes, at the largest text sizes and on iPad; TalkBack and VoiceOver announcing one;
  cook mode's bubbles clear of the current step (iOS 17 especially).

## Done shopping: putting things away in one step (#146)

The owner found the per-tick snackbars annoying and easy to miss: tick one more item and the
offer for the first was replaced and gone. Owner's decisions:

- **A tick only ticks.** No snackbar, no pantry change, nothing offered.
- **One "Done shopping" button for putting away** (one button rather than "Put away…" beside
  "Clear checked"; clearing without putting away came back in the menu with #219, below). It shows at the bottom of Groceries while anything is ticked, and
  opens a sheet of the ticked items with checkboxes: one row per pantry item or ingredient
  name (two butters the pantry tracks as "Butter" are one row), in the list's order. What the
  pantry tracks starts ticked; the rest starts unticked, since the pantry holds what the cook
  chose to track. One confirm restocks the ticked tracked items and adds the ticked new ones
  (the ingredient's name, the grocery's aisle, bought today), then clears **every** ticked
  line, listed or not. A line the app can't name ("salt and pepper") isn't listed but is
  cleared; with nothing to list, the button clears at once.
- **Pantry-tracked items restock only at put-away,** never on the tick.
- **One undo for all of it.** The snackbar ("Pantry updated, checked items removed", or
  "Checked items removed" when nothing went in the pantry) puts back the cleared lines, the
  restocked items as they were (a snapshot) and deletes the added ones.
  `PantryRepository.add` returns the new id for that.
- **Snackbars only for undo:** a delete, Done shopping, or a clear from the menu (#219). The
  rest of the app already used them that way.
- **Clearing from the ⋮ menu, both** (owner, 2026-09-29, testing on a phone, #219: waiting for
  the bottom "Done shopping" button to clear "doesn't feel good"). "Clear ticked items" removes
  only the ticked lines, at once, with no Done shopping sheet, so nothing goes into the pantry;
  it's disabled while nothing is ticked. "Clear the whole list" removes every line, ticked or
  not, after a "Clear all 14 items?" dialog (the count is the rows shown: an added-up or
  "Together" row is one item); it's disabled while the list is empty. Either raises the undo
  snackbar, whose Undo puts back exactly what went (the repository's delete and restore). The
  pantry is never touched by these; Done shopping stays for when the cook does want to put
  things away. Send list, Send as file and Paste a list stay in the menu.
- **Ticks don't move under the finger** (#219; the owner found the instant jump "strange"). A
  ticked row stays exactly where it is, struck through and dimmed, and so does an unticked one.
  The ViewModel lays the list out with each item's tick as it was when the order was last
  worked out, and shows it with its tick now; a row ticks all its lines, so an added-up row
  stays one row. The order is worked out afresh (ticked rows sink to the bottom of their aisle,
  as before) when the screen is left (Android: the screen leaves composition other than for a
  rotation, which keeps the order; iOS: `onDisappear`), and at once when anything but a tick
  changes: an item added, deleted or cleared, Done shopping, a move to another aisle, or a
  change from elsewhere (Add to groceries from a recipe, the pantry adding or removing a line).
  Leaving the app for another and coming back is the same visit.
- **Pantry to groceries shows a state, not a message.** Marking an item out (or, since #194,
  running low) adds its name to the list silently (unless its own line, the item's name as
  written, is there already: `PantryList.ownLines`), and the row shows a small tag. **The tag
  shows whenever the item is on the grocery list, in any stock** (owner, 2026-09-29: a typed
  "2 onions" or a recipe's "1 onion, sliced" showed no tag on "onions"): any unticked line, same
  language, that is the item's own name or whose ingredient's name matches it as What I need
  matches (`PantryList.onListLines`, through `IngredientName.matches` and the listed plural
  pairs: "red onion" isn't "onion", "rice flour" isn't "flour"). Tapping the tag removes every
  one of those lines, a recipe's included, so it raises an Undo snackbar ("Removed from
  groceries", or "Removed 2 items from groceries") that puts back exactly those lines. Its
  spoken action is unchanged ("Remove from grocery list"). Until then the tag meant only the
  item's own line, so tapping it never touched a recipe's line and needed no undo. Running out's
  check for the own line, and Done shopping, are unchanged. No schema change: the link is the name.
  Restocking (from the row, or putting away) returns an item to In stock; it never takes the
  line off the list.
- **The tag is the Groceries tab's basket and label** (owner, 2026-09-29, testing on a phone:
  "On list" wasn't understood). The same outlined paprika chip, same place (after the name and
  "Low", wrapping under a long name or large text), same meaning and tap, but it wears the tab's
  own icon (`ic_tab_groceries`; iOS `basket`) and its "Groceries" label, so it says where the
  item went. Spoken, it's "On your grocery list", and its action "Remove from grocery list". At
  the largest text sizes (Android font scale 1.5 and up, iOS accessibility sizes) only the basket
  shows. The Pantry tooltip names it: "The basket tag means it's on your grocery list."

## Using up the pantry at the end of cooking (#147)

The owner's idea: cook with 1 lb of the pantry's 2 lb of chicken and the pantry says 1 lb.
Owner's decisions: subtract **once, at the end of cooking, through one sheet**, never on a tick
(people tick to gather, untick by mistake, and change servings mid-recipe); and a line that
can't be worked out **asks each time** (keep, running low or out), never guessed.

- **Two triggers: cook mode's "Done — finish"** (the last step's button, which ends cook mode),
  with at least one ingredient ticked, **and a photo added with "I made this"** (#116; the owner's
  decision of 2026-09-26, because some cooks don't use cook mode, and it also covers recipes with
  no steps).
  - Leaving cook mode by Exit is a pause, not the end: cook mode keeps its place "so it is never
    lost by a stray tap on Exit", and cooks leave to check something and come back. It never
    triggers.
  - "I made this" offers the sheet once the new photo's full-screen view (its note and date) is
    closed, so the sheet never covers the note; on iOS from the cover's dismissal, since one view
    can't present a sheet while its cover is still leaving. A photo deleted straight away (the
    wrong picture) offers nothing. The lines: the ticked ones if any are ticked (what cook mode
    hands over), else every line, since the cook says they made the recipe.
  - "Mark as cooked" (#173) is this same trigger with no photo: the same `madeThis` signal once
    its entry's view closes, so the same lines and the same 12-hour guard. It adds no third rule.
- **One cooking is offered once.** Each photo is its own "cooked today" entry, and a cook who
  finishes cook mode may add a photo too, so both triggers check when that recipe's sheet was
  last **confirmed or dismissed**, and offer it only if that was **12 hours ago or more**: a
  dinner, its leftovers photographed later and a second photo all fit inside it, while cooking
  the same recipe twice within 12 hours is rare (and the Pantry can be edited by hand). Undo puts
  the time back as it was, since nothing was used up after all. A sheet left open by a killed
  app records nothing, as nothing changed.
  - **Stored as one preferences key, not a column.** `pantry_use_up` in `unit_preferences`
    (iOS: the settings' UserDefaults suite), `id:millis` pairs. A `recipes` column would have
    cost migration 14→15, its `MigrationTest`, the iOS `user_version` step and a device run, for
    a value that means nothing after 12 hours. Each write keeps only the entries still inside
    the window, and recipe ids are never reused (AUTOINCREMENT on both platforms), so a deleted
    recipe's entry can't hold back another and none needs cleaning on delete; the key holds
    only the recipes settled in the 12 hours before its last write. It is backed up with the settings (and restored
    with the database they match); the export file doesn't carry it, as it carries no settings.
- **The sheet lists the handed-over lines' pantry items**, the lines as the recipe showed them
  (scaled to the servings used and converted to the chosen units). A line is matched as Have/Buy
  matches it (`PantryMatch.find`, and the model's cached definite "same" when there is one; no
  new question is asked here). Staples are left out (a staple is never Buy, so it never runs out
  onto the list), and so are items already out (nothing to subtract). Lines with no name and
  lines matching nothing aren't listed. Several lines using one item are one row, added up.
- **No schema change.** The pantry's free-text `quantity` is read at subtract time by the same
  reader as the lines (the scaler's amount patterns, the unit words, a second measure in
  brackets) and written back in its own style: the amount and unit are replaced and the rest
  kept ("2 lb pack" → "1 lb pack", "6" → "4", "2,5 kg" → "1,5 kg"). Structured columns would
  need this same parser to fill them, plus a migration and an edit form on both platforms, and
  would buy no case the text can't do: a quantity the parser can't read ("half a bag") asks,
  which is exactly what the owner wants for it. The text the user typed stays the one truth.
- **What subtracts** (`PantryUseUp`, pinned for iOS by the corpus's `UseUp` rows): the
  quantity and every line are one exact amount each (no range, "plus", alternative, second
  amount after the name, package or piece); the same kind (weight, volume, or a count); volume and
  weight only through the density table or the line's own second measure ("1 cup (125 g)
  flour"). A count counts the ingredient itself only when nothing but a size stands between the
  number and the name ("2 large eggs", the names tables' new `countSizes`); "2 cloves garlic" or
  "1 can tomatoes" against "3" asks. Within one family (g/kg, oz/lb, the ml family, US spoons
  and cups, sticks, counts) the result is exact, in the quantity's own unit when that shows it
  exactly ("1 1/2 lb"), else a unit a line used, else g or ml ("880 g" rather than "0.9 kg").
  Across families it's rounded as the converter rounds its results (g/kg or ml/L, oz/lb), with
  Metric's 240 ml cup, so the pantry agrees with the recipe's Metric view; an imperial volume
  that isn't exact asks, since the converter never writes cups.
- **Used up** (zero or below, or too little to show): out of stock, the quantity cleared (none
  is left to know, and a restock shouldn't bring back an old amount as a confident number), and
  the name onto the grocery list unless it's there already, so the row shows #146's basket tag.
- **"Running low" sets the Running low state** (#194; before it, running low had no state of
  its own and only put the name on the list): the item stays in stock, gets its "Low" tag, and
  its name goes on the grocery list. "Out" sets Run out plus the list, as the row's "Ran out"
  does (the quantity stays as written). No schema change of #147's own: #194 added the column.
- **One confirm, one Undo.** Worked-out rows start ticked and can be unticked; asked rows start
  on Keep. The snackbar ("Pantry updated") puts the pantry rows back from a snapshot and takes
  off the grocery lines it added. Dismissing the sheet changes nothing; the sheet is in memory,
  so a killed app loses it with nothing changed. Behind `mealPlan`, like every pantry feature.

## Reddit posts (#11)

The design is the issue's: one `.json` fetch, the post body if it splits, else the best comment
that splits, else `NoTranscription` with the post's photo. What was decided when it met the
app main had become:

- **Behind a `reddit` flag, on in debug and release** (the owner's rule for flags). Off, a
  Reddit link goes to the blog source, as before #11. The iOS share extension never sees the
  flags, so the app mirrors this one into the App Group suite (`reddit_on`, on until written),
  as it does `mealPlan` for "Add this list".
- **No rendered page and no page text for a Reddit post** (`RecipeSource.readsRenderedPage`).
  The blog parsers can't read Reddit's rendered HTML (there's no recipe markup in it), so a
  WebView load after a 429 would only add up to 20 s before the same Blocked; and its text is a
  whole thread, which the on-device model (#103) shouldn't pick a recipe out of when the
  splitter, by design, declined. On iOS a page Safari already rendered (#35) is ignored for the
  same reason. `RoutingRecipeSource` forwards `fetchPage` too, so blog pages keep their text for
  the model.
- **Blocked by Reddit: "Clip it yourself" by default (#213).** In September 2026 reddit.com
  began answering the app's `.json` read, on the owner's phone and the development machine alike,
  with a 403 page saying "You've been blocked by network security": its wall for clients that are
  neither a browser nor signed in through its API. Waiting doesn't lift it, the app already sends
  Reddit's documented User-Agent shape, and disguising the app as a browser is out (no chasing a
  user-agent). Owner's decision (2026-09-29): a Reddit link (`reddit` flag on) whose read still
  ends `Blocked` after the repository's one retry, with no saved copy, opens "Clip it yourself"
  (#37) on the post in the import's place, where the web view, a real browser engine, is let in.
  `RedditUrls.clipsWhenBlocked` decides and the import's ViewModel acts on it; the clip replaces
  the import, so Back goes where the share came from, and a saved clip then replaces only the
  clip. A note at the top says "Reddit didn't let the app read this post, so it's open here:
  select the recipe." (announced by TalkBack and VoiceOver). **No Try again there** (owner, the
  same day): this block isn't one that lifts, so a retry would be a dead end; sharing the link
  again reads it again. Unchanged: a saved copy opens from it; `Offline`, `FetchFailed` and
  timeouts keep their error screens with Try again and the reload on reconnect; a post that reads
  but has no recipe text is still `NoTranscription` with "Read the photo"; other sites' blocks
  keep theirs. A `/s/` share link is loaded as it is (the web view follows the redirect) and the
  clip is saved under the link that was shared.
- **Reddit's check in the clip view** (the owner's S23, 2026-09-29: the post never showed, only
  a pulsing Snoo). Reddit first answers a browser with a check page whose script works out a
  token and submits a hidden GET form back to the same path (`?solution=…&js_challenge=1&jsc_token=…`).
  The clip view blocked that as a link to another page, so the check never finished. Now
  (`ClipNavigation`, both platforms, pure and tested) the page may send itself back to its own
  address, same host and path with a new query, when no one tapped (Android: no user gesture;
  iOS: not a tapped link); a tapped link there still doesn't load. An app's own link
  (`intent:`, `reddit:`, `market:`) never loads in the page's frame, even as a redirect: it
  could only leave an error page in the post's place.
- **Reddit's page as a reader in the clip view** (`shared/web/reddit-reader.js`, injected on
  reddit.com only, beside `clipper.js`: Android when the page shows and again when it has loaded,
  iOS at the document's start). The owner's S23, 2026-09-30: "the longer I'm on the page reddit
  kind of fades at the bottom to white and I can no longer scroll down". A signed-out reader gets
  a long post body cut off under a fade to white with a small "Read more"
  (`.read-more-overflow-cover`), and Reddit's app and sign-in prompts (Open App, Google's One Tap,
  bottom sheets) can lock scrolling. The script's style shows the whole body and hides those
  prompts, and restores `overflow` on html and body; an observer puts it back as Reddit renders.
  It only hides: nothing is clicked or removed, so Reddit's check (#223) and the selection work
  as before. It also hides the related posts under the comments and makes a tapped link to
  another page do nothing: Reddit changes post inside the page with its own router, which the
  clip view's navigation rule never sees, and on the emulator a stray tap there swapped the post
  being clipped for another.
- **A Text view for Reddit** (owner, 2026-09-30: built beside the reader, not as its fallback,
  because Reddit's prompts and markup change often and old.reddit.com now wants a login). A Text
  button in the clip's top bar reads the post as it stands (`RCReddit.text()`: the
  `shreddit-post` and the comment tree, from the DOM, so it works under any overlay) and shows its
  title, body and loaded comments as plain paragraphs in a second web view with `clipper.js`, so
  selecting, assigning, marks, Review and Save are the same; Page goes back, the page kept under
  it. What counts as the text is decided natively and tested (`RedditPageText`, a block a line,
  a comment's own text without its replies'; `RedditTextPage` builds the escaped page, its labels
  unselectable). **Only the comments Reddit has loaded are there** (deeper threads load as the
  page scrolls), which the view says; the post body and top comments are what matter. A page
  with no post yet (still loading, or the check) says to wait. The photo is still picked on the
  page: Photo goes back to it.
- **Not old.reddit.com.** Its plain HTML would suit clipping, but since September 2026 it answers
  a signed-out reader with "Log in to use old Reddit" (seen in the web view and with curl). So
  the clip view goes the other way: a link on old., new., m., np. or bare reddit.com loads from
  www.reddit.com (`RedditUrls.clipPageUrl`), with its path and query; the clip is still saved
  under the link that was shared.
- **Reddit's official API is not used, by decision** (owner, 2026-09-29): reading posts through
  it would need an installed-app OAuth client registered by the owner and Reddit's terms for an
  app, a cost the owner doesn't want. Clipping in the web view is the answer to the block.
- **iOS's share extension can't open the app** (#19), so for a blocked post its card says to open
  Recipe Clipper ("…the post will be open there, to select the recipe.", no Try again) and leaves
  the post in the App Group suite (`PendingClip`: `pending_clip_url`, `pending_clip_at`). The app,
  becoming active within 10 minutes, opens the clip on it in Recipes, with the note, once; an
  older one is dropped, and each new share replaces or clears it. The window keeps #19's rule
  (no jump on a later launch): a post shared a moment ago is what the cook opens the app for.
- **`NoTranscription` offers Try again, and "Read the photo" when the post has a picture**
  (#198, below): no "Report this site" (Reddit isn't a site whose markup the app could learn)
  and no "Clip it yourself" (the recipe, when there is one, is usually in the photo). Both stay
  tied to `NoRecipeFound`, which a Reddit link that isn't a post still gives.
- **The recipe's language** comes from its words, as for any page with none declared (#14).
  Since #208 (below) the text is also read in that language: its headers, labels and units.
- **Detection was widened after the first phone test.** On the owner's S23, posts that had a
  recipe (shared from the Reddit app, so `/s/` links) showed "No recipe text found": the
  fetch and the share link worked, the splitter missed. reddit.com answers 403 to the
  development machine, so real text came from recorded responses instead: PRAW's test
  cassettes (the `/s/` 301 and its `?share_id=…&utm_…` target, comment trees with
  `is_submitter`, `more` stubs and a stickied AutoModerator, crossposts, galleries) and 54
  archived r/recipes `.json` listings on GitHub. The first splitter read 21 of their recipes;
  this one reads 30, and the requests, questions and link-only posts still read none. What
  real posts needed:
  - Headers set apart as headers (bold, a Markdown heading, a trailing colon, capitals) may
    have up to three words before the keyword ("Dry ingredients", "Cooking steps",
    "Ingredient amounts:"), a typo two letters off ("Ingredeints:", "Intructions"), an emoji
    or a bracket. A word that names a group ("Dry", "Sauce") stays as the group's heading; a
    line that starts like a step ("**Mix the dry ingredients**") is never a header.
  - Ingredients with no header: the lines just above the steps, read upwards while each reads
    like an ingredient (an amount, a list item or a short line), with at least two amounts.
    The note or story above them ends the block.
  - Steps with no header: a numbered list starting at 1, or "Step 1", after the ingredients.
  - Reddit's editor escapes a typed "1." as "1\."; glyph bullets ("•", "・", a copied card's
    "▢"); a bold line inside a section is a group heading ("Sauce:", the app's convention);
    a line with a bare link ("More on my blog: https://…") is left out.
  - **The poster's own comment first** (`is_submitter`): r/recipes asks for the recipe in a
    comment by the poster of a photo, and another reader's recipe can come first.
    **AutoModerator's comments are skipped** (a subreddit's "post it like this" template can
    split), but not the replies under them: r/Old_Recipes asks for transcriptions as replies
    to the bot.
  - **A crosspost's comments are on the original's thread**, so a crosspost with no recipe
    of its own costs one more fetch, of the original (`crosspost_parent`); any failure there
    keeps the crosspost's own outcome.

  Still never prose: steps written as paragraphs with no header and no numbers aren't steps,
  so a real post whose method is "Preheat oven to 375" and three paragraphs stays
  `NoTranscription`, as does chatter with a number or a list in it. The fixtures are JSON
  files in `shared/fixtures/reddit`, made-up posts in the real `raw_json=1` shape.

## Pantry stock: In stock, Running low, Run out (#194)

Owner's decision (2026-09-27): the per-row on/off switch read like a setting, not a fact about
the cupboard. It is replaced, and a **Running low** state added.

- **Three states, shown by grouping.** In stock and running low sit in their aisle sections
  (running low with a small "Low" tag); run out sits in one dimmed "Run out" section at the
  bottom, in either sort (`PantryList.arrange`, `PantrySection.runOut`).
- **Every state is reachable from something visible; gestures are shortcuts** (owner,
  2026-09-28: touch and hold isn't discoverable). Tapping a row opens its edit sheet, which
  starts with an In stock | Running low | Run out segmented control showing the current state.
  It applies at once through the same path as the menu and swipes (`onEditStock` →
  `onSetStock`), not on Save, so the basket tag and the grocery side effects are identical;
  dismissing the sheet keeps it. The Pantry tooltip points at the first row: "Tap an item to
  change its stock or quantity; swipe left when it runs out. The basket tag means it's on your
  grocery list."
- **No button on the row** (owner, 2026-09-29: "redundant when I can click the item"). Until
  then each row ended in one text action ("Ran out", or "Restock" once out), which replaced the
  switch. In its place, on the right, the item's **quantity as written** (muted, cut short with
  an ellipsis, at most 40% of the row on Android; nothing when there is none), and the quantity
  line under the name is gone, so rows are shorter. State shows by section (Run out, dimmed) and
  the "Low" tag. The shortcuts: the row's menu (touch and hold) offers **both other states**
  (while the button existed it offered only the one the button didn't); swipes: leading
  Restock, trailing Ran out, each only where it changes something; iOS adds Running low as a
  second trailing action. Running low is also reached from #147's sheet. TalkBack and VoiceOver
  read the row as one ("Garlic, 1 head, Run out, On your grocery list") and offer both other
  states as its actions. iOS's Pantry became a `List` (as Recipes is) for the swipe actions; its
  basket tag is drawn inside the row's button, where it wraps with the name, with its own
  button laid over it.
- **Groceries as #146.** Ran out and Running low both put the name on the grocery list
  silently, shown by the basket tag; tapping the tag takes it off. Restock, Done shopping's
  put-away and typing a name already there all return an item to In stock (and bought today).
- **"Clear run-out items"** (owner, 2026-09-29), last in the ⋮ menu, disabled while nothing
  has run out: after a "Clear 3 run-out items?" dialog (the count is every run-out item, whatever
  the search; iOS a destructive alert), it deletes every item in the Run out section in one
  transaction (`deleteRunOut`), and one Undo snackbar puts them back exactly, ids and uids
  included. The grocery list is left alone: the lines running out added stay.
- **Presence only, unchanged.** Running low still counts as having it: What I need, the
  grocery sheet's first ticks, the model's candidates and expiry reminders all read `inStock`,
  which running low keeps. "Send list" and "Send as file" include running-low items (they are
  at home).
- **Stored as a second column, not an enum.** `pantry_items.runningLow` (Room 16,
  `MIGRATION_15_16`; iOS `user_version` 15, `addRunningLow`), `INTEGER NOT NULL DEFAULT 0`,
  beside `inStock`. It means something only while `inStock`: every write that takes an item out
  (`setStock`) and every restock clears it, and reading an out item ignores it. An `ALTER TABLE
  ADD COLUMN` leaves every existing item exactly as it was (in stays in, out stays out) and
  keeps every reader of `inStock` (matching, reminders, send, use-up) correct without a change,
  where a replacing enum column would have meant a table rebuild and touching all of them.
  The domain reads the pair as `PantryItem.stock` (`PantryStock`).
- **Files.** Export, backup and shared files carry `"runningLow"` beside `"inStock"`, still
  `formatVersion` 1: an older reader ignores the key and sees in stock or out, and an older
  file (no key) reads as In stock or Run out from its boolean. A file saying running low on an
  item that is out reads as Run out. Merging is unchanged: what's already here keeps its stock.
- "Always have" staples are unchanged.
- **iOS: one flat `ForEach` of headings and items** (#203; `PantryListRow`, ids `heading-…` and
  `item-<id>`). Items nested in a `ForEach` of sections got a row id that included the section,
  so a move between sections was a delete plus an insert, which the `List` once left drawn in a
  stale slot (garlic under Oils, a blank row, no Run out heading). Flat, the row keeps its id.
  A swipe's change waits 0.5 s for its row to close (#206, `afterSwipeCloses`). **Nothing at a
  row's root may depend on where it sits** (#216): #214 put the stock tooltip's anchor there as
  `if first { … } else { … }`, so a Restock that made garlic the first row gave the `List` a new
  row in the swiped cell's place, and the old cell stayed drawn, dimmed, under Run out; the
  anchor is now a `.background`. Only a screen recording shows it: the accessibility tree and
  XCUITest's screenshots were right.

## The recipe screen's ViewModel, split into collaborators (#169)

`RecipeViewModel` had grown to 821 lines on Android and 715 on iOS, ten constructor
dependencies on Android, and five jobs in one file, so every reading-view feature (Chef mode,
amounts in steps, planned servings, photos, #147's end of cooking) landed there and collided
with parallel work. An audit found MVVM otherwise followed cleanly, so the fix is inside the
screen, not a new layer. A behaviour-preserving refactor: no UI, string, state or behaviour
change, and every existing ViewModel, screen and UI test passes unchanged.

- **`RecipeRenderer`** (pure; `ui/recipe`, iOS `UI/Recipe`): the recipe, its servings, the
  settings (units, liquids, oven unit, amounts in steps), the model's decisions and Chef mode's
  short steps in, `RecipeContent.Success` out. It composes logic the corpus already pinned
  (scaling, conversion, timers, amounts in steps); its own rules (the servings clamp, the factor,
  short steps shown only one per step) are pinned by the corpus's `Render` rows, generated from
  the Kotlin like every other row.
- **`CookSession`** (pure over `Clock`): cook mode's state machine (start and resume, select,
  done, the ingredients bar, the end of cooking #147 hooks into) and the timers as wall-clock
  deadlines, the one thing it keeps. It never touches alarms: each transition returns what to
  schedule or cancel, and `CookController` (#234; the ViewModel until then) keeps the tick loop
  (a coroutine, a `Task`) and the `TimerAlarmScheduler` calls. That's where #10-style progress
  rules now change, in one place tested without a ViewModel.
- **`ChefMode`**: the on-device model's two inputs to the renderer, short steps (#100) and
  count-bracket decisions (#104), with their repositories and flags. Not pure (it runs the model
  and follows the repositories on the screen's scope), so it says when an input changed and the
  ViewModel renders again, synchronously, as before. On iOS it goes with its screen and cancels
  its writing then, as the ViewModel's `deinit` used to.
- **The ViewModel stays the single owner of `uiState`** (one `StateFlow`, one `private(set) var`)
  and keeps loading and recovery and `shareText`; since #234 the serialized writes (ticks,
  notes, cook progress and servings, in order) are its collaborators' (below). Screens don't change. The constructors are unchanged, so
  Hilt, the navigation code and every test build it as before.
- **Where the platforms already differed, they still do,** since each side kept its own
  behaviour: iOS doesn't follow the `chefMode` flag live (it reads it when the setting changes),
  and iOS's cook mode on a recipe with no steps doesn't write progress. Neither is visible to a
  cook; aligning them would be a behaviour change, so it's left for its own issue if wanted.
- **Not done:** a use-case layer across the app (most screens would get thin pass-throughs);
  the AI recommendation pipeline (#164–#166) is where one would earn its place.

## "Mark as cooked", without a photo (#173)

The owner's idea: "I made this" (#116) recorded a cooking only with a photo, so a cook who
neither finishes cook mode nor takes photos never recorded it, and was never offered the
pantry update (#147). "Mark as cooked" records today's cooking with no photo. Behind
`cookedPhotos`, like #116; the sheet it offers behind `mealPlan`, like #147.

- **Where.** A third item in "I made this"'s menu, after Take a photo and Choose from library,
  from both the empty section's button and the row's +. One tap writes the entry (cooked today,
  no note) and opens it as a new photo opens, so the optional note and the date are right there:
  "Cooked" with a tick in place of the picture, and no Share. In the row it is a dated tile
  with a tick and "Cooked"; TalkBack/VoiceOver read "Cooked, no photo, <date>". Its Delete is
  "Remove from your cooks", with the same Undo snackbar ("Removed from your cooks").
- **The same entry, not a new kind.** A row in `cooked_photos` with no `fileName` (and so no
  path and no picture; `CookedPhoto.hasPhoto` false). Everything that reads cooked entries
  already treats it as one: the recipe's list and full-screen view, the Recipes screen's
  Recently cooked (`MAX(day)`), the protection from the cull and the free tier's one-for-one,
  deleting with the recipe, the export and the merge. The alternatives were worse: an empty
  file name as a sentinel is a magic value every reader must remember, and a separate table
  would have duplicated the sort, the protection, the cascade, the backup section and the
  delete. The table keeps its name; renaming it would touch every query for no behaviour.
- **Migration.** Room 14→15 (`MIGRATION_14_15`) and iOS `user_version` 13→14
  (`allowCookedWithoutPhoto`): SQLite can't relax NOT NULL in place, so the table is rebuilt
  (create `_new_cooked_photos`, copy every row with its id, drop, rename, recreate both
  indices). The AUTOINCREMENT counter is carried over in `sqlite_sequence`, so an entry
  deleted before the upgrade never has its id handed out again. Pinned by
  `MigrationTest.migration14To15…` (device) and `CookedPhotoTests.testAVersion13…` (iOS).
- **What counts as a photo.** The recipe's delete confirmation names the photos that go with
  it ("with your 2 photos of it"): it counts only entries with a photo, since a cooking marked
  without one isn't a photo (it still goes with the recipe, like its note). The import
  summary's `photosAdded` counts photos only too. A marked cooking protects its recipe from
  the cull like a photo does: it is the user's own record, and it goes if the recipe goes.
- **The pantry.** Closing the new entry sends the same `madeThis` signal as a photo, so
  `PantryUseUpViewModel.onMadeThis` offers the same sheet with the same lines (ticked, else
  all) and the same 12-hour `pantry_use_up` guard: marking a dinner as cooked and then adding
  a photo of it offers the sheet once. Deleted straight away, it offers nothing, as a photo.
- **Export and import: `formatVersion` stays 1.** An entry with no photo goes in its own
  top-level section, `cookedWithoutPhotos` (`id`, `recipeId`, `day`, `note`, `createdAt`,
  `updatedAt`; no `file`), written only when there is one, not in `cookedPhotos` with no
  `file`. The reason is the older app: its reader requires a valid `file` on every
  `cookedPhotos` entry and would refuse the whole file as malformed, while it ignores a
  section it doesn't know and imports everything else. So an older app loses only the marked
  cookings, and a version bump (which an older app refuses outright) isn't needed. Ids are
  unique across both sections. An export with marked cookings but no photos is the plain
  `.json`, as there are no pictures to zip. The merge takes a marked cooking by uid like a
  photo, needing no picture; its recipe comes in like a listed one. Pinned by the shared
  fixture `backup-v1-cooked.json`, read by both platforms' `BackupJson` tests (including the
  file with that section removed, which is what an older app reads).
- **The file sent to someone else** (#149) still carries no cooked entries of either kind, and
  `ShareFile.chosen` drops any a file holds.

## Singular and plural names are one ingredient (#191)

Walkthrough 03 showed "1 onion, sliced" under To buy with "onions" in the pantry. **Owner's
decision (2026-09-27): "Treat onion and onions as the same."** And, on colours: "red onions are
different, just as yellow onions are different than white."

- **Listed pairs, never a rule.** Each language's `names.json` has `pluralPairs`, `[singular,
  plural]` pairs of whole words (en 80, de 48, es 68, fr 48, it 56, pt 57, ja none). Nothing is
  inferred from a word's ending: "glass", "hummus", "asparagus", "couscous" and "molasses" are
  only themselves, and "peas" is "pea" while "pea shoots" is neither. Irregulars ("leaf"/"leaves",
  "uovo"/"uova", "Apfel"/"Äpfel") are just entries. A word is listed once, and never one whose
  numbers name different things: "pepper" (the spice) and "peppers" (the vegetable) are left
  out, as is Portuguese "pimenta"; "bell pepper(s)" were already both in the aisle table.
- **The pairs change only a word's number, never which words match.** `IngredientName.key`
  lowercases and trims a name and puts every listed plural in its singular, **wherever it
  stands**, because French, Spanish, Italian and Portuguese put the plural head first ("pommes
  de terre") and inflect their adjectives ("oignons rouges", "cebollas rojas"): those languages
  list their common colour adjectives too. `matches` compares keys under #51's rule unchanged,
  so "onion" = "onions" and "red onion" = "red onions", but "red onion" ≠ "onion" and "red
  onion" ≠ "yellow onion": a colour isn't a `matchModifier`, in either number.
- **One place compares names:** Pantry Have/Buy and the use-up sheet (`PantryMatch.find`, through
  `matches`), "What I need"'s rows and grocery grouping (by key; a row is named by its first
  line), the aisle table (aliases and names keyed; `SharedTablesTest` checks no two aisles share
  a keyed alias), Done shopping's one row per ingredient, running out's own line and typing a
  name already in the pantry (`IngredientName.same`), and a received list's names. The basket
  tag (since 2026-09-29) matches as Pantry Have/Buy does, through `matches`. Amounts in steps
  (#101) keep `steps.json`'s ending rules: a step's word is only ever compared with a line's own
  head word there, so they can't pair two different ingredients.
- **Adding up** (`GroceryCombiner`): counts add across a pair only when their words are
  otherwise identical, and the total's words are worded by the pair (`IngredientName.counted`:
  above one plural, else singular, keeping capitals): "1 onion" + "2 onions" = "3 onions", "1
  onion" + "1 onion" = "2 onions", "1 Zwiebel" + "2 Zwiebeln" = "3 Zwiebeln". "1 large onion" +
  "2 onions" or "1 onion, sliced" + "2 onions" sit together as written; "1 red onion" and "2
  onions" are different ingredients, two rows. A measured amount's words aren't counted, so
  they stay as before (the shortest). The pantry's count after using up is worded the same way
  ("2 onions" less 1 is "1 onion").
- **The aisle table** gained "vanilla bean(s)" (baking): once "bean" read as "beans", a vanilla
  bean would have been filed as canned. Keying also fixed a few wrong aisles ("zumo de naranjas"
  was produce through "naranjas", now drinks) and filed names the table missed in one number
  ("2 zucchinis", "1 chicken wing"), which were Other.
- **Tests that pinned the old rule, updated:** the corpus's `Pant("2 large eggs, beaten", "egg")`
  (now Have), `Groc(["2 cebollas", "1 cebolla"], lang: "es")` (now "3 cebollas") and the
  zucchinis row's aisle (now produce), all regenerated from the Kotlin.

## Reading a Reddit photo on the device (#198)

A photo-only post (a recipe card on r/Old_Recipes nobody has transcribed yet) used to end at "No
recipe text found". The owner's decision (September 2026): read the photo on the device, but
never save what it reads without the cook checking it, and fall back to typing it by hand.

- **Entry:** on `NoTranscription` for a post with a picture, "Read the photo" (filled) beside
  Try again (outlined), behind the `photoText` flag (on by default). Only then are the pictures
  fetched, at full size: `NoTranscription` carries `imageUrls`, every picture in order (a
  gallery's `gallery_data` order, each `media_metadata` source, not the preview; else the one
  photo; a crosspost's borrowed from the original).
- **On the device, behind a seam** (`PhotoTextReader`; fakes in tests, a canned one in iOS UI
  tests via `RC_UITEST_PHOTO_LINES`). Android: ML Kit Text Recognition's Latin model
  **through Google Play services** (`play-services-mlkit-text-recognition`, see Size below);
  the picture comes through Coil (its cache when already shown), capped at 4096 px a
  side, and lines keep ML Kit's per-line confidence. iOS: Vision's `VNRecognizeTextRequest`,
  accurate, language correction on, hinted with the phone's language then English (the
  recipe's own isn't known until it is read), through `ImageLoader`. A picture that fails is
  skipped; the read fails only when all do.
- **Sorted by the same splitter as a typed post** (`PhotoTextSorter` over
  `RecipeTextSplitter`), so headers, lists and numbered steps read the same, in the language the
  lines' words say (#208, below). Nothing is
  corrected: "l cup butter" stays "l cup butter". Lines under 0.5 confidence (Vision answers
  0.3 when unsure) are listed under **"Check these lines"**; a piece under four characters
  marks only a line that is exactly it.
- **An amount shaped like a misreading is listed too, however sure the recogniser was**
  (`PhotoTextSorter.suspect`, the same rules on both platforms). Found on a real card: Vision
  read "1 1/2 cups flour" as "11/2 cups flour" at full confidence, which the scaler read as
  5½ cups. Listed, never corrected: an improper fraction over 2 to 8 ("11/2", "13/4",
  "31/3", "3/2"); a digit beside a look-alike letter at a slash or point ("l/2", "O.5", "1/Z",
  "1O", "35o°F", but not "1oz" or "1l"); and a line whose leading amount the scaler reads with
  no unit because a unit of three letters or more is run into the next word ("1 cupraisins",
  "2 tbspsugar"; "g", "c", "l" and "oz" are left out, since "2 green onions" starts with one,
  and "cupcake" is a word). The units are the lines' language's (#208: "2 ELZucker",
  "1 tazaharina"), none in Japanese.
- **The scaler leaves an improper fraction on its own as written** (`IngredientScaler.parse`,
  every reader of an amount: scaling, conversion, groceries, timers): "11/2 cups flour" and
  "3/2 cup milk" are never read as 5½ or 1½, since a lost space is likelier than an improper
  fraction a recipe meant. After a whole number ("1 3/2") nothing changed.
- **The review is the #29 editor, pre-filled,** not a new screen: title "Check the recipe",
  the post's pictures above (to compare against), a note on how it went, the lines to check,
  then the usual fields, with the post's title as the name and its first picture as the photo.
  The editor keeps text boxes, so an unsure line is listed rather than marked inside the box.
- **Fallback:** no text, or text the splitter can't sort, opens the same editor with every line
  read in the ingredients box and "Couldn't read a recipe from the photo: finish it by hand."
  A picture that won't load says so, with Try again. **Reader not ready** (Android only:
  Play services hasn't got the model yet, because it is still downloading, the first use is
  offline, or the phone has no Play services): the read is `PhotoTextResult.NotReady` before
  any picture is fetched, and the editor says "The photo reader isn't ready on this phone
  yet…", with Try again and the fields below to finish by hand. The reader checks
  `ModuleInstallClient.areModulesAvailable` first and, when the model is missing, asks for it
  (`installModules`, not awaited) so a later Try again works; an `MlKitException.UNAVAILABLE`
  from the read itself means the same. Save needs a recipe (#29's rule); **nothing
  is ever saved automatically**, and leaving the editor ends the read.
- **Saved as a clip** (`saveClip`: `CLIPPED`, the post's link, `REDDIT`), like #37: the user's
  version, so a re-share opens it without fetching; "Clipped by you · reddit.com" under the
  title; "Update from source" warns first (and on a post with still no transcription, fails and
  keeps the clip). The saved recipe replaces the editor and the error screen.
- **Size, and why Play services (owner, 2026-09-28):** the bundled model
  (`com.google.mlkit:text-recognition`) was built first: it works offline from install, but is
  a native library per ABI (about 11 MB each for arm64, x86 and x86_64, 7 MB for armv7) plus
  1.5 MB of models, so the universal debug APK grew from 20.1 MB to 65.4 MB (about 12.5 MB per
  phone from an app bundle). The owner chose the Play services model instead: the debug APK is
  back to 20.3 MB (20,332,832 bytes, main's plus the feature's code). The cost is that the
  model is downloaded once by Play services: the manifest's `com.google.mlkit.vision.DEPENDENCIES`
  = `ocr` asks for it at install from the Play Store, so usually it is there before first use;
  when it isn't, the not-ready fallback above applies. iOS is unchanged (Vision is in the OS).
- **The same reader and review scan the cook's own photos** (#226, below): a recipe card, a
  cookbook page or a screenshot, taken, picked or shared in, saved as a typed-in recipe.

## Reddit posts and photos in every language (#208)

The owner's decision (2026-09-29): read Reddit posts (#11) and photos (#198) in every language
the app reads recipes in, not only English. A German card ("Zutaten … Zubereitung") used to fall
to "finish it by hand", and "2 ELZucker" wasn't flagged.

- **The text's language picks the words, as a page's does (#14):** `LanguageWords.detect` over
  the text (a post's body with its title; a comment alone; a photo's lines joined), else English.
  One language's words only, never merged. Detection needs at least 3 of `language.json`'s words
  and more than twice the runner-up's, so a short card may be too sparse to tell and is then read
  in English (its headers unknown, so it falls to "finish it by hand", as before).
- **Stored:** the recipe's language is `resolve(that language, …)` over its name and
  ingredients, so the scaler, converter and timers use the same tables; a post whose words
  say nothing clear is stored as before (#14's rule, English by default). The photo editor keeps
  the language its lines were read in until the cook saves.
- **Words in `shared/tables/<language>/splitter.json`,** English's moved out of the code
  unchanged: whole-line headers (ingredients, steps, notes), header keywords, the words beside
  them that name no group, verbs that make a line a step, typo targets, "for" headers, "Edit:"
  words, step labels ("Schritt 3", "Paso 3", "Étape 3", "Passo 3"), labelled yields and times,
  the words before an amount ("ca.", "environ"), unit words that make a line an amount, and the
  glued-unit words. `headings.json` (#103) is a different reader and is unchanged.
- **The fuzzy rules work per language.** German puts the keyword last ("Trockene Zutaten"), like
  English; Spanish, French, Italian and Portuguese put it first ("Ingredientes secos",
  "Ingrédients pour la pâte"), so `keywordFirst` also reads the first word. A header with a
  servings phrase needs no colon ("Ingredienti per 4 persone", "Zutaten für 4 Personen", read
  with `yield.json`'s serving words; English's "Ingredients for 4 servings" too), and neither does
  a yield line of that shape ("Pour 6 personnes"). Typos two letters off, bold/colon/caps
  markers, emoji and trailing parentheticals are unchanged. French's space before a colon was
  already trimmed.
- **Japanese:** whole-line headers only (材料, 作り方, 手順, 【材料】（2人分）: full-width brackets now
  count as a trailing parenthetical), with no keywords or typos (no spaces to find words by).
  Its amounts come after the name, so `amountPatterns` says what one looks like. A script without
  case is never "in capitals" now (it had counted every kanji line as capitals; only
  headerLike read it, and Japanese has no keyword rules).
- **Glued units** (`PhotoTextSorter.suspect`, #198) use the language's `gluedUnits`: unit words
  the scaler reads, long or distinct enough to flag when run into the next word. Short ones that
  start ordinary words are left out, as English's "g" and "can" are: French "cas"/"càs"
  ("cassonade"); Italian "litri?" (the scaler doesn't read "litro", so "1 litro" would be
  flagged). German's `EL`/`TL` are case-sensitive, as in `language.json`. The word is taken
  whole (an atomic group), so a plural the scaler doesn't read ("2 cuillères de sucre") isn't
  cut back to a unit plus a letter. Never in Japanese (`spaced` false).
- **Same guarantees:** an ingredients block and steps, never prose; nothing corrected; the
  requests and prose in each language's fixture split to nothing.
- **Tests:** `shared/fixtures/languages/<language>.json` (a Reddit-style post, a photo's lines,
  negatives) read by `RecipeTextSplitterLanguagesTest(s)` on both platforms with the same
  expectations, and `Split`, `Photo` and `Sus` rows in the differential corpus. The words are
  drafts: a native speaker should check each table's headers, verbs and step labels, and which
  unit words are safe to flag.

## Scan a recipe: your own photos, read on the device (#226)

The owner's request (2026-09-29): "Read the photo" (#198) reads a Reddit post's pictures; do the
same for any photo the cook takes or picks (a recipe card, a cookbook page, a screenshot) or
shares into the app. Behind the existing `photoText` flag, on both platforms.

- **Entries.** "Scan a recipe" in the Recipes **+** menu (after Type a recipe and Paste a link),
  whose row turns the menu into its two choices (Android; iOS: a submenu): **Take a photo** or
  **Choose from library**. Home has no + menu, so "Scan a recipe" sits beside "+ New recipe"
  (under it at iOS's accessibility sizes) and opens the same two choices. Both are #116's
  plumbing: the camera app through `ACTION_IMAGE_CAPTURE` (no CAMERA permission) or
  `UIImagePickerController` (asks for the camera the first time; a simulator says it has none),
  and the system photo picker (no library permission). The library takes up to **six pages**
  in the order picked (iOS numbers them: `PHPickerConfiguration.selection = .ordered`); the
  camera takes one.
- **The same pipeline.** The pages go to `PhotoTextReader` as local pictures, read in order,
  then `PhotoTextSorter` in the detected language (#208), with the suspect-amount and unsure-line
  flags (#198, #205), into the same "Check the recipe" editor: the pages above (each named
  "Page 1 of 2" for TalkBack and VoiceOver), how it went, the lines to check, the fields.
  Nothing sorts: every line in the ingredients box, "finish it by hand". Android's reader not
  ready: the same note and Try again. A page that won't open: "Couldn't open the picture. Try
  again, or go back and choose another." (not #198's "check your connection"). **No title is
  guessed**: the name starts empty (Save points out the rule), since the splitter has no title
  and the card's first line might be anything.
- **Local pictures, never cached.** Android passes `content:`/`file:` URIs through the same
  reader: Coil opens them, with its memory and disk caches off for local pictures, and the
  review shows them the same way, because a reused camera file would otherwise answer with an
  earlier picture. For the same reason a scan's camera photo is a new file
  (`cache/camera/scan-<time>.jpg`, the last one deleted first), unlike #116's one reused
  `capture.jpg`, which its store copies at once. iOS's picker and camera hand back bytes, so
  `ScanPages` writes them as files in `Scans/` in the App Group container; `ImageLoader` reads a
  file URL straight from disk (never into its `URLCache`); Vision is given the picture's EXIF
  orientation, so a phone's sideways-stored photo reads the right way up.
- **Saved as the cook's own** (`addManual`, now with a language): `MANUAL`, `manual:<uuid>`,
  never fetched or refreshed, no "Update from source", no source credit; the language is the
  one the lines were read in (#208), then the checked recipe's words, else English, as for a
  Reddit photo.
- **No picture, and no switch for one** (coordinator, 2026-09-29, departing from the issue's
  "Use this photo as the recipe's picture" switch). A typed recipe's photo is only a link, and
  #116's store belongs to "Your cooks" rows (its sweep deletes any file no row names), so a scan
  as the recipe's picture needed a new store, and the export and backup wouldn't have carried it
  yet: a picture that silently doesn't travel with "photos included" isn't worth it now. The
  idea, with backup and export, is a separate backlog issue. So the pages are read and not kept.
- **Not kept.** Android copies nothing: picker and shared URIs are read where they are, and the
  camera's scan file is replaced by the next scan's. iOS: each new scan replaces the last one's
  pages, a saved scan clears them, and the app's launch sweeps any older than an hour (a review
  left open keeps its pages until then).
- **Shared in.** Android: `ACTION_SEND` and `ACTION_SEND_MULTIPLE` of `image/*` (at most six,
  in the order sent) open the review through MainActivity's intent queue, in Recipes
  (`ScanIntent`); with the flag off the app just opens. iOS: the share extension accepts up to
  six images (`NSExtensionActivationSupportsImageWithMaxCount`) but **hands off rather than
  reads** (#19: it can't open the app). The review needs the app anyway (never saved unchecked),
  reading there keeps Vision out of the extension's small memory, and the files are copied from
  the sharing app's file representation without being held whole. It copies them into `Scans/`,
  leaves their names in the App Group suite (`PendingScan`: `pending_scan_pages`,
  `pending_scan_at`) and says "Open Recipe Clipper to check the recipe read from this photo…";
  the app, becoming active within 10 minutes, opens the review in Recipes, once, as #213's
  `PendingClip` does. A link in the same share wins (it imports as before); a later link share
  clears the pages left, and an image share clears a post left. The extension reads the flag
  from the app's mirror (`photo_text_on`, on until written), like `reddit_on`.
- **Needs a phone:** the camera itself, the picker's order, sharing from Photos or a gallery,
  ML Kit's model through Play services, and real cards and cookbook pages (docs/testing.md).

## Undo snackbars close by themselves (2026-09-29)

The owner saw "Checked items removed" stay on the Groceries screen, and come back after changing
tabs. On Android, an undo snackbar was shown with an action and no duration, which Material keeps up
until it's tapped; and a tab's screen that left while it showed kept its pending removal, so the
snackbar showed again on the next visit. Now every undo snackbar (Groceries, Pantry, Week, Recipes,
the pantry use-up sheet, "Removed from your cooks") goes through `UndoSnackbarEffect`: it lasts
Material's `Long` (about 10 s), and leaving the screen, but not rotating it, settles the removal as
if it had timed out. iOS's `SnackbarTimeout` already closed them, after 4 s; its undo window is now
10 s too (`SnackbarTimeout.undo`), while plain notices keep 4 s.

## The recipe, groceries and pantry files, split further (#234)

After #169 the largest files were still the recipe screen (929 lines on Android), its ViewModel
(621; 558 on iOS), the Pantry screen (715; 585) and iOS's `GroceriesViewModel.swift` (495, with
the add sheet's ViewModel in it). A behaviour-preserving refactor, mirrored on both platforms
under the same names: no UI, string, state or behaviour change, the same public API, and every
existing test passes unchanged.

- **One ViewModel and one UiState per screen still.** Each collaborator is a plain class (or,
  on iOS, a struct where it holds no tasks) that the ViewModel owns and delegates to: no Android
  or UI imports, unit-tested with the fakes, returning state pieces or reducing the state the
  ViewModel then writes. None exposes a flow to the screen.
- **The recipe screen's** (`ui/recipe`, iOS `UI/Recipe`): `OrderedWrites`, the one queue for
  cook progress and the chosen servings (a plain queue drained `NonCancellable` on Android, a
  chain of `Task`s on iOS, each finishing after the screen goes); `NotesAndTicks`, ticks written
  as they change and the note after the 500 ms pause or when the screen goes; `CookController`,
  `CookSession` with its alerts, tick loop and saved progress, reading and writing the
  ViewModel's `CookState` through callbacks as `ChefMode` does; `RecipeDisplay`, the settings,
  servings and short steps the recipe renders with, as reducers over `RecipeUiState`; and
  `FailedLoad`, where a failed load goes (the walled Reddit post's clip, Cloudflare's check, or
  the error screen with its clip and report offers).
- **The Groceries tab's** (`ui/groceries`): `VisitOrder` (beside `GroceriesOrder`, now in its
  own file), the frozen ticks that keep rows still within a visit (#219); `GroceryQuestions`, the
  model's aisle and close-name questions, each asked once per visit (#99, #104); and
  `GroceryRemovals`, the one undo for a delete, a clear or "Done shopping" (#146, #219). iOS's
  `AddToGroceriesViewModel` moved to its own file, as on Android.
- **Screens split by section, code moved only:** Android's `RecipeScreen` into `RecipeActions`
  (the events and their platform effects), `RecipeErrorView`, `ReadingView`, `RecipeHeader` (the
  top row, source credit and times) and `RecipeOverflowMenu` (with its dialogs), `BackButton`
  joining `RecipeComponents`; iOS's `RecipeScreen` gives up `RecipeErrorView` (its `ReadingView`
  was already apart). The Pantry tab's row (with its tag and swipe background) and edit sheet get
  their own files on both platforms, and Android's menu too. Composables and views keep their
  test tags, strings and state.
- **Noticed, not changed:** each platform kept its own small differences. A note still waiting
  when the screen goes is written on iOS to the recipe it was typed for, on Android to the recipe
  on screen then (none while a retry is loading); and a cook finished with nothing ticked clears
  an unhandled `cookFinished` on Android but leaves it on iOS (the screen hands it on at once,
  so it is never there). Neither is visible to a cook.

## Code map and routes, and details moved out of CLAUDE.md (September 2026)

`CLAUDE.md` had grown back to about 750 lines, and it is loaded into every session,
so it was cut to the rules an agent needs almost every time (the rules themselves
stay there, condensed). Whatever it said that no section above already said is kept
here, nearly word for word, except where a feature's own section was the better home: Editing (#29), Week (#49), Groceries (#50), Pantry (#51),
"I made this" (#116) and the export's merge keys (#26) each gained an "in short"
bullet.

### Code map (Android)

`core/` (#238) is plain Kotlin/JVM, the packages unchanged: the code that can't reach
Android. `app/` is everything else and depends on it.

```
core/          (#238; package paths under com.example.recipeclipper, as in the app)
  data/model/  (as listed under data/ below)
  data/remote/ BlogPageParser (a page's HTML to a recipe: JsonLdRecipeParser, refined by WprmIngredients,
               SiteRules, CardHeadings, CardSelector, CardIngredients; else MicrodataRecipeParser), FetchedPage,
               PageTextReader, PageRecipe (#103), RedditRecipeParser, RedditCommentScorer, RedditPageText,
               RecipeTextSplitter, RedditUrls (#11), PhotoTextSorter (#198), CloudflareChallenge (#220)
  data/flags/  Flag (the enum; FeatureFlags and its store are the app's)
  ui/recipe/   RecipeRenderer + RecipeContent (#169: what the reading view shows; the corpus's Render rows)
app/
MainActivity   share intent or timer notification → queued route → navigated once the NavHost exists;
               a received .recipeclipper file → ReceivedFileInbox → its sheet over any screen (#149)
di/            DatabaseModule, RepositoryModule, SourceModule, ClockModule, PlatformModule,
               OnDeviceModelModule (Chef mode and decision models, swappable for the walkthroughs)
data/          RecipeRepository, ListRepository, MealPlanRepository, GroceryRepository, PantryRepository
               (interfaces; Default* are the Room-backed ones), Connectivity, ErrorLog, Clock, PlanCalendar (seams for tests),
               Entitlements (the unlock: PlayBillingEntitlements; iOS StoreKitEntitlements), LibraryPolicy (#107)
               CookedPhotoRepository + PhotoStore ("I made this" photos, #116)
               AutoBackup + BackupFolder (the automatic backup copy, WorkManager, #150)
               ShareFileRepository (the file sent to someone else, #149; rules in backup/ShareFile)
  local/       RecipeDatabase (+ migrations), entities, RecipeDao, ListDao, AppPreferences
  remote/      BlogRecipeSource (fetches; BlogPageParser parses), RecipeSource, RenderedPageSource (the seams),
               RedditRecipeSource (+ RoutingRecipeSource); the parsers are in core/
  model/       (in core/) Recipe, ParseError, UrlCleaner, Servings, IngredientScaler, UnitConverter,
               Units, IngredientDensities, TemperatureConverter, StepTimers, Durations (times),
               RecipeShareText,
               SiteReportLink, SourceDomain, SharedTables (loads shared/tables),
               LanguageWords (one language's tables, chosen per recipe)
               IngredientName (a line's ingredient name), IngredientRendering (scale+convert),
               TrailingAmount (name-first lines: Japanese), StepAmounts (amounts inside steps, #101),
               ClipSelection, ClipDraft, PageText, RecipeTextWindow, PageRecipeCheck (#103),
               PlanDays (the plan's epoch-day calendar), MealPlan (MealType, PlannedMeal), LibraryLimit (#107),
               Groceries (Aisle, Aisles, GroceryCombiner, GroceryShareText, GrocerySources),
               Pantry (PantryList: sort, search, expiry badge; PantryMatch: Have/Buy),
               PantryUseUp (what a finished cook takes from the pantry, #147)
ui/            navigation, home, recipes (the library), recipe, clip, edit, savetolist, lists, listdetail,
               settings, week (with What I need), plan (Add to plan sheet), mealtypes,
               groceries (the tab and the Add to groceries sheet), pantry, sharefile (Send as file,
               the received file's sheet, #149), theme, common
timers/        AlarmManager scheduler, alarm and boot receivers, the "time's up" notification
reminders/     the pantry's expiry reminder: one AlarmManager alarm, its receiver, the notification (#52)
```

### Routes

`home`, `recipes` (the library; was `history`), `settings` (and the hidden
`settings/developer`), `lists`, `lists/{listId}`, `recipe/{recipeId}?cook={cook}`
(`cook=true` from a timer notification opens cook mode), `recipe/import?url={url}` (the
share target: parse, then upsert with no list membership), and
`edit?recipeId={recipeId}` (no id: a new recipe; saving replaces the edit screen, and
the recipe screen under it, with `recipe/{id}`), and `clip?url={url}&blocked={blocked}&check={check}` (Clip it
yourself; `blocked=true` when Reddit's block opened it in the import's place, #213, with a note;
`check=true` when Cloudflare's check wants the cook, #220, likewise;
saving replaces it and the error screen under it, if any, with `recipe/{id}`), and
`edit/photo?url={url}&title={title}&images={images}` (Read the photo, #198: the editor filled
from a Reddit post's pictures, one address per line in `images`; saving replaces it and the
error screen like a clip), and `edit/scan?pages={pages}` (Scan a recipe, #226: the same editor
over the cook's own pages, local URIs one per line; saving replaces it with `recipe/{id}`). Behind the
`mealPlan` feature flag
(#47, on by default): a bottom tab bar nests this same graph under a Recipes tab
alongside `week` (with its own `week/recipe/{recipeId}?servings={servings}` and
`week/meal-types` and `week/need/{weekStart}`), `groceries` (#50) and `pantry` (#51)
(`AppShell`/iOS `RootView`'s `tabs`). Hidden on the recipe reading view and in cook
mode; any route from an intent (a shared link, a tapped timer notification) always
lands in Recipes, whichever tab is open.

### The database, in one paragraph

Room database `recipe_clipper.db`, **version 16** (iOS `user_version` 15): `recipes`
(with nullable `notes`, `language`, `cookState`, `servingsTarget` and `editedAt`, and
`contentOrigin`), `lists` and `recipe_list_cross_ref` (cascading), `meal_types` and
`meal_plan_entries` (#49), `grocery_items` (#50), `pantry_items` (#51; `runningLow`, #194), `menus` and
`menu_entries` (#52), `short_steps` (#100) and `ai_decisions` (#104) (derived: never
exported), `cooked_photos` (#116, cascading; no `fileName` for "Mark as cooked", #173). Recipes, lists, the plan, grocery,
pantry, menu and photo tables carry a unique, never-changing `uid`: what an export
file calls them. Plan, grocery, pantry, menu and photo rows also carry `updatedAt`
(for #53). iOS keeps the database in the App Group container that the share
extension writes to as well.

### Settings: the sections and the keys

**Sections:** Units (with "Also convert liquids" for Ounces only), Oven temperature
(independent of units, default As written), Appearance ("Dark while cooking"), Steps
("Amounts in steps", off, behind the `amountsInSteps` flag, #101; "Chef mode", behind
the `chefMode` flag, #100; each row shows with its own flag), Pantry ("Expiry
reminders", only with the `mealPlan` flag; asks for notifications when turned on,
never at launch), Unlimited recipes (only with `freeTier`: "Unlock for <store price>"
and "Restore purchase", or the sentence "Unlocked: every recipe is kept."; Developer
settings has an "Unlocked" override), Help ("Show tips again", #190), and Your
recipes (export, import and the automatic copy, #150). Reached from the gear beside
the Home title, on every tab of the shell. It could now open from elsewhere too (the
recipe screen follows `AppPreferences.settings`), but adding an entry point is the
owner's call.

**Keys** (the SharedPreferences file `unit_preferences`, and `UserDefaults` on iOS,
the same on both): `unit_system`, `convert_liquids`, `temperature_unit`,
`dark_while_cooking`, `expiry_reminders`, `chef_mode`, `amounts_in_steps`,
`recipe_sort` (the Recipes screen's sort), the tour's `tour_sample_added` (#151) and
`tooltip_<id>` (#190; #151's `tour_welcome` and `tour_tip_*` are ignored), and `pantry_use_up` (#147: recipe id to when
its sheet was settled, pruned to 12 h on each write), each enum stored by name, an
unknown one read as the default. `AppPreferences.settings` (a Flow over the change
listener; iOS a publisher over `UserDefaults.didChangeNotification`) emits them;
ViewModels that show a preference collect it rather than reading once.

### Smaller rules

- **Edge-to-edge** (targetSdk 36 enforces it): a screen's root surface fills behind the
  system bars and pads its content with `safeDrawingPadding()` (Recipes: its
  Scaffold's `contentWindowInsets = WindowInsets.safeDrawing`). Never set bar colours;
  the theme only flips the bar icons.
- **Strings:** every UI string lives in `res/values/strings.xml` plus
  `values-{es,fr,de,it,pt-rBR}` (iOS: `Localizable.xcstrings`, read through
  `Strings.swift`); a new string needs all six languages on both platforms.
  `RecipeShareText` takes its words as `Labels` from the screen; `SiteReportLink` is a
  report body, English by design.
- **The toolchain bump list:** Gradle, AGP, Kotlin (the Compose compiler plugin's
  version sets it), KSP, Hilt, Room, and the Compose BOM with `navigation-compose`.
  AGP 9 compiles Kotlin itself: there's no `kotlin-android` plugin and no legacy AGP
  flags.
- **The reading view's source credit:** under the title, quietly, the source's domain
  and "Open original" (reading view only, not cook mode).
- **Backup is an include list** (`res/xml/data_extraction_rules.xml` and
  `backup_rules.xml`): the database with its `-wal`/`-shm`, and `unit_preferences.xml`.
  Anything else, a new file or a renamed one, is not backed up until it's added to
  both. That excludes the export/import temp file, which lives in `cacheDir`, never
  backed up anyway. The user's photos (`filesDir/cooked_photos`) are left out on
  purpose (the 25 MB quota); the export file carries them. So is `auto_backup.xml`
  (#150): a folder's permission belongs to one phone. iOS keeps the database, and
  `CookedPhotos/` beside it, in the App Group container, which backups include.
- **Notes and ticks:** ticked ingredients are written as they change; the note once
  typing pauses (500 ms), or on leaving the screen. Recipes search ignores notes.
- **"Report this site"** is offered for `NoRecipeFound` from a shared link (never
  `Blocked`, `Offline` or `FetchFailed`): a prefilled GitHub issue (`SiteReportLink`,
  label `site-report`) opened in the browser. Nothing is sent unless the user submits
  it.
- **Decimal commas** (in scaling, conversion and step timers): a comma between digits
  followed by 1–2 digits is a decimal ("1,5 kg"), and the line's output keeps the
  comma, as decimals rather than fractions ("1,5 kg" ×1.5 is "2,25 kg"). Followed by 3
  digits ("1,500 g") it may be a thousands separator, so the whole line stays as
  written. A metric amount the app writes uses the recipe language's separator even on
  a line with no decimal (see "Metric amounts as decimals" below).
- **On-device OCR**, once deferred (a new dependency, and weakest on handwriting), is built
  as "Read the photo" (#198): never trusted blindly, always checked by the cook.

## ViewModels in their own files, checked by a test (September 2026)

`TooltipsViewModel` and `TooltipsUiState` (#190) had been written into the tooltip view
files (`ui/tour/Tooltips.kt`, `UI/Tour/TooltipViews.swift`), which import Compose,
SwiftUI and UIKit. The ViewModel used no UI type, so it moved unchanged into
`TooltipsViewModel.kt` and `TooltipsViewModel.swift`. Nothing had caught it, because no
check existed. Now `ViewModelImportsTest` (Android, JVM) and `ViewModelImportsTests`
(iOS) scan every source file that declares a `class …ViewModel`, whatever the file is
called, and fail on an `androidx.compose` import (Android) or a `SwiftUI` or `UIKit`
import (iOS).

## The `:core` module (#238)

The model and the parsers were pure by convention: CLAUDE.md said they never touch Android,
`Context` or Compose, and only review kept it so (`ViewModelImportsTest` polices the
ViewModels, not the model). They now live in their own Gradle module, `core/`, which has no
Android on its classpath, so an `android.*` or `androidx.*` import there is a compile error.

- **Plain Kotlin/JVM, not Kotlin Multiplatform: the owner's decision, 2026-10-01.** #9's
  reasoning stands (KMP would cost iOS its no-dependency property and need multiplatform
  Jsoup and org.json). iOS keeps its own Swift copy, pinned by the differential corpus.
- **In `:core`:** all of `data/model`; the parsers in `data/remote` that take text and give
  data; `Flag`, the enum only (`Tooltip` names flags; `FeatureFlags` and its store stay);
  `RecipeRenderer` and `RecipeContent`, which are pure and pinned by the corpus's `Render`
  rows, so the corpus generator moves with everything it checks.
- **In `:app`:** whatever fetches, renders in a WebView, needs `Context` or is wired by Hilt:
  `BlogRecipeSource`, `RedditRecipeSource`, `RoutingRecipeSource`, and the `RecipeSource` and
  `RenderedPageSource` seams. `BlogRecipeSource` was split at the seam: its page parse is
  `BlogPageParser`, which `BlogRecipeSource.parse` and `parsePage` hand over to (so callers,
  and iOS's `BlogRecipeSource.parse(html:url:)`, are unchanged); `JsonLdRecipeParser` and
  `FetchedPage` got files of their own.
- **Package names are unchanged** and the files moved with `git mv`, so no import changed and
  branches written before it merge by rename detection. The cost is a `ui.recipe` package in
  `:core`, for `RecipeRenderer`.
- **`internal` now means `:core` only.** Just `PageRecipe` had to become public (the
  repository calls it). Tests of internal code moved to `core/src/test` with it; the two
  fixtures `:app`'s fetch tests also serve (`RedditFixtures`, `MicrodataFixtures`) are
  `:core` test fixtures (`java-test-fixtures`).
- **`shared/`** (tables, flags, sample, web scripts; not `fixtures/`) is `:core`'s resource
  directory. The APK gets it through the jar, as it did through the app's own resources, and
  every JVM test still reads exactly what ships.
- **Dependencies:** Jsoup 1.17.2 is `api` (its types are in the parsers' signatures); `:app`
  still declares the same version, as it fetches with Jsoup and lint's `NewerVersionAvailable`
  advisory reads its line. `org.json` is `compileOnly`: the framework's copy is what runs on
  the device, and bundling one trips lint's `DuplicatePlatformClasses`; the tests get
  `org.json:json`. Jsoup's jspecify annotations are `compileOnly` too (AndroidX brought them
  to `:app`).
- **Tests:** `./gradlew testDebugUnitTest` still runs everything, as `:core` registers it as
  an alias of its `test` (skipped under `-PsiteCheck`). `DifferentialCorpusTest` writes to
  `core/build/differential-corpus/`. `ViewModelImportsTest` stays: the model side is now the
  compiler's job, the ViewModels' side still the test's.
- **iOS: a test, not a framework.** A local `RecipeCore` framework target would give the same
  compile-time guarantee, but Swift makes everything a framework exports say `public`: about
  750 declarations in `Data/Model` and the parsers, plus hand-written public initialisers for
  some 40 structs that use the memberwise one, and a second `@testable import` in the tests.
  That is a large, noisy diff for a rule all of that code already keeps (it imports only
  Foundation), so a test stands in for the compiler instead: `CoreImportsTests` fails when
  any file in `Data/Model`, or any Swift file named like a Kotlin file in `core/` (the parsers,
  `RecipeRenderer`), imports anything but Foundation. Matching Android's `:core` by name keeps
  the two platforms' notion of "core" the same as files move. The share extension still
  compiles `Data/` by target membership, unchanged.

## Metric amounts as decimals, and ingredient headings without a box (September 2026)

Two display fixes the owner asked for, on both platforms.

**Metric amounts.** A German recipe scaled from 4 to 5 servings showed "312 1/2 g Mehl", in
As written and in Metric: the scaler wrote every leading amount as a kitchen fraction, and
nobody weighs half a gram. Now, when the unit after the amount is metric (g, kg, ml, l, cl,
dl, in any language's spelling from `units.json`), the scaled amount is a decimal, both ends
of a range too:

- **g, ml, cl, dl:** whole from 10 up, one decimal below ("313 g", "6.3 g", "1.9 dl"). This is
  the scaler's existing metric rule (`IngredientScaler.formatMetric`), which already wrote the
  site's figures ("1 cup (120 g)" x 1.25 is "1 1/4 cup (150 g)"), unchanged.
- **kg, l:** two decimals, trailing zeros dropped ("0.75 kg", "0.63 l"), the converter's
  kg/L rule (`UnitConverter.formatThousands` now calls the same function).
- **The recipe language's separator:** `amounts.json` gained `decimalComma` (true for de, fr,
  es, it, pt), so "5 g Hefe" x 1.25 is "6,3 g Hefe" though the line wrote no decimal. The
  converter's metric output follows it too ("1/2 TL Zimt" in Metric is "2,5 ml Zimt"). A line
  that writes a decimal comma keeps it as before.
- **Unchanged:** cups, spoons, oz, lb and counts keep their fractions; an unscaled line stays
  as written ("1/2 kg" at the recipe's own servings).
- **A known rounding:** scale then convert reads the scaled text, so a kg figure rounded to two
  decimals can move a later ounce conversion by a quarter ounce ("0.75 kg" halved is "0.38 kg",
  13 1/2 oz rather than 13 1/4). Within the converter's own rounding; left as it is.
- **Corpus:** 77 existing `Ing` rows changed, all metric amounts (fractions became decimals,
  a converted metric figure in de, fr, es, it and pt took a comma, the "(0,24 l)" site figures
  kept two decimals); none outside `Ing` rows. New rows pin German, French and English metric
  lines, ranges and headings, and one German `Render` row.

**Ingredient headings.** Lines such as "Für die Füllung:", "For the sauce:" and "Pour la
garniture :" were drawn as tickable rows. `IngredientHeading.isHeading` (a line ending in a
colon, after trimming) is the test Groceries already used (`GrocerySources.buyable` now calls
it), and the form every parser, card reader and the splitter writes a group heading in
(#118, #119, #208), so it holds in every language.

- The reading view and cook mode's ingredients bar draw a heading as a small muted
  subheading (a heading for accessibility), with no checkbox and nothing to tap. Cook mode's
  bar counts only the other lines.
- `IngredientRendering` returns a heading as written: never scaled ("2 Portionen Soße:"),
  converted or cut as junk.
- **Ticks stay keyed by line index**, so nothing is reindexed and every saved tick still names
  its line. A tick stored on a heading (from before) is ignored: `RecipeViewModel` won't set
  one, and the end of cooking and "I made this" leave headings out of the lines they hand to
  the pantry's use-up sheet (which already skipped them). "Add to groceries" already left them
  out. Share text keeps them as plain lines, as before.

## Security hardening (#235)

Three changes, on both platforms, made in October 2026.

**The clip view hears its page's main frame only.** "Clip it yourself" (#37), Cloudflare's
check (#220) and Reddit's clip (#213) load arbitrary sites, with their ads and other sites'
iframes. The page's script talks to the app through a bridge, and before this any frame could
use it: Android's `addJavascriptInterface("RCAndroid")` is exposed to every frame of every
origin, and iOS's `rc` message handler is reachable from every frame. An ad could post a
selection, clear a field through a tag, or set the photo. Now:

- **Android** (`ClipBridge`): `WebViewCompat.addWebMessageListener` (androidx.webkit) injects
  `RCBridge` with the origin rule `*` (the page is any site) and reports which frame posted;
  a message whose frame isn't the main one is dropped, as is one that isn't a string. This
  needs `WEB_MESSAGE_LISTENER`, which a WebView kept up to date through Google Play has (the
  emulator's API 37 WebView does).
- **Android's fallback, honestly:** a WebView without the listener gets the old interface
  (now named `RCBridge` too), which every frame still sees and which can't say who called.
  The app generates a random token per web view, hands it to the main frame alone
  (`evaluateJavascript` runs only there) just before `clipper.js`, and drops any message
  without it. Another site's frame can't read the main frame's variables, so it can't post;
  a frame of the page's own site could read the token, but it could already reach the page's
  script. Robolectric's WebView reports no listener, so the JVM screen tests take this path.
- **iOS:** `ClipPageEvent.accept` drops a message unless `message.frameInfo.isMainFrame`. The
  three user scripts (`clipper.js`, `reddit-reader.js`, the walkthrough's hook) were already
  `forMainFrameOnly`. Reading the page (#220) and the Text view's read (#213) go through
  `evaluateJavaScript`, which runs in the main frame, so they never used the bridge.
- **What it doesn't stop:** the page's own scripts in the main frame, ads included, can still
  post, as they always could; they can also rewrite the page being clipped. The clip is the
  cook's selection on that page, reviewed before saving.
- androidx.webkit brings a lint check, `MissingOnRenderProcessGone`, which found that the clip
  view's `WebViewClient` didn't handle a crashed renderer (the app went down with it). It now
  returns true and stops talking to the page, which goes blank; the render (#36) already did.
  The check also flags Kotlin's `WebViewClient()` superclass call itself, so both clients
  suppress that id, with a comment saying why.
- Tests: `ClipBridgeTest` and `ClipPageEventTests` (the decision), and the fixture page in
  `ClipScreenTest` and `ClipUITests` now holds an iframe whose button posts a selection, which
  must never show.

**A photo address from a page must be a web image** (`WebImageUrl`, pure, in `:core`'s `data/model` and
iOS `Data/Model`, pinned by the corpus's `Img` rows). Coil loads `file:`, `content:` and a bare
path from the device, and iOS's `ImageLoader` reads a file URL where it is, so a page naming
`file:///…/recipe_clipper.db` as its image would have had the app open its own files. Only
`https` with a host is kept, `http` upgraded as `UrlCleaner` upgrades a link (cleartext is
blocked on both platforms anyway); anything else (`file:`, `content:`, `data:`, `javascript:`,
`blob:`, a relative or blank address) is no image. Where it applies:

- The clip's photo: an address the rule refuses ends the photo step with the existing "Couldn't
  read a picture there" (`onNoImageTapped`). `clipper.js` already sent only `http(s)`; the check
  is native so a page can't get round it.
- JSON-LD's `image` (blog pages, the rendered page #36, Safari's page #35), microdata's `image`
  (now the first one that is a web image, so a lazy loader's `data:` placeholder gives way) and
  its `og:image` fallback, the page text's `og:image` (#103), and Reddit's preview, gallery and
  image link (which already required `http`; now one rule, and `http` upgraded). A relative
  JSON-LD image had loaded nothing useful (Coil read it as a file path); it is now no image
  rather than being resolved against the page.
- An import file's recipe `imageUrl` (#26, #149: a backup, or a file someone sent), read in
  `BackupJson` on both platforms, so every import path gets it: anything not a web image reads
  as no photo. The cook's own photos (#116) are untouched: they travel as `photos/…` entries
  in the archive (`PHOTO_FILE`), are unpacked into a folder the app chooses, and are never
  named by an address from the JSON.
- Not changed: an image address typed by the cook in the editor, and a scan's local pages
  (#226, never a recipe's image).

**Dependabot, security updates only.** `.github/dependabot.yml` covers `gradle` and
`github-actions` with `open-pull-requests-limit: 0`, which turns version updates off, so the
only pull requests are security updates. A fix to the coupled toolchain (Gradle, AGP, Kotlin,
KSP, Hilt, Room, the Compose BOM with `navigation-compose`) is grouped into one PR, and is still
applied by bumping the whole set together by hand (docs/testing.md, "CI"). Dependabot alerts and
security updates were off on the repository and were turned on (2026-10-01) through the API.
