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
- **Limits.** Not a guarantee: some checks detect web views or need a click
  (a CAPTCHA), and still end `Blocked`; a later step could show the page to
  the user. The web view has the app's cookies, not the user's browser
  logins, so paywalls still fail. Not inside the iOS share extension, for
  memory (#19); Safari shares could use Safari's own page instead (#35).
- **Checked only by tests so far.** The repository rules are pinned by fakes
  on both platforms; the web views themselves need a device check on a page
  whose recipe data appears only after JavaScript runs, and on one that
  blocks the plain fetch.

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
  "Continue cooking" already puts it one tap away.
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

- Android adds `shared/` as a `main` Java resource directory, so the pure model
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
  singulariser: "eggs" and "egg" are different names.
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
- **The week starts on the locale's first day** (owner's call):
  `Calendar.getInstance().firstDayOfWeek` (the same answer as
  `WeekFields.of(Locale)` without java.time) and iOS
  `Calendar.current.firstWeekday`. Both number Sunday as 1. Both sit behind a
  `PlanCalendar` seam, so ViewModel tests pin today and the first day.
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
- **Moving is long-press → Move** on both platforms: the same day strip (the
  shown week and the next) and meal types as "Add to plan". Drag and drop
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
  "peanut butter" beats "butter", "butter beans" isn't butter. There's no
  singulariser, so plurals are listed. The aisle is chosen once when the item
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
  or counts whose words after the number are identical ("2 eggs" + "3 eggs",
  not "2 large eggs" + "3 eggs"). Grams never meet ounces, cups never meet
  grams, and a bare "oz" is a weight even for milk. The total is written in a
  unit the lines already used, the largest that shows it exactly under the
  scaler's own formatting ("1 cup" + "2 tbsp" is "1 1/8 cup"; 1.25 kg shows as
  "1250 g" because "1.3 kg" would round), followed by the shortest wording any
  line used ("300 g butter" from "200 g butter, softened" and "100 g
  butter"). If no unit shows it exactly, nothing is combined. Otherwise the
  lines sit together under the name, each as written. Japanese lines (amount
  after the name) are never combined. The combined row shows its lines under
  it, so the sum can always be checked.
- **Checked and shared.** Ticking a combined row ticks all its lines; checked
  rows sort after unchecked ones in each aisle and are struck through. Share
  sends the unchecked rows as plain text by aisle. Delete and "Clear checked"
  are undoable from one snackbar (one undo at a time, as on the Week).
- **Adding.** "Add to groceries" in the recipe menu and "Add this week's
  ingredients" in the Week menu open the same sheet (Paprika's basket): every
  line ticked, headings (a line ending in ":") and blanks left out, one
  button. It's a deliberate act, like "Add to plan", since the point is to
  untick what's in the cupboard first. "Checking off offers Add to pantry"
  waits for the pantry (#51).
- **In the export file** (#26) with the pantry and the plan: see the Pantry
  section's Export note.

## Pantry (#51)

The fourth tab of #46, still behind the #47 flag, with the week's Have/Buy.

- **One table** (`pantry_items`, Room 10, iOS `user_version` 9, new, so nothing
  existing changes): `name` as typed, `language`, optional `quantity` as written,
  an `aisle` key (from the aisle table when added, like a grocery's), `inStock`,
  `alwaysHave`, and optional `purchasedDay` and `expiresDay`, plus #26's `uid` and
  #53's `updatedAt`. The dates are **epoch days** (named `…Day`, like
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
  exact name and language, each shown as written with its recipe and day; nothing
  is added up here (the grocery list does that, when it's exact).
- **"What I need"** is its own screen on the Week's stack (`week/need/{weekStart}`),
  from the Week menu, for the week shown. Its lines come from the same
  `GrocerySources.fromPlan` as "Add this week's ingredients", so both show the
  same text at the same servings. It follows the pantry live. "Add to groceries"
  puts every Buy line on the list through #50's add path, once.
- **The grocery sheet starts with what the pantry covers unticked** (in stock or a
  staple), so "untick what's in the cupboard" is done for the cook, who still
  sees and can re-tick every line.
- **Ticking a grocery off feeds the pantry**, the issue's "on by default for
  tracked items": if the pantry tracks that ingredient and it was out, it's back
  in stock at once, bought today, with Undo in the snackbar; if the pantry doesn't
  track it, the snackbar only offers "Add to pantry" (the ingredient's name, the
  grocery's aisle). An item already in stock, a staple, an untick, or a line with
  no name does nothing. The offer is never automatic for untracked items: the
  pantry holds what the cook chose to track.
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
  - `IngredientName.matches` has one caller, `PantryMatch.find`. Grocery
    combining and aisles use `IngredientName.of` by exact name or the aisle
    table, so they're unchanged.
- **Running out offers groceries**: switching an item out shows "… is out" with
  "Add to groceries" (the name as a typed item). Typing a name already in the
  pantry puts it back in stock rather than adding a twin.
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

## Clip it yourself (#37)

A page with no recipe data (`NoRecipeFound` from a shared link, never Blocked, Offline or
FetchFailed) offers **Clip it yourself**: the page opens live in a web view, the user selects
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
- **Photo:** a Photo button, then the next tapped image. No long-press. Lazy-loading
  placeholders (`data:` URIs) are skipped for the image's real `http(s)` address.
- **Session draft per URL:** Cancel keeps the draft in memory (`ClipDraftStore`, keyed by the
  cleaned URL; Android also mirrors it into `SavedStateHandle`); reopening restores it with a
  "Draft restored" snackbar offering Discard. Save or Discard drops it. Never on disk.
- **One script, `shared/web/clipper.js`,** injected by both apps (Android as a Java resource, iOS
  from the bundled `web/` folder). The page only reports (selection, tag tapped, image tapped);
  native code pushes the draft's marks back with one declarative `RC.sync(...)`, so replace,
  undo and clear all redraw from state. Mark ids come from the draft, so an undo can show a mark
  again. Marks don't survive a page reload (rotation on Android, a restored draft); the draft does.
- **Links to other pages are blocked** in the clip view (redirects and fragment jumps load), so
  a clip is always saved under the page it came from.
- **Save** upserts on the cleaned URL like an import (same id, note and list membership) with
  `contentOrigin` CLIPPED (#29's column; no schema change), replacing whatever the row held,
  then the recipe replaces both the clip and the error screen in the back stack.
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

Features that ship dark are local flags, with no server (remote flags need
accounts and a backend the app deliberately doesn't have; revisit only if #53
brings one).

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
- **Retiring a flag:** when a feature ships for good, delete it from
  flags.json and the enums, with its branches, in one PR. flags.json keeps no
  history. `mealPlan` retires when the meal plan ships.

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
  (the Week tab opens on this week, as #49 decided). No new schema: the grid reads the same
  `observeDays` range query as the week, over the grid's first to last day.
- **The grid is whole weeks from the locale's first day** (`PlanDays.monthGrid`, both
  platforms), four to six rows, the days before and after the month muted but tappable. The
  month arithmetic is plain integers (Hinnant's civil-from-days), like the rest of `PlanDays`:
  no `java.time` on minSdk 24.
- **A day with meals shows a paprika dot, not a count or titles.** The month answers "which
  days are planned"; the week answers "what". Screen readers hear "…, meals planned".
- **Tapping a day opens its week, scrolled to that day.** Which month opens: today's for this
  week, otherwise the month holding most of the shown week (its fourth day), so Sep 28 – Oct 4
  opens October.
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
  shown week's meals into a new menu: each keeps its weekday (`dayOffset` 0–6 from the week's
  first day), meal type, recipe with planned servings, or note. Nothing links a planned meal
  to a menu afterwards, so editing the plan never changes a menu, and vice versa.
- **Applying only adds.** "Apply a menu…" opens the menus sheet (name, meal count); tapping one
  copies its meals into the week shown on the same weekdays, each at the end of its day and
  meal type. What is planned stays, so applying twice doubles up (visibly, and each meal
  can be removed) rather than silently replacing a week the user built. The snackbar says how many
  meals came in. A week starting on another weekday (a locale change) keeps offsets from the
  week's first day, not weekday names.
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
  both builds). Where the phone can't, the switch is disabled with one line saying why (can't,
  Apple Intelligence off, model not ready); where it can, a line names the recipe languages it
  writes. A recipe in another language keeps its steps as written, silently. Android offers
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
    runs against `ios/RecipeClipper.storekit`.
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
  `NoRecipeFound` as before. Times go through the parsers' `formatDuration`.
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
