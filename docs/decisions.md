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
- `ui/theme/Theme.kt` — palette, typography, shapes (`Corners`) and
  `RecipeClipperTheme(cookMode)`. Market (#455, below): Bricolage Grotesque and
  Instrument Sans are bundled as static weights in `res/font` (SIL OFL;
  licences in `assets/licenses`). `tertiary` is the accent-for-text slot
  (`AccentText`, `AccentTextOnInk`); `primary` is the filled-button basil
  (`Accent`, `AccentOnInk` with dark words on ink); `secondaryContainer` is the
  `AccentTone`. Two schemes: `RecipeClipperTheme(forceDark)`
  takes `DarkScheme` when `forceDark` or `isSystemInDarkTheme()`, else
  `LightScheme`. There used to be a third, `CookScheme`, forced whenever cook
  mode was active; it was deleted once cook mode began following the system
  theme, and it was byte-for-byte identical to `DarkScheme` anyway, so nothing
  was lost. `forceDark` is now passed only as `cooking && darkWhileCooking`.
  Status/nav bar icon appearance is light-appearance only when `LightScheme`
  is actually showing. `res/values-night/` gives the
  window background/status/nav bar colour (the on-ink ground `#0E1310`) so there's no
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
  History row swipes away (`SwipeToDismissBox`, the theme's `error` background) with
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
  and feels slow. The snackbar is keyed on the burst (`deleteBatch`), so a
  further swipe updates its count in place (#268, below). Deliberately a hard delete, not a soft flag: a `deleted`
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
  the recipe screen. Removing a recipe *from* a list was deliberately not
  offered on the detail screen (membership edited in the sheet only); since #376 and #377 a row's
  long-press menu and a selection there offer "Remove from this list" (see "Row actions and
  selecting several recipes").
- **Covers (#286, the owner's pick V2; both platforms).** Lists is a two-column
  grid of square covers, in the same order as before (Favorites and the seeded
  lists first), each with the name and count ("Empty" at zero) under it, and
  "New list" (a dashed tile with a plus) last. The tile opens today's inline
  field, full width under the grid, in the tile's place until Create or Cancel.
  *Changed (#371, owner, 2026-11-19):* the tile was out of reach below many lists, so it is gone: a
  **"+" beside the title** (the top bar on iOS, as the Recipes "+") starts the same flow, the name
  field opening just under the title, labelled `lists_new_list_tile`.
  A cover is a mosaic of up to four photos from the list's recipes, most
  recently added first: four make a 2×2, one fills the square, two sit side by
  side, three put the newest on the left half and the next two stacked on the
  right, so no cover has empty cells. A list with recipes but no photo shows
  those recipes' designed images in the same mosaic, and an empty list a plain
  hairline tile with an outlined bookmark (#403, below; with `noPhotoImage` off,
  any list with no photo gets the tile), never a stock image. The photos
  come from a new query (`ListDao.observeCoverPhotos`, iOS
  `ListDao.coverPhotos`), not a column: no migration. It caps each list with a
  correlated `LIMIT` because API 26's SQLite predates window functions; a
  recipe with no photo is skipped rather than counted. Photos go through Coil /
  `CachedAsyncImage`, so covers show offline. iOS lays the grid out as rows in
  a plain VStack rather than a `LazyVGrid`, so every cover is in the
  accessibility tree, and puts one cover to a row at the accessibility text
  sizes, where half the width would break a name mid-word. A list's own screen
  is unchanged. The covers are in the cook's own order (#381, below), not "built-ins first".
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

*Revised (#286, the owner's pick "Inset photo, one-line times").* The times sit
on one line, "Prep 35m · Cook 40m · Total 1h 15m": labels muted (the title-case
`label_prep`/`label_cook`/`label_total`, no longer uppercased), values bold, a
muted " · " between, only the entries the recipe has. It's one text, so it wraps
in a long language or at large type, but only between entries: an entry's
spaces and the one before its "·" are non-breaking, so "1h 15m" and "Prep 35m"
never split and a line never starts with "·". A value longer than 20
characters (a phrase as written) keeps its spaces, so it wraps at a word rather
than mid-word. The photo and top bar are unchanged, and so is the share text.

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

**Ingredients for this step (#286, the owner's pick, 2026-10-19).** Opening the ingredients bar
lists the lines the current step names first, washed in basil, under "In this step", then the
rest under "Later"; the bar's label, Start timer and "Done — next step" are as before. Which
lines: `StepAmounts.named`, the amounts-in-steps matching (#101, #254): every line a mention
names, whether it lends an amount, is a link, or stays as written (the step already says how
much, "the flour mixture", a dusting sentence); a step's title before a spaced dash names none.
It runs whether "Amounts in steps" is on or off (the renderer annotates steps only while the
switch is on; the grouping only reads the matching and never changes what the steps show), on
the step as shown (Chef mode's short version unless as written), against the lines as rendered.
A step that names no line, or a language without words or spaces (Japanese), shows the full
list as before, with no labels. The grouping, `StepIngredients`, is derived in `RecipeUiState`
whenever the ViewModel writes it (Android on each copy; iOS by observers on `content`, `cook`
and `asWrittenSteps`). **Headings:** one moves to "In this step" only when every line under it
does, with its lines under it, after the step's other lines; otherwise it stays in "Later" over
the lines still to come, and the step's lines from its group show without it, so no line ever
sits under another group's heading. Ticks stay keyed by line index. A #254 link's lines are
always the step's own, so the link opens the bar on them in "In this step", their highlight a
stronger wash over the group's. Known limit, the matching's: "the chicken" doesn't name "2 lb
chicken thighs" (only describing words may be dropped), so that line sits under "Later".

**Design language:** Market since #455 ("Visual identity: Market", below):
Bricolage Grotesque (display) over Instrument Sans (body) on a white ground
`#FFFFFF`, ink `#14201A`, muted `#56645B`, hairline `#E1E7E2`, basil accent
`#2C7A4A`. System dark mode uses the on-ink token set (ground `#0E1310`,
`TextOnInk`, `MutedOnInk`, `HairlineOnInk`, `AccentOnInk`, `AccentTextOnInk`).
No manual light/dark toggle for the app as a whole: the system setting
decides. Avoid the default Material purple. (Until #455: Fraunces over Karla,
`#FBF9F6` ground, paprika `#BF4A2B`.)

**Android: every dialog, sheet and menu is composed inside its screen's
`RecipeClipperTheme` block** (#142, #186). The theme is per screen, so one
called before or after the block gets Material's baseline purple; only hosts
over any screen (the tab bar, the received-file sheet) carry their own. The
#186 sweep moved Recipes' Paste a link, List detail's Rename, the full-screen
cooked photo with its date picker, and Edit's library-full prompt inside
(`LibraryFullDialog` no longer wraps itself); a Robolectric test on each checks
its button is the app's primary (basil since #455; `assertInAppTheme`).

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
- Creating a list from the sheet auto-ticks the current recipe into it. Since
  #286 (the owner's pick "New list, inline") the open field says so: one muted
  line under it, "<recipe> goes into the new list." (`save_to_list_new_list_note`),
  shown only while the field is open. The title reaches the sheet through
  `SaveToListViewModel.setRecipe(id, title)`, again on an edit. The mockup's
  Create beside the field and counts on the right weren't part of the pick:
  Cancel and Create stay under the field, each count under its list's name.
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
- **Double-encoded entities (#266, October 2026).** 750g.com and
  tudogostoso encode their JSON-LD twice (`&amp;eacute;`, `&amp;oacute;`),
  so one `stripHtml` showed "&eacute;mincer". Every JSON-LD string (name,
  ingredients, steps, yield, section names) now gets one more decode
  (`jsonText`): only a complete reference with its `;`, never one giving `<`
  or `>`, never re-parsed as HTML. A literal "R&D" or "&amp pepper" stays.
- **A step's `text` as an array of strings (#266).** einfachbacken.de cuts
  each HowToStep at its commas (`["Für den Teig Mehl", "Backpulver …"]`),
  which showed as JSON. Such an array is one step, joined with ", ". Only an
  array holding nothing but strings; a string that looks like JSON is text.

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
- **The site rules' cards refine it as they do JSON-LD** (#437): Jetpack
  names its groups in `<h5>`s between the microdata's lines, and a card that
  lines up one-to-one adds them as headings. Only the headings; the step
  noise rules and WP Recipe Maker's parts stay JSON-LD's.

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
  recipe through `recipe/{id}?cook=true`, back to the cook, #333) only if the database
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

## Timers outside the app (#309)

Behind `liveTimers`. A running cook-mode timer shows outside the app, counted
down by the system from the deadline cook progress already saves, so nothing
writes or posts per tick. A tap opens the recipe and comes back to the cook.
The time's-up alert and `TimerAlarmReceiver`'s check of the database are
unchanged.

- **A tap never cancels the timer it shows** (#333). A countdown, the alert,
  Home's resume card and the `recipeclipper://cook` link all open the recipe
  with `cook=true` / `.cookRecipe`, which *resumes* (`CookSession.resume`):
  cook mode in its saved state while the run isn't finished; the reading view
  when it is, where a timer still running (the last step's rest, say) shows
  under its step. Only the reading view's own Start cooking starts a finished
  run afresh and stops its timers, as before. The cook link opens cook mode
  like the Live Activity it belongs to: any app can open it, but with only an
  id above zero, and only the cook's own saved state. A tap while that recipe
  is already on top opens no second copy (by recipe id on Android, not
  `launchSingleTop`, which would hand one recipe's screen another's id).
- **The countdowns follow the database** through one path, `TimerCountdownSync`
  → `TimerAlarmScheduler.reconcile(running)`: every countdown the database has
  no running timer for goes (all of them with the flag off). Called after a
  delete (the Recipes swipe and the recipe screen), a re-share, an edit and
  Update from source, whenever `liveTimers` changes (Android follows the flag
  from `RecipeApp`, iOS from `AppContainer`), and on iOS when the app becomes
  active (the share extension re-shares in its own process). The alerts need
  none of this: Android's receiver checks the database, iOS's `replaceAll`
  squares them when the recipe opens. A timer that runs out while its recipe
  is on screen ends its countdown too (`CookSession.tick` names it,
  `TimerAlarmScheduler.ended`); its alert is left alone, since it has fired or
  is firing and cancelling would only race it.

- **Android: an ongoing notification**, posted by
  `AndroidTimerAlarmScheduler.schedule` beside the alarm and removed by
  `cancel` (`TimerNotifications.postRunning`). Its own channel, "Running
  timers", at low importance, so it never makes a sound; the chronometer counts
  down to `endsAt` (`setUsesChronometer` + `setChronometerCountDown`), and
  `setTimeoutAfter` removes it at the deadline, where the alert takes over. No
  foreground service (rejected in #10): a plain notification survives the
  process, and a reboot reposts it through `TimerBootReceiver`'s reschedule.
  One per timer (tag `running:<recipe>`, id the step). It asks to be promoted
  (`setRequestPromotedOngoing`, `POST_PROMOTED_NOTIFICATIONS`, a normal
  permission), which on Android 16 makes it a Live Update: the status bar chip
  and the lock screen. Without the notification permission it isn't shown;
  Android 13+ asks on the first timer, and the grant reposts what it refused.
  The alarm is set before the countdown is posted, so the alert never depends
  on the display; a countdown already posted to the same deadline is left as
  it is (one query of the active notifications, not a repost per recipe
  open), and the channel is created once per process.
- **iOS: a Live Activity** per timer, on the lock screen and in the Dynamic
  Island, drawn by the new widget extension `RecipeClipperWidgets`
  (`TimerLiveActivity`, `TimerActivityAttributes` shared with the app under
  `RecipeClipper/Shared`). `LiveActivityTimerScheduler` wraps
  `NotificationTimerScheduler` and drives `ActivityKitTimerDisplay`, whose
  operations are chained like the notifications', since ending is
  asynchronous. The activity's stale date is the deadline: past it, it reads
  "Time's up: step N" until the app ends it, which opening the recipe does
  (`replaceAll` keeps only running timers), as does the timer running out
  with the recipe on screen. An activity the system has ended (over eight
  hours) is no longer live: it is cleared and a new one requested. The Dynamic
  Island's compact countdown takes a wider frame for a timer of an hour or
  more (h:mm:ss). The link is
  `recipeclipper://cook?id=N` (`AppLinks`, compiled into both targets). The
  extension can't use `Strings.swift` (it formats the app's types), so
  `WidgetStrings` (`UI/Theme`, compiled into both targets; `Strings` delegates
  the keys it shares) reads the catalog, compiled into its bundle too. Its
  text is Bricolage Grotesque and Instrument Sans by PostScript name
  (`WidgetFont`, #455): a `Font` made from a UIFont with extra descriptor
  attributes (the variable axes Fraunces once took) crashed the extension,
  which WidgetKit can't encode. The system asks once, on the first activity, whether to allow
  Live Activities.
- **The flag off** shows nothing new, and ending never checks the flag; turned
  off mid-cook, the reconcile above removes every countdown.

### Home-screen widgets

Behind `widgets`. Two, on both platforms: **Tonight**, today's planned meals
under their meal type (Mela's next-meal widget is the model; a tap opens the
Week), and **Groceries**, what's left to buy (a tap opens Groceries). What they
show is `WidgetContent` (core, with its Swift port, pinned by `Tonight(`,
`ToBuy(` and `Fitted(` corpus rows since #329; it was first left to matching
unit tests): today's meals in meal type order, a meal with
no title or note left out; the list's unticked rows as the screen shows them
(a combined row is its total, lines kept together each listed, a row with a
ticked line in the basket, as "Send list" has it). The list's lines are as
stored, without the on-device model's decisions (#104): the iOS widget runs
apart from the app and its model, and Android matches it. An empty plan or
list says so. With the flag off a placed widget shows only the app's name.

- **As many rows as fit, then "+N more"** (`WidgetContent.fit`): the last row
  that fits is kept for the count. **An item is always named** (#329): with
  room for one row, the count sits beside the first item as a bare "+N" (short
  enough to leave the item its room), where "+N more" used to replace it.
  Both platforms size this by the text as it is drawn, since a larger text
  size means fewer rows, never clipped ones (a clipped list looks complete).
- **Android: RemoteViews** (`widgets/HomeWidgets`, two `AppWidgetProvider`s).
  Glance isn't in the Compose BOM (it's versioned on its own and brings
  WorkManager), and two short lists don't need it. System fonts (serif title):
  a font resource in RemoteViews depends on the launcher. The tap is an intent
  action MainActivity routes to the tab, like the expiry reminder's.
  - **Rows are budgeted in sp** against the font scale, and for both
    orientations: `RemoteViews(landscape, portrait)`, from the launcher's least
    and most height, so turning the phone needs no redraw. The smallest sizes
    (`minResizeHeight` 80dp and 96dp) hold the title and one row at the default
    font scale. Every colour, "+N more" included, is a `@color` in a layout,
    which the launcher resolves for the theme it draws in.
  - **No periodic refresh** (`updatePeriodMillis` 0; it was 30 minutes, about
    96 wake-ups a day for one change). `HomeWidgets.start` (from `RecipeApp`)
    redraws while the process lives, when the mapped `WidgetContent` changes:
    nothing is observed with no widget placed or the flag off, and a write that
    changes nothing shown redraws nothing. One redraw at a time (a `Mutex`,
    reading inside it), so an older list is never drawn over a newer one.
  - **Midnight is one alarm** (`WidgetDayChange`): inexact, by the decision
    that rules out an exact-alarm prompt, in a ten-minute window (a plain `set`
    may run hours late), and not a wake-up, since nobody sees a widget with the
    screen off. Its redraw arms the next. `WidgetClockReceiver` also hears the
    date, time and time zone change. The alarm and the receiver (disabled in
    the manifest) are on only while a Tonight widget is placed.
- **iOS: WidgetKit**, in the same extension as the Live Activity. It opens the
  App Group database itself and reads it through the app's own DAOs
  (`WidgetData`, in the data layer so the unit tests reach it). The app's
  `WidgetRefresher` calls `reloadAllTimelines` when the plan (from the day
  before it started, with no last day), the meal types or the list changes,
  and mirrors the flag into the App Group suite (`widgets_on`, like
  `reddit_on`), since the extension never sees the flags. The link is
  `recipeclipper://tab?name=week` (`AppLinks`).
  - **Tonight's timeline holds tomorrow** from midnight, so the day turns on
    time when WidgetKit is slow to reload; the next timeline is asked for once
    that entry shows. The list's timeline never reloads by the clock.
  - **An unreadable database is not an empty plan** (before the first unlock
    after a restart, or while the app migrates or imports): the widget shows
    its title and nothing else, never "Nothing planned for today", and asks
    again in five minutes.
  - **`FittedRows`** offers each cut of the list to `ViewThatFits`, fullest
    first, so the rows are measured at the Dynamic Type size and widget size in
    use rather than assumed. When even one row and the count are too high (the
    accessibility sizes in a small widget), the text steps down a size at a
    time until they fit: smaller text, rather than an item unnamed. Not a fixed
    cap on the text size, which would shrink it where there is room.

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
  counting every recipe; with `oneCookbook` on, #345: every recipe counts and none is exempt, only the
  places under 50 are filled, listed ones first, then the most recently viewed, and a skipped
  recipe's lists, plan entries and photos stay out with it; unlocked: all come in); pantry items by uid then name
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
  `contentOrigin` (`PARSED` | `EDITED` | `CLIPPED` | `MANUAL` | `EXTRACTED` | `CHAT`, by
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

- **One flag,** `mealPlan` (#87, below), until it was retired with the
  single-stack navigation (#242): the tab shell is the only root now.
- **Where the bar shows is an allow-list, not a deny-list**
  (`tabBarRoutes` on Android; `.toolbar(.hidden, for: .tabBar)` set only on
  the recipe destination on iOS). A new screen is bar-less by default, so
  forgetting to update the list fails safe (no bar) rather than leaking the
  bar onto the recipe reading view or cook mode.
- **Each tab is its own nested graph** (Android: `navigation(route =
  tab.route, ...)` under one `NavHost`, with `popUpTo`/`saveState`/
  `restoreState` on tab switch; iOS: one `NavigationStack` per `TabView`
  case). Recipes' graph is `recipesDestinations` in `RecipeNavHost.kt`.
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
- **The day strip** (#286, the owner's pick V2 "Day strip", 2026-10-19; both platforms). Under
  ‹ range ›, a row of the seven days the header names: the same block ‹ › move, so it follows
  free scrolling too. Each is a short weekday over its date; today, when it's among them, is
  filled in basil, and a basil dot (white on today) sits under each day with something
  planned. A tap brings that day to the top through the same `scrollTo` as an arrow or the month
  (`onStripDaySelected`). The two can't fight: the strip only asks for scrolls and never follows
  the list itself (it is `WeekUiState.strip`, from the block at the top), and the tapped day is in
  the block already shown, so neither the header nor the strip moves. Screen readers hear the
  full date ("Monday, October 19") and, as the month says it, "meals planned". The month view
  hides it.
- **An empty day says "Nothing planned"** (#286), above its "+ Add", which stays where it was
  (the mockup put "+ Add" beside the heading). Only once the day's meals have loaded
  (`isNothingPlanned`), so a day outside the window of meals never claims to be empty. On iOS
  a day with no meals keeps the line's room before then (the text hidden): the days above
  today growing by a line once loaded pushed today off the top on opening, which the UI
  tests caught (#296). Android's LazyColumn holds its first item when those above it grow.
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
- **Skip tonight** (#398, `skipTonight` flag, both platforms). **Free for everyone** (owner,
  2026-11-23: "Skip shouldn't be in Pro"; #413 had put it behind the Pro badge for two days): no
  badge on today's Skip or the menu item, no Pro screen, no gate in `WeekViewModel.onSkipStart`.
  A planned meal's long-press menu
  gains **Skip…** beside Move, and today's planned meals show a visible **Skip** button; neither is
  offered for a meal planned in the past (`PlanShift.canSkip`: day before today). It opens a small
  sheet with three one-tap choices, each followed by one undo snackbar that restores exactly what
  was there (the rows as they were, `updatedAt` and order included):
  - **Push the rest of the week back a day**: the skipped meal and every later planned meal in the
    whole plan (not just the week shown) go one day later, meal types kept, gaps kept, notes
    included. "Later" is the plan's order (day, then meal type order, then added order): on the
    skipped meal's own day, only the meals after it in that order move (skipping lunch carries
    that day's dinner, not its breakfast). Nothing before the skipped meal ever moves.
  - **Move to the next free day**: only the skipped meal moves, to the first later day with no
    meal of its meal type (a note counts as a meal; a lunch never blocks a dinner).
  - **Remove from the plan**: the same as Remove, through the same undo (the recipe stays).
  `PlanShift.plan` (core; iOS line for line, pinned by `Skip(...)` corpus rows) is pure and returns
  the moves, the removals and the moved meals that landed past the last day the list shows; the
  repository applies them in one transaction (`MealPlanDao.skip`: every move lands or none) and
  returns what moved (`SkippedMeals.result`: id, from day, to day), the seam for #396's "a
  perishable meal now sits too late" note, which this does not build: after a move, nothing else
  changes. The snackbar names the choice ("Pushed the rest of the week back a day", "Moved X to
  Fri 25", "Removed X from the plan"); a push that leaves a meal past the list's last day (two
  years ahead) says so. A second skip or a removal settles the first (one undo at a time).
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
  them, over a strip of those seven days (#286: dots on planned days, today
  filled, a tap scrolls to the day); under it one scroll of day sections, opening on today, from a year back
  to two ahead; ‹ › snap to the previous or next block. Meal rows (type,
  thumbnail, title, servings, or a note) or "Nothing planned", "+ Add" per day (a meal type, then a
  recipe from history or the typed text as a note). Long-press: Move (the day
  strip and meal types), Skip (#398: push the week, next free day, remove) or Remove (undo snackbar). A tapped recipe opens at its
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
  - **Why a line starts unticked** (#457, `groceryPantryLabel`; the owner saw unticked lines and
    guessed "already on my list"). The only reason a line starts unticked is the pantry (#51), so
    each such line carries a muted "In your pantry" (`what_i_need_have`, the same words as What I
    need's section, bodySmall in the muted colour) under its text. The ViewModel says which:
    `AddToGroceriesUiState.inPantry`, set with the first unticks and never changed by a tick, so the
    label stays when the cook ticks the line anyway (it is about the pantry, not the tick). Which
    lines start ticked, the button and what is added are unchanged. The same sheet serves the
    recipe menu and, with `shopForWeek` off, the Week's "Add this week's ingredients": both show
    it. Shop for this week (Buy now / Buy later) is a different screen with its own In your pantry
    section and does not use this sheet. Nothing detects "already on the grocery list" (the sheet
    adds lines, and combining happens on the list), and none is shown. iOS reads the flag through an
    environment value set at the root, as `noPhotoImage`. Tests: `GroceriesPantryTest`,
    `AddToGroceriesPantryLabelTest`; iOS `GroceriesViewModelTests`,
    `AddToGroceriesPantryLabelUITests`.
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
- **Aisle labels, recipe lines, round ticks** (#286, the owner's pick V1, both platforms). Behaviour
  is unchanged; only the look moved:
  - **Aisles** are small spaced capitals (the theme's `labelSmall`, muted, no hairline). Each one is
    a heading that screen readers and tests read as written ("Dairy & eggs"), not in capitals.
  - **Each row names its recipes** on a line under it, as "Send list" does
    (`GroceryShareText.recipes`): each recipe once, in the order its lines were added. A
    combined row keeps its lines ("200 g flour + 100 g flour"), and lines that sit together keep
    theirs, as written. The recipe line comes last, so the sum can still be checked. It has no
    "× 2": the lines above it already say that. A row only typed in shows what it did before,
    with no recipe line, and so does a recipe that's gone. The ViewModel works the lines out
    (`recipeLines`, by the row's id) whenever the list or the recipes' titles change.
  - **The tick** is a ring on the right, under the thumb. It fills basil with a white check.
    The ring is drawn in muted, as the old checkbox was, so it stands out. The whole row is
    still the control: Android's row keeps `Role.Checkbox` and its toggle state, and iOS's
    keeps the selected trait. The tick sits level with the row's first line.
  - **Done shopping** is a bar pinned above the tab bar while anything is ticked: a hairline,
    then the button with the number of rows ticked ("Done shopping · 3 ticked"; an added-up row
    counts once). The list stops above it (Android's Scaffold bottom bar, iOS's bottom safe-area
    inset), so it never covers the last rows. The Done shopping tooltip points at the bar.

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
- **The quantity never decides Have or Buy.** It's a note for the cook ("half a bag"),
  used up after cooking only when exact (#147) and shown beside the week's total (#302,
  below), never compared with it. Matching
  is by name only, so Have means "you have flour", never "you have enough flour":
  the screen says so under "In your pantry". This keeps the pantry inside "never a
  confident wrong number".
- **Have/Buy** (`PantryMatch`, pure, both platforms, pinned by the corpus's `Pant`
  rows): a line's `IngredientName.of` against the item's name by
  `IngredientName.matches`, and only in the same language. "What I need" shows
  which pantry item it matched ("You have butter"), so the cook can check. A staple (`alwaysHave`)
  is never on Buy, in stock or not. A line the app can't name ("salt and pepper",
  a heading) stands alone and is always Buy: nothing is guessed. Lines group by
  exact name (a listed pair's number aside, #191) and language, each shown as written with its recipe and day; the
  lines are never merged into one (a Have row's total sits beside them, #302 below).
- **"What I need"** is its own screen on the Week's stack (`week/need/{weekStart}`),
  from the Week menu, for the week shown. Its lines come from the same
  `GrocerySources.fromPlan` as "Add this week's ingredients", so both show the
  same text at the same servings. It follows the pantry live. "Add to groceries"
  puts every Buy line on the list through #50's add path, once.
- **The pantry's amount beside the week's, never a verdict** (#302, owner, 2026-10-22). Under
  an "In your pantry" ingredient, after "You have chicken thighs", one muted line:
  - "You have 3 lb · this week's recipes use 4 lb" when the pantry item has an amount and the
    row's lines add up exactly; "You have 3 lb" when they don't; nothing new when the item has
    no amount. Staples keep their "Always have" alone.
  - The pantry's amount is shown as written (trimmed); the week's is the lines summed as they
    were rendered (the planned servings, the user's units) by `GroceryCombiner.total`: the
    grocery list's own adder (`combine`'s exact rules, no second one), giving the figure and
    its unit ("4 lb", "1 1/8 cup") or a count with its words ("5 eggs"). One line counts when
    it is one exact amount. A range, a second measure, mixed families or "× 3" repeats give no
    total, never an estimate.
  - No arithmetic between the two, and nothing moves to To buy or says "enough" or "short":
    the cook judges. Without it a week planning two chicken recipes showed chicken as Have,
    and the cook learned it had run out only after the second "Update the pantry".
  - In core (`NeedRow.pantryQuantity`, `weekTotal`, set only on a Have row, the total only
    beside an amount), pinned for iOS by the corpus's `Need` rows. The ViewModels pass the
    rows through, so the line follows the pantry live like the rest.
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
- **Expiry**: a badge only (#52 later added an opt-in morning reminder) (Expired before today; the date in basil from today
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
  #52, #146, #147, #149 and #194): three counts (#286), "Add to the pantry", search, then items by
  aisle (menu: by expiry, radio glyphs), with what has run out in a dimmed "Run
  out" section last; each row shows its quantity as written on the right (no
  button since 2026-09-29) and a "Low" tag while running low; tap for the edit
  sheet (the stock, quantity as written, "Always have", a use-by date, Delete
  with undo), with swipes as shortcuts; touch and hold selects several (#258). Expired or within 3 days shows a basil
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
- **Counts at the top** (#286, the owner's pick of the redesign, V3; both platforms): three
  boxes under the title, In stock, Running low and Run out, each a number in the display face
  over its label (the stock control's own strings). They count items by the three states the
  list already shows, so they are disjoint (In stock is plain in stock; Running low is the
  "Low" tag; Run out the dimmed section) and sum to the rows shown: a search narrows them too,
  so every count matches rows below it. The ViewModel computes them (`PantryCount`: stock,
  count, the first row's id). A tap scrolls to that first row, or to its section's heading
  when the row heads the section (Run out always does), so the heading shows too; a zero is
  off, and a box that is off is drawn at half strength. Hidden while the pantry is empty. **While selecting (#258) they stay, dimmed and off,
  rather than hiding,** so the rows never move under the finger that has just started
  selecting. The colours are the theme's: In stock ink, Running low the Low tag's basil text
  (the mockup's amber isn't a theme token), Run out muted like its section, on the
  `surfaceContainer` fill. The add and search fields and the list are unchanged.
- **An amount typed with the name** (#302, the owner's choice, 2026-10-22; both platforms).
  "Add to the pantry" stays one field and an amount is optional: "3 onions" adds onions with 3,
  "2 cups rice" rice with 2 cups, shown on the right at once (a tap opens the sheet to fix it;
  no confirmation). `PantryEntry.split` (pure, `:core` and Swift, pinned by the corpus's `Entry`
  rows) splits only when the text starts with one real amount that the typed language's tables
  (`typedLanguage()`) read: a positive number, fraction or unicode fraction, then a unit the
  tables know or a space, then a name that starts with a letter and holds no other number. It
  reuses the scaler's and converter's patterns, so it refuses what they refuse. The quantity is
  the amount as typed ("1½ lb"); the name is the rest as typed, never singularised or cut,
  except that a word joining a unit to it ("2 cups of rice", "200 g de farine", "2 c. à soupe
  d'huile") belongs to neither (amounts.json `unitLinks`, per language). Anything else stays
  whole as the name, as before: "00 flour", "7up", "5-spice", "1/2" or "2 kg" alone, a range
  ("2-3 onions"), two amounts ("2 eggs plus 3 yolks", "1 cup plus 2 tbsp flour", "2 cups rice
  (400 g)", "1 kg 00 flour"), a size ("2-inch ginger", "2% milk"), a temperature ("180 C
  water", "350°F"), "1,500 g" (thousands or a decimal?), and Japanese, whose amounts follow the
  name. A container belongs to the amount as a unit does (amounts.json `containers`, the
  list the scaler already reads package sizes by), so "2 cans tomatoes" is tomatoes with
  "2 cans" and "1 boîte de tomates" is tomates with "1 boîte" (owner's call: "cans tomatoes"
  would not match "tomatoes" in What I need).
  - **Restocking:** a name already here (`PantryList.sameName`, on the split name) is put back
    in stock as before; a typed amount replaces its quantity, and with none the old one stays.
  - **The hint:** the field shows "e.g. 3 onions" (`pantry_add_example`, drafts in the five
    other languages, each an amount its own tables split) under its label: Android's
    placeholder; on iOS `OutlinedField`'s `example`, the placeholder while it's focused, with
    the label then on the outline and still the field's accessibility name.
  - **Not done:** "Done shopping" carrying a grocery line's amount into the pantry.

## Use it up (#305)

Saved recipes for a pantry item about to expire, behind the `useItUp` flag (on).

- **Where it shows: the Pantry row and the expiry reminder,** both opening one sheet. The row
  is where the use-by date already is, so the line ("3 saved recipes use it", basil, under
  the date) sits beside what it explains and costs nothing when there's no match. The
  reminder is where the cook learns something expires, so its tap opens the Pantry with the
  sheet on everything due (Android `UseItUpInbox`, iOS `Router.useItUpPending`); with no
  saved recipe for any of it, the Pantry opens as before. The Week's ⋮ was the issue's third
  idea: left out, since planning from the Pantry already lands on the Week.
- **Soon is the reminder's window** (`ExpiryReminders.due`): in stock, not a staple, expiring
  today or tomorrow. Not the basil badge's three days: the line and the morning note then
  name the same items. An item past its date isn't offered.
- **Only saved recipes** (in at least one list, `ListDao.observeSavedRecipes`). Not history:
  a recipe merely opened isn't one the cook chose to keep, and the issue rules out anything
  not chosen (#165 is where new ones would come from).
- **Matching is What I need's** (`UseItUp.uses`: each line's `IngredientName` against the item
  by `PantryMatch.find`, same language, headings skipped; a recipe stored with no language,
  from before #14, is detected first, as What I need's are). Presence only; "rice flour" is
  never "flour", "butter beans" never "butter". No model decisions (#104): a name match or
  nothing. Lines are read as stored (only names matter, so scaling changes nothing). Pure, in
  `core` (`UseItUp`), ported to Swift and pinned by the corpus's `UseIt` rows.
- **The sheet:** a heading per item, the name then the day ("Chicken thighs · use by
  tomorrow", `use_it_up_heading_*`; #332: the reminder's sentence put a singular verb on a
  plural name, in every language, so the shape has no verb to agree), soonest first, its
  recipes A–Z, each with one tap per day: "Plan today" and, when the use-by day is tomorrow,
  "Plan tomorrow". A tap plans it as Dinner at its own yield (as "Add to plan" starts) and the
  button turns to "Planned …" (any recipe already on the plan that day, from anywhere, shows
  so). The note under the title says the tap plans dinner. A tap whose write failed is freed
  (`addRecipe` says whether it wrote), so it can be tried again.
- **Today is the real day** (#332). The Pantry tab's ViewModel outlives the day, so today is
  re-read when the Pantry comes back in front (`onScreenResumed`, as the Week's) and when the
  reminder is tapped: the next morning's reminder shows that day's window, and "Plan today"
  plans onto it. Nothing shows from yesterday's groups while the new day's are matched.
- **A reminder nothing answers is forgotten at once** (#332): tapped with no saved recipe for
  anything due (or the flag off), the request is dropped, so a recipe saved later never pops
  the sheet up by itself. One tapped before the first matching waits for it.
- **The row's line stays while selecting** (#258, #332), no longer tappable (the row's tap
  toggles the checkbox): rows never change height under a dragging finger, which drag to
  select assumes. The line is the row's last line in the on-ink text token (`tertiary`, as the
  use-by line above), and its tap target is the minimum touch size (48 dp, 44 pt), standing
  in the row's bottom padding, so a near miss opens the sheet rather than the edit sheet.
  iOS's tooltip host counts the open sheet as blocking, as it does the edit sheet.
- **Matched off the main thread, once per change** (#332): the groups come from (pantry,
  saved recipes, today) with equal inputs skipped (a tick or a cook step rewrites a recipe
  without changing a line); the sheet's target only picks from the result. Each recipe's lines
  are parsed once (`UseItUp.names`), not once per expiring item, and not at all while nothing
  expires.
- **Strings:** `use_it_up_*` in all six languages, drafts (`docs/translations.md`).

## Clip it yourself (#37)

A page with no recipe data (`NoRecipeFound` from a shared link, never Blocked, Offline or
FetchFailed) offers **Clip it yourself** (and a Reddit post Reddit won't let the app read opens in
it by itself, #213: see "Reddit posts (#11)"): the page opens live in a web view, already on
step 1 of four guided steps; the cook taps each field's text on the page and confirms (or selects
first and taps the field), then reviews and saves. The first design was approved as a mock-up (six
frames, the owner's nine decisions in the issue's comments); the field first flow below replaced
its select-then-tag toolbar (#237), and guided steps (#251) put it in order.

- **Guided steps, with both orders working (the owner's call, 2026-10-13, #251).** The owner:
  "some people won't know they can copy text immediately, some will try to click the options to
  extract like photo or steps and notice nothing happens." So:
  - **Guided on entry.** The clip opens on "Step 1 of 4 · Tap the recipe's name", Name armed
    (not while the page is Cloudflare's check, #220: it arms once the check has passed; a
    restored draft opens on its first empty step). Steps run Name → Ingredients → Steps →
    Photo; after each add the next step still to do (empty, not skipped) arms by itself, in
    order and then round from the start ("Step 2 of 4 · Tap the ingredients", with what was
    just added and Undo above it). Each step has **Skip**; Skip on Photo ends the steps. When
    every step is filled or skipped, the header is the Done prompt ("7 steps added. Tap Done to
    review and save.").
  - **Visible when armed.** While a text step is armed the page outlines what a tap takes, a
    light dotted accent outline (basil, `rgba(44,122,74,.55)` since #455, the same on light and dark pages): for
    Name each paragraph, list item and heading; for Ingredients and Steps each list as a whole
    (a list comes whole) and the paragraphs and headings. On Photo the images are outlined
    instead. Tapping a field button changes the header and the outlines at once, so a tap never
    seems to do nothing.
  - **Field buttons still jump:** tapping another field arms it and makes it the current step
    (and takes it off the skipped); the armed one again disarms it, and the header names the
    next step still to do.
  - **Select first works too:** with text already selected (a long press, or a tap), tapping a
    text field's button adds it there at once, as the confirm would (a name replaces;
    ingredients and steps add; Undo), then the next step arms. Choosing the field is the confirm,
    so there's no second one. (Photo's button with text selected drops the selection, as
    before: a photo is picked on the page.)
  - **A second block for the same field** (a list, then the line under it) is one tap on that
    field's button away: the steps move on after each add rather than keeping the field armed,
    which #237 did. Undo of an add makes its field the current step again.
  - **ViewModel state:** the current step is the armed field; the skipped steps, the outline
    (`ClipOutline`, from the armed field), the pending selection and the Undo stack (each entry
    with the field it filled) are the ViewModel's too. The page only reports, and draws the
    outlines `RC.sync` names (`outline`: TEXT, IMAGES or none).
- **Field first (the owner's call, 2026-10-01, #237).** The owner: "they're not sure what to do
  next; they'd have to just know to hold long press to extract text. Wouldn't it be better if they
  can press name or ingredients and then all they have to do is drag and extract the text". A
  long press isn't discoverable, and with the photo first the toolbar left people unsure what
  came next. So the field buttons come first: **Name, Ingredients, Steps, Photo**.
  - **Tapping one arms it:** filled, with the hint bar above saying what to do (since #251 the
    step's header). Tapping it again disarms; another switches (with a selection made, since
    #251 it takes the selection instead: select first, above).
  - **While a text field is armed, a tap selects** (`clipper.js`, in the capture phase, so a
    tapped link or button does nothing else): a paragraph, list item or heading; for Ingredients
    or Steps, the **whole list** the tap landed in. A second tap outside the selection, for
    Ingredients or Steps, stretches it to cover that block too (tap the first line, then the
    last), which suits the Reddit Text view's one paragraph a line. A tap between blocks, on a
    label the page keeps out of selections (`user-select: none`), or on nothing with text, does
    nothing.
  - **On Android the page draws its own two handles** on a selection it made: Android's WebView
    shows none for a script's selection. (iOS's WebKit shows its own once the tap has given the
    page the focus, so there the page draws none: two pairs sat on top of each other, seen on
    the simulator.) Dragging one moves that end a whole block at a time (snapping to blocks
    keeps lines whole); dragged past the other end, the selection is that one block; near the
    top or bottom the page scrolls along. The handles take the finger with `touch-action: none`,
    so a plain drag anywhere else still scrolls. A long press still selects as the browser does
    (the page's handles step aside for the browser's), and a selection made with nothing armed
    says "N lines selected · tap the field they go in".
  - **A confirm, not auto-assign (the owner's call, 2026-10-01).** The selection shows in the
    hint bar as one button, "Add 12 lines to Ingredients" ("Use as the name"), with Clear beside
    it. One tap adds it. Auto-assigning would have taken partial selections while a handle was
    being dragged, and a wrong grab (a list's container, a heading) would have gone in silently.
  - **Name and photo replace; ingredients and steps add up,** so separate blocks (a list, then
    the list under "For the glaze:") are added one after the other. (Each add moves on to the
    next step since #251; #237 kept Ingredients and Steps armed for the next block.)
  - **Each add can be taken back:** Undo in the hint bar right after it (the last change only,
    since #264), and each add's own tag on the page ("Ingredients · 3", one tag per add) takes
    back just that add, with a snackbar Undo. Taking an add back removes the lines it put there,
    by position (a line edited by hand in Review since stays). A hand edit in Review ends Undo.
  - **Guidance after each step:** the hint bar says what was added, then the next step (#251;
    with nothing armed, it names the next field still to do: "Next: tap Ingredients."). Each
    field button shows what it holds: a check, with the count for ingredients and steps.
  - **ViewModel state:** the armed field, the hint (derived from the state), the pending
    selection, the last add and the Undo stack are the ViewModel's; the page only reports
    selections and taps. A count (`clearSelection`) tells the page to drop its selection (a
    disarm, Clear, the other view).
- **Photo:** Photo arms photo picking ("Step 4 of 4 · Tap the recipe's photo"), then **one tap**
  on the page. An image with a readable `http(s)` address becomes the photo (kept as `https`,
  and checked natively: `WebImageUrl`, #235): lazy-loading placeholders (`data:` URIs) are skipped for the real address (`data-src`, a `srcset`, the
  `<picture>`'s sources, inside open shadow roots too). The next step still to do then arms (or,
  all done, the hint bar shows its thumbnail and the Done prompt). Anything else ends the step
  (as skipped) and says "Couldn't read a picture there. The photo is optional." Selecting new
  text, tapping a tag, or **Skip** (beside the hint) ends it too. Picking used to last until a readable image was tapped: on a Reddit post
  the owner found every other tap swallowed ("stuck in the photo section"), so neither the page
  nor the ViewModel keeps it past one tap. A tag is kept inside the page's width (Reddit's
  backdrop image, drawn wider than the page, put its tag outside and pushed the page's own
  controls off the screen). Android's clip snackbars are `Long`, not the Indefinite an action gets
  by default.
- **Error screen:** Try again stays first, outlined; under a hairline, one line of explanation,
  then Clip it yourself (the one filled button), then Report this site (#30) as a text button.
- **Nothing is guessed.** No field is suggested for a selection. A selection splits one item per
  line (`getSelection().toString()` breaks between blocks); a name joins its lines. Serves and
  Total time are typed in Review, optional, never read from the page.
- **Session draft per URL:** Cancel keeps the draft in memory (`ClipDraftStore`, keyed by the
  cleaned URL; Android also mirrors it into `SavedStateHandle`); reopening restores it with a
  "Draft restored" snackbar offering Discard. Save or Discard drops it. Never on disk.
- **One script, `shared/web/clipper.js`,** injected by both apps (Android as a Java resource, iOS
  from the bundled `web/` folder). The page only reports (selection, tag tapped, image tapped,
  no readable image), and the apps hear its main frame only (#235, "Security hardening");
  native code pushes the draft's marks, the armed field and the clear count back with one
  declarative `RC.sync(...)`, so add, undo and removal all redraw from state. Mark ids come
  from the draft, so an undo can show a mark again. Marks don't survive a page reload
  (rotation on Android, a restored draft); the draft does. The script says `ready` once it is
  in (#395): iOS injects it at document end and pushes the state then, not at `didFinish`,
  which waits for every image and frame, so a slow picture or ad no longer leaves the page deaf
  to taps; in a debug build the web view's accessibility value reads "ready" from the first
  push (what `ClipUITests` waits for). Android injects at `onPageFinished` and pushes at once, so it
  ignores the message.
- **Links to other pages are blocked** in the clip view (redirects and fragment jumps load, and
  so does a page sending itself back to its own path when no one tapped, as Reddit's check does,
  #213; an app's own `intent:` or `reddit:` link never loads), so a clip is always saved under
  the page it came from.
- **The Reddit Text view (#230) works the same way:** the armed field carries between Page and
  Text, and the guided steps, the confirm, Skip and Undo are the same there. **Text, then Page,
  left the page dead on Android (#237):** the hidden Text view (then a web view) was shrunk to
  no size, and its last frame stayed on the screen over the page. `ClipScreenTest` still checks
  the screen's pixels after Text, then Page.
- **Text view, line by line (the owner's pick, #286, V4).** The Text view lists the post's lines
  as rows, each with a checkbox (the whole row ticks it; a checkbox to TalkBack and the tests, a
  selected button to VoiceOver, as every tick in the app): native rows now (`ClipTextRow`,
  `ClipTextView`), not a web page, so the ticks are the ViewModel's (`ticked`) and survive
  rotation, where a web view's selection was lost with its reload.
  - **Ticked lines are the selection,** washed in basil (the app's 14% wash, as #254's), and
    the dock says "3 lines selected · tap the field they go in" (the count in ink, the rest
    muted, over the field buttons). Both orders work as on the page: armed, the step's confirm
    adds them; with nothing armed, a field's button does (#251). The page under the Text view
    isn't heard while it shows (it only ever reports dropping its own selection).
  - **A run in two taps:** with lines ticked, a tap on a line past them ticks every line up to
    it, as a second tap stretched a selection (#237); a tap in a gap ticks that line, a ticked
    line unticks. Not for Name, where each tap ticks its own line.
  - **Adds stay marked:** a line an add took has a basil bar, and the add's first line its tag
    ("Ingredients · 3"), which takes the add back as the page's tag does. New text (more
    comments loaded) is new rows, with the old marks dropped, as a page loses its marks on a reload.
  - **The dotted outlines aren't drawn in the Text view:** the checkboxes already show what a tap
    takes. Unchanged from the mockup's other differences: the Text view is Reddit's only, and Text
    and Page stay one text button in the top bar (the mockup draws two pills).
- **Save** upserts on the cleaned URL like an import (same id, note and list membership) with
  `contentOrigin` CLIPPED (#29's column; no schema change), replacing whatever the row held,
  then the recipe replaces both the clip and the error screen in the back stack.
- **Done and Save always answer: they save, or say what's missing** (the owner's S23 on a Reddit
  post, 2026-09-30: "save doesn't work"). With ingredients and steps but no name, Done was greyed
  out with nothing saying why, and in Review the top bar's Done stayed tappable but did nothing,
  the real Save being at the foot of a long list. Now Done is always tappable (bar Cloudflare's
  check): with lines but no name it opens Review, where the name can be typed, and says so; with
  no lines it stays on the page and says what to do. In Review the top bar's button is Save. Save
  with too little says what's missing (`ClipMessage.Missing`); a failed save, a full library
  (#107) and a save under way already said or showed so.
- **A clip is the user's version** (#29's rule): a re-share opens it without a fetch. "Clipped
  by you · host" replaces the domain under the title (Open original stays), and History rows
  say "Clipped by you" (a derived `isClipped` in the summary queries). Update from source is
  always offered for a clip, warning "Replace your clip?"; on failure the clip is kept and the
  snackbar says why. On success it becomes PARSED and the line goes.
- **Review fixes (#264, 2026-10-14).** Checked against the guided steps (#252), all seven still
  applied (the restored photo on Android only: iOS has no process-death restore).
  - **Undo holds one change.** The hint bar offers Undo only for the add just made, so the
    50-deep stack was unreachable past its top; it is one entry now, the add's or a removal's.
    A removal's snackbar goes as soon as another change is made (an add, Undo, a hand edit), so
    its Undo can only take back that removal; a hand edit in Review clears the hint bar's Undo
    with the entry.
  - **Mark ids only rise:** an undone or discarded draft no longer rewinds `nextMark`, so an
    add after an Undo isn't drawn on the undone block (the page keeps every mark it recorded).
  - **Each add records where its lines start** (`ClipMark.start`): its tag removes those lines,
    never an equal line another add put earlier; removing a line in Review shrinks its add and
    moves the later ones up.
  - **A photo restored after the process died gets a mark**, so its tag can take it back.
  - **A list is taken whole only from one of its own items** (an `<li>`, or the one block an
    item holds) or the list itself: a paragraph inside one comment of a comment thread
    (`<ol class="commentlist">`) is its own block, not the whole thread.
- **UI tests** use a fixed local page, never the network, and no long press: Android's
  `ClipScreenTest` taps the page's elements by script (`click()`, which reaches the page's own
  listener as a tap does), drags a handle with script pointer events, and makes the Text →
  Page check with a real tap through the shell; iOS's `ClipUITests` taps the page's text with
  XCUITest. Either way the app hears the selection through the page's `selectionchange`. The
  Text view's rows are ticked as any Compose or SwiftUI row is (#286); iOS's fixture page holds a
  hidden `shreddit-post` for it to read.
- **Walkthrough clips:** 31 (live Reddit, selected by script) and 32 (a live blog page, real
  taps) show the guided flow (#251: 31 selects first, 32 goes step by step). Since #286, 31's
  second post ticks the Text view's rows with real taps; both re-recorded 2026-10-22.
  `ClipScreenTest` and `ClipUITests` walk the guided steps from step 1, jump back to Steps for a
  second block added select first, and Skip.

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
  The flags stay as kill switches until they are retired (below).
- **Tests don't lean on the defaults.** A unit test that needs a flag on or
  off sets it through the fake store. iOS UI tests start with every flag off
  and turn on only the ones `launch(flags:)` names (`UITestSeeding`).

- **One registry, `shared/flags.json`:** key, a one-line description, the
  default per build type (`debug`, `release`) and the issue. Both apps read
  it (Android as a Java resource, iOS as a bundled file), so the list can't
  drift. Each platform also has a typed `Flag` enum with the same keys, and a
  unit test (`FeatureFlagsTest` / `FeatureFlagsTests`) fails if the enum and
  the file differ, or if a flag is no longer referenced by the app's code.
- **Typed access over an injectable store:** `FeatureFlags.isOn(Flag.REDDIT)`
  / `isOn(.reddit)`. `FeatureFlagStore` holds only overrides; choosing a
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
  `FeatureFlags.values` and provides them as `LocalFlagValues`, which screens
  read. iOS: `FeatureFlags` is `@Observable`, so a view reading it redraws.
- **UI tests set flags through the store,** not a special launch argument:
  `launch(flags: ["reddit"])` passes `-uiTestFlags`, which the UI-test
  container writes as overrides into its own throwaway suite.
- **Retiring a flag:** a flag is retired once its feature is shipped and
  stable with no open bug: delete it from flags.json and the enums, keep the
  on behaviour, and delete the off paths, their strings and tests, in one PR.
  flags.json keeps no history. An override a phone still holds for a retired
  key names no `Flag`, so it is never read; Reset clears it with the rest
  (tested on both platforms).
- **Retired, 2026-10-01 (#242):** `mealPlan` (#47: the tab shell, Week,
  Groceries, Pantry and the recipe screen's plan actions; the single-stack
  navigation is gone), `amountsInSteps` (#101: Settings' Steps section always
  shows the switch) and `cookedPhotos` (#116: "Your cooks" and the Recently
  cooked sort). Open bug #218 (an iOS Pantry swipe's timing, seen only under
  XCUITest) touches the Pantry, but nobody would switch the whole tab shell
  off for it, so `mealPlan` went too: the rule keeps a flag only when it would
  plausibly be switched off to contain a bug. Tooltips no longer carry a flag
  (`shared/tooltips.json`), and iOS's share extension no longer reads a
  mirrored `groceries_on` (a stale key in the App Group suite is never read).
  Kept: `freeTier` and `aiCountBrackets` (off on purpose), `chefMode`,
  `llmExtraction` and `aiDecisions` (kill switches for on-device AI), and
  `reddit` and `photoText` (changed that week).

## Pantry expiry reminders (#52)

Owner-approved extra of #52: a morning notification when something in the
pantry is about to be used up by its date. #51's badge stays; this adds the
nudge for a cook who isn't looking at the pantry.

- **Opt-in, in Settings.** A Pantry section with one switch, "Expiry
  reminders", off by default. Stored as `expiry_reminders` in `unit_preferences` /
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

The optional extras of #46, one PR each.

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
- **A day with meals shows a basil dot, not a count or titles.** The month answers "which
  days are planned"; the week answers "what". Screen readers hear "…, meals planned".
- **Tapping a day scrolls the days to it** (#232: that day at the top, the header naming its
  block from today; before, it opened the day's locale week). Which month opens: today's for
  this week, otherwise the month holding most of the shown week (its fourth day), so Sep 30 –
  Oct 6 opens October.
- The week's own menu actions (Shop for this week, #384; before it, What I need and Add this
  week's ingredients) act on the week shown, so they're hidden while the month shows; Meal types
  stays.

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

## The evening-before reminder, and Tonight (#397)

**Free for everyone** (owner, 2026-11-23). #413 made switching a reminder on a Pro act, and the evening one's default "on" a Pro default; neither holds now: anyone can switch either reminder on, and the default applies to all (`ProReminders` is gone; the coordinators and Settings read the setting as stored).

The owner's hardest part of the app is shopping and then cooking through the week
(2026-11-19): fresh food going off, time on weeknights, plans changing, forgetting
what's next. #52's expiry reminder covers the first; this is "what's next", the evening
before, with the one thing a cook most often forgets: the step that has to be started ahead.
Behind `eveningReminders` (on by default, issue #397).

- **A notification at the cook's time, default 19:00,** naming tomorrow's planned meals:
  "Tomorrow: Porridge, Lemon chicken". Meals are in meal type order (Breakfast before Dinner,
  as the Tonight widget has them, then the plan's order), a recipe by its title and a planned
  note by its text; the same recipe planned twice is named once; a recipe that's gone and an
  empty note name nothing. **Nothing planned tomorrow, nothing sent.** A tap opens the first
  planned recipe on the Week's own stack (`week/recipe/{id}`, iOS `Router.openInWeek`), so Back
  returns to the week; when every meal is a note, the Week tab.
- **"Tonight", off by default, default 16:00:** the same notification on the day itself,
  "Tonight: Lemon chicken". It leaves out Breakfast and Lunch (by their `builtInKey`, so a
  rename doesn't change it): they are over by the afternoon. It never quotes an ahead step,
  since it is too late for one.
- **Settings > Week** (a new section, #286's index: Units, Oven temperature, Appearance, Steps,
  **Week**, Pantry…; only with the flag). Each reminder is a switch and, while it is on, a
  time (Android's Material time picker in the phone's 12 or 24-hour format; iOS's compact
  `DatePicker`). The permission is asked **when a switch goes on, never at launch**, as #52
  asks (Android 13+ `POST_NOTIFICATIONS` from the Settings screen; iOS `requestAuthorization`
  through the `NotificationPermission` seam). Refused, the switch stays off and the page says
  to allow notifications in the system settings, in the error colour (the same sentence as the
  Pantry's). **The evening reminder is on by default**, as the issue says ("on by default once
  notifications are allowed"): so a phone that has already allowed notifications (a step timer
  asked, #10) gets it without a prompt, and one that hasn't gets nothing until the cook allows
  them. Each time the page comes to the front it looks again whether notifications are allowed
  (`onNotificationsAllowed` / `onWeekPageShown`), and while a reminder is on and they are not
  it shows that line, so the default never leaves a silent switch unexplained. The index's value is "Reminders on" while
  either is on.
- **Keys** (`unit_preferences` / UserDefaults, the same on both): `evening_reminder` (default
  on), `evening_reminder_minute` (1140), `tonight_reminder` (off), `tonight_reminder_minute`
  (960); a minute outside the day reads as the default.

### What it says about the steps to start ahead

- **Found from the steps by a word table, `shared/tables/<language>/ahead.json`, in the
  recipe's own language (#14), never the phone's.** `AheadSteps.find(steps, words)` (core, iOS
  the same line for line; the corpus's `Ahead` rows pin the two) returns the first step, in
  step order, with a sentence that qualifies, and that sentence, trimmed, as the quote. A
  language with no table detects nothing; every shipped language has one (en, de, es, fr, it,
  pt, ja), as every table does.
- **A sentence qualifies by an `always` phrase** ("overnight", "the night before", "the day
  before", "a day ahead", "make ahead", "can be made ahead", "thaw … in the fridge") **or by a
  `verb` with at least two hours** in the same sentence, the hours right after the verb (60
  characters) or just before it (40): marinate, brine, soak, thaw, defrost, chill,
  refrigerate, rest, rise, prove, proof, freeze, ferment, stand, sit. "Marinate at least 8
  hours or overnight." and "Chill 2+ hours" qualify; "Marinate for 30 minutes", "rise for 1 to
  2 hours" (a range's first number decides, so 8 to 12 hours qualifies) and "chill for 1.5
  hours" do not. Hours are digits (decimal comma too), or a count word from the table
  ("two", "several", "a few", "a couple of"); `hours` has the unit words, so "Std.", "h" and
  "時間" work. **A limit is not a wait:** an amount right after an `upTo` word ("up to 2
  hours", "no more than", "bis zu", "jusqu'à", "最大") never counts.
- **`skip` phrases never trigger:** a match that overlaps one is dropped ("soak up the
  sauce", "rest for 5 minutes", "overnight oats", "no need to marinate", "not necessary…").
  A skip only blocks what it overlaps, so a later qualifying sentence in the same step still
  counts. Patterns are regex fragments in the table, case-insensitive, as whole words in a
  spaced language (Japanese has none); groups are non-capturing; `\p{L}` rather than `\w`, since
  Java's `\w` is ASCII only.
- **Sentences end at `.`, `!` or `?` followed by a space and not a lower-case letter** ("1.5
  hours" and "8 Std. im Kühlschrank" stay whole; a German abbreviation's period is not a full
  stop), and at `。`, `！`, `？` wherever they stand. The quote is that sentence on one line,
  at most 100 characters, cut at a word with an ellipsis. Rules of thumb, not guesses: a
  step the table doesn't recognise says nothing, and a missing quote is the table's gap to
  fill (edit the table, both apps read it), never code.
- **The notification's body** (one string per sentence, in the UI language; the quote itself
  stays as the recipe wrote it): one recipe, "To do ahead: “Marinate at least 8 hours or
  overnight.”"; several, a header line and a line each, "Lemon chicken: “…”". The owner's
  example read "Marinate tonight:"; the verb isn't known in six languages without a string per
  verb, so the label is the one general "To do ahead" (the owner's call to refine).

### Scheduling

- **Android: one `AlarmManager` alarm per kind, for its next firing** (`MealReminderCoordinator`,
  `reminders/AndroidMealReminderScheduler`). Exact where the app may schedule exact alarms
  (`setExactAndAllowWhileIdle`; unlike a timer's `setAlarmClock` it shows no alarm icon: a
  reminder is not an alarm clock), else `setAndAllowWhileIdle`, which can be minutes late, as
  #10 decided; no Settings prompt for the exact-alarm permission. The alarm is armed whether or
  not anything is planned. **The receiver decides**: at fire time `MealReminderCoordinator.due`
  reads the plan, the recipes' steps and the setting as they are then, so a meal moved or
  removed since, a switch turned off, the flag off, or an alarm that arrives a day late
  (its day isn't today) sends nothing; then it arms the next, a day later at the earliest
  (`rearm`: an alarm a moment early can't re-arm itself for the same minute), touching the other
  kind's alarm not at all (both may share a minute). Because the receiver reads the database,
  the plan changing needs no rescheduling here; the settings, the flag, a boot or app update,
  the time or the time zone changing (`TimerBootReceiver` also takes `TIME_SET` and
  `TIMEZONE_CHANGED`) and every app start re-arm from scratch. Channel "Meal reminders"
  (default importance); the tap's `OPEN_MEAL_REMINDER` intent goes through MainActivity's route
  queue to `week/recipe/{id}` (`openRoute` lands a `week/recipe/` route in the Week tab, not
  Recipes).
- **iOS: pending `UNCalendarNotificationTrigger` requests, replaced wholesale**
  (`meals.<kind>.<day>`), each carrying its words as planned: nothing runs when one fires.
  `MealReminderCoordinator` observes the next nine days' plan and the meal types, the settings
  and the flag, and replans on every change; coming back to the foreground replans from today
  (the share extension saves in its own process, and a re-share can change a recipe's steps).
  At most `MealReminders.maxDays` (7) per kind, so at most 14 pending with the expiry reminders'
  30 and the timers under iOS's 64. It never asks for permission itself.
- **Pure planning** (`MealReminders`, both platforms, `MealRemindersTest(s)`): the day it fires
  and the day it names (`mealDay`), the first day a time can still fire (`firstDay`: today while
  the minute of the day is before it), the meals named, the reminder built, and the text
  functions that take the platform's strings as functions.

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
  - *Prompt API* (`genai-prompt:1.0.0-beta5`): free prompts to Gemini Nano, under 4,000 tokens,
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
(Settings → Steps, off by default, key `amounts_in_steps`); its flag was retired (#242).
Deterministic, no AI: `StepAmounts.annotate(steps, lines, words)`, pure, both platforms, pinned
by the differential corpus's `Step` rows. The lines are the ingredient lines **as the reading
view renders them** (scaled, then converted), so the amount follows the servings stepper and the
unit menu. It applies to the reading view and cook mode; sharing out keeps steps as written.

A mention gets an amount only when every rule holds; otherwise it stays exactly as written:

- **One line.** A run of words ending in a line name's last word (singular or plural, per
  `steps.json` `plurals`) names that line when `IngredientName.matches` says so (the pantry's
  rule, #51, so "rice flour" never takes "flour"'s amount), or when the line's name is the
  step's words after describing words only, `steps.json` `lineDescriptors` with the names
  table's modifiers (#254: "the butter" for "very cold unsalted butter", "the oil" for "olive
  oil", "the stock" for "low-sodium beef stock", "the onion" for "1 red onion"). Any other word
  makes another ingredient: "the sauce" never takes "Worcestershire sauce"'s amount, nor "the
  leaves" "bay leaves"'s, nor "the butter" "peanut butter"'s. A mention named only that way
  also names every line ending in its words ("the oil" with olive oil and toasted sesame oil
  names both). The longest run wins ("brown sugar" is the brown sugar line, not "sugar"), and
  a line named exactly beats one named past its describing words ("the sugar" beside "brown
  sugar" and "sugar" is the "sugar" line).
- **Several lines.** The same ingredient lending the same amount ("30g / 2 tbsp unsalted butter"
  twice, one for searing, one cold to finish) is one answer; two ingredients never are, even
  with equal amounts ("los pimientos" for "1 pimiento verde" and "1 pimiento rojo" is two
  peppers, so a link, 2026-10-14). Lines with different amounts, a line
  with no amount among them ("2 cups flour" and "flour, for dusting"), or a line with no name
  that uses the word ("salt and pepper") get no number: the mention becomes a link (below).
  "flour for dusting" stays a link rather than lending the other line's amount: the harness
  found "1/4 tsp salt" beside "pinch of salt" for the topping, and "Dash of salt" for the
  frosting, so a line without a number isn't evidence the step means the one with.
- **First mention only**, once per line per step; later mentions stay as written, whatever
  became of the first. Each step is read on its own. A step's title before a spaced dash
  (RecipeTin Eats' "Sauté onion and mushrooms - Turn the heat down…") is no mention, so the
  first mention is the one after it, and the word after the dash counts as a sentence's first.
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
  "la crème anglaise") may be a longer name, so it stays. Where modifiers follow the noun, the
  words giving its state may come first ("le beurre fondu", "la cebolla picada": the names
  table's `trailingWords` and the density table's `trailingModifiers`). German writes compounds as one word,
  so any word may follow (`anyWordAfter`). Before it: its article (which the amount replaces),
  a list comma, a word in `before` (a preposition, a conjunction, a common verb: "Stir in flour",
  "Whisk flour"), or a sentence's first word (the imperative verb). Anything else ("Dust with
  rice flour" when only flour is listed) stays. Describing words between the article and the
  name stay after the amount: "the melted butter" → "115 g melted butter".
- **Japanese is a no-op** (no spaces, so no word boundaries), and so is a language without words.

Where it shows: the inserted amount is a separate run of the text, in the basil text accent
and a heavier weight (Android SemiBold, iOS strongly emphasised); in a done cook-mode step it
keeps only the weight, so the dimmed row stays dim. The screen checks the flag; the ViewModel
computes the parts only while the switch is on, and again on every servings or units change.

**Ingredient links (#254, the owner's call, 2026-10-14).** A mention naming several lines with
different amounts, where the step doesn't say which, is a link: the mention's own words in the
basil text accent, underlined, never a number. `StepAmounts.Part.lines` holds the lines (the
corpus marks it `⟪butter|0,1⟫`), and which lines match is the core's, pinned by the corpus. A
tap is `onIngredientLink(step, lines)`; the ViewModel holds `IngredientJump` (the lines, the
step, whether they are still highlighted, the scroll the view still owes) and fades the
highlight after 3 s (`HIGHLIGHT_MILLIS`, iOS `highlightDuration`, through the injected sleep).
The view does the scrolling. Reading view: scroll to the first line, a fading basil wash on
the lines, and a "Back to step N" chip above Start cooking that scrolls back and ends the jump.
Cook mode: the folded ingredients bar opens with the lines highlighted and the chip under them;
the chip folds it again (only if the link opened it), as do toggling the bar, starting or
leaving cook mode. In cook mode only the current step's links open: a tap on another step
makes it current, as before. Accessibility: each link is also an action on the step's text
that reads as a button, "butter, 2 matching ingredients, show" (Android a custom accessibility
action, iOS an accessibility action), from `step_link_matches` and `step_link_show`.

**What the harness found (2026-10-14).** The owner reported butter and flour missing their
amounts. A JVM harness ran 30 real pages (RecipeTin Eats, King Arthur, BBC Good Food, Once Upon
a Chef, Taste of Home, Pinch of Yum, Bon Appétit, Delish, Food.com, Spend with Pennies, Jamie
Oliver, Betty Crocker and others) through `BlogPageParser` and `StepAmounts`, counting each
step's first mention of a line's head word: 362 mentions. Before: 128 got an amount, 234 none.
After: 201 an amount, 22 a link, 139 none. The causes fixed: the line's describing words ("the
butter" for "unsalted butter, very cold"; "the oil", "the stock", "the sugar" for "granulated
sugar"), "8 tablespoons of salted butter" naming "of salted butter" (`of` is now an English
`leadingWords` entry, which corrects the pantry's name for such lines too), duplicate lines
with the same amount, "Cream together butter and sugar" (`together` is a `before` word), King
Arthur's "King Arthur Unbleached All-Purpose Flour", and step titles. What stays as written,
on purpose: the step already gives the amount ("add 1 tbsp olive oil", BBC Good Food's style:
about a third of what remains), says how much ("half the butter", "1 tablespoon of the oil"),
a longer or other name ("the flour mixture", "Cheese Sauce", "lemon juice", "the bay leaves",
"the cheese" for "mozzarella cheese": cheese, sauce, chocolate and herb varieties aren't
describing words), a line without an amount to lend ("Kosher salt", "lemon wedges, for
serving", "pinch of nutmeg"), a line used in parts ("divided", "plus more"), a brand the table
doesn't list ("Gold Medal™ All Purpose Flour"), and a describing word in the step that isn't on
the line ("the mashed bananas" for "mashed very ripe bananas"). Every fixed and kept case has a
corpus `Step` row.

Known limit in English, left as written on purpose: an ingredient also used for greasing or
dusting when the list has only one line for it ("grease the pan with butter" then gets the
batter's butter, since the step's words can't tell the two uses apart). The other languages
now guard it with `dustingWords` (below); English's list is empty, so its results stay as
measured. (The French elision and the English-only `lineDescriptors` limits noted here before
are fixed below.)

**Spanish, French, German, Italian and Portuguese (#262, 2026-10-14).** The same harness ran
101 real pages from native sites (recetasgratis, directoalpaladar, cocinatis; marmiton, 750g,
cuisineaz; gutekueche, emmikochteinfach, einfachbacken; giallozafferano, cucchiaio, misya;
tudogostoso, receitasnestle, cozinhatecnica), counting each step's first mention of a line by
the name's first or last word (German: also a compound's tail). Amount / link / none, before
→ after: es 108/5/202 → 146/8/161, fr 82/4/175 → 125/6/133, de 60/6/57 → 70/6/47, it
28/7/125 → 39/12/109, pt 79/8/165 → 111/9/132. English's 30 pages give the same report,
word for word. Every newly shown amount was checked against its line by hand. What changed,
all table-driven (`steps.json`, empty lists behave exactly as before):

- **Describing words after the noun** (`trailingDescriptors`): a line's name may be the step's
  words followed only by these ("el aceite" for "aceite de oliva virgen extra", "a farinha" for
  "farinha de trigo", "le beurre" for "beurre doux", "lo yogurt" for "yogurt bianco", "a
  cebola" for "cebola pequena"). The same kinds of words English's `lineDescriptors` holds:
  oil, flour, sugar and stock kinds, salted or not, colours, sizes, ground, powdered, ripe,
  frozen, canned. Any other word is another ingredient: "harina de arroz", "aceite de
  sésamo", "vinagre de Jerez", "azúcar glas", "arroz basmati". Such a mention names every line
  starting with its words, so two oils are a link.
- **French elision**: "3 c. à s. d'huile d'olive" lends "3 c. à s. d'", which takes the place
  of "l'" ("Faites chauffer 3 c. à s. d'huile d'olive"): the line's head word is read from its
  own words, so "d'olive" is "olive".
- **States after the mention** (above): "le beurre fondu", "la cebolla picada", and `after`
  words like "caliente", "coupé", "égouttés", "progressivement", "insieme", "durante".
- **German compounds** (`compoundHeads`, written "weizen-mehl"): Weizenmehl, Dinkelmehl,
  Süßrahm- and Sauerrahmbutter, Schlag- and Kochsahne, Gemüse-, Hühner-, Rinder- and
  Geflügelbrühe, Oliven-, Raps-, Sonnenblumen-, Pflanzen- and Speiseöl, Baby- and Blattspinat,
  Vollmilch, Kristall- and Rohrzucker, Gemüsezwiebel, Hokkaido- and Butternutkürbis answer
  their last part. Only listed compounds: a first part alone would let "Sonnenblumenkerne"
  answer "die Kerne" (fruit pips) and "Meerrettich" "den Rettich", and "Erdnussbutter" never
  answers "die Butter". A compound mention also names every line whose last word ends in it,
  so "das Mehl" beside Weizenmehl and Mandelmehl is a link. German's `lineDescriptors` hold
  the adjectives before a noun ("brauner", "gemahlener", "TK").
- **Contractions** (`contractions`): only those that say where ("nella farina" → "in 250 g di
  farina", "na água" → "em 3 colheres de água", "zum Mehl" → "zu 100 g Mehl"). "al", "au",
  "ao" and "à" stay out: they also make dish names, and the harness found "fenouil au citron",
  "chocolate ao leite" (milk chocolate) and "garbanzos al curry".
- **Dusting and greasing** (`dustingWords`): a sentence holding "untar", "polvilhar",
  "enfarinhar", "engrasar", "fariner", "beurrer", "infarinare", "imburrare", "ungere",
  "einfetten", "bestäuben"… keeps every mention as written: the harness found "Polvilhar a
  farinha", "unte a forma com manteiga", "uma assadeira untada e polvilhada com farinha de
  trigo" lending the dough's whole amount. German lists verbs only, since "in einer gefetteten
  Auflaufform" describes a tin greased earlier. "mit dem Mehl bestäuben" for a roux loses its
  amount: the safe side.
- **Verbs** in `before` (Italian sites write "Unite farina, cacao e lievito"), unit words in the
  names tables (German "Stg", "Kn", "Zw", "Schuss"; French "pièce(s)", "branche(s)";
  Portuguese "unidades de"; Italian "bacca"), so those lines have a name.
- **An amount the mention repeats** is trimmed: "el diente de ajo" with "1 diente de ajo" reads
  "1 diente de ajo", not "1 diente de diente de ajo" (also "1 Feuille(s) Laurier").

Left as written on purpose: "con", "com" and "se" after a mention ("el arroz con leche", a
dish; "el curry se cocine", the dish not the powder); produce, rice, ham and vinegar varieties
("tomate pera" is in, as English's "roma"; "arroz bomba", "jamón serrano", "vinagre de Jerez"
aren't); "passata di pomodoro" and "salsa" lines (a sauce word is never a describing word); "le
paprika fumé" for "paprika"; a describing word in the step that isn't on the line ("die
gelben Bohnen" for "Bohnen, gelb"); and, as in English, a step that gives its own amount (most
recetasgratis, emmikochteinfach and cozinhatecnica steps).

Remaining gaps: a dish named after its ingredients still takes an amount after a conjunction
or "con"/"with" before it ("la torta pere e 150 g di cioccolato", "Refrigera el arroz con 1
litro de leche" when milk has one line); GialloZafferano's name-first lines ("Burro 100 g")
lend nothing (#15); "farina 00" has no name (a digit in a name); some sites' steps reach the
app with HTML entities undecoded (750g's "l&rsquo;oignon", tudogostoso's "&oacute;leo") or as a JSON
array in one string (einfachbacken), which no word table can read; and Japanese stays a no-op
(it has tables, but no spaces between words to find a mention by).

## Home: the resume card and three ways in (#286)

**With `libraryFirst` (#375) there is no Home**: the Recipes tab opens the library, which takes the card, the
ways in, the gear and the restore row; see "Library first (#375)". What follows is Home with the flag off.

The owner's pick (2026-10-19): 01 V2 "Resume first" with 02 V1 "Three equal doors". It
bends the settled link field: an always-visible field became a tile that opens it.

- **Today's card (#402, owner, 2026-11-19; `homeTonight`).** The resume card used to be the most
  recent recipe whether or not anything was being cooked, so a finished recipe still sat under
  "Continue cooking". Now it is one of two cards (or none):
  - **Mid-cook** (the recipe below): "Continue cooking" with Resume cooking, as before.
  - **Otherwise: "Today".** Today's planned meals from Week, **in the meal types' own order** (the
    order Week uses: Breakfast, Lunch, Dinner, Snack and the cook's own, as reordered; not dinner
    first), and the card leads with **the earliest meal not yet cooked** (the owner first asked for
    dinner first, then changed it, since the lead may be breakfast): the recipe's photo (a plain
    band without one), "Today" over its name, and **Start cooking**. The rest of today's uncooked
    meals follow, one short line each ("Dinner: Lemon chicken"), in order, each tapping through to
    its recipe. A planned **note** shows its text, no button (and is never "cooked"). Nothing left
    to cook today and nothing mid-cook: **no card at all**, and the ways in stay.
  - **Meal windows** (owner follow-up, 2026-11-19: at 7pm an uncooked lunch must not still lead).
    By the meal type's built-in key, in local time: **breakfast until 11:00, lunch until 15:00,
    dinner and snack until the end of the day**; the cook's own meal types have no window. A meal
    whose window has passed drops off the card entirely (not onto the second line), cooked or
    not, a note planned under it too. The times are constants in one place on each platform
    (`MealWindows`, core and iOS, line for line); the end of a window is exclusive (10:59 breakfast
    shows, 11:00 doesn't). The ViewModel's clock wakes at the earliest of midnight and the next
    window's end (and within a minute at most), so the card changes by itself at 11:00 and 15:00.
  - **Cooked today** = the recipe has a `cooked_photos` entry dated today ("I made this" photo,
    "Mark as cooked", #116, #173), **or a run of it was finished today**: finishing cook mode
    ("Done — finish") now **records today's cooking** in that same table, once a day (no entry if one
    is dated today already; no new store, and the entry shows in Your cooks and the Cooked view
    like any). A recipe planned twice today (lunch and dinner) drops both once cooked.
  - **Start cooking** opens the recipe **on the Week's stack** at the planned servings, in cook mode:
    Android `week/recipe/{id}?servings=&startCook=true`, iOS `Route.startWeekRecipe`, and Back
    returns to the Week. It starts with `CookController.start`: a finished last run starts afresh
    (the reading view's Start cooking), an unfinished one stays where it was. A tap on the card's
    photo or name, or on a line, opens the recipe on the Week's stack with no cook flag.
  - **Live**: it follows the plan, the cooked entries and the day (the ViewModel's clock wakes at
    midnight, or within a minute if the clock or zone moved), never the screen's own date.
  - **A finished run is never a resume (#402).** `CookResume.of` is null when every step is done,
    whatever its timers, and finishing the last step now **stops the run's timers**
    (`CookSession.done`: the same as starting a finished run afresh): their alarms and countdowns
    are cancelled (Android ongoing notification, iOS notification and Live Activity), and the saved
    progress holds no deadline. Until then a finished run with the last step's rest timer still
    counting was offered as Continue cooking's "Resume cooking". (A finished run opened from a
    countdown or alert stays in the reading view, #333; with the timers stopped on finish, that is
    only for a timer started after finishing.)
  - Off (`homeTonight`): the card is exactly as below, always the most recent recipe.
- **The resume card** is Continue cooking's recipe (the most recent, as before; with `homeTonight`
  only while it is being cooked). The ViewModel
  follows that recipe through `observeRecipe(id)` (a repository flow that counts no view) and
  derives a `CookResume`: the recipe is being cooked when cook mode was left on or a step timer
  still runs. Then it shows the step stopped on ("Step 2 of 4", the existing `cook_position`)
  and the running timer that ends first ("18:42 left"), counted down from its saved deadline
  every 250 ms as cook mode does, never written; "Resume cooking" opens
  `recipe/{id}?cook=true` (which resumes, never restarts: #333). Otherwise it shows only the name and **"Open recipe"** (decided
  here: the plain wording). The photo and name also open the recipe, as the Continue cooking
  row did. A recipe with no photo gets its designed image, 112 dp tall (#403, below; a short band in
  the basil wash with `noPhotoImage` off), never a stock image.
  A newer recipe never shows the last one's place, even for a moment.
- **Add a recipe:** Paste a link opens the Recipes screen's dialog (Android `PasteLinkDialog`,
  iOS the same alert), whose Go is enabled only for what `UrlInput` reads as a link, so
  Home's `urlInput`/`urlError` state and "That doesn't look like a link." went. Scan a
  recipe is the same `ScanRecipeButton` (#226) drawn as a tile; with `photoText` off there
  are two tiles. Type a recipe is the #29 editor. The muted line under the tiles ("Or share a
  link to Recipe Clipper from any app.") replaces the subtitle under the title. On iOS the
  tiles stack at the accessibility sizes.
- **Recently viewed** was a row of photos with their names, the five before Continue cooking.
  **Removed (#371, owner, 2026-11-19):** it repeated the top five of Recipes, whose default sort
  is Recently viewed (#345's research: recent is a sort over the one library, never a home
  section). Home now lists only Continue cooking's recipe. The Recipes sort stays.
- **Counts:** Recipes is what the Recipes screen lists (`observeHistory("")`, so the tour's
  sample counts, as in its "N recipes" line); Lists is every list.
- **Home's rows after #371 (owner, 2026-11-19):** the Recipes bottom tab opens Home, so Home's
  Recipes row (and the shelf's See all) was the only way into the Recipes screen: the row stays.
  The Lists row goes **with `oneCookbook` on** (redundant with Recipes), and stays with it off, where
  Recipes has no Lists card. The Lists screen and its route stay. Reaching it from Recipes took
  three tries (owner, 2026-11-19): two cards at the end of the strip (too far to scroll to with many
  lists), then a fixed "Lists" header with "All lists" and "+" above a lists strip, then the final
  one: **a single Lists card at the front of the views strip**, in the views' card style, its count
  the number of lists, title `nav_lists`, opening the Lists screen (Back returns to Recipes). No
  strip of the lists on Recipes and no "+" there: a list is made on the Lists screen (its "+" beside the
  title) or in the save-to-list sheet (needs a recipe). `lists_all` was added and
  removed again. The card shows whenever the strip does.
- **The tour:** `HOME_LINK` is anchored to the Paste a link tile, and now reads "…or tap here
  to paste one."
- Unchanged: the empty-library hint and "Restore from a backup file", the one-time backup
  card, the sample-only state and the gear. The mockup's step text ("· simmer covered") was
  left out: the card names the step by number only.

## Library first (#375, 2026-11-20)

The owner's pick (2026-11-19) was variation A, "Card on top", of the Recipes-tab designs: the tab
opens the library, as Paprika, Mela and Crouton do. It **removes Home** (#286, #371) and moves its
jobs; each rule it bends is named here. Behind `libraryFirst` (on by default; **needs `oneCookbook`**,
whose views strip carries the Lists card that Home's Lists row was the only way to without it, so with
`oneCookbook` off the tab keeps opening Home). Off, everything below is as the Home section says.

- **The tab opens the library.** Android: the Recipes graph's start destination is `recipes` (not
  `home`); iOS: the Recipes stack's root is `RecipesScreen`. It is the stack's first screen, so there
  is **no Back**, and **choosing the open tab again pops to it** (the same code as before: pop to the
  graph's start / empty the path). A route from an intent still always lands in Recipes: a share's
  import is pushed over the library, and Back from it returns there. A received file's "Recipes" (#149)
  goes back to the library rather than stacking a second one (`openRoute(Routes.RECIPES)`,
  `Router.showLibrary()`). Not built: scrolling the list to the top on a second tap.
- **Home's jobs, kept by `HomeViewModel`.** The library root takes `HomeViewModel` as a second
  ViewModel (`LibraryRoot` on both platforms) for what it already follows: the cook's place, today's
  meals, the restore row and the Ask an AI sheet. Its link-dialog and counts go unused. Nothing else
  uses it with the flag on. The Paste a link dialog is the Recipes ViewModel's own.
- **The card (#405's, compact).** Above search, the first item of the list, so it **scrolls away with
  it**: a 64 dp thumbnail (the photo, or a plain paprika-wash block, never a stock image), "Today ·
  Dinner" (the meal type's own name) or "Continue cooking", the name, one line, and the button. Today:
  the next meal still to cook as "Dinner: Lemon chicken" (it opens that recipe) and **Start cooking**;
  a planned note has no button. Mid-cook: "Step 2 of 4" with the running countdown, and **Resume
  cooking**. The rules for what it shows are #402's, unchanged (meal windows, cooked today, a finished
  run is never a resume), with one difference: **only a recipe being cooked, or today's next meal with
  `homeTonight` on, gets a card.** Home's "Open recipe" card for a recipe nobody is cooking is gone: with
  nothing to cook, search sits under the title. The photo and name open the recipe.
- **Start cooking opens on the Recipes stack**, so Back returns to the library (Home's went to the
  Week's stack, because Home had no plan of its own). Android: `recipe/{id}?servings=&startCook=true`
  (the Recipes route gained the Week route's two optional arguments; `Routes.plannedRecipe`); iOS:
  `.startWeekRecipe` / `.weekRecipe` pushed on the Recipes path (the destination is shared). A planned
  meal's other lines, and the photo, open it the same way, at the planned servings, without cooking.
- **The + opens "Add a recipe"**, a bottom sheet replacing #102's two-item menu: Paste a link (the
  existing dialog, #372), Scan a recipe (#226, `photoText`), Type a recipe (#29), Ask an AI (#351,
  `askAi`), each with Home's tile line, then "Or share a link to Recipe Clipper from any app." A choice
  closes the sheet and then goes where it leads (iOS waits for the sheet to be away before opening the
  next sheet). The + is a basil circle; on a page that is not the root (a view) there is none, as
  before. **The Settings gear sits beside the Recipes title**, left of the + (the Settings rule left
  this to the owner; this is that call). Select (#377) sits left of the gear. **Superseded with
  `recipesTopB` on (#449, owner, 2026-11-21):** Settings and Select move into a ⋮ menu, last in the
  title row after the +; see "The top of Recipes, design B".
- **An empty library** (nothing, or only the tour's sample, #151): in the list's place a heading
  "Your recipes go here", the four ways in as tiles (two by two; one column at the accessibility sizes
  on iOS), the sharing line, "Restore from a backup file" (#150) with how it went, and the sample under
  it. No search, strips or sort line yet. Home's "Recipes you open will show up here." is gone with it.
- **The backup-folder card** ("Keep a backup copy?", #150, Android) now sits under the card, above
  search, until answered (owner's call to confirm: it was between Home's tiles and rows).
- **The tour.** One tip pointed at Home: `home_link`. It keeps its id, so a cook who has seen it has
  seen it, and its screen (`home`), and **moves to the +** with its own words: "Share a recipe link to
  this app, or tap + to add one." (`tooltip_library_add`; Home's `tooltip_home_link` stays for the flag
  off). The library is the first screen, so it shows on first launch. No other tooltip pointed at Home
  (the others are on the recipe, cook, groceries, pantry and settings screens).
- **Tests.** UI tests launch with the flag off, so the suites that go through Home are untouched;
  `libraryFirst` in `launch(flags:)` turns on `oneCookbook` with it and makes `atRoot`, `openRecipes`,
  `openLists`, `pasteLink` and `backToHome` mean the library (`LibraryFirstUITests`, its own shard
  weight). Android's walkthrough recordings (`WalkthroughBase.start`) pin the flag off unless a clip asks
  for it, since they are written through Home.
- Not changed: variation D (a cooking bar docked above the tab bar on every tab) is filed separately; the
  ongoing notification and Live Activity still show a timer outside the app.

## Closing the Today card (#448, 2026-11-21)

The owner's request (2026-11-21): "the announcement of what to cook today should be optionally able
to close, maybe have a setting to make that optional." Behind **`todayCardClose`** (on by default in
debug and release, both platforms); off, no close button and no switch, and the card is #402's.

- **A close button on the Today card** (#402's card: the compact one above search with `libraryFirst`,
  Home's with it off): a small muted × on a disc of the card's own colour, in the card's top right
  corner (over Home's photo; beside the thumbnail and name on the compact card), a 40 dp / 44 pt touch
  target, labelled "Hide today's meal until tomorrow" for TalkBack and VoiceOver. A tap hides the card
  **for the rest of that local day**; it returns at midnight, whatever is planned. **Per day, not per
  meal**: the card's next meal after a window ends stays hidden too. No snackbar (nothing to undo; a
  wrong tap costs a day's card, and the Plan tab still has the meal).
- **The mid-cook Continue cooking card is never closable** and ignores the close and the switch: it
  reflects a cook in progress, and is how the cook gets back to it.
- **"Show today's meal"**, a switch, on by default, in Settings' **Plan** section (`settings/week`),
  above the reminders: "Today's planned meal at the top of Recipes, with Start cooking. Closed, it comes
  back tomorrow." (true with `libraryFirst` off too: Home is the Recipes tab's first screen). Off, the
  Today card never shows; on again, it shows unless closed today. The Plan section now shows with
  `eveningReminders` **or** `todayCardClose` (its reminder rows only with the first, the switch only with
  the second; the index's "Reminders on/off" value only with the reminders), and the Plan tab's "Plan
  settings" link (#412, `planSettingsLink`) follows the section.
- **Keys** (`unit_preferences` / `UserDefaults`, the same on both): `show_today_card` (a boolean, on
  when unset) and `today_card_dismissed_day` (the local epoch day of the last close, a Long / an
  integer; absent when never closed). Both are in `unit_preferences.xml`, so backed up already.
- **One pure rule**, `TodayCardChoice.shows(closable, showSetting, dismissedDay, today)` (core
  `data/model`, iOS `Data/Model`, line for line): with the flag off it is always true; otherwise the
  switch must be on and the close not dated today (a close dated another day, a clock moved back
  included, counts for nothing). `HomeViewModel` applies it to #402's `TodayCard.of` with the day of
  its injected clock, and follows `AppPreferences.settings`, so the switch and a close take effect at
  once and the card comes back when the day turns over. iOS reads the flag as it stands each time the
  plan or the settings change.
- **Not changed:** the Tonight widget and the evening and Tonight reminders (#309, #397) read the plan
  themselves, not this card, and ignore both choices: the switch is about the card only.
- **Tests.** `TodayCardChoiceTest` / `TodayCardChoiceTests` (the rule), `TodayCardCloseViewModelTest` /
  `TodayCardCloseTests` (closed today hidden, yesterday shown, the switch, mid-cook untouched, the flag
  off as before, the Settings switch and section), `ShowTodayCardSettingsTest` (the switch on its page),
  a tap on the close in `LibraryFirstScreenTest` and `HomeScreenTest`, and
  `LibraryFirstUITests.testTheTodayCardsCloseHidesItForTheRestOfTheDay` (closed, and still closed on a
  relaunch).

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
  enabled only for what `UrlInput` reads as a link (since #286, Home's tile opens it too), then the
  usual import route. No clipboard is read unasked: Android shows a toast and
  iOS a permission prompt on every read.
- **(Since #345 step 2, with `oneCookbook` on, nothing is culled at all: see "One cookbook" below.
  What follows is the clean-up as it stands with the flag off.)** **A typed-in recipe is never culled,** like a listed one, and doesn't count
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
- **The tidy list (#286, the owner's pick V1).** Each row is the photo, the
  title, then the site and total time ("sundaysupper.blog · 1h 15m"), with a
  filled basil bookmark for a recipe in any list instead of the word
  "Saved" (its spoken label is "Saved to a list"). The site is credited as
  the reading view credits it, through one helper on each platform
  (`sourceCreditText`, `Strings.sourceCredit`): the domain, "Clipped by you ·
  domain" for a clip, and nothing for a typed or scanned recipe, whose
  `manual:` key is never read as a site. A long site is cut short, never the
  time. "1 min ago" left the row with the mockup; the day headings the owner
  also picked (V3's, in the Recently viewed sort) are a later step. The
  summary queries now select `sourceUrl` (a query change, no migration);
  `RecipeSummary.sourceDomain` reads it. The row is `LibraryRow`, Recipes
  only: Home and List detail keep `RecipeRow` until their own redesigns.
- **The sort is a quiet line under search** (#286): the count on the left
  ("4 recipes", the matches while searching, or the free tier's "12 of 20
  recipes" when it shows) and the current order on the right, which opens
  the same radio menu. The header's ⋮ (⋯ on iOS) held only the sort, so it
  went. The line hides when there is nothing to order, unless the free
  tier's count is showing. Photos stayed 64: the mockup's 56 would have made
  them smaller, not bigger.

- **"History" is not a word the cook sees** (#345 step 1, the owner's pick V1 "Say what it
  is"; V4 "One cookbook" is step 2). Recipes is the one library, in Recently viewed order by
  default; the delete dialogs, the list delete, "Update from source" and the import note say
  "your recipes" or "Recipes", in all six languages, on both platforms. Code identifiers keep
  the name (`observeHistory`, `cullHistory`, `LibraryLimit.History`).
- **The filter** under search: All, In a list and Not in a list, as plain text pills with the
  library's counts ("In a list 12"), the chosen one filled in ink (Android `RecipeFilterPills`,
  radio semantics; iOS `RecipeFilterPills`, selected trait). They pick recipes, never
  ingredients, so the reading view's no-chips rule is untouched. "In a list" is the `isSaved`
  `EXISTS` over the cross-ref table, the same one as a row's bookmark, so the two can't disagree;
  the filter is in the query (`observeHistory(query, filter)`, DAO `listed` = -1 / 1 / 0), with
  the search still applying inside it. The counts (`observeRecipeCounts`) ignore both. The
  filter lives in the ViewModel's state (it survives rotation) and is **not remembered**: the
  screen opens on All, unlike the sort. A tap answers at once (no debounce); the pills hide
  while the library is empty and stay when a filter matches nothing, which has its own empty
  line ("Every recipe is in a list.", "No recipes are in a list yet…").
- **The clean-up line (only with `oneCookbook` off; with it on nothing is cleared, so the line is
  not true and does not show):** one muted panel with an info glyph, only under Not in a list, only
  with a row under it, and only with the free tier off (`LibraryLimit.History(50)`): "Recipes in
  no list are kept up to 50. Past that, the ones you opened longest ago go first. Save one to a
  list to keep it." The free tier's count line and an unlocked library need no such line. The
  mockup's outlined bookmark on each Not in a list row (opening the save-to-list sheet from
  Recipes) is not built: a recipe is still saved from its own screen.

## The free tier and the unlock (#107)

**Superseded with `proUnlock` on (#413):** recipes are unlimited for everyone and this unlock is Pro;
what follows is the app with `proUnlock` off. See "Pro" below.

Owner's decision (2026-09-25, rules approved as proposed): the free app keeps
20 recipes; a one-time purchase unlocks unlimited ones. **Changed by #345 step 2 (2026-11-18,
`oneCookbook` on): the limit is 50 and at the limit a new recipe is shown, not saved; nothing is ever
swapped out. What follows is the swap at 20, which applies with `oneCookbook` off.** Behind the `freeTier`
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

## One cookbook (#345)

**With `proUnlock` on (#413) the free tier's 50 never applies** (no shown-not-saved, no undo rule);
the rest of this section stands.

The owner's pick (2026-11-18) from the library designs (private artifact, source
`~/Downloads/RecipeClipper-designs/library-history/`): **V1 "Say what it is"** (step 1, #346), then
**V4 "One cookbook"** (step 2). Recipes and "history" were one list under two names, and Home's Recently
viewed repeated its top. Every recipe manager checked (Paprika, Recipe Keeper, Crouton, Samsung Food,
NYT Recipe Box, Mela) keeps one library that never deletes on its own, makes "recent" a sort or a
shelf, and, when it has a free limit, blocks the next save at the limit instead of deleting an old
recipe (Paprika 50, Recipe Keeper 20, Crouton 5). Paprika's "Uncategorized" is the model for the
automatic "Not in a list" view.

Everything below is behind the **`oneCookbook` flag** (on by default in debug and release); turning it
off restores the old behaviour: the clean-up of 50, the free tier's swap at 20, V1's filter pills.

- **Nothing is removed automatically.** With the free tier off, or unlocked, `LibraryLimit.of` answers
  `Unlimited`: no `cullHistory`, no one-for-one swap. "History is automatic… capped at 50" became
  "every recipe stays until the cook deletes it". The protections (listed, planned, in a menu, typed in,
  with photos) no longer decide anything with the flag on; the NULL-safe cull SQL stays for the flag-off
  path (and `ShareFileRepositoryTest`'s cap test).
- **The free tier is 50, and a 51st is shown, not saved.** `LibraryLimit.Capped(50)` (Android
  `Capped`, iOS `.capped(max:)`): `RecipeDao.upsert` returns `NOT_KEPT` for a new recipe when
  `count() >= max`, with no removal; the existing "Not saved" path then applies unchanged (the quiet bar
  with Unlock on the recipe screen, the "Your library is full" dialog over the editor or the clip, the
  share extension's card). Re-sharing or refreshing a recipe already here is allowed (it is already
  counted), as are "Update from source" and editing; deleting one of the 50 frees a place, which is
  intended (a cap on what you keep, as Paprika's). The tour's sample still counts toward nothing. The
  strings (`recipe_not_kept`, `library_full_body`, `unlimited_body`, `backup_skipped_free`) say "the free
  app keeps %d recipes" and no longer "all in lists, plans or typed in"; the number follows the flag
  (`LibraryLimit.freeRecipes(oneCookbook)`; iOS reads the mirrored limit, so the share card agrees).
- **One limit, every way in.** All paths reach `upsert` with `library.limit`: a shared link, a Reddit
  post, a page passed through Cloudflare's check (`importPage`), a clip (`saveClip`), and typed, scanned
  (#226), photo-read (#198) and shared-text (#306) recipes, which all save through `addManual`. Imports
  take a second route, `BackupMerger` (`capsEveryRecipe`): a backup or a sent file stops at the limit,
  counting every recipe here, listed ones first, then the most recently viewed, with the existing
  "wasn't added: the free app keeps %d recipes" summary; a skipped recipe takes its list memberships,
  plan entries, menu meals and photos with it. The iOS share extension saves in its own process, so it
  reads the limit the app mirrors into the App Group suite (`library_limit`: now also `capped`) and
  applies the same `upsert`. Tests for each path: Android `OneCookbookLimitTest`,
  `OneCookbookDaoTest`, `OneCookbookImportTest`; iOS `OneCookbookTests`.
- **Undo takes the same limit** (owner, 2026-11-18). Undo after a swipe delete on Recipes (or on a
  view's screen, the same ViewModel; the recipe screen's delete has no undo) used to restore whatever
  the count, so delete one at 50, add one, Undo left 51, and repeating grew the library by one a
  cycle. Now `restoreAll` (Android `RecipeRepository.restoreAll`, `RecipeDao.restoreAll`; iOS
  `RecipeRepository.restoreAll`, `RecipeDao.restoreAll`) checks `LibraryLimit.Capped` in the one
  transaction, counting as `upsert` does (the tour's sample counts toward nothing): if restoring would
  take the library past the limit it restores **none** and answers `LIBRARY_FULL` / `.libraryFull`.
  A burst shares one all-or-nothing undo, so a burst that doesn't fit all fits none. Then the delete
  stands (the photo files are forgotten, as when the snackbar times out) and the screen says once, in a
  plain snackbar with no action, "Couldn't restore: your library is full" (`snackbar_restore_full`; the
  undo's own outcome, so within "snackbars are only for undo"). Unlocked, or with `freeTier` or
  `oneCookbook` off, Undo restores as before. No Unlock action on it: after a refusal nothing is held
  to restore, so unlocking from there could only unlock, not bring the recipe back. Tests: Android
  `OneCookbookUndoTest`, `RecipesViewModelTest`, `RecipesScreenTest`; iOS `OneCookbookUndoTests`,
  `RecipesViewModelTests`.
- **Recipes is a cookbook.** Under search, a **strip of cards**, Lists first (#371, see Home's rows),
  then the automatic views, in place of V1's pills: Not in a list (the `isSaved` `EXISTS`, as the bookmark), Cooked (any
  `cooked_photos` row, so "Mark as cooked" counts, #173), Typed by you (`contentOrigin = 'MANUAL'`,
  never the tour's sample, so scanned and shared-text recipes count too), From a chat (`contentOrigin = 'CHAT'`, #355). Each is a card with its
  count; a view opens as **its own screen** (route `recipes/view/{view}`, Back to Recipes; iOS
  `Route.recipesView`) which is the Recipes screen over the same ViewModel with its filter fixed: title,
  a one-line note, search, sort, swipe to delete with the usual undo, no strip, no + menu. The Lists card
  opens the Lists screen. Counts come from the one `observeRecipeCounts` query (no migration). Views are
  questions about what is stored, never a filing step: "Lists are deliberate", "Only Favorites is
  permanent", "No Saved section" and "Search is on Recipes only" are untouched, and Home keeps its
  Recipes row (its Lists row only with `oneCookbook` off, #371).
- **Recently viewed** is a sort (still the default), no longer also Home's shelf: #345 first gave
  the shelf a **See all** (it set the sort and opened Recipes), and #371 removed the shelf and
  See all together (2026-11-19: the shelf repeated Recipes' top five).
- **A recipe in no list has an outlined bookmark** on its row (not in a view's header), opening the
  existing save-to-list sheet (checkboxes that write at once, "+ New list" inline); the recipe then
  leaves Not in a list by itself. **The filled and the outlined bookmark share one slot** (48 dp on
  Android, 44 pt on iOS, glyph centred; a grid cell's badge too), so saving a recipe never moves the
  glyph or the title's column ("the bookmark moves to the right when it turns red", 2026-11-22);
  `BookmarkSlotTest` and `BookmarkSlotTests` pin it. The filled one is still not a button.
- Unchanged and still true: the migration count (none: counts, views and the cap are queries), the
  `freeTier` flag's default (off until the store products exist), the unlock's seams and cached state.

## Recipes: search filters and a photo grid (#378, #380, 2026-11-19)

The owner asked for both on 2026-11-19 ("Do 1-8" of the common UX flows list). Two flags, on by
default in debug and release, on both platforms: `recipeFilters` (#378) and `recipesGrid` (#380).
Off, Recipes is exactly the screen of "One cookbook" above. (With `recipesFilters` on, #454, the library's
sheet folds Not cooked yet into a Cooked: Any / Cooked / Not yet choice beside the views; the rules below are
unchanged and reused.)

**Quick filter chips (#378).** Under the search field, three plain text pills (the old filter
pills' look, ink-filled when on): **30 min or less**, **Not cooked yet**, **Uses what's in my
pantry**. They sit between search and the views strip, on Recipes and on each view's screen (a
view is the same screen; **Not cooked yet is not offered inside the Cooked view**, where it could
only empty the list). They are on/off switches, not a radio group (a checkbox for TalkBack, the selected
trait for VoiceOver when on), and they **combine with each other (AND), with the search text and
with the sort**: the search is still the one SQL query (`instr(lower(...))`, never `LIKE`, the
`%` rule untouched), the chips narrow what it answers, then the sort orders what is left. Like the
views they are questions about stored data, never filing; they are **not remembered** (Recipes
opens with none on, as the old pills opened on All) and live in the ViewModel, so they survive
rotation. The count line says what is shown ("2 recipes"); the line, the chips and the toggle stay
when a chip leaves nothing, and the empty line says "No recipes match these filters." (it wins over
the search's own).

- **Done in the ViewModel over the observed list, not in the SQL** (owner's "SQL where cheap"):
  the three rules read stored text (times) or another table's names (the pantry), which SQL can't
  do; the one that could be SQL, Not cooked yet, reads `lastCookedDay == null`, the column the row
  already carries: `NOT EXISTS` over `cooked_photos` is exactly `MAX(day) IS NULL`, the Cooked
  view's `EXISTS` turned round (`RecipeQuickFiltersDaoTest` proves the two partition the library).
  So no `observeHistory` signature, query parameter or migration changed. The summary rows gained
  `prepTime`, `cookTime` and `language` (a query change only).
- **30 min or less** (`RecipeQuickFilters.minutes`, `:core`; Swift port; corpus rows `Mins` and
  `Quick`): the recipe's **total time if it has one, else prep plus cook, and only when both are
  present and readable**. Readable is `Durations.minutes`: ISO, a phrase in the recipe's own words
  and what the app stores back ("1h 30m", "1h 30min", "1時間 30分"), read with the **recipe's**
  language's tables (a recipe with no stored language, from before #14, as English; a language with
  no tables, ISO and English's "1h 30m" alone). Anything else is **never matched, never guessed**:
  "Overnight", "20 to 25 minutes", "1-2 hours", a zero ("PT0S" is absent anyway), a figure past ten
  million, a time in another language's words. A total that is present but unreadable does **not**
  fall back to prep plus cook (the site said a total; if we can't read it we don't pretend we can).
  **30 min or less**: 30 min itself counts (the owner's call, 2026-11-19, over a first draft that
  was strictly under; the chip says "30 min or less", the code `thirtyOrLess` / `THIRTY_OR_LESS`). Prep plus cook is a
  floor (resting time isn't in it), which is why a stated total always wins.
- **Not cooked yet**: no cooking entry, a photo of it (#116) or "Mark as cooked" (#173): the
  Cooked view's definition, reused (`RecipeSummary.lastCookedDay`, itself `MAX(cooked_photos.day)`).
- **Uses what's in my pantry** (`RecipeQuickFilters.coverage`; corpus rows `Cover`): the chosen
  rule is **at least half of the recipe's nameable ingredient lines are Have**. Nameable lines are
  what Use it up and What I need already read: blank lines and headings ("For the sauce:") dropped
  (`GrocerySources.buyable`), then each line's `IngredientName.of` in the **recipe's language**
  (stored, else detected, as What I need's); a line the app can't name ("salt and pepper to
  taste") is not counted either way. Have is `PantryMatch` and nothing else: the name matched
  against a pantry item **in the same language**, **in stock or a staple (`alwaysHave`) counts as
  had**, an item out of stock is Buy, "rice flour" is never "flour", "butter beans" never "butter",
  and the **quantity is never compared** (a pinch of flour covers a kilo): the chip answers "do you
  have these things", never "enough". `PantryCoverage.matches` is `total > 0 && have * 2 >= total`
  (half rounds in the cook's favour: 1 of 2, 2 of 3, 2 of 4), so a recipe with nothing nameable
  never matches, nor one in a language with no tables.
  - **Why half and not all.** "All of it" would match almost nothing (nobody has every line); "any
    one" would match nearly everything (everyone has salt or eggs). Half is the plainest middle the
    cook can check by eye. The rule is **not a hidden verdict**: with the chip on, **each row it
    keeps says "You have 6 of 8"** (`recipes_pantry_have`, under the site and time; a basil line,
    like Use it up's) and one muted line under the chips says "Shows recipes where at least half of
    the ingredients are in your pantry, by name, not by amount." Both count the nameable lines, so
    a recipe of ten lines with two unnameable says "of 8". No model decisions (#104): a name match
    or nothing, so nothing here can be a confident wrong answer the cook can't see.
  - **Cost.** Every recipe's lines are read (`observeRecipeLines`: id, title, language and
    ingredients, one query) **only while the chip is on**, matched with the pantry **off the main
    thread** (an injected dispatcher on Android, `Task.detached` on iOS) **once per change of the
    pantry or of a recipe's lines**: a tick or a cook step rewrites a recipe without changing a
    line and matches nothing again (the same rule as Use it up, #332). While the answer is on its
    way the list is "loading" (null), never a wrong "no recipes". The chip follows the pantry live.
  - Ingredient lines are read **as stored** (not scaled or converted): only names matter.

**Grid or rows (#380).** A button **beside the sort** on the line under search (the count stays on
the left), showing the layout it switches to (the grid's glyph over the rows, the rows' over the
grid; named "Show as a grid" / "Show as a list" for the screen reader). The grid is the same screen:
the same header (back, title, search, chips, strip, count, sort), then **square photo tiles**, a
**title (two lines) under each, then the site and total time as a row has them**, and with the
pantry chip on, "You have 6 of 8". **Two columns on a phone, more as the width allows** (Android
`GridCells.Adaptive(140.dp)`, iOS `GridItem(.adaptive(minimum: 150))`: two at 320dp of content, three
and four on a tablet or in landscape). A recipe with no photo has the blank tile rows have (the same
hairline-coloured square); PR #404's designed image for it had not landed, and will want the grid's
placeholder swapped for it (one place per platform: `LibraryGridCell`).
- **The bookmark is kept as the save control**, on the photo's corner on a round ground-coloured
  disc (so it reads on any picture): filled for a recipe in a list (the accessibility label "Saved
  to a list"), outlined and tappable for one in none (opens the save-to-list sheet, `oneCookbook`),
  with the 48dp / 44pt target of a row's.
- **No swipe to delete in the grid**: a grid has no row to swipe, and the rows' swipe is untouched.
  The grid has the rows' other ways instead (#376, #377, wired when the two landed together): a
  long press on a cell opens the same menu (Add to list, Add to plan, Share, Select, Delete), and
  while selecting each cell carries a checkbox on its photo and a tap ticks it, through the same
  `RecipeSelection`, so a selection, its bar and its one Undo behave as they do in the rows. The
  selection acts on what the chips and the search leave in sight, in either layout (`listed` is
  the filtered list). Switching layout while selecting keeps the ticks.
- **Stored as a setting**: key **`recipes_layout`** in `unit_preferences` / `UserDefaults`, the enum
  by name (`ROWS`, `GRID`), **an unknown or absent name reads as `ROWS`**. A view preference like
  `recipe_sort`: not in the export file, not in Settings. It applies at once (the ViewModel writes
  it and updates its state without waiting for the preference's echo) and `settings` keeps every
  open Recipes screen in step. **With `recipesGrid` off the rows show whatever is stored**, and
  nothing is lost: turning it back on restores the choice.
- **Keys and scroll**: grid items are keyed `recipe-<id>` (Android; the header, note and empty line
  have their own keys, unique across the whole grid, as the LazyColumn rule says); iOS's
  `ForEach` ids are the recipe ids inside one `LazyVGrid`. Rows keep their keys.

**Tests.** `:core` `RecipeQuickFiltersTest` (minutes, 30 min or less, coverage, combining); the corpus's
`Mins`, `Quick` and `Cover` rows (Swift generated from the Kotlin); Android `RecipeQuickFiltersDaoTest`
(real SQLite: times and language in the rows, Not cooked as the Cooked view's complement, the
`%` search under a chip, the lines query), `RecipesQuickFiltersViewModelTest` (combining with the
sort, search and each other; the pantry read only while on, matched once per change, nothing shown
while pending; the layout), `RecipesFiltersGridScreenTest` (the chips, the "You have" lines, the
note, the empty line, the grid, the toggle, the bookmark), `SharedPrefsAppPreferencesTest`,
`DatabaseErrorTest`; iOS `RecipesQuickFiltersViewModelTests`, `PreferencesAndSourceTests`,
`DifferentialCorpusTests`, and `RecipesFiltersGridUITests`. Strings `recipes_quick_*`,
`recipes_pantry_have`, `recipes_no_results_filters`, `cd_layout_to_*` in all six languages, drafts
(`docs/translations.md`).

## The top of Recipes, design B (#449, 2026-11-21)

The owner picked variation B, "Search holds the filters", of the top-of-Recipes mockups (private
artifact https://claude.ai/artifact/JZoooH1eVsTEZvVZBeAUsV, source
`~/Downloads/RecipeClipper-designs/recipes-top/`, `recipes-top-B.png`), with one change of their own:
B's checklist icon and gear became **one ⋮ menu**. Behind `recipesTopB` (on by default in debug and
release), and only on **the library as the Recipes tab's first screen** with one cookbook (`libraryFirst`,
#375): a view's own screen, Home and the `oneCookbook`-off pills are untouched. Off, the top is #375's.
Nothing new is filtered, stored or counted: the views, the filters (#378), the sort, the toggle (#380)
and selecting (#377) are the same code, moved.

From the top:

- **Title row:** "Recipes", the accent + (the Add a recipe sheet, unchanged), then **⋮, last on the
  right**. Its menu: **Select** (starts selecting with nothing ticked, as the old Select button did;
  only while there are rows) and **Settings** (what the gear opened). **No gear and no Select button in
  the row** (owner, 2026-11-21: this supersedes #375's "the gear beside the title" and #377's Select
  placement). Android `LibraryTitleRowB` (`libraryMore`, `librarySelect`, `librarySettings`), iOS
  `LibraryTitleRowB` (`library.more`, a `Menu`, the dots turned upright).
- **Today's card, as B draws it:** outlined on the ground (1 dp hairline, radius 18) rather than filled
  (Market, #455, bends this back to a soft filled panel on the surface colour: on white the outline read
  as a form field), the eyebrow in the accent ("TODAY · DINNER"), the name in the display face at 18, and
  under it a row with the next meal on the left ("Snack: **Banana Bread**", the name in ink) and the button
  on the right as an accent pill with a play glyph. Continue cooking puts its step and countdown under the name. **The close button
  of #448 stays** at the top right, with its setting untouched (merged from `today-card-close`, PR #450).
  What the card shows is #402's and #448's rules, unchanged.
- **The views shelf** (*superseded with `recipesFilters` on, #454: no shelf, Lists in the title row, the views in
  the filter sheet; see the next section*)**:** one band of five cells (Lists, Not in a list, Cooked, Typed by you, From a chat),
  each an icon, the count in the display face at 20 and the name (11.5, two lines when it needs them), divided by
  hairlines; **Lists in the accent tone** (`AccentTone` / `Palette.accentTone`; introduced here as the
  paprika tone, `#F6E3DA` with `#8E3219` on it, `#45261C` with `#F4B7A3` on ink; since #455 Market's
  basil tone, `#E1F0E5` with `#1C5232`, `#1B3324` with `#A9E3BC` on ink, also the tab bar's indicator). The cells **navigate, never filter**, to the same screens as the strip's cards. They
  share the width (Lists 1.1 to the others' 1); **when a name's longest word doesn't fit its share**
  (German's "eingegeben", or a large font), every cell takes the width it needs and the shelf
  **scrolls sideways**, so nothing clips (Android: a `Layout` over the cells' minimum intrinsic widths,
  tested in German at twice the text; iOS: each name's widest word measured in its own font at the
  current Dynamic Type size). Above search, where #378 had the chips.
- **Search** with a **filter button at its end** (44 dp, radius 12): plain while no filter is on, the
  accent tone with a **badge counting the filters on** once one is (TalkBack and VoiceOver read "Filter
  recipes, 2 filters on"). It opens **the filter sheet**: "Filter recipes", **Clear**, and the three
  filters as **switches**, each with its line ("Total time, or prep plus cook", "Never marked as
  cooked", "At least half the ingredients, by name"); Not cooked yet is left out inside the Cooked view as
  before. The switches act at once (the list behind updates); there is no "Show N recipes" button,
  which the mockup drew: the owner asked for the switches and Clear only.
- **The filters on, as pills** under search, in the offered order, each removed by a tap on it (its ×;
  "Remove 30 min or less" to a screen reader), then **Clear**, which turns them all off. None on, no
  pills. The pantry filter's note ("Shows recipes where at least half…") follows the pills. The rule
  that they combine with each other, the search and the sort, and are not remembered, is #378's.
- **The count line:** the count, then the sort (its menu unchanged), then the rows / grid toggle, last
  on the right (the old line had the toggle before the sort).
- **A pinned bar down the list** (owner's follow-up, 2026-11-21: Select and Settings were out of reach once the
  top scrolled away): when the large title has scrolled out of sight, a slim bar ("Recipes" small, the +, the
  same ⋮) stays over the list on the ground with a hairline, never while selecting (Android `PinnedTitleBar`
  over the `LazyColumn`/grid's state; iOS the same bar as an overlay, from the title row's frame in the scroll view).
- **Selecting:** the bar shows the count, **Select all** (new: ticks every row in sight, the filters and
  search applied, never one hidden; disabled once all are ticked) and Done, then Add to list (with the
  bookmark) and Delete. On iOS it is pinned above the rows in the page (the navigation bar stays hidden)
  and the tab bar stays, as in the mockup; on Android it is the Scaffold's top bar, as before. While
  selecting the rest of the top (title row, card, shelf, search) is hidden, as the mockup draws it. A long
  press on a row still opens its menu, as before.
- **ViewModel.** `RecipesUiState.activeQuickFilters` (the filters on in the offered order: the pills, the
  switches, the badge), `allSelected`, `onClearQuickFilters` and `onSelectAll` (`RecipeSelection.tickAll`),
  on both platforms. The sheet's open state is the screen's (`rememberSaveable` / `@State`), like the
  other sheets.
- **Tests.** Android `RecipesTopBViewModelTest`, `RecipesTopBScreenTest` (the ⋮ menu, Select all, the
  shelf above search and navigating, the sheet's switches, the badge, a pill's ×, both Clears, the
  card's close, German at twice the text, the flag off); iOS `RecipesTopBViewModelTests` and
  `RecipesTopBUITests` (its own shard weight). The suites written for #375 run with the flag off and
  still pass unchanged.

## Recipes: the views folded into the filter sheet, Lists in the title row (#454, 2026-11-22)

The owner (2026-11-22): "I find the row of list filters redundant to the filter in search. The only thing
I like is that Lists is its own page." They chose the Recipes top of the visual revamp's direction A
(`~/Downloads/RecipeClipper-designs/visual-revamp/visual-revamp.html`, `revamp-filters.png` for the sheet),
with one change of their own: **the Lists button sits left of search**, not right of it as drawn (and, on 2026-11-23, moved into the title row, below). Behind
**`recipesFilters`** (on by default in debug and release, both platforms), over design B: it applies only
where #449 does (`recipesTopB`, the library as the Recipes tab's first screen, one cookbook). Off, the top is
design B exactly. **Supersedes, with the flag on:** #449's views shelf ("The views shelf" above) and #449's
three-switch sheet, and so #345's "a strip of cards, Lists first, then the automatic views" and #378's "Not
cooked yet" as its own filter on that screen. "Views are questions about stored data, never filing" still
holds: they are now asked through the filters.

- **No shelf.** The five-cell band is gone. **Lists** is a compact pill **in the title row** (the owner,
  2026-11-23; first drawn left of search, which left search's placeholder cut on a phone): "Recipes", then the
  pill, then the + and the ⋮, and **in the pinned bar** too, beside its + and ⋮, so Lists stays in reach scrolled.
  The list icon, "Lists" and the number of lists, in the accent tone, a capsule (`Corners.Pill`) 36 dp tall with a
  48 dp target (Android `ListsPill`, tags `recipesListsPill` and `recipesListsPillPinned`; iOS `ListsPill`,
  `recipes.listsPill` and `recipes.listsPill.pinned`; TalkBack and VoiceOver read "Lists, 6"). It opens the
  existing Lists screen; Back returns to Recipes. **It never pushes the + or the ⋮ off-screen or clips the title,
  and is decided by what fits, not by language:** the title keeps its whole word; the pill shows its word and
  count when title, pill and buttons fit side by side, else **only its icon and count** (`compact`, tag suffix
  `-compact`; at 360 dp in English the title row already takes this form, while the pinned bar's smaller title
  keeps the word), and if even that does not fit (very large text) it moves **under the title, right-aligned**.
  Android `TitleRowLayout` (a `SubcomposeLayout`, so only the drawn variant exists for TalkBack; the full width is
  worked out from the words with a text measurer), iOS `TitleRowFit` (`ViewThatFits` of the three). **Search is
  back on its own row at the full width**, and its placeholder, "Search titles and ingredients", is set at 15 sp
  (not the body 16) so it is whole on a 360 dp phone beside the icon and the filter button in English and German;
  French ("Rechercher par titre ou ingrédient") and any language at large text still end in an ellipsis there. The
  pill shows when the shelf did (a recipe or a list to show), and not over an empty library, as before.
- **Search keeps its filter button** with the badge, which now counts the pills below.
- **The filter sheet, grouped by kind of control** (the owner's regrouping, 2026-11-23; it first had two headed
  groups, "The recipe" and "Where it is, where it came from", whose two headings are gone from all six languages):
  - **The switches together, each with its hint line:** **30 min or less** (#378's rule); **Uses what's in my
    pantry** (#378's rule, rows still saying "You have 6 of 8"); **Not in a list** ("Saved to none of your lists
    yet": the view's `EXISTS`). Hairlines between them.
  - **Then, after a gap, the two exclusive choices together:** **Cooked: Any / Cooked / Not yet** (Cooked is the
    Cooked view, Not yet is #378's "Not cooked yet", the two partitioning the library); **Came from: Anywhere /
    Typed by you / From a chat** (a recipe is one or the other, `contentOrigin` MANUAL, never the tour's sample,
    or CHAT). A hairline between them.
  - The two exclusive choices are **segmented**, as the mockup draws them and as the Pantry edit sheet's stock
    choice already is (#194) on both platforms; not radio rows, which are Settings' rule ("exclusive choices
    are radio rows") and would make the sheet twice as tall. The reading view's "no segmented pickers above
    the ingredients" is untouched. Drawn as the mockup's track (the surface-container-high ground, the chosen
    option a raised pill on the ground colour), one custom component per platform (Android `ChoiceSegments`,
    radio buttons for TalkBack; iOS `ChoiceSegments`, buttons with the selected trait). **When an option's
    words don't fit their share on one line** (German, a large font, Dynamic Type) the options **stack**, each
    the full width, so nothing is cut (Android: each option's one-line width against an equal share; iOS:
    `ViewThatFits`). The sheet scrolls.
  - Every control acts at once (the list behind follows), as before; **Clear** stays in the sheet's header,
    enabled while anything is on. No "Show N recipes" button.
- **Pills** under search, in their own fixed order (30 min or less, Cooked or Not cooked yet, Uses what's in my
  pantry, Not in a list, Typed by you or From a chat), each removed by a tap on it (a choice goes back to Any /
  Anywhere), then **Clear**, which clears everything. A pill's words are the view's or the filter's own name.
- **Nothing is re-derived.** The pure model is `RecipeFilterChoices` (core `RecipeQuickFilters.kt`, iOS
  `Data/Model/RecipeQuickFilters.swift`, line for line), beside #378's filters: `views` are the view screens'
  own filters (`RecipeFilter.NOT_IN_A_LIST`, `COOKED`, `TYPED`, `CHAT`), `quick` are #378's
  (`THIRTY_OR_LESS`, `NOT_COOKED`, `USES_PANTRY`), `pills` the order, `without(pill)` a pill's ×. The views are
  answered by **the very SQL the view screens use**: `RecipeRepository.observeHistory(query, views)` maps the
  set onto the existing `observeHistory(query, listed, cooked, typed, chat)` DAO arguments, which that query
  already ANDs (each view's screen is the one-element set). **No DAO or schema change**, `instr(lower(…))` and
  no `LIKE` as before, the search applying inside. Test doubles get a default that intersects the single-view
  answers. #378's rules then narrow that answer in the ViewModel, as the chips did, and the sort orders it.
- **ViewModel** (both platforms): `RecipesUiState.choices`, `filtersOn` (any chip or choice: the empty line
  "No recipes match these filters." and the count line), `pantryOn` (the "You have" lines);
  `onFilterChoicesChange`, `onRemoveFilter(pill)`; `onClearQuickFilters` now clears the choices too. The pantry's
  lines are read only while its filter is on, from either control. The sheet's open state is the screen's.
- **Not remembered**, as #378's filters: Recipes opens with none on; they live in the ViewModel, so they
  survive rotation. No new keys.
- **The view screens and routes stay** (Android `recipes/view/{view}`, iOS `Route.recipesView`): nothing on
  Recipes links to them with the flag on. They are still opened by design B's shelf (`recipesFilters` off),
  by the strip of cards on the old top (`recipesTopB` off) and on the Recipes page pushed over Home
  (`libraryFirst` off). No deep link, widget or notification opens them.
- **Colours, type and radii come from the theme** (Market, #455, merged in): the accent tone (`LocalAccentTone` /
  `Palette.accentTone`), the scheme's containers, the typography's label and body styles, and the radius
  tokens: the Lists pill is `Corners.Pill`, like the filter pills; the segmented track `Field`, its chosen option `Small`.
- **The count line never breaks inside a word** (a fix made here, for every Recipes top: German at large text
  read "Reze / pte"): the count keeps one line, and when it and the sort (with the toggle) don't fit side by
  side, the controls move under it (Android a `FlowRow`, iOS `ViewThatFits`); the sort's label keeps one line
  too. Tests: `RecipesFiltersScreenTest`'s German case (one line, not cut) and
  `RecipesFiltersUITests.testInGermanAtALargeSizeTheCountNeverBreaksInsideAWord`.
- **A tab's name keeps one line too** (the same fix, Android's bar: Market's labels read "Rezept / e" in German at
  twice the text): `TabLabel` keeps the label style while it fits, shrinks step by step down to 9 sp when it
  doesn't, and only then ends in an ellipsis. `AppShellTest.inGermanAtTwiceTheTextEveryTabNameKeepsOneLineWhole`.
  iOS's system tab bar already keeps its labels on one line (at accessibility sizes it shows the large-content viewer).
- **Tests.** `:core` `RecipeFilterChoicesTest` and iOS `RecipeFilterChoicesTests` (each control, combined,
  Any / none, a pill's ×); real SQLite: Android `RecipeQuickFiltersDaoTest.theSheetsViewsTogetherSelect…` and
  iOS `RecipesFiltersDataTests` (one view is its screen; several are the intersection of their screens; the
  `%` search inside); `RecipesFiltersViewModelTest` / `RecipesFiltersViewModelTests` (each control, combining
  with the sort, the badge, a pill's ×, Clear, the one-choice rule, the pantry lines);
  `RecipesFiltersScreenTest` (the pill in the title row and the pinned bar above a full-width search, the placeholder whole at 360 dp, the title row fitting in six languages and at large text, Lists and Back through a real nav host, the switches before the two choices,
  each control narrowing, pills and badge, both Clears, German at twice the text, the flag off) and
  `RecipesFiltersUITests` (its own shard weight). Design B's suites (`RecipesTopBScreenTest`,
  `RecipesTopBUITests`) run with this flag off and pass unchanged.
- Not done: a differential-corpus row for `RecipeFilterChoices` (a mapping of five fields, pinned instead by
  the twin unit tests above).

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

- **Android: ML Kit GenAI's Prompt API** (`com.google.mlkit:genai-prompt:1.0.0-beta5`,
  `Generation.getClient()`, `checkStatus()`, `download()`, `generateContent(generateContentRequest(
  TextPart(…)) { temperature = 0f; topK = 1; maxOutputTokens = 4096 })`). Input under 4,000
  tokens; 4,096 output tokens is the most `GenerateContentRequest` accepts (#128 raised it from
  1,024, which cut off a 20-ingredient recipe copied out in the #105 evaluation). Rewriting, which Chef mode uses, takes under 256 tokens and only rewrites, so it can't
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
  The title is the first `<h1>`, else `og:title`, else `<title>`; the photo is `og:image`. Two
  marks (#271): a list item of up to 600 characters is one line even with a `<div>` or `<p>`
  inside it (BBC Good Food writes `100g <a>butter</a><div> softened</div>`, which had become
  "100g butter" and "softened"), though a list nested in it still breaks; and the section
  headings' lines (`<h2>`–`<h6>`) are kept apart (`PageText.headings`). The model is given the
  window as written; `RecipeTextWindow.marked` adds a colon to each heading (ending in a letter,
  digit or ")") only in the text `PageRecipeCheck` reads, the form the parsers write a group
  heading in ("For the filling:"). Putting the colon in the model's window was tried first: on
  RecipeTin Eats' zucchini tots the 3B stand-in then copied ingredient names only or merged
  "Zucchini:" into the next line (0, 9, 0 and 0 gold lines found over four runs, 2 unreadable
  replies, against 11, 4 and 16 without it).
- **The window** (`RecipeTextWindow`, pure, both platforms): the ingredients heading
  (`headings.json`, every shipped language at once) followed, before the next heading, by the
  most ingredient-looking lines (an amount first, or a short line holding a number), with a
  bonus when the steps heading follows; else the densest run of such lines (three in ten); else
  nothing, and the model isn't asked (a login page or an article costs no model call). Up to 12
  lines before the anchor (a fifth of the budget: the card's title, times and servings), then as
  many as fit, with the page title first if the window doesn't already hold it. A nutrition
  box is left out first (#271, `headings.json`'s `nutrition` and `nutrients`, every shipped
  language at once): its heading ("Nutrition: per serving") and each line that is only a nutrient
  and its figure ("kcal507", "Fat: 10g"), never an ingredient line with a unit ("Salt 1 tsp").
  On BBC Good Food, which has no "Ingredients" heading, the box's short numeric lines were the
  densest run, so the window started there, after the cake's own ingredients.
- **The verifier** (`PageRecipeCheck`, pure, both platforms, pinned by the corpus's `Pick`
  rows): each picked string is looked for in the window's text, both folded the same way (NFKC,
  so "½" is "1/2"; typographic quotes, dashes, "⁄" and "×" as ASCII; lowercase; whitespace runs,
  line breaks included, as one space). A match must not start or end inside a word, nor inside a
  number ("2 cups" in "12 cups", "25 minutes" in "20-25 minutes", "5 hours" in "1.5 hours"); an
  ingredient must start its line, after nothing but a bullet or checkbox, so "2 tbsp" can't be
  lifted out of "1 cup plus 2 tbsp"; a name, ingredient or step must hold a letter. The name is
  required; the recipe still needs ingredients or steps (the parsers' rule), otherwise it's
  `NoRecipeFound` as before. Times go through `Durations.format`, as the parsers' do.
- **One recipe's lines only** (#128, pinned by the corpus's `Verify` rows): a page can show a
  second recipe (a "you'll also love" card), whose lines are on the page too, so the check above
  passes them. The window's lines are cut into cards at each ingredients heading that follows a
  steps heading ("Ingredients … Directions … Ingredients", `headings.json`, every shipped
  language). Each picked ingredient and step counts once in every card it is found in; the card
  holding the most is the recipe's (a tie goes to the earlier), and only lines found in it are
  kept, as its own text. Deliberately narrow, because a dropped line is silent: a second
  "Ingredients for the glaze" heading before the steps is the same recipe; with every pick in
  one card, the usual case, nothing changes. Yield and times are not lines and aren't checked
  this way. The #105 evaluation's Delish "second recipe" turned out to be a scoring artifact,
  and on the #128 run the rule never fired, so it is a guard, not a measured gain. #271 added a
  fixture page in that shape (`shared/fixtures/pages/blog-two-recipe-cards.html`), tested from
  HTML to kept lines on both platforms and read by the harness.
- **Headings and the method's lists** (#271, pinned by the corpus's `Verify` rows): a pick that
  is a whole line ending in a colon in the text it is checked against (a heading, as `marked`
  writes them) is a group heading when picked as an ingredient, shown with its colon (`IngredientHeading`) and
  kept only directly above an ingredient line, so "Method:", "step 1:" or a trailing "Notes:"
  go; picked as a step, it is dropped. An ingredient found only below its card's steps heading
  (after the card's ingredients heading) is dropped: Great British Chefs repeats under each step
  the lines it uses ("75g of Parmesan" of the recipe's "150g"). Where a pick is on the page more
  than once, a whole line wins over the start of a longer one ("Zucchini" is the "Zucchini:"
  heading, not "Zucchini sweating: 30 minutes"). A card title's colon is not the name's.
- **No ingredient-shape check** (#271, measured offline): keeping only picked lines with an
  amount, a unit or a known food word (the aisle and density tables) would drop real lines
  the sites' own JSON-LD lists: "Cooking spray, for pie dish", "Crumbled cannoli shells, for
  topping", "Lemon wedges, for serving", "Salt and pepper, to taste" (no name the tables read).
  The headings, step labels and nutrition it was meant for went with the fixes above, and BBC's
  app promo and "Ad" lines stopped being picked once the window started at the ingredients, so
  it wasn't added.
- **Measured** (#271, `docs/eval/llm-vs-regex.md` section 2c, the 3B stand-in, two runs each
  way on 24 pages): wrong ingredient lines kept 9 and 33 before, 0 and 0 after; wrong steps 8
  and 15 before, 8 and 2 after; gold ingredient lines found within the run-to-run noise (on the
  pages every run answered: 141 and 135 before, 139 and 149 after).
- **Copying, not line numbers** (#128, measured in `docs/eval/llm-vs-regex.md` section 2b):
  asking the model for runs of numbered lines (`{"first": 12, "last": 25}`) instead of copying
  them out keeps the reply short however long the recipe, but on the harness's 3B stand-in it
  showed more than twice the wrong ingredient lines and 50 wrong steps against none: runs stop
  early or end far too late (one took in a page's nutrition facts and reader comments), and
  every such line is on the page, so the check can't catch it. Dropped by the owner's rule
  (ship only if at least as good as one call, wrong lines weighted most); it lives on as the
  harness's `PageLines` arm, to re-measure on the phone models. Two calls (the recipe, then its
  steps) were tried first and dropped too (fewer recipes, more wrong lines).
- **A runaway reply is stopped, then asked once more** (#273, both platforms, pure parts in
  `PageReply`, pinned by the corpus's `Runaway` rows). An unreadable reply is the model padding a
  field (a time growing into prose: "30 minutes mins, 30 minutes mins, …") or repeating one line
  ("1/4 cup (65 ml) water" over and over) until the reply cap; the page then showed no recipe (0
  to 9 of 24 pages a run on the 3B stand-in). Now:
  - **The reply streams** (Android `generateContentStream`, checked every 256 characters through
    `RunawayReply.scan`, a lenient reader of the JSON so far; iOS `streamResponse`, each typed
    snapshot) and stops at the first sign of a runaway (`RunawayReply.isRunaway`): a name over
    200 characters, a yield or time over 300, a line over 2,000, or the same ingredient or step 4
    times running. Generous on purpose, because a stop throws the reply away: the stand-in
    sometimes finishes a time with an explanation ("60 minutes (55-65 minutes based on tester
    method, …)", 105 and 115 characters), a reply that shows today, so a first cut at 100 lost a
    page in the harness; a runaway runs to thousands. A line twice in a recipe (an egg in the cake
    and the glaze) is never four in a row. The finished reply is checked the same way.
  - **One retry** (`PageReplyRetry`): on an unreadable reply only (stopped, cut off, or not
    parsing; iOS: also the context's end or a decoding failure), with `PageReplyRetry.REMINDER`
    after the instructions ("Keep the yield and each time short … Copy each line once, then
    stop.") and a 2,048-token cap, so a deterministic model doesn't loop the same way and a second
    runaway costs half. Never a second retry: a second failure keeps today's outcome (no recipe,
    "Clip it yourself"). Never after a failure that isn't the reply's (Android: any exception, so
    BUSY, the battery quota and background use; iOS: a guardrail, rate limit, unsupported
    language, timeout): those are `PageReply.Failed`. A retry runs only for a page that would
    otherwise show nothing, so its lower cap can't cost a recipe that shows today, and it adds
    latency only on those pages.
  - **Measured** in `docs/eval/llm-vs-regex.md` section 2d (`pages-copy-4096-retry`).
- **Provenance:** `contentOrigin` `EXTRACTED` (no schema change: the column is text). It is the
  source's, like `PARSED`: a re-share fetches and refreshes it, and an edit makes it `EDITED`.
  An older app reads the unknown name as `EDITED`, the safe side. The reading view says, quietly
  under the source credit, "Picked from the page text — check against the source", with Open
  original as usual.
- **Needs a real phone to judge:** whether the models copy text faithfully enough for the
  verifier to keep most lines, how long a page takes, and whether Gemini Nano honours 4,096
  output tokens for a long recipe. CI and the tests run the fake model only; `tools/eval`
  measures a 3B stand-in (`docs/eval/llm-vs-regex.md`).

## A recipe written inside an article (#435, 2026-11-21; follow-up the same day)

Some pages hold a real recipe inside an ordinary article: no schema.org Recipe, no microdata, no
recipe headings (the owner's example, recetas.elperiodico.com: `NewsArticle` JSON-LD, a sentence, a
`<ul>` of six quantity lines, then the method in five paragraphs). It ended in `NoRecipeFound`.
The owner's decision: read it, **only where the page has a list of amounts** followed by method
text, never from prose alone, and never save it unchecked. Behind `articleRecipes` (on).

- **Where it runs:** `ArticleRecipeParser` (pure, `:core`, with a Swift port over `HtmlTree`), on
  every page that loaded with no recipe data (`BlogPageParser.parsePage`, `FetchedPage.articles`),
  so the plain fetch's, the rendered page's and (iOS share extension) Safari's page all carry it.
  The repository offers them last: after the rendered fetch and after the on-device model's pick
  (#103), which, when it finds a recipe, still wins (it imports straight away). Then
  `Error(InArticle(recipes))`, a cause like `NoTranscription`: an outcome, never retried, never
  reloaded on reconnect; "Update from source" on a recipe saved from it reads as `NoRecipeFound`
  (the cook's copy stays). **Past Cloudflare's check** in the visible clip view (#220,
  `importPage`), the page that view already has is read the same way, with no second fetch.
- **What it accepts** (thresholds in the parser, pinned by the corpus's `Article` rows): the
  article is the first `<article>`, else `<main>`, else the body. A list of amounts is a `<ul>` or
  `<ol>` in it, not inside a nav, header, footer, aside or form, nor nested in another list's item,
  with **at least 3 items that read as an amount and more than half of its items that do** (the
  splitter's `isAmount`, in the article's language: `<html lang>` unless the words of its
  paragraphs and items clearly say another, #14; a language with no tables finds nothing). An item
  counts only at 200 characters or fewer and when it isn't just a link (a "more to read" list of
  "10 easy dinners"). Every item of that list is an ingredient, as written, the unamounted ones
  too ("Aceite para freír."). Its steps are the paragraphs, and an `<ol>`'s items, after it in
  document order, through containers, **up to the next heading, whatever it says**, another `<ul>`
  or the end of the article; figures, tables, asides, blockquotes and the like are passed over (an
  image caption is never a step). A paragraph that is a steps header ("Método:", `splitter.json`)
  is passed over; one that is an ingredients or notes header ("Notes:") ends them; at most 40; then
  `SiteRules.steps` as for any page. No step, no recipe.
- **Plain blog pages are not offered** (the owner, reversing the first build): a heading after the
  list ends the steps even when it is a steps header ("Instructions"), so a page laid out as
  "Ingredients" / "Instructions" headings with no recipe data finds nothing here and is left to the
  on-device model and the no-recipe screen's offers, as before (`blog-no-recipe-data.html`).
- **Several recipes in one article:** every list of amounts is read, in page order, each with the
  steps that follow it; an `<ol>` read as steps is never a list of its own. **At most 6.** Each is
  named by the nearest heading above its list in the article, else the page's headline (first
  `<h1>`, else `og:title`, else `<title>`), which each also carries for the chooser; the photo is
  `og:image`. So the El Periódico recipe is named by its section heading ("Cómo convertir una lata
  de mejillones…"), and the editor's note says to change the name to the dish's.
- **Refused:** the site's menus and footers, a list of links, a short bulleted list of tips, prose
  with amounts inside sentences, a list of amounts with nothing after it before the next heading
  (fixtures `shared/fixtures/pages/article-*.html`, read by both platforms' tests, with
  `article-two-recipes.html` for two). The "También te puede interesar" line came before the list
  on the example; after it, a line that isn't a `<p>` is passed over, and one that is would show as
  a step for the cook to delete.
- **What the cook sees** (the owner's reversal: no button, no error screen):
  - **One found:** the share opens the #29 editor directly, titled "Check the recipe", filled in as
    written, with "Taken from the article as written. The name is the page's heading above the
    recipe…". It opens **in the import's place** (Android `popUpTo(IMPORT)`, iOS `replace(1, …)`),
    so Back without saving goes where the share came from (Recipes), never to an import screen that
    would open the editor again. Nothing is saved until Save.
  - **Several found:** a chooser in the import's place: the page's photo and headline, a note
    ("This page writes out more than one recipe. Choose one…"), then one plain row per recipe, its
    name and "Ingredients: N · Steps: M". A row opens the editor on that one; Back returns to the
    chooser. After a save the recipe opens over the chooser (the editor goes), and Back from it
    returns to the chooser, which asks each time it shows which are saved by now
    (`RecipeRepository.savedIds`): a saved row says "Saved: tap to open it" and opens the recipe.
  - **Past Cloudflare's check:** the same, in the clip view's place; none found keeps the clip.
  - Clip it yourself and Report this site stay for pages where nothing is found.
- **Saved as a clip** (`saveClip`: `CLIPPED`, the cleaned link, the language that found the list):
  the closest existing origin, since the cook checked lines taken from the page, as with #37's
  clips and #198's photo read. So it is the user's version: a re-share opens it without fetching,
  "Clipped by you · domain", counted by the one `upsert`.
- **Each recipe's own `sourceUrl`** (no precedent fitted: a Reddit post is one recipe, a meal plan's
  and a version's recipes are `manual:<uuid>` with no page link, and `UrlCleaner` drops a
  `#fragment`): the first recipe on the page is saved under the page's cleaned link, as any clip;
  each later one under the same link plus `pith_recipe=<n>` (n from 2, `ArticleRecipe.link`), a
  query parameter `UrlCleaner` keeps because it is not tracking. A page with one recipe is saved
  exactly as before. Consequences: re-sharing the page's own link opens the first recipe once it is
  saved (the user's version, without fetching), so the chooser is reached again only while the
  first is unsaved; "Open original" for a later one opens the page with the parameter, which sites
  ignore; "Update from source" fetches it the same way (and reads as `NoRecipeFound`).
- **Android** finds the recipes by link: `edit/article?article={url}&index={n}` and
  `article/choose?url={url}`, with `RecipeRepository.articleRecipes(url)` answering from the last
  few the import found (in memory), else, after process death, fetching the page again (if it no
  longer holds them, the editor says so and stays empty to type in). **iOS** carries the
  `ArticleRecipe`s in the routes (`.articleRecipe`, `.articleChoice`), as shared text's reading.
- **iOS share extension:** it can't open the editor, so, like shared text (#306), it leaves all it
  read for the app (`PendingArticle`: `pending_article`, `pending_article_at`, 10 minutes, taken
  once, cleared by any later share) and says "This page writes its recipe inside an article. Open
  Pith to check it before it's saved."; the app, becoming active, opens the editor on the one, or
  the chooser over several, in Recipes. The extension reads the flag from the app's mirror
  (`article_recipes_on`).

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
  can hold several cards; the first that lines up is used. Microdata recipes don't get these parts
  (only a site rule card's headings, #437).
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
  end in a colon or be wholly bold (unless its card opts in with `trustHeading`, #439, below),
  and a card is used only when its items line up one-to-one
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

### The 2026-11-21 recheck's fixes (#436–#440)

Five sites from the home-connection recheck (`docs/site-checkup.md`), each needing more than a
table entry. The owner decided each; `site-rules.json` went to version 3. No new feature flag: the
site-rule mechanisms (#118–#120) never had one, and each new one changes only the site whose rule
names it, or (#436) reads JSON-LD that gave no step at all before.

- **#436, panelinha.com.br: a HowToStep's text in its `itemListElement`.** Every step is
  `{"@type": "HowToStep", "itemListElement": {"@type": "HowToDirection", "text": "Preaqueça o forno a
  180 ºC (temperatura média)."}}`, with no `text` of its own, so the recipe had no steps.
  `JsonLdRecipeParser` (both platforms) now reads a step's `itemListElement`, **only when the step has
  no text of its own** (so no working site changes): one object or an array; each HowToDirection's
  text (else its `name`), or a plain string, joined with a space in order, is the step; a HowToTip
  there is a tip (#432's place for it); nested arrays and numbers are ignored and nothing deeper is
  walked. A HowToTip's own `itemListElement` is never read. With no directions the step falls back to
  its `name`, as before. The ingredient group headings stay lost: they are `<h3>`s outside each `<ul>`
  in a container that also holds the step lists, which no card can pick out (no combinators). Pinned
  by `Parse` rows and the fixtures `shared/fixtures/jsonld/panelinha-*.json` (7 steps on the banana
  cake, 16 over the birthday cake's sections).
- **#437, smittenkitchen.com: cards on a microdata recipe.** Its Jetpack card (no JSON-LD) names its
  groups in `<h5>`s between the `itemprop="recipeIngredient"` items. `BlogPageParser` (iOS
  `BlogRecipeSource.parse`) now runs `SiteRules.ingredients` on a microdata recipe too, and a
  `smittenkitchen.com` card (`.jetpack-recipe-ingredients`, `li`, `h5`) adds "Crispy Onions:",
  "Mushroom Sauce:", "Assembly:". Chosen over teaching `MicrodataRecipeParser` to keep headings because
  it is the same guarded path every JSON-LD page takes: the card must line up one-to-one with the
  microdata's own lines, which come from the same `<li>`s, so a card can only add headings, never
  change or lose a line; the microdata parser itself is untouched; and only a site with a card (or a
  page with a plugin card) changes. Only the cards: step noise and WP Recipe Maker stay JSON-LD's.
  The site check reads a microdata recipe for its rule check too.
- **#439, a card may trust its heading selector.** Giallo Zafferano names its groups in
  `<dt class="gz-title-ingredients">` and CuisineAZ its marinade in `<li class="ingredient_step">`,
  neither a heading element, a colon or bold text, so `CardHeadings` refused them. A card may now say
  `"trustHeading": true`: whatever its own `heading` selector finds is a heading (a colon added, as
  always), still only when the card lines up one-to-one. Without it nothing changed. Cards:
  `ricette.giallozafferano.it` (`.gz-ingredients`, the block around its five `dl`s, since a card's list
  must hold every line; `dd.gz-ingredient`; `dt.gz-title-ingredients`): "Per il riso (per circa 12
  arancini):", "per il ripieno al ragù:" (the site's own case, as written), …; `cuisineaz.com`
  (`section.ingredients`, `li.ingredient_item`, `li.ingredient_step`, `amount: ".ingredient_qte"`, since
  the card writes "Magret de canard 4" where JSON-LD writes "4 Magret de canard"): "Pour la marinade:".
  **kurashiru.com is left out**: its group names sit in `<div class="_8u4gzj9">`, a build hash that
  changes on the site's next deploy, so a card would silently stop matching; its embedded data's
  `{"type":"heading"}` entries need a non-card approach (#439 stays open for it).
- **#438, Giallo Zafferano's bare photo numbers.** Its steps read "lessate il riso … con il sale
  grosso 1 ; …", "Mescolate di tanto in tanto 2 .": numbers pointing at the step photos, which its
  JSON-LD (plain strings) leaves out but its page shows: each step a `div.gz-content-recipe-step`
  holding `<picture data-step="1"><img src=…></picture>` and the text's numbers as
  `<span class="num-step">1</span>`. The text is never rewritten and the numbers never stripped;
  instead a site rule's **`stepPhotos`** (`step`, `photo`, `number`: the photo's attribute, `marker`;
  `PageStepPhotos`, both platforms) gives the numbers something to point at through #433's step
  photos. **Read only when the page proves every number:** as many steps on the page as JSON-LD has,
  each one's letters and digits found in its JSON-LD step, each step's markers exactly its photos'
  numbers in order, those numbers 1, 2, 3… through the recipe, every photo a web image, none twice,
  at most 8 a step, and only when JSON-LD gave no step photos of its own; anything else is no photos.
  Because the numbers run 1, 2, 3… through the recipe, `StepPhotos.numbered`'s fallback (a photo's
  place among the recipe's step photos) is exactly the number in the text, so **`StepPhotos.markers`
  is not extended to bare numbers**: from the text alone "1" in "sale grosso 1 ;" can't be told from
  "1,2 l di acqua" or "lo zafferano 3 e mescolate", and the proof is made once, at parse time, where
  the page's markup says which numbers are markers. Known limit, as for any step photos numbered by
  place: an edit that removes a step before others shifts the later badges off the text's numbers.
  The arancini get 33 photos, three a step, badged 1 to 33.
- **#440, recetas.elperiodico.com: a last step that only points at a video.** "Además, si dispones
  de leche, no te pierdas este VÍDEO receta de galletas de avena crujientes elaboradas con este
  ingrediente:" starts with the recipe's own words, so `stepNoise` (cut from the phrase) would have
  left "Además, si dispones de leche," as the last step. A new kind, **`wholeStepNoise`**: phrases
  (matched in any case, both lowercased) that make the whole last step noise; it is dropped, after
  `stepNoise` has cut, one step at most, and never the only step. `stepNoise` is unchanged. Listed for
  `recetas.elperiodico.com` and `recetasgratis.net` (which redirects there): "no te pierdas este VÍDEO".
  The galletas de avena now end on "Pasado ese tiempo, deja que las galletas de avena se enfríen…".
- Tests: fixtures `shared/fixtures/pages/site-smittenkitchen-green-bean-casserole.html`,
  `site-giallozafferano-arancini.html`, `site-cuisineaz-baeckeoffe.html`,
  `site-recetasgratis-galletas-avena.html` (`SiteRulesTest` / `SiteRulesTests`), `Site` rows for the new
  cards and the whole-step kind, and `SitePh` rows (`SiteRules.stepPhotos` and the badges) in the
  differential corpus. The site check's report names the new rules "whole step noise" and "step
  photos", and fetches each site's page.

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
the groundwork for the backlog social feed (#117). Its `cookedPhotos` flag was retired (#242).

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
    byte for byte. A file that doesn't start with a zip
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
  (bulleted lines since #306, whose other text opens the recipe editor; a recipe beside a link
  too) opens the Groceries tab on the sheet (through `ReceivedListInbox`, in memory). The iOS share extension can't open the app (#19), so it shows the same
  sheet in its card and writes to the App Group database itself; the app catches up when it
  becomes active, like a shared recipe.
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
  then one Add.
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

## Printing a recipe, and a list as a cookbook (#318)

Cooks print recipes for the kitchen and make family cookbooks as gifts. Behind the `print`
flag, on by default.

- **A recipe: "Print or save as PDF"** in the recipe screen's ⋮ menu, after Send as file, with
  the muted line "This recipe as shown, ready for paper" (#260). It opens the system print
  dialog (Android `PrintManager`; iOS `UIPrintInteractionController`), which also saves a PDF
  (Android's "Save as PDF" printer; iOS's share button in the print options).
- **What prints is what the screen shows.** The card is built by `RecipePrint.blocks` (pure,
  `core`, ported to Swift) from the lines the reading view already rendered: scaled, converted,
  group headings as subheadings, nothing re-parsed, the serves and times lines from
  `RecipeShareText.metaLines`. Order: the photo (only if it is already cached: never fetched
  for paper), the title, the site, serves, times, Ingredients, Instructions (numbered), then
  the cook's note under Notes. Ticks print plain (no strike, no boxes). Chef mode's short
  steps and amounts inside steps don't print: the card keeps the steps as written.
- **The note: on the card, not in the cookbook** (a default, the owner's call). A card is for
  the cook's own kitchen, where their "less salt" belongs; a cookbook is for giving, and Send as
  file (#149) leaves the note out for the same reason.
- **A list as a cookbook: "Make a cookbook"** in a list's ⋮ menu (hidden for an empty list),
  line "Every recipe here in one PDF". One PDF: a title page with the list's name, a contents
  page (each recipe and its page number), then each recipe from a new page, pages numbered
  from the contents on. Each recipe renders as the reading view would open it
  (`RecipeRenderer.content`: its chosen servings, the cook's units and temperatures, the
  model's decisions). The file, `<list name>.pdf`, goes to the share sheet as Send as file's
  does (Android: `cacheDir/exports/cookbook/`, through the app's FileProvider).
- **The look:** the theme's type, Bricolage Grotesque over Instrument Sans (since #455; SemiBold for
  headings, Bold for step numbers), in ink, muted and basil on white, whatever the phone's
  theme. US Letter in the US, Canada, Mexico and the Philippines, A4 elsewhere; the print
  dialog fits it to the paper.
- **Where pages break is pure and shared:** the platforms measure each block's lines (a
  photo is one), then `RecipePrint.paginate` places them as pieces: a block that doesn't fit
  starts a page; the title, headings and the lines under the title never end one (they move
  on with the next line, or the first line of a block taller than a page); a block taller than
  a page is split between lines and runs on over the next pages (#331: it used to be cut off
  at the foot of the page). Each drawer clips the content area, so nothing is ever drawn off
  a page. `RecipePrint.startPages` numbers a cookbook's recipes for its contents.
- **Drawing is the view layer's,** off the main thread on both platforms: Android draws with
  `PdfDocument` and `StaticLayout` (`ui/print/PrintPdf`), iOS with `UIGraphicsPDFRenderer`
  and TextKit, so each line is known (`UI/Print/PrintPdf`). The `PrintViewModel` (both)
  holds the job and reads a cookbook's recipes in one query; `PrintEffect` draws it and opens
  the dialog or the share sheet, or says "Couldn't make the PDF." (also when iOS's print
  dialog can't open). On Android a page's photos are loaded before the page opens and
  nothing suspends while one is, so leaving mid-cookbook is a clean cancellation; every page
  is finished before the document closes.
- **Progress and leaving (#331):** while a cookbook is built or drawn the list shows "Making
  the cookbook…". Leaving the screen (not rotating it) drops the job, built or still
  building: the share sheet or print dialog must never pop up unprompted on return, so the
  cook asks again. A failure line stays up for its whole time; the flag is lowered only once
  it has gone.
- **Photos on paper (#331):** a photo prints only from the disk cache (probed without
  decoding), decoded at the band it is drawn in (2 px/pt, 144 dpi) just before its page and
  dropped after it, so a long cookbook holds one page's pictures at a time. The file is
  lossless whatever we do: Android's `PdfDocument` deflates every bitmap drawn on its canvas
  (Skia's JPEG pass-through needs the encoded bytes, which the public API can't hand it), so
  the file-size lever is the band's pixel count, not re-encoding.
- **Tests.** Pure: `RecipePrintTest` / `RecipePrintTests`. ViewModel over fakes:
  `PrintViewModelTest(s)`. Screens: `PrintScreenTest` (Robolectric, both menus, with a printer
  that records the job through `LocalPrinter`, since a JVM test has no PDF engine). The PDF
  itself: `PrintPdfTest` (device) and the PDF cases of `PrintViewModelTests` (pages counted
  with PDFKit).
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
  - **Home:** the Paste a link tile ("Share a recipe link to this app, or tap here to paste
    one."; the link field until #286).
  - **Recipe (reading view):** the units dropdown, explaining "As written" (every amount as
    the recipe gives it; tap for Metric or Ounces; what can't convert exactly stays as written);
    "Start cooking"; "I made this"
    (the button, or the + tile once there are photos).
  - **Cook mode** (its own screen, though it is the recipe screen's): "Done — next step"; the
    next step ("tap any step to make it the current one"); the current step's timer.
  - **Week:** none (its host stays, for a later one). **Groceries:** the first row
    (long-press); "Done shopping" (while anything is ticked). **Pantry:** the first row's
    "Ran out" / "Restock" (#194; it names the long-press's "Running low" too).
  - **Settings:** "Show tips again".
- **No Serves tooltip** (owner, 2026-09-29: "write a tooltip [that] explains the 'As written'
  section; can remove the serving tooltip, it's straightforward"). `recipe_servings` is gone
  from the catalogue, so the units dropdown is the recipe screen's first; a stored
  `tooltip_recipe_servings` key is simply ignored. The units text used to be "Show amounts in
  metric or ounces, in every recipe", which didn't say what "As written", the label a new user
  sees, means. Its bubble is asked to go below the dropdown on both platforms: left to choose,
  iOS put the longer text beside it, over the title. Asking showed that iOS's side was
  inverted: `arrowEdge` is the popover's own edge, so `.bottom` put a bubble above its control
  (cook mode's "below" ones too). Fixed with it; `TooltipsUITests` now checks the side.
- **Only what isn't self-explanatory** (owner, 2026-10-13, #246): ten tooltips went because
  they pointed at self-explanatory controls: `recipe_bookmark`, `recipe_share`,
  `settings_units`, `week_add`, `groceries_add`, `pantry_add`, `home_new_recipe`,
  `groceries_tick`, `cook_ingredients` and `week_month`. Those left explain a hidden gesture, a
  menu or a behaviour. A stored `tooltip_<id>` for a removed one is ignored, like
  `recipe_servings`'s (only the catalogue's keys are read), and costs nothing.
- **Menus explain themselves; the ⋮ tooltips removed** (owner, 2026-10-14, #260: the ⋮ menus
  hold concepts that confuse first-time users). A muted second line sits under each ⋮ option
  that isn't self-explanatory, every time the menu opens: at most about eight words, saying what
  happens. Recipe: Send as file ("A copy someone can open in Recipe Clipper"), Update from
  source ("Replace your edits with the website's latest"). Week: Shop for this week (#384; What I need and Add this week's
  ingredients with `shopForWeek` off), Share as calendar file, Save week as menu, Apply a menu, Meal types. Groceries:
  Send as file, Paste a list, Clear ticked items ("Remove ticked items without restocking the
  pantry"). Pantry: Send list ("What's in stock, as text"), Send as file, Clear run-out items.
  The rest stay one line (Add to plan, Add to groceries, Edit, Delete, Rename, Send list on
  Groceries, Clear the whole list, the sorts, Select items, the list, meal-type and saved-menu
  rows' menus). Android: `MenuItemText` in the `DropdownMenuItem`'s text slot, the line in
  `bodySmall` and `onSurfaceVariant` (muted, `MutedOnInk` in dark mode), wrapping inside the
  menu's own width cap; the item merges its semantics, so TalkBack reads both. iOS: the native
  menu subtitle, from a button label of `Text`, `Text` and, for an icon, `Image` (inside a
  `Label`'s title the second line is dropped); VoiceOver reads "Title, line". With
  the menus saying it themselves, `recipe_menu`, `week_menu`, `groceries_menu` and
  `pantry_menu` went; their stored keys are ignored like #246's.
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
  put their bubble below themselves (`TooltipSide.ABOVE` is kept for a control near the bottom;
  the ingredients bar used it until #246). Android places it itself; iOS 18 and later honour the side asked for, and iOS 17
  picks the side itself.
- **Android's bubble** is a `Popup` of its own, placed from the control's window bounds
  (`Modifier.tooltipAnchor`, a `Modifier.Node` reporting `onGloballyPositioned` to its screen's
  `TooltipHost`), in the theme's inverse colours (ink with ground text and "Got it" in light basil on
  ink; the reverse in dark mode) and Instrument Sans, with an arrow drawn at the control. It is **not
  focusable**, so a tap outside it and TalkBack reach the screen as before (no focus trap), and a
  live region announces it. "Something covers the screen" is the window losing focus (a dialog,
  a sheet, a menu and the share sheet are windows of their own), the keyboard, or the screen's
  own `blocked` (its snackbar). Material 3's `TooltipBox` was the alternative: it wraps each
  control in a box of its own, and its popup is focusable and dismissed by any tap outside,
  which a tooltip that waits for "Got it" doesn't want.
- **iOS's bubble** is SwiftUI's `.popover` kept a popover on iPhone
  (`presentationCompactAdaptation(.popover)`), shown from our state (`.tooltipAnchor` on the
  control, `.tooltipHost` on the screen), in the same inverse colours and Instrument Sans, wider at the
  accessibility text sizes; VoiceOver reads it as it appears, and its escape gesture or a tap
  outside closes it. **Not TipKit**, though it is first-party and has `popoverTip`: TipKit keeps
  its own datastore of which tips were shown and closed, which can only be reset before
  `Tips.configure` (so "Show tips again" would need a relaunch), and it decides eligibility itself,
  asynchronously, so it would be a second source of truth beside the settings keys, the visits,
  the first second and the anchors' visibility, which are ours on both platforms anyway. A popover
  can't show over another presentation, so each screen passes `blocked` for its own sheets,
  dialogs and snackbars, and the host blocks while the keyboard is up. Toolbar items (the recipe's
  menu and the tabs' menus) are anchors too (`inToolbar`: on screen while the
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
  (`TooltipsScreenTest`: Home's first visit shows its tooltip after its first second, Got
  it, and a later visit none; Settings' shows only once scrolled to; the bubble's
  placement at its control), and iOS's recipe screen in `TooltipsUITests` (one per visit, at the
  units dropdown, then "Start cooking" on the next). Every other screen test, UI test and walkthrough
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
  "On list" wasn't understood). The same outlined basil chip, same place (after the name and
  "Low", wrapping under a long name or large text), same meaning and tap, but it wears the tab's
  own icon (`ic_tab_groceries`; iOS `basket`) and its "Groceries" label, so it says where the
  item went. Spoken, it's "On your grocery list", and its action "Remove from grocery list". At
  the largest text sizes (Android font scale 1.5 and up, iOS accessibility sizes) only the basket
  shows. The Pantry tooltip names it: "The basket tag means it's on your grocery list."
- **Select all on both pantry checklists** (#406, `pantrySelectAll`; owner, 2026-11-19: the
  put-away checklist needs one). The first row of Done shopping's sheet (here) and of "Update the
  pantry" (#147, below) is a tri-state checkbox, "Select all" while anything is unticked and "Clear
  all" once everything is ticked: tapping it ticks every row, or, when every row is ticked, unticks
  every row. Checked is everything ticked, indeterminate (iOS: a dash, with the VoiceOver value
  "Some selected") is some, unchecked none; some and none both read "Select all". It is a
  convenience only: it moves the sheet's ticks, held in the ViewModel's sheet state (`PutAwaySheet`,
  `UseUpSheet`), and writes nothing; what Put away or Update writes is unchanged. The row is left out
  when there is a single row to tick. Android uses `TriStateCheckbox`; iOS a checkbox-style row
  (`SelectAllRow`). Off, the sheets are as before. Both sheets read the flag in the view layer.

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
  so a killed app loses it with nothing changed.
- **Select all** (#406, `pantrySelectAll`): the same tri-state row as Done shopping's sheet (#146,
  above) tops this one. It counts only the worked-out rows, the ones with a checkbox: an asked row
  (keep, running low or out) is a choice, not a tick, so it is never moved by it, and the row is
  left out unless two or more rows have a checkbox. It writes nothing; the one confirm still does.

## Reddit posts (#11)

The design is the issue's: one `.json` fetch, the post body if it splits, else the best comment
that splits, else `NoTranscription` with the post's photo. What was decided when it met the
app main had become:

- **Behind a `reddit` flag, on in debug and release** (the owner's rule for flags). Off, a
  Reddit link goes to the blog source, as before #11. The iOS share extension never sees the
  flags, so the app mirrors this one into the App Group suite (`reddit_on`, on until written).
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
  - **"Get the app to keep using Reddit" (#251, 2026-10-13).** On the iOS simulator Reddit then
    covered the lower half of the first post with a bottom sheet of that title, over a full-page
    overlay, and locked scrolling (`body.rpl-scroll-lock`). Its DOM, captured from the live page:
    a `div#configured-xpromo-global_direct_blocking.rpl-bottom-sheet.configured-xpromo-bottom-sheet`
    under `shreddit-app`, whose shadow root holds the overlay and a `role="dialog"`
    `aria-modal="true"` panel, the words slotted in from the light DOM. The reader hides it by
    name (those classes and the `configured-xpromo` id) and, since Reddit renames these, by what
    it says: any dialog, `aria-modal`, bottom-sheet or xpromo element whose text asks for the app
    ("get the app", "keep using Reddit", "open/use the app"), never one holding the post or the
    comments, re-checked as the page changes; the scroll lock's class is overridden too. On the
    emulator the same post showed no sheet with the reader in place (walkthrough 31's run).
- **A Text view for Reddit** (owner, 2026-09-30: built beside the reader, not as its fallback,
  because Reddit's prompts and markup change often and old.reddit.com now wants a login). A Text
  button in the clip's top bar reads the post as it stands (`RCReddit.text()`: the
  `shreddit-post` and the comment tree, from the DOM, so it works under any overlay) and shows its
  title, body and loaded comments a line a row, each with a checkbox (#286; at first a second web
  view with `clipper.js`), so assigning, marks, Review and Save are the same; Page goes back, the
  page kept under it. What counts as the text is decided natively and tested (`RedditPageText`, a
  block a line, a comment's own text without its replies'; `ClipTextRow` makes the rows, its
  labels without a checkbox). **Only the comments Reddit has loaded are there** (deeper
  threads load as the page scrolls), which the view says; the post body and top comments are what matter. A page
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
  the "Low" tag. The shortcuts: the row's menu (touch and hold, until #258 gave touch and hold
  to selecting) offered **both other states**
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

## Selecting several pantry items (#258)

Owner's call (2026-10-14): "for pantry can we have checkboxes to quickly remove items in pantry, I
have to hold press each item and its slow. I want to have checkboxes where user can drag their
finger and clear a lot of it". Both platforms, the same way.

- **Touch and hold now selects** (the Gmail and Photos pattern), with that row checked; the ⋮
  menu's first item, "Select items", starts it with nothing checked, so it can be found.
- **What moved off touch and hold:** it opened a menu of the row's two other stock states
  (#194). Nothing was lost: the same choices are on the edit sheet a tap opens (its stock control,
  applied at once), the swipes (Restock; Ran out, and iOS's Running low) and the row's TalkBack /
  VoiceOver actions, which keep both other states and gain **Select**. The walkthroughs and UI
  tests that used the menu now use the swipes or the sheet.
- **While selecting:** every row leads with a checkbox and a tap toggles it (no sheet); a bar
  replaces the title: ✕, "N selected" (announced as it changes), Select all, then Mark run out and
  Remove (filled, the primary action). Each aisle heading, and Run out, offers its own Select all
  while not all of it is checked. Back (Android) or ✕ leaves; swipes are off; the tooltips wait. A
  search narrows the selection to what it shows, so nothing hidden is removed.
- **Drag to select:** a drag that starts on a checkbox, or a touch and hold that goes on moving,
  checks every row from where it began to where the finger is; going back over rows gives them
  their state back (a range from the anchor over the selection as it was, as in Google Photos),
  and a drag that starts on a checked row unchecks instead. Near the list's top or bottom edge it
  scrolls on its own. A plain drag elsewhere scrolls. The selection, its anchor and the range are
  ViewModel state (`PantrySelection`), so it survives rotation; the view only maps the finger to a
  row: on Android, one `pointerInput` over the `LazyColumn` (Initial pass, so a begun drag takes
  the moves before the list scrolls and the release before the row counts a tap) reading
  `LazyListState.layoutInfo`; on iOS, two UIKit recognisers added to the `List`'s scroll view
  (`PantryDragSelect`): a `UILongPressGestureRecognizer`, which goes on reporting the finger after
  it fires, and a `UIPanGestureRecognizer` that begins only on a checkbox while selecting, which
  the scroll view's own pan waits on (it fails at once anywhere else); the rows' on-screen frames
  are kept in that unobserved class, and it scrolls the scroll view itself at the edges. **Not
  SwiftUI gestures:** a `LongPressGesture` (sequenced before a `DragGesture`, or simultaneous with
  the row's Button) on a `List` row stopped the row's swipe actions, and an exclusive one stopped
  its tap. **Not `EditMode`'s native multi-select:** it can't carry a touch and hold on into a
  drag, and draws UIKit's own selection. The iOS checkbox is always in the row, zero wide while not
  selecting, so a row's structure never changes with its state (#203, #216), and the selection
  bar sits above the `List`, not in it.
- **Remove** deletes every checked item in one transaction (`PantryRepository.delete(ids)`) and
  one Undo snackbar ("12 items removed") puts them all back whole, ids and uids included, like
  "Clear run-out items". **Mark run out** runs out every checked item still in stock, through
  the same rules as a swipe (its name goes on the grocery list unless it's there), with one Undo
  ("2 items marked run out") that puts their stock back from a snapshot; the grocery lines it
  added stay, as they do when an item is restocked. Either ends selecting. Snackbars stay
  undo-only.

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
pantry update (#147). "Mark as cooked" records today's cooking with no photo.

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
  (`PhotoTextSorter.check`, the same rules on both platforms). Found on a real card: Vision
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
- **Glued units** (`PhotoTextSorter.check`, #198) use the language's `gluedUnits`: unit words
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
  expectations, and `Split`, `Photo` and `Check` (`Sus` until #283) rows in the differential corpus. The words are
  drafts: a native speaker should check each table's headers, verbs and step labels, and which
  unit words are safe to flag.

## Scan a recipe: your own photos, read on the device (#226)

The owner's request (2026-09-29): "Read the photo" (#198) reads a Reddit post's pictures; do the
same for any photo the cook takes or picks (a recipe card, a cookbook page, a screenshot) or
shares into the app. Behind the existing `photoText` flag, on both platforms.

- **Entries.** "Scan a recipe" in the Recipes **+** menu (after Type a recipe and Paste a link),
  whose row turns the menu into its two choices (Android; iOS: a submenu): **Take a photo** or
  **Choose from library**. Home has no + menu, so "Scan a recipe" is one of its Add a recipe
  tiles (#286; beside "+ New recipe" before that) and opens the same two choices. Both are #116's
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
- **From a chat** (#355): a recipe read from shared JSON (#350; `SharedText.Reading.fromChat`) is
  saved with `contentOrigin` **`CHAT`**: AI recipes can be wrong (times, food-safety temperatures),
  so the cook sees where one came from. It is `MANUAL` in every rule (a `manual:<uuid>` link, never
  fetched or refreshed, "Update from source" absent, counted by the one `upsert`, never culled,
  imported even on a full library, stays `CHAT` when edited, exported by name), and not `MANUAL` in
  the label and the view. No migration: the column already stores the name, and an older app reads
  the unknown `CHAT` as `EDITED` (the user's version, never overwritten). The reading view says
  "From a chat" quietly under the title, where a site's domain would sit, with no Open original
  (the recipe has no link); the Recipes and list rows say it where the site would be. The Recipes
  strip's **From a chat** view (`RecipeFilter.CHAT`, route `recipes/view/CHAT`, speech-bubble icon)
  sits after Typed by you, always shown like the others; it is disjoint from Typed by you (that
  query stays `contentOrigin = 'MANUAL'`; iOS's `RecipeSummary.isTyped` excludes `isFromChat`). A
  summary row carries `isFromChat` (`contentOrigin = 'CHAT'`). Every query that said
  `contentOrigin != 'MANUAL'` (the two cull queries) or `= 'MANUAL'` (the import's listed test) now
  names both. Text that only looks like a recipe, not JSON, stays `MANUAL`. Three strings
  (`recipes_view_chat`, `_note`, `recipes_empty_chat`) in six languages, on both platforms.
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

## Share text, not just links (#306, 2026-10-30; the review's fixes, #334, 2026-11-06)

A recipe in a note, an email from a relative or a message is shared into the app as text, split
into a recipe and opened in the editor for the cook to check, as "Read the photo" (#198) and a
scan (#226) are. Behind the `shareText` flag, on both platforms.

- **Where shared text goes** (`SharedText.route`, pure, in `:core` with its Swift port, pinned by
  the corpus's `Shared(…)` rows; the owner's rules from the code review, #334), in order:
  0. **A recipe as schema.org JSON** (#350, tried before all of these; see below).
  1. **A recipe:** the text splits (`RecipeTextSplitter`, in the language its words say, #208),
     or is the app's own shared text (below). **It wins over a link in it.** The cook chose this
     text; a link beside a recipe (an email signature, "see also", the highlight link Chrome
     adds to a shared selection) may lead anywhere, and importing it could open a different
     recipe. The link is not kept as the source credit (a recipe of the cook's own has none, and
     "Update from source" would fetch it); it stays in the note (below).
  2. **A link, only when the text is essentially just the link:** the links taken out, at most
     two other lines, each at most 160 characters (a title, a caption: what a browser or an app
     shares). Anything more is a recipe for the editor, even prose with a signature's link.
  3. **A list** (#149's "Add this list"): every non-blank line bulleted ("- ", "• ", …), as the
     app's own "Send list" writes them; an unbulleted line passes as a heading when it is short
     (40 characters or fewer), doesn't end a sentence and a bulleted line follows it somewhere
     after (the title, an aisle). A bulleted line beside prose is a recipe for the editor, so a
     method under a bulleted ingredient list is never dropped.
  4. **Anything else** (prose, unbulleted lines): the editor, as written. Blank text goes
     nowhere, on both platforms. Text past 50,000 characters is never offered to the editor
     (the review keeps it in saved state): a link in it imports, a bulleted one is a list, else
     nothing.
  Line breaks are normalised first: `\r\n`, `\r`, the vertical tab, form feed, NEL, U+2028 and
  U+2029 (Pages, Messages on a Mac and Google Docs share some of these) all become `\n`.
- **What the editor opens with** (`SharedText.read`): split, its ingredients, steps, yield and
  times. Shared text is **plain text, never HTML**: "2 eggs <room temperature>" keeps its
  brackets and "&amp;" stays as written (only whitespace is collapsed). **Nothing the cook shared
  is lost:** the lines a split leaves out (`SplitLines.leftover`: the story before the first
  section, a "Notes:" or "Tip:" block, a link, a second yield or time) open in a **Notes** field
  under the steps, as written, in order, and are saved as the recipe's note (`addManual(notes:)`);
  the review says so ("Lines that didn't fit anywhere are under Notes, as written."). Not split:
  **every non-blank line, trimmed and otherwise as written, as a step**, with "Couldn't find
  ingredients and steps in the text, so it's all under Steps, as written: finish it by hand."
  Steps rather than ingredients (the photo's fallback) because a step is never scaled,
  converted, bought or used up, so an unsorted line can never become a confident wrong number;
  prose reads as steps, and the cook moves the ingredient lines. **No name either way** (but a
  schema.org JSON recipe, below, brings its own): the
  first line might be "Hi Mum," or "Fwd:", so Save points out the rule, as a scan's does
  (the title line of the app's own text sits first in the note). Nothing is corrected, and no
  lines are listed to check: typed text has no recogniser to doubt.
- **The app's own shared text round-trips** (`SharedText.appShared`): what "Share" writes
  (`RecipeShareText`) is read back whichever of the six UI languages the sender's phone wrote
  its words in, so a German phone's "ZUTATEN … ZUBEREITUNG" around an English recipe opens
  sorted, with its serves line ("Portionen: 3 (ursprünglich 6)", kept whole as the yield) and
  its times line ("Vorbereitung 10m · Garzeit 20m"). The words live once per language in
  `shared/tables/<lang>/splitter.json` under `shareText` (`{n}` stands for the number; French
  writes a no-break space before its colon, read as a space since an app in between may swap
  it), pinned to each platform's strings by `ShareLabelsTableTest` (Robolectric, six locales)
  and `ShareLabelsTableTests` (the compiled catalog). The recipe's language is still its own
  words', never the sender's phone's. Tried before the splitter: the two headings on their own
  lines, in order, each with lines under it.
- **schema.org Recipe JSON** (#350): what an AI chat writes when asked for "a schema.org Recipe",
  bare, in a ```` ```json ```` fence or among prose, is tried first (`SharedText.jsonRecipe`). The
  candidates are the text's `{…}` and `[…]`, each cut at its matching bracket (string-aware, at
  most 20 tried, a failed one skipped whole), read by the existing `JsonLdRecipeParser` (never a
  second reader: `@graph`, arrays, HowToSection, the depth guards and the one `Throwable` catch
  all as for a page). The first Recipe wins, so an array of recipes opens its first (a week of
  recipes with days opens the meal-plan review instead, #352, below). It opens sorted with `Reading.name` filling the editor's name, plus its
  ingredients, steps, yield and times; the prose around it is not kept (a chat's "Here you go!"
  is not the cook's note), and **its `url` and image are never used** (an AI may make either up;
  the recipe has no source credit). JSON that doesn't parse, or holds no recipe, falls through
  to the rules above. Saved like any shared text, but as **`CHAT`** (#355), counted by the one `upsert`.
- **Saved as the cook's own** (`addManual` with the text's language, then the checked recipe's
  words, else English), like a scan: `MANUAL`, `manual:<uuid>`, no source credit, the note as
  the review left it. Nothing is saved until Save; Back discards.
- **Android:** `ACTION_SEND` text/plain as before, read as a `CharSequence` (some apps send
  styled text), the text beside shared pictures (`EXTRA_TEXT` on an image share), and
  **`ACTION_PROCESS_TEXT`**: "Recipe Clipper" in any app's text-selection menu. The menu starts
  its target for a result, inside the other app's task, so a small trampoline
  (`ProcessTextActivity`, translucent, excluded from recents) takes it and forwards the text to
  the single-task `MainActivity` in the app's own task; `MainActivity` declares no
  `PROCESS_TEXT` filter itself. `ProcessTextEntry` enables and disables the trampoline
  component with the `shareText` flag (Developer settings included), so with the flag off the
  entry shows nowhere, and the clip page's WebView hides the app's own entry from its selection
  menu (`OwnProcessTextFilter`; Compose's text fields show no such entries). **The hand-off:**
  `MainActivity` reads the text once, off the main thread (`TextIntent.target`), and leaves the
  text with its reading in `SharedTextInbox` (in memory, like `ReceivedListInbox`); the route
  is `edit/text?key=…`, a short key, never the text (past about 117,000 encoded characters the
  route stopped matching and crashed, #334). `EditRecipeViewModel` takes the entry by the key
  and keeps the text in its `SavedStateHandle`, so after process death it reads the text again
  (hence the 50,000-character cap). **Pictures with text:** a recipe in the text wins (the
  editor); anything else leaves the pictures to the scan (`photoText` on) or does nothing.
- **iOS:** the share extension reads the shared text (also beside a link, or beside pictures,
  while the flag is on), **reads it once** (`SharedText.route` in `ShareImportViewModel.start`)
  and **hands off rather than opens** (#19), as a scan does: `PendingText` (`pending_text`,
  `pending_text_at`) holds the reading itself as JSON, and the card says "Open Recipe Clipper to
  check the recipe from this text…"; the app, becoming active within 10 minutes, opens the
  editor in Recipes on that reading, once, and only while the flag is on. Each new share
  replaces or clears what an earlier one left. The flag is mirrored as `share_text_on`.
  Pictures with text as on Android; blank text is ignored.
- **Not built:** shared PDFs (a PDF's text through PDFKit or `PdfRenderer` plus OCR would feed
  the same editor; a follow-up issue). Guessing the name from the app's own text's title line.
- **Tests:** `SharedTextTest`, `TextIntentTest`, `EditRecipeTextViewModelTest`,
  `EditRecipeTextScreenTest`, `EditTextRouteTest` (a 20,000-character Japanese text through the
  real NavHost route), `ProcessTextEntryTest` and `ShareLabelsTableTest` (Android);
  `TextHandOffTests`, `EditRecipeTextTests`, `ShareLabelsTableTests` and `ShareTextUITests`
  (iOS; `RC_UITEST_SHARED_TEXT` writes to the real `PendingText`, since the share sheet can't be
  driven, so the UI test takes the app's own path and fails with the flag off). Sharing from
  Notes, Mail, Messages or a selection, and the trampoline's task behaviour, need a phone.

## Ask an AI: prompts that come back as recipe JSON (#351, #353, 2026-11-19)

People plan meals with an AI chat and share the answer back in (#350). Without guidance the
replies vary; Ask an AI makes them reliable. **No AI runs in the app**: a prompt goes out by Copy
(the clipboard) or the system share sheet, and the answer comes back as shared text. Behind the
`askAi` flag (on by default), on both platforms.

- **Entry points.** Home's fourth tile, **Ask an AI** (full width, under Paste a link / Scan /
  Type), opens a sheet; the recipe screen's overflow menu has **Change this recipe**. Both are
  absent with the flag off.
- **The sheet.** A "What do you want to cook?" field, then three radio rows: *What I describe
  above* (the default; needs words), *What can I make?* and *Plan my week*. A ready prompt turns
  the field into "Anything else? (optional)", added to the prompt as "Also: …". *What can I
  make?* is off while nothing is in stock. **Copy prompt** (reads "Copied" until anything
  changes) and **Share**. Closing the sheet starts it over empty.
- **What a ready prompt carries** (read by the ViewModel from what it follows itself, never
  once): *What can I make?*: the pantry items in stock, those **Use it up** (#305) calls soon
  (expiring today or tomorrow, not a staple) first as "use these up first", then the rest, each
  with its quantity as typed; capped at 60 items. *Plan my week*: the seven days from today as
  `YYYY-MM-DD (Weekday)` lines, the Favorites list's recipe titles (as ideas) and the titles of
  recipes marked cooked, latest first (as "don't repeat"), each capped at 15; a list with
  nothing is left out. Only titles and pantry names leave the app, and only when the cook shares
  or copies.
- **Every prompt asks for schema.org Recipe JSON in a ```json block**, names the fields to use
  (`name`, `description`, `recipeYield` as the servings, ISO 8601 `prepTime` and `cookTime`,
  `recipeIngredient` strings with amounts, `recipeInstructions` as `HowToStep`), follows the
  Settings **units** (As written / Metric / Ounces: one sentence each) and **oven temperature**
  (nothing for As written), and says "write it in" the UI language's own name. The prompt
  itself is in the UI language: its sentences are `ai_prompt_*` strings in the six languages on
  both platforms (drafts for a native speaker, like the rest), read into `AskAiPrompt.Words` by
  the screen, as `RecipeShareText` takes its labels. `AskAiWordsTest` pins the English strings
  to `Words.ENGLISH`.
- **The meal-plan format** (what *Plan my week* asks for, and what #352's import reads): **one
  JSON array in one ```json block, one schema.org Recipe object per listed day, in the order
  listed, each with one extra property, `"day"`, holding that day's date exactly as listed
  (`YYYY-MM-DD`)**. `JsonLdRecipeParser` finds each Recipe in the array as it does any; the day
  is read from the object beside it. (A meal type, `recipeCategory`, is left out: Week files a
  planned recipe under Dinner, which is what the prompt asks for.)
- **Change this recipe.** The prompt is the cook's request, the recipe as schema.org JSON and the
  reply format. **`isBasedOn` is the recipe's `sourceUrl`**: the one id every recipe has, unique
  and never changing (the cleaned link of a clipped page, `manual:<uuid>` for a typed-in one, so
  it survives an edit, an export and an import), and a URL, as schema.org's `isBasedOn` takes.
  The prompt asks for it to be kept exactly; #354 finds the original by it. The JSON is written
  by hand (`AskAiPrompt.recipeJson`: two-space indent, fixed key order, one step per line,
  escaped as JSON, empty fields left out), so both apps print the same bytes; times and the
  yield stay as stored, ingredients as stored (not scaled or converted: the prompt asks for the
  units).
- **Pure and tested.** `AskAiPrompt` (`:core`, `data/model`; iOS `Data/Model/AskAiPrompt.swift`)
  builds every prompt; `AskAiPromptTest` and `AskAiPromptTests` hold the same expectations word
  for word. The ViewModels (`AskAiViewModel`, both platforms) are tested over fakes; Home's tile
  and the sheet over fakes on Android (`AskAiSheetTest`, `ChangeRecipeMenuTest`), and
  `AskAiWiringTest` checks every recipe route passes the ViewModel, as `PrintWiringTest` does.
- **Not here:** reading the answer is #350's (one recipe) and #352's (a week, next section);
  saving a version linked to its original is "Recipe versions" (#354, after it).

## Meal-plan import: a week of AI recipes into Week (#352, 2026-11-19)

The answer to Ask an AI's **Plan my week** (#353) is one JSON array of schema.org Recipes, each
with `"day": "YYYY-MM-DD"`. Shared in, it opens a **review** instead of the single-recipe editor.
Behind the `mealPlanImport` flag (on by default; off, the first recipe opens the editor as in
#350), on both platforms.

- **A cut-off answer is the case that matters most** (found on a real phone: seven recipes asked
  for, one saved). A long answer often stops mid-recipe: the ```json array never closes, so the
  old reader (the first closed value only) found no plan and the editor kept one recipe.
  `MealPlanImport.read` now steps into an unclosed bracket and takes **every whole recipe before
  the unfinished one** (the unfinished object is never a value, and the HowToStep objects inside a
  recipe are not recipes); `Plan.cutOff` is true when a JSON-looking value (`{`/`[` followed by
  `{`, `[` or `"`) never closed. The review then shows a muted note ("The answer was cut off, so
  only these recipes came through. Ask the AI to continue for the rest.") and **"Copy a message
  for the AI"**: `CutOffMessage` (`:core`; iOS the same) builds "Your answer was cut off. Please
  send the rest of the plan: one recipe for each of these days: <YYYY-MM-DD, ...>. Use the same
  JSON format as before, in one ```json code block." in the UI language. The days are **derived**:
  the review knows only the text, so the week is the block of `PlanDays.BLOCK_DAYS` days from the
  earliest day read, and the days of it with no whole recipe are named; with no readable day, or
  nothing missing in the block, it asks for "the remaining days". The view layer copies it
  (clipboard / `UIPasteboard`) and the note becomes "Copied. Paste it into the chat, then share
  or paste the reply here." (no snackbar). The reply (prose and a fence first, a plan of the
  missing days) reads as a normal plan; **one** missing day makes a single recipe, which opens the
  editor as any single recipe does.
- **Cut off with no whole recipe** (the text ends inside the first recipe, or inside the one recipe
  asked for): no editor on a fragment. `SharedText.isCutOff` (an unclosed JSON-looking value with
  `"@type": "Recipe"` after it, asked only of text that read as no recipe) gives
  `ParseError.CutOff`; the screen picks the words ("That answer was cut off before the recipe
  ended. Ask the AI to send it again, or to continue.") and offers the same copy action with
  "Your answer was cut off. Please send the whole recipe again as schema.org Recipe JSON in one
  ```json code block." Shared and pasted text take the same road (#372): Android
  `TextIntent.Target.Failed` -> `PasteTarget.CutOff` (`PastedTextRouter`) -> the `cutoff` route
  (`CutOffScreen`) from `MainActivity` and `openPasted`; iOS `PasteTarget.cutOff` -> `Route.cutOff`
  (`CutOffScreen`) from a paste, and the share card's `.failed(.cutOff)` from the extension (no
  Try again, the text fails the same way). Paste a link and Paste the answer close as for any
  other target. One whole
  recipe followed by a cut-off one stays the editor's (a single recipe is never a plan).
- **What counts as a plan** (`MealPlanImport`, `:core`, `data/remote`; iOS `MealPlanImport.swift`):
  the recipes of **every whole JSON value in the text** (candidates cut by their matching bracket,
  as #350; up to 100 tried, so seven code blocks with prose and braces between work), gathered in
  order through `JsonLdRecipeParser.parseEach` with its `deep` option, **at least two**, and
  either **a `day` on at least one** or, with no day anywhere, **all of them in one value** (an
  array, a wrapper, or one that was cut off) **and none naming `isBasedOn`** (a changed recipe,
  #354, is not a week). The shapes: one array (the prompt's), several separate values (a chat's
  code blocks, one recipe each), `@graph` / ItemList, and for `deep` a wrapper object walked
  through every array and object under any key, in sorted key order (`{"mealPlan": [...]}`,
  `{"days": [{"day": ..., "recipe": {...}}]}`): a wrapper's `day`, or its `date` when it reads as
  `YYYY-MM-DD`, or a key that is such a date, is carried to the object directly under it (not
  through an array), a recipe's own `date` stands for a missing `day`, and an object with
  `recipeIngredient`/`recipeInstructions` counts as a recipe without `@type`. **Page parsing is
  unchanged** (`deep` is off for every other caller). Recipes with no day in separate blocks, one
  recipe, and a changed recipe open the editor as before. **No day on any recipe** is a plan with
  "No day" rows (nothing is planned until the cook picks a day; the review already does that), the
  rule chosen so a list of ideas in one array is not cut to its first recipe, while loose blocks
  stay the editor's. At most 31 are kept. A recipe's `url` and image are never used, as for one
  recipe. A `day` is a real calendar date `YYYY-MM-DD` (a time after it is ignored): anything else
  ("Monday", "2026-02-30") is no day.
- **The review** (`mealplan?key=`, `MealPlanReviewViewModel`; iOS the same name, route
  `.mealPlan(text)`): a recipe per row, **all ticked**, its name, its first three ingredients
  and a day button (a menu of today and the next 13 days, the answer's own later day, and *Don't
  plan*). **Nothing is saved before Save.** Save runs each ticked recipe through the one
  `addManual` (CHAT, `manual:<uuid>`, #355, so `LibraryLimit` and the one `upsert` decide) and
  plans it on its day under **Dinner** (the recipe's own servings). Groceries follow as today, from
  the planned week ("What I need"). When every ticked recipe is saved the review is replaced by
  **Week** (or just closes when none was planned); Back discards.
- **A day that has passed, or none/unreadable**, is shown as "No day" with a muted line for a
  passed one, and the recipe is saved **unplanned** unless the cook picks a day. Chosen, kept
  simple: no guessing a weekday, no shifting a week.
- **The free limit** (#107, #345): the review follows the limit and the library count and says
  "Your library has room for N more" with **Unlock** when fewer than the ticked ones fit; the first
  ticked ones that fit are saved (a recipe that doesn't is marked "No room for this one yet" and
  stays in the list), **nothing old is ever removed**. Unlocking makes room at once; a recipe the
  `upsert` doesn't keep after all (id 0) stays listed too. A failed save leaves its recipe listed
  with the usual error.
- **Android hand-off:** `MainActivity` reads the text once (`TextIntent.target(..., mealPlanOn)`
  -> `Target.Plan`), leaves the plan in `SharedTextInbox` (`offerPlan`/`takePlan`, in memory) under
  a key; the review keeps the text in saved state and reads it again after process death (every
  recipe ticked again). **iOS:** the share extension (`ShareImportViewModel.leavesText`) leaves the
  **text** in `PendingPlan` (`pending_plan`, 10 minutes, flag mirrored as `meal_plan_on`; the card
  says to open the app) and the app reads it again; a new share clears an earlier plan.
- **No migration:** nothing but existing tables. Six strings (`mealplan_*`) in six languages on
  both platforms, plus iOS's `share_plan_in_app`.
- **Tests:** `MealPlanImportTest`/`MealPlanImportTests` and the corpus's `PlanText(...)` rows
  (core + iOS port), `MealPlanReviewViewModelTest`/`MealPlanReviewTests` over fakes,
  `MealPlanReviewScreenTest` (Android, through a NavHost route), `TextIntentTest`, `PlanHandOffTests`
  (iOS). The share sheet/extension on a real phone, and a real AI's answer, need a device.

## Recipe versions: an AI's change saved linked to its original (#354, 2026-11-19)

"Change this recipe" (#353) sends the recipe as schema.org JSON with `isBasedOn` set to its
`sourceUrl`; the chat's answer comes back as shared JSON (#350), read as from a chat (#355). When
that JSON's `isBasedOn` names a recipe in the library, the new recipe is saved as a **version** of
it. Behind the `recipeVersions` flag (on by default in debug and release), on both platforms.

- **The link is a column, by uid.** `recipes.basedOnUid`, nullable (Room 16 to 17, `MIGRATION_16_17`,
  `MigrationTest`; iOS `user_version` 15 to 16, `addBasedOn`): the `uid` of the original, not its
  row id, so it survives an export and an import (the same reason a list membership uses uids).
  No foreign key: every delete clears it by hand (below), so a link never points at nothing.
  `update` and a re-share never rewrite it (`keepingUserState` keeps it on Android; iOS's
  `update` doesn't write it, as it doesn't write `uid`), so editing a version, editing or
  refreshing its original, and re-sharing either all leave the link alone.
- **Finding the original.** `SharedText.Reading.basedOn` is the JSON's `isBasedOn` as written
  (`JsonLdRecipeParser.isBasedOn`: a string, or an object's `@id` or `url`, or the first of an
  array; trimmed; blank is none). It is only a claim: `addManual(..., basedOn:)` looks it up when
  the editor saves. By `sourceUrl` as written (a typed or chat recipe's `manual:<uuid>`, a clipped
  page's cleaned link), else, for an `http` link, cleaned by `UrlCleaner` (an AI may keep or drop
  tracking). Only with `fromChat` and the flag on. **An `isBasedOn` that matches nothing is ignored**:
  a plain chat recipe. The editor still opens to check the recipe first, as for any shared JSON;
  nothing about the link is shown there.
- **A version of a version.** Changing a version names its own `manual:` link, so the answer links
  to that version, not to the first original. The original lists only its direct versions; each
  version lists its own.
- **Seen on the recipe.** A version says **Based on <original title>** quietly under its title (the
  muted line "From a chat" sits in; a tap opens the original, pushed on the back stack). The
  original lists **Versions** after its steps (before the note), newest first, one tappable line
  each. Both follow the database as it changes (`RecipeRepository.observeBasedOn` and
  `observeVersions`, over a join on `uid`), only for a kept recipe, and are absent with the flag
  off. Three strings (`recipe_based_on`, `recipe_versions_heading`, `cd_open_recipe_named`) in six
  languages on both platforms (drafts for a native speaker). The Week tab opens a linked recipe
  in its own tab (`weekRecipe`), the others push `recipe/{id}`.
- **Deleting.** Deleting the original keeps its versions and clears their link (`clearOrphanedLinks`:
  `UPDATE ... SET basedOnUid = NULL WHERE basedOnUid NOT IN (SELECT uid FROM recipes)`, run after a
  delete, the free tier's swap-out and the old clean-up's cull, in the same transaction). An undo
  links them again: the delete reads their ids (`versionIdsOf`) into `DeletedRecipe.versionIds`,
  and a restore relinks those rows, then clears any link whose original is gone meanwhile.
  Deleting a version leaves the original alone.
- **The free limit.** A version is saved through the one `upsert`, so it counts like any recipe and
  at the limit is shown, not saved ("Not saved"), the editor staying open as for any shared JSON.
- **Export and import.** `BackupRecipe.basedOn` (the original's uid; `"basedOn"` in the file, absent
  in an older file, which reads as none and an older app ignores). `BackupMerger` keeps a new
  recipe's link only if its original is here after the import, under the uid it has then: the
  file's own (a new recipe, written), this phone's (a recipe matched by its link), or an existing
  uid the file doesn't carry; a link to a recipe left out by the limit or missing is dropped, and
  never to itself. A recipe already here keeps what it has: import never changes a link. Shared
  files (#149) carry it too, and drop it on a phone without the original.
- **Tests.** `RecipeVersionsRepositoryTest`, `RecipeDaoTest` (versions) and `MigrationTest`
  (16 to 17) on Android; `RecipeVersionsTests` and `RecipeVersionsViewModelTests` on iOS; the shared
  fixture `backup-v1-versions.json` read by `BackupJsonTest` and `BackupMergerTest` and their iOS
  twins; `SharedTextTest`, `SharedTextJsonTests`, and the corpus's `Based("text")` rows (generated
  from the Kotlin) for `isBasedOn`; the editor's `addManual` claim in `EditRecipeTextViewModelTest`
  and `EditRecipeTextTests`; the recipe screen in `RecipeVersionsTest`.

## Week menu: one "Shop for this week" (#384, 2026-11-19)

The owner found the Week menu's "What I need" and "Add this week's ingredients" the same: both
show the week's lines (`GrocerySources.fromPlan`, at the planned servings); What I need also splits
them into To buy and In your pantry (#302's amounts beside the week's), and its "Add to groceries"
adds only To buy, once. Behind the `shopForWeek` flag (on by default; off, the old pair returns
exactly), on both platforms.

- **One item**, "Shop for this week" (`action_shop_for_week`, muted line `menu_desc_shop_for_week`:
  "What the week needs and what you have"), opens What I need for the week shown (Android
  `week/need/{weekStart}`, iOS `.whatINeed`). "Add this week's ingredients" is not in the menu.
  Both are still week-only: the month view has neither. With an empty pantry every line is under
  To buy, so nothing is lost; sending a list stays Groceries' "Send list".
- **The screen is unchanged but for its title** (owner, 2026-11-19): with the flag on it reads
  "Shop for this week", as the menu item that opens it (the same string); off, it stays "What I
  need". The sections (To buy, In your pantry) keep their names. `WhatINeedScreen` takes
  `shopForWeek` as `WeekScreen` does. The old strings stay (flag off).
- **No other entry** pointed at either item: the tour's tooltips, empty states and widgets don't
  mention them. The grocery sheet itself (`AddToGroceriesSheet`) stays for the recipe's "Add to
  groceries"; only the Week's way into it is gone with the flag on.
- **Code.** Android `WeekScreen` takes `shopForWeek` (default `LocalFlagValues`); iOS `WeekScreen`
  takes it from `RootView`, and `WeekShoppingMenu.items` chooses the items.
- **Tests.** `WeekScreenTest` (Android: one item, opens the week shown, the old pair with the flag
  off, none in the month view); `WeekShoppingMenuTests` and `WeekUITests` (iOS); the walkthroughs
  tap "Shop for this week".

## Import from Paprika (#382, 2026-11-19)

The owner asked for a one-time import from another recipe app, starting with Paprika's export
(`.paprikarecipes`). Settings, Your recipes gains **Import from Paprika** beside Export and Import
(the same status line under them: progress, then a summary or the cause), behind the `paprikaImport`
flag (on by default in debug and release), on both platforms. Other apps' formats are separate issues.

- **The format** (field names from the open-source libpaprika crate's `Recipe`, not from an export of
  ours: see "Not verified"): a zip with one `<name>.paprikarecipe` entry per recipe, each a
  gzip-compressed JSON object: `name`, `ingredients` and `directions` as newline-separated text,
  `notes`, `servings`, `prep_time`, `cook_time`, `total_time`, `source`, `source_url`, `image_url`,
  `categories` (names), `photo_data` (base64 JPEG), `uid`, `created`, and more that are ignored. A
  missing, null or unknown field is simply absent; a number where text goes keeps its digits.
- **Reading.** Android: `PaprikaArchive` and `PaprikaRecipeParser` in `:core` (`java.util.zip`'s
  `ZipFile` over a copy of the picked file in the cache, which is deleted after; `GZIPInputStream`;
  `org.json`). iOS: `PaprikaZipReader` (Apple's Compression framework, which reads the raw DEFLATE
  both a zip entry and a gzip body hold, over the memory-mapped file; the zip structure and the gzip
  header and checksum are read by hand, as `BackupArchive` does, so there is still no third-party
  dependency) and `PaprikaRecipeParser` (Foundation only, so `CoreImportsTests` covers it). The
  reader is outside the pure files on iOS only because of Compression.
- **A stranger's file is not believed** (`PaprikaLimits`, the same numbers on both platforms): at most
  10 000 entries, 32 MiB unpacked per entry, 2 GiB unpacked in all, a 2 GiB file. An entry's declared size
  over the cap is refused without unpacking; the real size is counted as it unpacks and stops at the
  cap (a 700-byte fixture that unpacks to 40 MB is dropped as one unreadable entry); the entry count
  and the total are refused for the whole file (`TOO_LARGE`, nothing imported). An entry's name is only
  compared (`isRecipeEntry`: a file called `*.paprikarecipe`, not hidden, not under `__MACOSX/`), never
  used as a path; nothing is written to disk but Android's cache copy of the file itself.
- **What is counted.** Entries that aren't recipes (folders, macOS copies, a readme) are ignored, not
  counted. A JSON object with no name, or with neither ingredients nor steps (the parsers' rule), is
  **not a recipe**: skipped and counted. An entry that isn't gzip, isn't UTF-8, isn't JSON, isn't an
  object, is nested too deep, or is over a cap is **unreadable**: counted, and the rest still import.
  A file that isn't a zip, or is a zip with no recipe entry, is `NOT_A_PAPRIKA_FILE`; one cut short or with
  a broken directory is `DAMAGED`; both import nothing. `PaprikaError` is a cause, the screen picks the words.
- **Never invent, text as written.** Names, lines, notes and servings are Paprika's, trimmed; lines
  are split on newlines, blanks dropped (as the editor's `RecipeDraft.lines`). Times go through
  `Durations.format` with the words of the recipe's language (detected from its own name and
  ingredients as for any recipe with none declared, never the phone's): a whole-string phrase
  ("1 hour 30 minutes") renders like ISO ("1h 30m"), a zero total is absent, anything else
  ("Overnight") stays as written. `source_url` is kept only if it is a real web link (`WebImageUrl`),
  cleaned by `UrlCleaner`; `image_url` only as a web picture link.
- **Identity: how a second import adds nothing.** The key is the recipe's cleaned `source_url`; else
  `manual:paprika-<uid>` (Paprika's id lower-cased, only if it is plain `[A-Za-z0-9-]{1,64}`, so an id
  can't smuggle a link or a path into the key); else `manual:paprika-h<hash>`, an FNV-1a (64 bits) of
  the name, ingredients and steps, for a file with no ids (pinned in both platforms' tests). The key
  is the recipe's `sourceUrl`, so a recipe that is already here by that key, shared, clipped or
  imported before, is **already here**: it keeps its content, ticks and last view, and only gains
  Paprika's note if it has none (`BackupMerger`, import "merges, never replaces or deletes"). A
  recipe twice in one file counts as already here too, the first winning.
- **Never re-fetched.** A recipe with a link is `EDITED` (with `editedAt` the import's time), the
  cook's version: a re-share of its link opens it as imported without fetching, and "Update from
  source" is the way to the site's. One with no link is `MANUAL` with its `manual:` key: it appears
  under Typed by you, which is what Paprika's typed-in recipes are, and the old clean-up (with
  `oneCookbook` off) never removes it. Both are exactly the values a backup keeps (#29).
- **The one limit.** The recipes become a `Backup` (with the lists and memberships of #387 when the
  switch is on) and go through `BackupDao.importBackup`, the route every import takes (`BackupMerger`, `capsEveryRecipe`: #345), in one
  transaction: at the free limit what fits comes in (in the file's order: the first recipe is the most
  recently viewed, a millisecond apart, so Recipes keeps the file's order, listed ones first as for any
  import), the rest are counted in `skippedForLimit` with "the free app keeps 50 recipes" (the existing
  strings), and **nothing old is removed**. Unlimited, every recipe comes in. A database failure writes
  nothing (`SAVE_FAILED`). The result is `PaprikaSummary`: imported, already here, skipped for the limit,
  not recipes, unreadable, pictures left out (#386) and lists made (#387).
- **Photos come through** (#386, the owner's decision after the first version left them out): each
  recipe's `photo_data` (else the first of `photos`' `data`) is the recipe's own stored photo, see "A
  recipe's own photo" below. The archive reader hands each picture to the photo store as it reads
  the recipe (so none waits in memory for the rest of the file); a picture over 8 MiB decoded, not
  base64, or not an image is **left out and counted** (`photosLeftOut`), never the recipe. A recipe
  already here with no photo of its own gains the file's; one with a photo keeps it. Pictures no
  recipe ends up with (already here, left out by the limit, a failed write) are deleted again. The
  file name is the store's (a fresh uuid), never the entry's. `image_url` is still kept as the recipe's
  web link, under the stored photo.
- **Categories become lists** (#387, the owner's decision; "Lists are deliberate" is kept by this
  being the cook's choice): a switch under the Import from Paprika row, **"Put recipes in lists by their
  Paprika categories"**, on by default, this visit's choice (not a saved setting). With it on, each
  category puts the recipe in the list of that name: one already here with the same name, trimmed
  and case-insensitive (the seeded Dinner is found by "dinner"), else a new list; categories that
  differ only in case are one list, the first spelling kept; blank ones are none. Paprika's own
  `on_favorites` flag (a boolean or a number that isn't 0) files the recipe in Favorites, found by
  `isFavorites`, never by name: a category called "Favorites" is a list of its own. A recipe already
  here gains the lists too; adding is insert-or-ignore, so an `addedAt` is never rewritten and nothing
  is taken out of a list. A recipe the free limit leaves out is filed nowhere, and a new list none of the
  recipes that came in belong to is not made (`BackupMerger`'s `dropEmptyNewLists`, which a backup
  import leaves off). The summary says how many lists were made. A repeat of a recipe in the same file
  adds its categories to the first's. `source`, `description`, `rating`, `difficulty`,
  `nutritional_info`, `scale` and `created` are not kept.
- **Strings.** 15 in six languages on both platforms (`paprika_*`; drafts for a native speaker,
  `docs/translations.md`); the already-here and free-limit sentences are the backup's.
- **Tests.** The shared fixtures `shared/fixtures/paprika/*.paprikarecipes` (made by
  `tools/paprika-fixtures/make_fixtures.py`: valid, truncated, bad entries, a declared 4 GB entry, a
  small file that unpacks to 40 MB, one with pictures that are good, not images, not base64 and too big) are read by `PaprikaArchiveTest` and `PaprikaZipReaderTests` with the
  same counts, the parser by `PaprikaRecipeParserTest`/`PaprikaRecipeParserTests`, the mapping by
  `PaprikaImportTest`/`PaprikaImportTests`, the repository (identity, a second import, a recipe already
  here, the free limit, failures) by `DefaultPaprikaImportRepositoryTest` over an in-memory DAO and
  `PaprikaImportRepositoryTests` over in-memory SQLite, and the Settings state and words by
  `SettingsPaprikaImportViewModelTest`, `SettingsPaprikaImportScreenTest` and `SettingsPaprikaImportTests`;
  lists and photos in the repository tests above. Nothing here is under the differential corpus's scope.
- **Not verified.** A real Paprika export (the fixtures follow the format as libpaprika documents it, not
  a file Paprika made; the fixtures' pictures are not images, so a real decoder only meets a real picture
  in the by-hand run), the system file picker choosing a `.paprikarecipes` file from Drive or iCloud on a
  phone, and a very large export (hundreds of MB) on a low-memory phone.

## Import from Recipe Keeper (#390, 2026-11-19)

The owner asked for Recipe Keeper next (Mealie and AnyList are separate issues). Settings, Your recipes
gains **Import from Recipe Keeper** under Import from Paprika, behind the `recipeKeeperImport` flag (on
by default in debug and release), on both platforms. It is **the Paprika import's route with another
reader**: the same repository, `PaprikaImport.toBackup`, `BackupDao.importBackup`, `BackupMerger`, the one
`LibraryLimit`, the stored photo (#386), the categories-to-lists switch (#387) and the summary, so every
rule in "Import from Paprika" holds here (merge, never replace or delete; at the free limit what fits
comes in and the rest is counted; the user's version is never refetched; a cancelled or failed write
leaves nothing). What follows is only what differs.

- **What was generalised, lightly.** `ImportSource` (`paprika`, `recipeKeeper`) says which reader reads the
  file and the prefix of a recipe's own key; `PaprikaImportRepository.import(uri, filing, source)` and
  `PaprikaFiles.read(uri, source)` take it (iOS: `importPaprika(_:filing:source:)`, `read(_:source:)`, each with a
  Paprika default so #383's callers did not change). The Recipe Keeper reader hands back the same
  `PaprikaRecipe`/`PaprikaContents`/`PaprikaRead` (so `PaprikaSummary` too), and `PaprikaError` gained
  `NOT_A_RECIPE_KEEPER_FILE`. Names still say Paprika where #383 put them: renaming was left out to keep this
  diff reviewable. In Settings the **one switch** ("Put recipes in lists by their categories", its title no
  longer says Paprika) serves both rows and shows when either is on; it is this visit's choice for both.
- **The format** (no published spec: from the open-source importers RecipeSage's and Mealie's, Norish's
  notes on a real 488-recipe export, and a sample page one of them tests with; see "Not verified"). The
  export is `RecipeKeeper_YYYYMMDD_HHMMSS.zip`: `recipes.html`, at the root or inside one wrapper folder, and
  `images/<recipeId>_<N>.jpg`. Each recipe is a `div.recipe-details`, read **by `itemprop` only**, because the
  visible labels are translated ("Portate", "Porzioni") and the itemprops are not. The HTML is not
  strict (`<meta>` never closed, attributes unquoted, `itemprop=photo0/` whose value is "photo0/"), so
  it is read leniently: Jsoup on Android, `HtmlTree` (the microdata parser's Jsoup stand-in) on iOS. A value
  is a `<meta>`'s `content`, else the element's text (the first `recipeCourse` is a `<span>`, any more are `<meta>`).
- **Fields kept** (`RecipeKeeperParser`, in `:core`; iOS's twin is Foundation only and sits under
  `CoreImportsTests`): `name`; `recipeIngredients` (one `<p>` per line, blanks dropped; a line whose whole text is
  bold is a heading, stored as a line **ending in a colon** as `IngredientHeading` reads them, a colon added if it
  has none; a bold word inside a line is no heading); `recipeDirections` (one `<p>` per step, blanks dropped, a
  leading `1. ` to `999. ` and its space stripped and nothing else: "2 minutes before…" and "2.5 cups" stay);
  `recipeYield` as written ("Serves 6-8", "4 persone": no number invented, so a recipe with no leading number
  has no stepper, as for any); `prepTime` and `cookTime` as the ISO text Recipe Keeper writes, through the
  existing time rules (`PT0S` is none, `PT1H` is "1h"; no total time is made up: the old importers' prep plus
  cook is a number the cook did not see); `recipeNotes` (its `<p>`s joined with newlines); `recipeSource`
  **only when it is an http(s) link** (Recipe Keeper writes "Recipe Keeper" or "n/a" for a typed-in recipe), cleaned
  by `UrlCleaner`; `recipeIsFavourite` `True`; the photos. Not kept: nutrition, rating, share id, serving size
  (the app has no field for them, and nothing is guessed).
- **Lists** (the switch on): `recipeCourse`, `recipeCategory` and `recipeCollection` values are all list names, in
  that order, then the #387 rules as for Paprika's categories (found by trimmed, case-insensitive name,
  else made; a recipe the limit leaves out is filed nowhere). `recipeIsFavourite` files it in Favorites by
  `isFavorites`, never by name. A course is a list like a category (the owner's wording, "by the switch"); a
  cook who keeps seeded lists will find "Dessert" beside "Desserts", which is the cook's to merge.
- **Identity: how a second import adds nothing.** The cleaned source link; else `manual:recipekeeper-<recipeId>`
  (lower-cased, only if plain `[A-Za-z0-9-]{1,64}`); else `manual:recipekeeper-h<hash>` of name and text, the
  same FNV-1a as Paprika's. The prefix keeps a Paprika recipe and a Recipe Keeper one with the same text
  apart. Two blocks with one `recipeId` in a file are one recipe (the first wins, the second's lists join).
  A recipe with a link is `EDITED`, one without is `MANUAL` (Typed by you), as for Paprika.
- **Photos** (#386). `photo0`, `photo1`, … are sorted by their number; the first that can be stored is the
  recipe's own, so a missing or broken `photo0` falls back to `photo1` (at most 10 tried). A `src` is
  resolved against the page's folder inside the zip and **only ever compared with entry names**: `.` and
  `..` are folded away, a `..` that would leave the zip, a scheme (`https:`, `file:`), an absolute path, a
  backslash or a drive letter is no picture, a percent-encoded name is read decoded, case is ignored. A recipe
  none of whose pictures could be stored counts one in `photosLeftOut`; the recipe comes in.
- **A stranger's file is not believed**: the caps are `PaprikaLimits` (10 000 entries, 32 MiB for the page, 8 MiB
  a picture, 2 GiB unpacked in all, a 2 GiB file; an entry's declared size is a claim and the real size is
  counted as it unpacks). Unlike Paprika's per-recipe entries the page is one entry, so a page over its cap
  is `TOO_LARGE` for the whole file and nothing is imported; a picture over its cap is left out and counted.
  `recipes.html` is the shallowest such entry (not `__MACOSX/`, not hidden); no page, or a page with no
  `div.recipe-details`, is `NOT_A_RECIPE_KEEPER_FILE`; a cut-short zip is `DAMAGED`. The page is decoded as
  UTF-8 leniently (a stray byte costs a character, not the library) with a BOM stripped. A block with no
  name, or with neither ingredients nor steps, is **not a recipe** (counted); there are no per-entry
  "unreadable" counts. Deep nesting cannot overflow: Jsoup on Android, a flat tree on iOS (whose open-element
  search is capped at the nearest 128, so a million unclosed `<div>`s cost seconds, not hours; shared with the
  microdata fallback). A page that lies about its size reads as `TOO_LARGE` on Android and `DAMAGED` on iOS
  (the inflater only says it failed); both import nothing.
- **Strings.** 6 plus the generalised switch title in six languages on both platforms (`recipekeeper_*`; drafts
  for a native speaker, `docs/translations.md`); the picker takes any file (a plain `.zip` has no type of its own).
- **Tests.** `shared/fixtures/recipekeeper/*.zip`, made by `tools/recipekeeper-fixtures/make_fixtures.py` (valid:
  a real-export-style page with unquoted attributes, bold and colon headings, numbered steps with empty
  paragraphs, an Italian recipe with translated labels, a favourite, a course given twice, a non-URL source,
  a `PT0S` time, a recipe repeated by id, a recipe with no id, a percent-encoded picture name, a missing
  picture and one that is not an image; also a wrapper folder with a BOM and macOS junk; not Recipe
  Keeper's; truncated; a 40 MB page; a page claiming 4 GB; pictures that are too big or claim to be; 100 000
  nested divs), read by `RecipeKeeperArchiveTest`/`RecipeKeeperZipReaderTests` with the same counts; the parser
  by `RecipeKeeperParserTest`/`RecipeKeeperParserTests`; the mapping by `RecipeKeeperImportTest`/`RecipeKeeperImportTests`;
  the repository by `DefaultRecipeKeeperImportRepositoryTest` (in-memory DAO) and `RecipeKeeperImportRepositoryTests`
  (in-memory SQLite); the Settings state and words by `SettingsRecipeKeeperImportViewModelTest`,
  `SettingsRecipeKeeperImportScreenTest` and `SettingsRecipeKeeperImportTests`.
- **Not verified.** A real export: no Recipe Keeper account or file was available, so the fixtures follow what
  the importers above document (the sample page one of them uses is the model for the markup), not a file
  Recipe Keeper made. Whether `recipeCollection` is a `<meta>` as categories are (no real export had one; the parser
  reads any element carrying the itemprop, `<meta>` or text), a recipe whose yield is a bare number, and the
  system picker choosing the `.zip` from Drive or iCloud on a phone.

## Import from Mealie (#391, 2026-11-19)

The owner asked for Mealie (self-hosted) next, on top of the Paprika import (#382) and its pictures
(#386) and lists (#387). Settings, Your recipes gains **Import from Mealie** below Paprika's row, with
the one shared switch ("Put recipes in lists by their categories", on by default, this visit's
choice; the owner folded Mealie's own switch into it when the three importers landed together, so
Paprika, Recipe Keeper and Mealie share it, and it shows when any of the three rows does) and the same status line, behind the `mealieImport` flag (on by default in debug and release),
on both platforms. Everything that is not named here is the Paprika section's: the guarded archive, the
one limit, `BackupDao.importBackup` and `BackupMerger`, "never re-fetched", the stored photo, the counts.

- **The link import already works.** Verified in Mealie's source (`mealie/routes/spa/__init__.py`, at
  `0e923e3`, version 3.31): a public recipe's page (`/g/<group>/r/<slug>`) and a shared one
  (`/g/<group>/shared/r/<token>`) are served with an `application/ld+json` schema.org `Recipe` written
  into the HTML's `<head>` by the server (name, description, `image`, ISO `prepTime` / `cookTime` /
  `totalTime`, `recipeYield`, `recipeIngredient` as text lines, `recipeInstructions` as plain strings,
  `recipeCategory`, `keywords`), so no JavaScript is needed. `shared/fixtures/pages/mealie-pasta-e-ceci.html`
  is made to that shape and `MealiePageTest` / `MealiePageTests` read it through the ordinary link
  import. A private recipe's page asks for a login and stays out of reach, as every login page does. A
  server's recipe with no `description` or categories is the same page with those keys empty. **Not
  verified against a running server.**
- **Three kinds of file, one reader** (`MealieArchive` in `:core`, `MealieZipReader` on iOS, which reuses
  `PaprikaZipReader`'s zip structure and inflating): the **bulk export** (Data Management > Recipes >
  Export: a zip of `recipes/<slug>/<slug>.json`, no pictures; `mealie_export.py` copies only `.json`),
  **one recipe's Download** (`<slug>.json` and `original.webp` at the zip's root, written by
  `routes/recipe/shared_routes.py` from `recipe.model_dump_json()`, which is why its keys are
  snake_case), and a **bare `.json`** (the recipe page's JSON editor and the API write camelCase). The
  zip is told from a bare file by its first four bytes, not its name. Entries are found by what they are
  (`*.json`, a picture ending `.webp`, `.jpg`, `.jpeg` or `.png`; not hidden, not under `__MACOSX/`), and a
  name is only ever compared, never used as a path.
- **A picture belongs to the recipe whose JSON is in its folder** (nearest up the path, so
  `recipes/<slug>/images/original.webp` goes to `recipes/<slug>/<slug>.json`); a lone recipe's zip
  gives it any picture wherever it sits; of several, one whose name starts "original" wins, else the
  first; a picture that fits no recipe is ignored. It goes to the photo store as that recipe is read
  and is a recipe's own photo (#386): a WebP is decoded by `BitmapFactory` on Android and ImageIO
  on iOS and stored as the JPEG every photo is. A picture that is over 8 MiB, not an image or not stored is
  left out and counted, the recipe stays.
- **The same guards as #382** (`MealieLimits`: 10 000 entries, 32 MiB a JSON entry, 2 GiB in all and
  for the file, 8 MiB a picture): a declared size over the cap is refused unpacked, the real size is
  counted as it unpacks (the fixture `mealie-bomb.zip` says its entry is 100 bytes and unpacks to 40
  MB), entries and totals over the caps refuse the whole file (`TOO_LARGE`), a broken directory is
  `DAMAGED`. **Counts:** a JSON that is Mealie-shaped with no name, or neither ingredients nor steps, and
  any JSON that is not Mealie's (another app's, schema.org) are **not recipes**, skipped and counted; a
  file that isn't UTF-8 or JSON, is nested too deep or over a cap is **unreadable**, counted, the rest
  import. A zip with no JSON, or none of them Mealie's, and a bare file that isn't a Mealie recipe, are
  `NOT_A_MEALIE_FILE`; nothing is imported.
- **Detection** (`MealieRecipeParser`; Mealie's own `parse_mealie_export` uses the same test to take
  a pasted dump): a non-blank `slug`, `recipeIngredient` (or `recipe_ingredient`) a list of objects with
  at least one `referenceId` / `reference_id`, and **no `@context` or `@type`**. An empty ingredient
  list can't show a reference, so it needs a `recipeInstructions` list beside it. **A schema.org file
  is not read here, by decision**: it is a recipe page's data, which the link import reads; a bare one
  says "That isn't a Mealie export ... To bring in a recipe from a Mealie page, share its link", and
  inside a zip it is counted as not a recipe. (Parsing it with the JSON-LD parser would have made this row
  a second, different way to import a link.)
- **Fields.** Both key styles are read (`recipeIngredient` / `recipe_ingredient`, `orgURL` /
  `orgUrl` / `org_url`, ...). `name` is the title. **Ingredients are never rebuilt from `quantity` and
  `unit`**: before Mealie 3.1 a line that was never parsed was stored with `quantity` 1 and its text in
  `note`, and `display` (Mealie computes it from the quantity) reads "1 2 cups red lentils". A line is
  its `originalText`, else, **for a line with a food or a unit**, Mealie's own `display`, else the
  food's name and the note; **a line with neither food nor unit is its `note`** (or nothing, and is
  dropped), never its `display`. A `title` puts a heading line ("For the sauce:", a colon added if there
  is none) before the line. Lines are single lines (Mealie's line breaks become spaces); the text is as
  Mealie holds it (its steps may carry Markdown, left as written, like every text from a file).
  **Steps:** the app has no step headings (`IngredientHeading` is for ingredients), so *a step's `title` is
  put in front of its text* ("Sauce: Whisk the cream."); a step that is only a title is put in front of
  the next step that has text, and a trailing one is dropped. **Yield:** `recipeYield` text, else
  `recipeServings` as a number (4.0 is "4", 2.5 is "2.5", 0 is none); `recipeYieldQuantity` is not read.
  **Times:** `prepTime`, `performTime` (Mealie's cook time; `cookTime` is read as its legacy name) and
  `totalTime` as text, through the time rules; with no text, a `prepTimeSeconds`-style number
  (`prep_time_seconds`, `perform_time_seconds`, `total_time_seconds`) becomes `PT<n>S` and goes through
  the same rules ("PT5400S" is "1h 30m"). **Notes:** each `{title, text}` is a paragraph of the
  recipe's note (title, a line break, text; title alone or text alone also), the paragraphs apart by a
  blank line. **Source:** `orgURL`, a web link cleaned by `UrlCleaner`, else none (the picture's link,
  `image`, is a token for Mealie's server and is not kept). **Not kept, listed:** `description` (the
  app has no place for it; a later place would be its own decision), rating, nutrition, tools, assets,
  comments, `extras`, dates, and the household and user ids.
- **Identity** (`MealieImport.identity`): the cleaned `orgURL`, else `manual:mealie-<slug>` (a plain
  slug, `[A-Za-z0-9_-]{1,100}`, lower-cased), else `manual:mealie-<id>` (a plain id), else
  `manual:mealie-h<hash>`, the FNV-1a of the name, ingredients and steps (the Paprika hash's text, the same on
  both platforms). A recipe with a link is `EDITED`, one without is `MANUAL` and appears under Typed by
  you, exactly as Paprika's.
- **Lists:** each category and each tag is a list of that name (the merger finds one here by the
  trimmed, case-insensitive name, else makes it; categories and tags that differ only in case are one
  list, the first spelling kept). **Mealie's favourites belong to a user and are not in a recipe's file,
  so nothing is filed into Favorites.** The rest is #387: a repeat in the file adds its lists to the
  first, a recipe the free limit leaves out is filed nowhere, no empty list is made.
- **Strings.** 15 in six languages on both platforms (`mealie_*`; drafts for a native speaker,
  `docs/translations.md`), the already-here and free-limit sentences the backup's. The not-a-recipe line
  says "isn't a Mealie recipe, or has no name, ingredients or steps".
- **Code shared with Paprika's, and not.** Mealie's types (`MealieError`, `MealieLimits`,
  `MealieSummary`, ...) and the lists loop in `MealieImport` are written beside Paprika's, not
  generalised, to leave the Paprika layer alone while other importers were being added; merging them
  into one importer layer is a clean-up for when more than two exist. iOS widened `PaprikaZipReader`'s
  `Entry`, `centralDirectory`, `contents(of:)`, `inflate`, `u16` and `u32` from `private` to internal so
  the Mealie reader uses the one zip implementation.
- **Tests.** `MealieRecipeParserTest` / `MealieRecipeParserTests` (each field, both key styles, the pre-3.1
  lines, detection, schema.org), `MealieArchiveTest` / `MealieZipReaderTests` (the fixtures in
  `shared/fixtures/mealie/`, made by `tools/mealie-fixtures/make_fixtures.py`: the bulk export, a recipe's
  download with its WebP, the same with the picture in the bulk layout, bare camelCase and pre-3.1 JSON,
  a schema.org file, and the hostile ones: truncated, bad entries, a declared 4 GB entry, a 40 MB bomb that
  declares 100 bytes, pictures that are good, not images, too big and stray; the WebP is encoded by the
  script, and the iOS test decodes it through the real photo store), `MealieImportTest` /
  `MealieImportTests` (identity, times, lists), `DefaultMealieImportRepositoryTest` over an in-memory DAO
  and `MealieImportRepositoryTests` over in-memory SQLite (second import, already here, the limit,
  failures, pictures), `SettingsMealieImportViewModelTest`, `SettingsMealieImportScreenTest` and
  `SettingsMealieImportTests` (state and words), and `MealiePageTest` / `MealiePageTests` (the link).
- **Not verified.** A real Mealie export (the fixtures follow the schema in Mealie's source, they are
  not files a server wrote; a real server's `recipes/<slug>/` may hold more files than `<slug>.json`, which
  are ignored, or counted as not recipes if they are JSON), a real Mealie page's HTML (above), the system file picker choosing a
  `.zip` or `.json` from Drive or iCloud, and a very large export on a low-memory phone.

## A recipe's own photo (#386, 2026-11-19)

The owner's decision, from the Paprika import: a recipe can have a picture of its own, stored on the
phone, not only a web link. Both platforms, no flag (it changes nothing until something stores one;
the Paprika import is the first user, behind `paprikaImport`).

- **A column, a file.** `recipes.photoFile`, the name of a file in the photo store (Android
  `filesDir/cooked_photos/`, iOS `CookedPhotos/` beside the database: the store "I made this" uses, so
  its downscale to 2048 px and JPEG rules, and its random-uuid names, apply). Room 18 to 19
  (`MIGRATION_18_19`, `MigrationTest`, schema `19.json` committed; numbered after #381's list renumbering,
  so a database from 17 gets both), iOS `user_version` 17 to 18 (`addPhotoFile`): a nullable column, every recipe comes across with none. **It is not a cooking**: no
  `cooked_photos` row, so the Cooked view, "I made this" and the cull's protections never see it.
  `PhotoStore.importBytes` / `importData` stores picture bytes in hand: refused if over 8 MiB, not an
  image, or declaring over 100 megapixels; a fresh name, never one from a file.
- **Shown wherever the recipe's picture shows, in preference to `imageUrl`.** One string names it to
  the image loader (`StoredPhoto`, core / `Data/Model`): `photo://<file name>` (only a plain name; a
  name with a path in it is none). The list queries return it in place of the link
  (`COALESCE('photo://' || photoFile, imageUrl)`: Recipes, a list's rows and cover, a planned meal), the
  recipe screen and the print use `Recipe.displayImage`, and the image loader turns the string into the file
  (Android: `StoredPhotoMapper`, registered in `RecipeApp`'s Coil loader; iOS: `ImageLoader`, which reads
  `photo://` from the photo store's folder). The editor still shows and saves only the web link
  (`Recipe.image`).
- **A missing file shows the link as before, never a blank.** A phone restored from Android's own backup
  has the rows without the files (as for "I made this" photos), and a store can be cleared. So the model
  carries the link behind the photo: `photo://<name>#<web link>` (the link's own fragment cut; `StoredPhoto`
  builds it, `RecipeImageSql` builds the same in SQL, `photo://<name>` alone when there is no link). It is
  decided in one place per platform, when the picture is loaded, not when the row is read: Android's
  `StoredPhotoMapper` answers the file if `File.isFile`, else the link (which Coil's own mappers then
  handle), else nothing; iOS's `ImageLoader` reads the file if it exists, else loads the link, else throws
  and the view keeps its placeholder. That is a stat, never a decode on the main thread, and it holds for
  rows, covers, planned meals, the recipe screen and the print (Android's `cached` check and iOS's
  `cachedData` follow the same rule). The column is never cleared by a miss: an import may bring the file
  back, and the next load shows it.
- **Kept, deleted, restored.** A re-share, "Update from source" and an edit never change it
  (`keepingUserState`; iOS's `update` doesn't write the column). Deleting a recipe deletes the row; its
  file stays while the Undo may still come (the delete's snapshot is the whole row, so Undo brings the
  name back) and goes with `forget`, once the delete stands; every other delete path (the cull, the free
  swap) leaves the file to the **sweep**, which at each start removes files no cooking and no recipe names
  (`CookedPhotoDao.recipePhotoFileNames`), after its 10-minute grace.
- **Export, import and the automatic copy.** `BackupRecipe.photo` (`"photo"` in the file: a path
  `photos/recipe-<row id>.jpg` in the zip, only for a recipe whose file is here; absent in a plain JSON
  export, a shared file (#149) and older files, which an older app ignores). The same zip as "I made this" photos
  (`BackupArchive`), so the automatic backup copy (#150) carries them too. An import takes the picture
  only with its file and only for a recipe with none: a new recipe gets it, a recipe already here with
  none gains it (`PhotoUpdate`; it never replaces one). The Android backup include list is unchanged:
  photo files stay out of Google's Auto Backup, as the cooked ones do.
- **Tests.** `StoredPhotoTest`/`StoredPhotoTests`, `StoredPhotoMapperTest` (file there, file missing with a link, missing with none, the file coming back) and the iOS `ImageLoader` tests of the same three; `RecipePhotoTest` (Room) and
  `RecipePhotoTests` (SQLite): lists show it, a re-share and an edit keep it, Undo and `forget`, the sweep,
  never a cooking, the export round trip, a photo without its picture, a recipe with none gains the
  file's; `MigrationTest` 18 to 19 and 17 to 19 (device) and the iOS 17 to 18 and 16 to 18 migration tests.

## Fresh food: shop in two trips, and cook the delicate meals first (#396, 2026-11-19)

**Free for everyone** (owner, 2026-11-23). #413 put Buy later and the swap behind Pro for two days; both are free again, with no badge, locked line or Pro screen, on the Week, in Shop for this week and in the meal-plan review.

The owner named shopping and then cooking through the week as the hardest part of the app, and fresh
food going off its first half. Behind the `freshFood` flag (on by default; off, To buy is one list and
there is no note), on both platforms. Never a date and never a fact about the cook's own food: the
only claim anywhere is "keeps about N days once bought", a range from a table.

- **The table, `shared/tables/<lang>/perishables.json`** (`Perishables`, `:core` and Swift, pinned by
  the corpus's `Fresh(...)` rows): groups with `key`, `minDays`, `maxDays` and `aliases`, then a `skip` list,
  and for fr, es, it and pt a `skipPrefixes` list. **Matching is the density table's:** the ingredient's
  name (`IngredientName.of`, so amounts, sizes and preparation are gone) ends in an alias at a word
  boundary, longest first, with a listed singular and plural one word (`IngredientName.key`). A skip is a
  name that would otherwise end in an alias but isn't fresh ("smoked salmon", "dried parsley", "frozen
  strawberries", "canned salmon", "dried porcini mushrooms", "rotisserie chicken", "cauliflower steak",
  "goji berries"); it wins by being longer. "Fish sauce" and "mushroom stock" never end in "fish" or
  "mushroom", so end-of-name matching already refuses them; they are pinned by tests. **Smoked salmon is
  a skip** (decided): it keeps longer than the fresh-fish range says, and a wrong "1-2 days" is worse
  than buying it now. A name the table doesn't list, or a line the app can't name ("salmon and
  potatoes:", "juice of 1 lemon"), is **not perishable as far as the app says**, so it is bought now: the
  failure is buying early, never an unsure claim.
- **Each language has its own file; the recipe's language picks it** (`LanguageWords`, never the phone's,
  never merged). The groups and their days are the same in every file (a test pins that, and that
  `Perishables.LATER_DAYS` is at or past every group's `minDays`). **en is the solid one**; de, es, fr, it, pt
  and ja are best-effort drafts for a native speaker (`docs/translations.md`). German compounds are
  matched whole as in the density table ("Lachsfilet" is listed itself; "Räucherlachs", one word, matches
  nothing). French, Spanish, Italian and Portuguese put the modifier after the noun, so "saumon fumé" is
  refused by the rule itself, but "bouillon de poulet" or "confiture de fraises" ends in the food, so
  `skipPrefixes` lists the openings that make a name a product ("bouillon de", "caldo de", "mermelada de",
  "pesto di"…). Japanese is unspaced, so "塩鮭" ends in "鮭" and is a skip. **Meat cuts** (3-5 days) are in
  the English table only.
- **The groups and where their ranges come from** (refrigerator, after purchase; checked against the
  sources below on 2026-11-19). The rule: **inside the sources' range, at the short end**, because too
  short only makes the app suggest buying later or cooking sooner, and too long is a wrong number. Where
  one group holds foods the sources rate differently, the **shortest** of them sets it. **Every food listed
  shows its own source figure, never another member's:** a food that keeps a week or more (blueberries,
  basil, cilantro, mint, dill, chives, tarragon, chervil, generic "fresh herbs") is **off the table**, because
  it never needs the later trip; it is bought now, with everything else. Only "berries" / "mixed berries"
  (a mix that holds raspberries) take the shortest member's range.

  | Group | App | Source figure | Source |
  |---|---|---|---|
  | fish and seafood | 1-2 | FSIS chart: fin fish 1-3, squid 1-3, shrimp 3-5, shucked shellfish 3-10; FoodKeeper: fish 1-2, shrimp/squid/scallops 1-3; Nebraska: 1-2 | FSIS/FoodSafety.gov chart, FoodKeeper, Nebraska |
  | raw mince, fresh sausage | 1-2 | FSIS: ground meats 1-2, raw sausage 1-2; FoodKeeper: ground and raw sausage 1-2 | FSIS chart, FoodKeeper |
  | fresh poultry | 1-2 | FSIS: whole and pieces 1-2; FoodKeeper: 1-2 | FSIS chart, FoodKeeper |
  | meat cuts (en only) | 3-5 | FSIS: steaks, chops, roasts 3-5; FoodKeeper: 3-5 | FSIS chart, FoodKeeper |
  | mushrooms | 3-5 | FoodKeeper: 3-7 (was 4-6: its low end was past the source's) | FoodKeeper |
  | ripe avocado | 3-4 | FoodKeeper: 3-4, "applies to ripe fruit"; Nebraska 3-4 (was 2-3, under the source: kept short, now equal) | FoodKeeper, Nebraska |
  | parsley | 2-3 | FoodKeeper: parsley 2-3 days (its own row). Off the table: basil 10 days, "Herbs" 7-10 days, cilantro and mint 2-3 weeks | FoodKeeper |
  | salad leaves, spinach | 3-5 | FoodKeeper: leaf lettuce/spinach 3-7, arugula 3-7, bagged greens 3-5 unopened; Nebraska: spinach 3-7, bagged greens 3-5 | FoodKeeper, Nebraska |
  | strawberries, raspberries, "berries", "mixed berries" | 2-3 | FoodKeeper: strawberries 2-3, raspberries 2-3 (a mix holds raspberries, so it takes the short end); Nebraska: strawberries 2-3 | FoodKeeper, Nebraska |
  | blackberries | 3-5 | FoodKeeper: blackberries 3-6 (its own group: 2-3 would sit outside the range) | FoodKeeper |
  | blueberries | off the table | FoodKeeper: 1-2 **weeks** | FoodKeeper |

  - **FSIS / FoodSafety.gov "Cold Food Storage Chart"**, refrigerator (40 °F or below) column, reviewed
    2021-09-20: https://www.foodsafety.gov/food-safety-charts/cold-food-storage-charts. The live page
    refuses automated fetches (403); the chart was read as the identical PDF
    https://www.vdh.virginia.gov/content/uploads/sites/6/2023/07/Cold_Food_Storage_Chart.pdf. Note the chart
    now gives fin fish 1-3 days (older FSIS charts said 1-2) and shrimp 3-5; 1-2 stays, inside both.
  - **USDA FoodKeeper** (the data behind the FoodKeeper app, "from date of purchase" figures):
    https://www.fsis.usda.gov/shared/data/EN/foodkeeper.json (catalogued at
    https://catalog.data.gov/dataset/fsis-foodkeeper-data). It also refuses automated fetches, so the
    figures above were read from an older, third-party copy of the same dataset,
    https://raw.githubusercontent.com/jelera/food-shelflife-db/master/lib/seeds/ingredients.csv. **Re-check
    the produce rows (mushrooms, herbs, avocado, berries, greens) against the current file in a browser**
    before release; the meat and fish rows agree with the current FSIS chart.
  - **Nebraska Extension, "Home Food Storage Chart"** (cites the FSIS chart and FoodKeeper):
    https://food.unl.edu/food-storage. Not covered there: mushrooms, fresh herbs, raspberries, blackberries.
  - Other extension guides read and **not** used for a number, because they measure at 32 °F or give one
    blanket figure: UMass (strawberries 7 days, raspberries 2-5, blueberries 2 weeks,
    https://www.umass.edu/agriculture-food-environment/fruit/ne-small-fruit-management-guide/general-information/post-harvest-handling-storage)
    and Montana State (mushrooms, berries and greens 1-3 days,
    http://www.montana.edu/extension/buyeatlivebetter/other_nep_resources/fact_sheets/howshouldistoremyfruitsandvegetables/).
    Woody herbs, cherries, cranberries, asparagus and the rest are not listed: bought now.
- **Shop for this week splits To buy** (`FreshShop.split`, pure): a row goes to **Buy later in the week**
  only if its ingredient is in the table **and every planned line of it is for a meal at least 3 days from
  today** (`Perishables.LATER_DAYS`); otherwise **Buy now**. A row with a meal sooner, a line with no day or
  a name the app can't read stays now whole. **Why 3:** it is the first day count at or past the low end of
  every group's range (the highest low end is 3: meat cuts, mushrooms, ripe avocado, salad leaves, blackberries), so a
  meal three or more days out is one a shop made today may already have outlasted; at 2 those groups
  would split on a meal the table says they last for. (It was 4 while mushrooms were 4-6.) A fixed number, a hint like the table. Quantities, "You have…" and #302's amounts are
  exactly as before (the rows are the same objects, in the same order); In your pantry is unchanged, and a
  pantry item still makes the row Have, never later. Each later row says **"For {day}"** (its earliest meal)
  and **"Keeps about 1-2 days once bought"**, under its name, above the lines that already say each recipe
  and day.
- **The flow: two buttons, no third action.** "Add to groceries" adds **Buy now** only; the **Buy later**
  group has its own "Add these to groceries". A cook who shops once taps both, one who shops twice taps
  the second on the second trip, and neither is a mode to choose or remember. Each button is used once and
  reads "Added to groceries" (the later one keeps working after the first). With nothing to buy later (or the
  flag off) the screen is exactly as before: one "To buy" with its one button. With only later rows, Buy now
  says "Nothing to buy now".
- **The plan nudge** (`FreshPlan.nudges`, pure): "**{Salmon fillets}: keeps about 1-2 days once bought.
  Cook it earlier?**" with one button, **"Swap with {day}"**. The first day is the plan's earliest planned
  day not before today (the cook shops once before the plan starts); a meal is late when its day is more
  than its group's `maxDays` after it (the long end: the note speaks only when even the generous reading
  says it will have gone). The partner is the earliest-day other meal (a note counts) within the group's
  `maxDays` of the first day that can itself take the later day (a partner with a perishable of its own must
  keep that long). **No partner, no note** (nothing to tap). Only the meal's most delicate perishable is
  named (a recipe holds many). The ingredient's name is capitalised from `IngredientName.of`; the sentence
  has no verb to agree with it ("Name: keeps…"), so it reads in every language and number. **It never
  moves anything by itself:** a tap swaps the two meals' days, each keeping its meal type.
- **Where the nudge is: the meal-plan review (#352) and the Week's meal rows.** The review counts the ticked
  rows with a day, so a day picked, a recipe unticked or a swap re-works the notes, and the swap trades two
  rows' days in the review (still unsaved until Save). The Week covers today and the next 13 days
  (`WeekViewModel.FRESH_DAYS`), reading each planned recipe's ingredient lines through
  `GroceryRepository.plannedIngredients`, and a swap is two `MealPlanRepository.move` calls. **Left out:**
  the Add to plan sheets and the Move sheet (they place one meal on a chosen day with no list of the others
  to swap with; the Week's note appears the moment the plan is late, so a Move or Skip that makes it late is
  noticed there: no extra code, because the note is worked out from the plan whenever it changes, and `WeekFreshNudgeTest`/`WeekFreshNudgeTests` pin a Skip to the next free day showing the note and its undo clearing it), the month view, and days beyond the first two weeks. The two shop trips and the note
  agree on purpose but are independent: the note is the "cook it earlier" answer for a cook who shops once,
  the split the answer for one who shops twice, and both read the same table.
- **Strings.** Nine, `what_i_need_buy_now`, `_buy_later`, `_buy_later_note`, `_nothing_to_buy_now`,
  `_add_later`, `_later_for`, `fresh_keeps`, `fresh_nudge`, `fresh_nudge_swap`, in six languages on both
  platforms. None states a date or a fact about the food: "keeps about N days once bought" is the table's
  range, and "For meals 3 or more days away" is the rule.
- **Code.** `Perishables.kt`/`.swift` and `FreshFood.kt`/`.swift` (`FreshShop`, `FreshPlan`, `LaterRow`,
  `ShopTrips`, `FreshMeal`, `FreshNudge`) in `:core` and `Data/Model`; `WhatINeedViewModel` takes the
  calendar and the flag (state `trips`), `MealPlanReviewViewModel` and `WeekViewModel` the flag (and the
  grocery repository); `FreshNudgeNote` is the shared note view.
- **Tests.** `PerishablesTest` (each group, each skip, the languages, the table's own invariants),
  `FreshFoodTest`, `WhatINeedFreshViewModelTest`/`WhatINeedFreshScreenTest`, `MealPlanReviewFreshTest`, a
  case in `MealPlanReviewScreenTest`, `WeekFreshNudgeTest`/`WeekFreshNudgeScreenTest`; iOS `PerishablesTests`,
  `FreshFoodTests`, `FreshShopTests`, `MealPlanReviewFreshTests`, `WeekFreshNudgeTests`, the corpus's `Fresh`
  rows, and `FreshFoodUITests`.

## Groceries: the recipe is a tag, apart from the amount (#422, 2026-11-20)

The owner found a grocery line's description and the recipe it is for looked the same (both muted
lines under the name), so they blurred. Decision: the recipe becomes a small tag. Behind the
`groceryRecipeTags` flag (on by default; off, the muted comma line of #286 returns exactly), on both
platforms. Supersedes the "recipe line" bullet of #286 above while the flag is on.

- **A row is: the name; its amount and note as plain text, as before** (an added-up row's lines, the
  "× 3" row, the lines that sit together); **then the recipes it is for, each a tag on a line of its
  own.** A tag is a small rounded pill tinted with the accent wash (the accent-for-text token at 12%:
  basil on the ground, the lighter on-ink basil in dark mode, as the Home timer's tag) with a
  small book glyph (Android's hand-drawn `ic_book`, iOS's SF Symbol `book`), "Mushroom omelette · Thu"
  when the line came from the plan (the day is `plannedDay`, short weekday as Week's), just the title
  when it came from "Add to groceries" on the recipe. Tags wrap.
- **One tag per recipe and day** the row's lines came from, in the order added (`GroceryRecipeTags.of`,
  pure, `:core` and Swift; the same cases in `GroceryRecipeTagsTest` / `GroceryRecipeTagsTests`, not the
  corpus, since it is a filter and a distinct). Up to three show whole; **more than three, the first two
  and a quiet "+N"** (`shown`: N is the rest, so never "+1"), as the issue says. The "+N" is a label,
  not a button, read "N more recipes". The same recipe planned on two days is two tags (the days
  differ, and the day is the point). A typed or shared-list line, and a line whose recipe has been
  deleted, has no tag, as before (the title map is `observeRecipeTitles`).
- **A tap opens the recipe on the current tab's own stack**, so Back returns to the list. The Week's
  stack already had `week/recipe/{id}?servings=` (Shop for this week lives there); **Groceries gained
  its own, `groceries/recipe/{id}?servings=`** (Android: `Routes.GROCERIES_RECIPE`, a shared
  `tabRecipe` destination with the Week's; iOS: `Router.groceriesPath`, reusing `Route.weekRecipe`). A
  line that came from the plan opens at **the servings the week plans it at**: Shop for this week knows
  them (`GrocerySource.servings`, `NeedLine.servings`, from `PlannedIngredients`); Groceries has no
  column for them (a stored line has only `recipeId` and `plannedDay`, no schema change), so
  `GroceriesViewModel.onOpenRecipe` looks the meal up in the plan (`MealPlanRepository.observeDays` for
  that day, the recipe's first meal) and falls back to the recipe's own yield when the meal has since
  been moved or removed. A recipe not planned opens at its own yield.
- **Accessibility.** A tag is a button that reads "For Mushroom omelette, Thursday" (the full weekday),
  or "For Mushroom omelette" with no day (`grocery_tag_for`, `grocery_tag_for_day`, `grocery_tag_more`,
  six languages on both platforms). **The row's tick is unchanged and the tag never steals it:** on
  Android a clickable inside the row's `combinedClickable` stays its own node (a test pins the row is
  still `Role.Checkbox` with its toggle state and a tag tap ticks nothing); on iOS the tags sit beside
  the row's tick button in a stack, not inside it (nesting buttons in a label is unreliable), so a
  tap on the row's own area ticks and a tap on a tag opens, and the row's accessibility label no longer
  holds the recipe. The tag's touch area is a few points taller than the pill; the tick, a long-press
  and the tooltip anchors are untouched.
- **A typed item can be linked to a recipe** (owner's addition, 2026-11-20; same flag). Long-press a row
  with no recipe of its own: **"For a recipe…"** opens a picker of the cook's recipes (the Week's "+ Add"
  list without its meal types and notes: `observeHistory`, most recently viewed first, searchable by title
  or ingredient, one tap links at once). The row's lines then carry that recipe: its tag, with **the next
  day this week (today and the six after) it is planned**, else no day. A row that has a recipe offers
  **"Not for a recipe"**, which takes the link off every line. **No new column and no migration:** a link
  is the item's existing `recipeId` (and `plannedDay`), set by `GroceryRepository.setRecipe` (the DAO's
  one `UPDATE`), so export/import already carry it and the schema is unchanged (Room 17, iOS
  `user_version` 16). **The trade-off:** nothing records *who* made a link, so the app's own links (an
  item added from a recipe or the plan) cannot be told from the cook's. The rule that keeps the app's
  links safe is that **"For a recipe…" is offered only to a row none of whose lines has a recipe**, so a
  link is never overwritten; "Not for a recipe" can take any link off (a cook who ate that recipe
  elsewhere), which only loses a tag. A row mixing linked and unlinked lines is offered the second only.
  If telling the two apart ever matters, a `linkedByUser` column is the migration to add. The item's
  tap-to-tick, aisle and the rest of its menu are unchanged. Strings `action_for_a_recipe`,
  `action_not_for_a_recipe`, `link_recipe_title` (six languages, both platforms).
- **Shop for this week's rows** (Buy now, Buy later, In your pantry) use the same tag under each line:
  there each line already named one recipe and day, so a line is its text with its one tag below, in
  place of the muted "Recipe · Thu" line. The Week's stack opens the recipe at the planned servings.
  Nothing else on those rows changed ("For {day}", "Keeps about…", "You have…" and #302's amounts).
- **Anywhere else a line names its recipe:** the "Add to groceries" sheet groups lines under the recipe's
  own heading and the "Add this list" sheet shows a line as written, so neither has a muted recipe line to
  replace; "Send list" keeps its brackets (plain text). The Pantry's basket tag is the pantry's own.
- **Code.** `RecipeTags.kt` / `RecipeTags.swift` (`RecipeTagRow`, shared by Groceries and Shop for this
  week); `GroceriesUiState.recipeTags` beside `recipeLines` (kept for the flag off); the screens take
  `recipeTags` and `onOpenRecipe`, the flag from `LocalFlagValues` / `featureFlags`.
- **Tests.** `GroceryRecipeTagsTest`, `GroceryRecipeTagsScreenTest` (tag shown with and without a day,
  hidden for typed and gone, "+N", a tap opens at the planned servings or the yield, the tick still
  ticks, flag off, linking and unlinking from the menu, the planned day), `GroceryDaoTest.aTypedItemIsLinkedToARecipeAndUnlinked`,
  `WhatINeedRecipeTagsScreenTest` (all three sections); iOS `GroceryRecipeTagsTests`,
  `GroceryRecipeTagsViewModelTests` (including the link), `GroceryDaoTests`, and `GroceriesUITests`
  (`testARecipesLinesGoOnTheListByAisle`: tag, tap, Back, the row still ticks;
  `testATypedItemIsLinkedToARecipeAndUnlinked`).

## Paste an AI's answer in the app (#372, 2026-11-19)

An AI's answer used to come in only by Share (or Android's text-selection menu). Most chat apps
offer Copy on a code block, so pasting is the natural step; the owner asked where in the app to
paste the JSON. Two entries, both platforms, both **through the one shared-text route**
(`TextIntent.target`: #306 text, #350 JSON, #352 a week, #354 versions), so a paste lands exactly
where the same text shared would. Nothing new in the parsers, and **nothing is saved before the
editor's or the review's Save** (the free limit as everywhere).

- **Paste a link takes a link or recipe text** (Home's tile, the Recipes + menu; the tile says
  "A link or a recipe's text", the dialog "Paste a link or recipe"). **Go is on for any text.** A
  lone link (one token: a URL, or `host.tld/path` with the scheme left off, never JSON or "1.5
  cups") imports as before; anything else goes through the route: one recipe opens the editor, a
  week the meal-plan review, a bulleted list Groceries' "Add this list" sheet, a link with a short
  caption imports. Text the route doesn't read (too long, and neither link nor list) leaves the
  dialog open with "No recipe found in that text."; the next keystroke clears it. Android's field
  is multi-line; **iOS replaced the alert with a sheet** (`PasteLinkSheet`), because an alert's
  field is one line and would flatten a recipe's lines.
- **Ask an AI: "Paste the answer"**, under Copy prompt and Share. It reads the clipboard **once,
  on the tap, in the view layer** (never on its own; the ViewModel gets the text), and routes it
  the same way, closing the sheet first. An empty clipboard says "Nothing to paste. Copy the
  answer first."; text with nothing to open says "No recipe found in what you copied."; both clear
  when anything changes. (iOS asks the system's "Allow paste" once per copy when the text came
  from another app; the system's own control would avoid that but cannot carry this label.)
- **Code.** Android: `PastedTextRouter` (`ui/edit`, a Hilt singleton over the flags, `SharedTextInbox`
  and `ReceivedListInbox`) returns a `PasteTarget` (`Link`, `Review(key)`, `Plan(key)`,
  `Groceries`); `MainActivity.routeFor` uses the same router for shares, so there is one decision.
  `HomeViewModel` / `RecipesViewModel.onOpenLink(onTarget)` and `AskAiViewModel.onPasteAnswer`
  call it; `NavHostController.openPasted` maps a target to the route. iOS: `PastedTextRouter` /
  `PasteTarget` (`Data/Remote/PastedText.swift`), the same three ViewModel entry points, and
  `RootView.openPasted` (a list waits in `AppContainer.pendingListText` for Groceries to open its
  sheet). Six strings changed or added in six languages on both platforms.
- **Not here:** "Change this recipe" has no paste button (its answer still comes back by Share or
  Paste a link); a recipe's `url` and image are never used, as for a share.
- **Tests:** `PastedTextRouterTest` and `PasteTextViewModelsTest` (link, JSON, week, recipe
  written out, empty, over all three entries), `AskAiSheetTest` and the screen tests (Android);
  `PasteTextViewModelsTests` and `PasteAnswerUITests` (iOS, with the clipboard the app's own copy,
  `-uiTestPasteboard`).

## Reorder lists: the cook's own order (#381, 2026-11-19)

The cook drags lists into their own order on the Lists screen (the cover grid), and that order is
what every place that lists lists shows: Lists, the save-to-list sheet, Recipes' list tiles (they
read `observeLists`), and an export file. Behind the `reorderLists` flag (on by default in debug and
release), on both platforms.

- **`lists.sortOrder` is the position, and the only thing lists sort by.** The column was already
  there (seeded 0 to 5, a new list took max + 1, export and import carried it), but every list query
  sorted `isBuiltIn DESC` first and `sortOrder` second, so a seeded list could never be moved after
  a user's. Now `ORDER BY sortOrder, id` everywhere (`ListDao.observeLists`, `BackupDao`'s two list
  queries; iOS's twins). `isBuiltIn` means only "seeded"; `isFavorites` still guards the delete, and
  Favorites reorders like any other list.
- **The migration only numbers.** Room 17 to 18 (`MIGRATION_17_18`, `MigrationTest`; a JVM twin,
  `ListsRenumberMigrationTest`, runs it on every build) and iOS `user_version` 16 to 17
  (`numberListsInShownOrder`) renumber every list 0, 1, 2... in exactly the order it was shown
  (`isBuiltIn DESC, sortOrder, id`), so nobody's order changes on upgrade. Data only: the schema
  JSON for 18 differs from 17 in the version. The ranks are written one by one, not with a
  self-referencing `UPDATE`. Old upgrades had left user lists sharing a position with seeded ones
  (`MIGRATION_1_2` appended Breakfast and Snacks after user lists); the numbering settles that.
- **A new list goes last** (`COALESCE(MAX(sortOrder), -1) + 1`, as before). Deleting leaves a gap,
  which nothing minds: only the order counts, and the next `reorder` closes it.
- **Storing it.** `ListDao.reorder(orderedIds)` (one transaction, so the screen sees one change):
  the named lists first in that order, then any list not named (one created while a drag was in
  flight) in the order it had, ids that are not lists ignored, every list ending with a distinct
  position. `ListRepository.reorderLists`, through `ErrorLog.guard` (iOS: `perform`).
- **The ViewModel owns the drag.** `ListsViewModel` keeps the stored order (collected itself, not read
  off `uiState`, the `WhileSubscribed` rule) and a `Drag(id, order)`; `uiState.lists` is the drag's
  order while one is in flight and `draggingId` says which cover is held. `onDragStart`,
  `onDragOver(target)` (the held cover takes that cover's place, the ones between shift),
  `onDragEnd` (stores once, and only if the order changed), `onDragCancel`; and `onMoveEarlier` /
  `onMoveLater` for the accessible route, stored at once. Nothing is written while the finger is
  down, so a cancelled drag leaves no trace.
- **Android's gesture is ours, no library.** `Modifier.gridReorderGestures` (`ListsReorder.kt`) is
  `detectDragGesturesAfterLongPress` on the `LazyVerticalGrid`, using `layoutInfo.visibleItemsInfo`
  to find the cover under the finger and the one the held cover's centre is over, and scrolling
  the grid when the finger is within 72dp of an edge. The held cover rides the finger
  (`graphicsLayer`: translated by finger minus grab point minus its current cell, 1.05 scale, a
  shadow, `zIndex`) and the others `animateItem()` to their new places. A tap still opens the list.
  Chosen over a reorder library (`sh.calvin.reorderable` would do it) because the grid is small, the
  lazy grid already reports where everything is, and a dependency for one screen was not worth it.
  The tile's key is `list-<id>` and the new-list tile and header have other keys, so neither is
  ever dragged over.
- **iOS uses the system's drag and drop.** `.onDrag` on each cover (the long-press lift, with its
  haptic) and `.onDrop` with `ListDropDelegate`: `dropEntered` on a cover calls `onDragOver`, a drop on
  a cover, or on the screen around the covers, calls `onDrop(on:)` (the held cover takes the
  target's place first, then `onDragEnd`: on a slow runner the lift finished with the finger
  already over the target and `dropEntered` never came, so nothing moved); leaving the screen mid-drag
  calls `onDragCancel`. The grid is `CoverGridLayout`, a `Layout`, in place of the rows of
  `HStack`s: the old rows were keyed by their first cover, so moving a cover between rows rebuilt
  the one being held and lost the drop target. One `ForEach` in a custom layout keeps every cover's
  identity as it changes row, animates (`.snappy`), and, being not lazy, keeps every cover in the
  accessibility tree as before.
- **Accessible alternative.** Each cover has **Move earlier** / **Move later** (TalkBack's actions
  menu through `customActions`; VoiceOver's actions through `accessibilityActions`), absent where the
  move isn't possible (the first can't go earlier). Two strings, `lists_move_earlier` and
  `lists_move_later`, in six languages on both platforms (drafts for a native speaker).
- **Flag off.** The cover grid has no drag and no actions, and the repository gives the order from
  before: a stable sort of the stored order with seeded lists first (`DefaultListRepository`'s
  `inShownOrder`, iOS's `reorderOn`). The stored positions are kept, so turning the flag back on
  brings the cook's order back. New lists still go last.
- **Export and import.** `BackupList.sortOrder` was already in the file (`"sortOrder"`); the export now
  writes lists in position order, so file order is the cook's order. Import still **merges, never
  replaces**: a list already here (matched by uid, by name, or Favorites by flag) keeps its place, and
  a list the file adds goes after the lists here, in the file's order (`maxSortOrder + 1...`), whatever
  positions the file carries. An older file with none (read as 0) imports the same way.
- **Not done.** No drag on the Recipes screen's list tiles (Recipes shows the order, #373 is changing
  that screen); no reordering of meal types, which have their own `sortOrder`.
- **Tests.** `ListDaoTest` (order, `reorder`, new list last, delete, export order),
  `ListOrderRepositoryTest` (both readers, the flag), `ListsRenumberMigrationTest` and
  `MigrationTest` (17 to 18), `ListsViewModelTest`, `ListsScreenTest` (actions, a held-and-dragged
  cover, a tap), `BackupMergerTest`, `DatabaseErrorTest` on Android; `ListDaoTests`, `ListOrderTests`
  (including the user_version 16 file), `ListsViewModelTests` and `BackupMergerTests` on iOS, and
  one iOS UI test, `ListsUITests.testAHeldCoverDraggedOntoAnotherTakesItsPlace`
  (`press(forDuration:thenDragTo:)`, launched with `-uiTestFlags reorderLists`, because UI tests
  start with every flag off): it passed three times in a row. The ViewModel owns every rule; the
  test only proves the system drag reaches it. Android's gesture is checked by `ListsScreenTest`'s
  touch-input drag.

## Row actions and selecting several recipes (#376, #377, 2026-11-19)

Owner's ask (the "common UX flows" list): act on a recipe from its row, without opening it first,
and on several at once. Two flags, `rowActions` and `multiSelect` (both on by default; either off
removes just its entries: the menu, or every Select), on both platforms.

- **A long press on a row opens a menu** (Android: a `DropdownMenu` on the row, from
  `combinedClickable`'s long click, labelled "Recipe actions" for TalkBack; iOS: `.contextMenu`).
  On Recipes, an automatic view and a list's own screen (Home has no rows since #371). In this
  order: **Add to list**, **Add to plan**, **Share**, **Select**, **Remove from this list** (a
  list's own screen only), then **Delete**, last. Each entry is the existing act, not a new one:
  - *Add to list* opens the save-to-list sheet for that recipe, which writes at once as ever.
  - *Add to plan* is the recipe screen's sheet, starting on the recipe's own yield (the servings
    the recipe screen passes). The label is the existing "Add to plan" (the owner's note said "Add
    to Week"); the sheet it opens carries that title, and the Week is where it lands.
  - *Share* sends the text the reading view would: `RecipeRowSupport` (Android; iOS has the same
    name) reads the recipe **by id with `byIds`, never `open`** (opening counts as a view and moves
    the row to the top), renders it with `RecipeRenderer` under the user's settings (units, liquids,
    temperature, amounts in steps and the typed decisions) at the recipe's saved servings, and
    formats it with `RecipeShareText`: plain text, no source link. The words come from the screen's
    `shareLabels`, as the recipe screen's do.
  - *Delete* asks first ("Delete "Chicken Adobo"?"; "Delete 3 recipes?" for a selection), then
    deletes for good and shows the usual **Undo snackbar**, so unlike the recipe screen's own delete
    (no undo: its screen is gone) the dialog says "You can undo this for a few seconds"
    (`delete_row_body`, `delete_rows_body`). A swipe still deletes at once, as before.
  - *Remove from this list* **is not a deletion** and asks nothing: the recipe stays in Recipes,
    under Not in a list, and no snackbar says so (snackbars are only for undo). **This changes the
    older "removing a recipe from a list is deliberately not offered on the detail screen"**: the
    owner asked for it, only there and only through a row's menu or a selection; membership is still
    edited in the sheet everywhere else.
- **Select** is in the long-press menu (that row ticked) and in the header on Recipes and a view
  (beside the +; a view has no +), and in a list's overflow menu. (With `recipesTopB`, #449, the
  library's Select is in the title row's ⋮ menu instead, and selecting gains Select all.) Selecting is **ViewModel state**
  (`RecipeSelection`, shared by `RecipesViewModel` and `ListDetailViewModel`), so it survives
  rotation; it is not in `remember`. While selecting: every row leads with a checkbox and a tap
  ticks (the whole row is one checkbox for TalkBack); swipes, the long-press menu and the row's
  bookmark button are off; a bar replaces the header (Android: the Scaffold's top bar, the list's own
  screen a column above the rows): "N selected" (announced as it changes), **Done**, then **Add to
  list**, **Remove from this list** (a list's own screen) and **Delete**, each needing one ticked.
  Back leaves selecting first (Android `BackHandler`); iOS has Done.
- **What an action acts on is the ticked rows still listed** (`RecipeSelection.effective`): a
  search that hides a ticked recipe takes it out of the count and out of every action, and it is
  ticked again when the search clears. Nothing out of sight is deleted or moved (the Pantry's rule,
  #258). The selection starts empty from the header's Select, has no Select all (not asked for; a
  follow-up) and ends with Done, Back, or any action that completes (a delete, a Remove).
- **Add to list for several** reuses the one sheet (`SaveToListViewModel.setRecipes(ids)`): a list
  holding all of them is ticked, one holding some is **indeterminate** (spoken "Some of them",
  `save_to_list_partial`), none unticked; a tap on a ticked one removes all of them from it, a tap on
  any other adds all (so an indeterminate list fills). Each tap is one write, as ever (no Save). "+
  New list" creates the list with all of them in it, in one transaction, and the note under the field
  counts them ("3 recipes go into the new list.") instead of naming one.
- **One confirmation, one transaction, one Undo.** A selection's Delete is one dialog, then
  `RecipeRepository.deleteAll(ids)`: every recipe in one Room transaction (`RecipeDao.deleteAll`;
  `delete(id)` is now the one-id case, so there is one code path), reading what each took first. All
  of it joins the burst's one snackbar ("3 recipes deleted"), a swipe made meanwhile included, and
  **Undo restores all of it through `restoreAll`**, so **#345's free-tier rule holds unchanged**: if the
  restore would take a free library past its limit, none comes back, the deletes stand and the screen
  says "Couldn't restore: your library is full". The burst bookkeeping (captured rows, batch, pending
  titles, refusal) moved out of `RecipesViewModel` into `RecipeDeletions`, which a list's detail uses
  too. Deleting an original and its versions together leaves no dangling link (the same
  `clearOrphanedLinks` as a single delete; an undo links them again).
- **Membership in bulk** is `ListRepository.setMembershipAll` (one transaction: add is the same
  INSERT-or-IGNORE as `addToList`, so a recipe already in keeps its `addedAt`; remove deletes the
  rows), `createListWith` (the list and all its members together, or neither) and
  `observeMemberCounts` (list id to how many of the recipes it holds, for the tri-state). A recipe
  that is gone makes the whole write fail, leaving nothing half done (`ListDaoBulkTest`).
- **Code (Android).** `ui/common/RowActions.kt` (`RowActionSet`, `RowWithMenu`, `RecipeRowMenu`,
  `recipeRowClicks`, `ConfirmDeleteRecipesDialog`), `ui/recipes/` (`RecipeSelection`,
  `RecipeDeletions`, `RecipeRowSupport`, `SelectionBar`), the row composables `LibraryRow` and
  `RecipeRow` (a long click and `selecting`/`selected`), `SaveToListViewModel.setRecipes`,
  `ListDetailViewModel` (now also given `RecipeRepository` and `AppPreferences`).
- **iOS differences.** The menu is a `.contextMenu` (`UI/Recipes/RecipeRowMenu.swift`, with the
  delete alert, the sheets and the selection bar); `RecipeDeletions`, `RecipeSelection` and
  `RecipeRowSupport` are the same collaborators beside `RecipesViewModel`. The row's checkbox is
  always in it, zero wide when not selecting (#203, #216: a row's structure never changes with its
  state). While selecting the navigation bar shows "N selected" and Done (the back button is hidden,
  so Done is the way out), the tab bar is hidden and the actions sit in a bar at the foot of the
  screen. With `rowActions` off a held press on a row opens the recipe, as a tap does. Repository
  writes are one SQLite transaction each (`ListDao.setMembers`, `createWith`, `deleteAll`), the
  rollback of a failed write is a unit test.
- **Tests.** Android: `RecipesRowActionsViewModelTest`, `ListDetailRowActionsViewModelTest`,
  `SaveToListSeveralViewModelTest`, `DefaultRecipeRepositoryBulkDeleteTest`, `ListDaoBulkTest` (real
  SQLite: all or none), `RecipesRowActionsScreenTest` and `ListDetailRowActionsScreenTest` (the menu,
  Select, Back, rotation, the one confirmation and the one Undo, the free-tier refusal, the
  indeterminate row), over the hand-written fakes.
  iOS: `RecipeRowActionsTests`, `SaveToListSeveralTests`, `RowActionsDataTests` (all or none, the
  free-tier undo refusal for several) and `RowActionsUITests` (long press, select, delete, Undo).

## Plan my week: meals, a start day, and your own recipes (#392, #367, 2026-11-20)

Ask an AI's **Plan my week** (#353) asked only for seven dinners of new recipes. With the
`planMyWeekPlus` flag (on by default; off, the sheet, the prompt and the review are exactly as
before) the cook chooses the meals, the first day, and where the recipes come from, on both
platforms. **Free for everyone** (#413 gated it as a Pro extra; the owner took that back on
2026-11-23: Pro is kept for future AI features, so there is no lock, no badge and no Pro screen here).

- **The sheet** (chosen *Plan my week*, `PlanOptions`; iOS the same): **Meals**, the cook's meal
  types as multi-select chips (`meal_types`, their order, current names; **Dinner ticked** at
  first; at least one, at most `MAX_RECIPES / PLAN_DAYS` = 10); **Start on**, the Add to plan day
  strip (today and the next 13); **Recipes**: *New recipes* (today's), *From my recipes* (all of
  them, or one list, chosen from chips of the lists that hold a recipe) or *A mix* ("Up to N of
  mine, the rest new", a stepper, 1 to the week's meals, 3 at first); then switches for *Use my
  favourites as ideas* and *Don't repeat what I cooked lately* (both on, as the prompt always did;
  neither shown for From my recipes, where they make no sense). The **hint** under the row follows
  the source ("New recipes for the meals and days you choose, shaped by what you like" and so on;
  flag off, the reworded `ask_ai_kind_plan_hint`, "A week of new dinners, shaped by what you
  like": the old line read as favourites only). Copy and Share are off with no meal ticked, and
  with From my recipes or a mix when the library (or the list) has no recipe.
- **Remembered:** only the ticked meals, by `MealType.key` (a seeded type's `builtInKey`, else its
  `uid`; never the name, which changes on rename), in `unit_preferences` / `UserDefaults` under
  `plan_my_week_meals`, comma-joined (`PlanChoices`, its own interface like `UseUpLog`). A key
  whose type is gone is dropped; with nothing left, Dinner. **Not remembered:** the start day
  (it never silently starts in the past; closing the sheet puts it back on today), the source,
  the list, the count, the switches.
- **The prompt** (`AskAiPrompt.planMyWeekPlus`, `PlanChoice`): "Plan one recipe for each of these
  meals (Breakfast, Dinner) on each of these days:", the `PLAN_DAYS` days from the start day as
  `YYYY-MM-DD (Weekday)`, then (for the cook's own recipes) "My saved recipes, one per line as
  name | link:" with each recipe's name and **`sourceUrl`**, most recently viewed first, **capped
  at 40** (the sheet says "Sending 40 of your 112 recipes, the most recently viewed first"; the
  tour's sample is never named), and the instruction: *From my recipes* "Use only my saved
  recipes… don't write them out: give just @type "Recipe", its "name", its link exactly as listed
  in "isBasedOn", and its day and meal" (so no fields, units or oven temperature are asked for);
  *A mix* "Use up to N of my saved recipes… suggest new recipes for the rest… write every new
  recipe out in full". The reply format asks for one object per meal per day, day by day, each
  with **`"day"` and `"meal"`** (the meal's name exactly as listed: the cook's current name).
  Words are `ai_prompt_*` strings in six languages, `AskAiWordsTest` pins the English ones.
- **A long answer:** more than 14 recipes to write out (`LONG_ANSWER`; saved ones are named, not
  written, so From my recipes never warns) makes the sheet say chat apps may cut the answer off;
  the cut-off road (#385) already copes.
- **Reading** (`MealPlanImport`, `JsonLdRecipeParser.parseEach` with `deep`): each recipe keeps its
  `meal` text (`Item.meal`; a wrapper's `meal` is carried down as its `day` is). **An object that
  names a saved recipe by its link and carries no ingredients or steps is `Item.link`** (the
  link is `isBasedOn`, a thing's `@id`/`url`, else for a Recipe-typed one `url`; it needs a name;
  `Item.recipe` is then only a name): never a recipe. An object with a body is a new recipe
  whatever it links. A `meal` counts like a `day` to tell a plan from a changed recipe (#354).
  `MAX_RECIPES` is **70** (ten meals a week; was 31), at most 100 000 characters read
  (`MAX_LENGTH`; `SharedText`'s 50 000 still holds for everything else), 200 JSON values tried.
- **The review:** every row shows its **day and meal**, both changeable before Save (a meal
  button over the cook's types). The meal is `MealType.idForMeal`: the type whose *current name*
  matches the answer's `meal`, case and spaces ignored (the first in the cook's order when two
  share a name), else **Dinner**; never guessed from the recipe. A link-only object is matched
  to the library by `SavedLink.find` (the link as written, else cleaned by `UrlCleaner`, as a
  version's original is found, #354): found, the row says **"Already in your recipes"** under the
  library's own title, takes **no room under the free limit**, and **Save plans that recipe on its
  day and meal, never saving it again**; with no day it asks for one and Save skips it. Not found:
  **left out, never invented**, and the review says "Left out, not found in your recipes: N".
  A new recipe is saved as from a chat as before (the one `addManual`, so the free limit holds).
  With the flag off a link-only object is left out silently and every recipe is planned under
  Dinner.
- **The cut-off follow-up** (#385's `CutOffMessage`) names **day and meal pairs** when the answer
  carried meals: the block of seven days from the earliest day read, the meals *the answer
  named*, the pairs no whole recipe is on ("2026-11-21 (Breakfast), 2026-11-21 (Dinner)…"). A meal
  the answer never reached is unknown to the review, so it cannot be named; with no meal read, no
  readable day, or nothing missing it is the days (or "the remaining days") as before.
- **Code.** Core: `AskAiPrompt` (`PlanSource`, `PlanCandidate`, `PlanChoice`, `candidates`,
  `recipesToWrite`), `MealType` (`uid`, `key`, `chosenKeys`, `idForMeal`), `SavedLink`,
  `CutOffMessage.forPlanMeals`. Android `AskAiViewModel` (takes `MealPlanRepository`,
  `PlanChoices`, `FeatureFlags`), `PlanOptions`, `MealPlanReviewViewModel` (takes the flags);
  iOS the same names. Strings: 31 new keys in six languages on both platforms.
- **Tests.** `AskAiPromptTest(s)`, `MealPlanMealsTest(s)` (reader, meal mapping, cut-off pairs,
  keys), the corpus's `PlanText(...)` rows (generated from the Kotlin; `Meal(...)` gains the
  meal and link when it has them), `PlanMyWeekViewModelTest` and `AskAiViewModelTests`,
  `MealPlanReviewMealsTest` and `MealPlanReviewTests`, `PlanMyWeekSheetTest` and
  `MealPlanReviewScreenTest`.
- **Not here:** the pantry (#363) and food profile (#364) toggles (neither exists to read yet),
  and any Pro gate (#413; the choices were Pro's from 2026-11-21 to 2026-11-23, now free).

## Pro: unlimited recipes free, a one-time unlock for the Week extras (#413, 2026-11-21)

The owner's decision (2026-11-19, issue #413, with the 2026-11-20 comment): **unlimited recipes,
free, forever; no ads, no account**; a **one-time "Pro" unlock, never a subscription**, first holding
the Week planning extras and, **since 2026-11-23, none of them: those are free, and Pro is kept for
future AI features** (see "What is gated"). Behind the **`proUnlock`** flag (on by default in debug and release, both
platforms); off, the app is exactly as before (the free tier of #107 and #345 when `freeTier` is on,
every extra free).

**How the three flags combine.** `proUnlock` on overrides `freeTier`: the library is limited as if
`freeTier` were off (`LibraryLimit.of(..., proUnlock = true)`), so with `oneCookbook` on (the default)
it is `Unlimited`, and with `oneCookbook` off it is the old `History(50)` clean-up of unprotected
recipes (that clean-up is `oneCookbook`'s own flag-off behaviour, not the free tier's; never the free
20). `freeTier`'s Settings row ("Unlimited recipes") and the Recipes count are hidden; the Pro row
takes its place. `proUnlock` off: `freeTier` and `oneCookbook` decide exactly as in #107 and #345
(`Capped(50)`, `Free(20)`, `History(50)` or `Unlimited`), and nothing is gated.

| proUnlock | freeTier | oneCookbook | Library | Week extras |
|---|---|---|---|---|
| on | any | on | unlimited | free (Pro adds nothing today) |
| on | any | off | old clean-up of 50 unprotected | free |
| off | off | on | unlimited | free |
| off | on | on | 50, shown not saved (unlocked: unlimited) | free |
| off | on | off | 20, swap the oldest (unlocked: unlimited) | free |
| off | off | off | old clean-up of 50 | free |

- **The limit is gone on every way in** through the one value: `RecipeDao.upsert` (a link, Reddit,
  Cloudflare's `importPage`, a clip, typed, scanned, photo-read and shared-text recipes through
  `addManual`), `BackupMerger` (a backup, a sent file, the Paprika, Recipe Keeper and Mealie imports),
  `restoreAll` (so no free-tier undo rule), the meal-plan review's room, and the iOS share extension,
  which reads the App Group mirror `LibraryPolicy` keeps (it now says "unlimited"). The "Not saved"
  bar, the "library full" dialog and the extension's card simply never fire. No code path was removed:
  the flag turns it all back.
- **The purchase is #107's, re-pointed, not a new one.** #107 built real store code: Google Play
  Billing 9.1.0 (`PlayBillingEntitlements`: one-time product, acknowledged purchases, pending
  purchases, a launch-time re-check, a cached answer in the `entitlements` prefs file) and StoreKit 2
  (`StoreKitEntitlements`: `Transaction.updates`, `AppStore.sync()` for restore, a cached answer in
  `UserDefaults`, the local `RecipeClipper.storekit` with a placeholder price). The product id stays
  **`unlimited_recipes`** on both stores, so **a cook who unlocked under #107 is Pro** with nothing to
  migrate (the same entitlement, the same cache). No SDK or product id was added.
  - **One place per platform reads Pro:** `ProAccess` (`DefaultProAccess` over the flag, the store and
    Developer settings' override; Android a `Flow<ProState>` the ViewModels collect, iOS an observable
    `state` the ViewModels' computed properties read). `ProState` has `gated` (the flag), `isPro`,
    `pending` and the store's `price`; `extras` is `!gated || isPro`, `locked` its opposite.
  - **Developer settings' "Unlocked" switch is now "Pro (unlocked)"** (the same `override.unlocked`
    key, so Reset clears it): on, Pro counts as bought with no store. It can only add Pro; a real
    purchase on a test account can't be switched off from there.
  - **Release setup still to wire** (the owner's, #18 and #22): the one-time product in Play Console
    and the non-consumable in App Store Connect, both `unlimited_recipes` (display name "Pith Pro";
    price is the owner's call, never written in the app), **Family Sharing turned on** for the iOS
    product, and the Play license testers. Restore purchases is already there on both (Play's fresh
    `queryPurchasesAsync`, `AppStore.sync()`). Until the products exist a purchase answers "Couldn't
    reach the store".
- **The Pro screen** (Android `ui/pro/ProScreen` with `ProViewModel`, iOS `UI/Pro`): Settings' Pro
  page (`settings/pro`; iOS `Route.settingsSection(.pro)`), **listed only while `ProExtras.all` isn't
  empty (it is empty: no row, no screen)**. The badge, then "Pith Pro", "Extras for planning your week.
  Everything else in Pith stays free.", the extras each with a line (`ProState.available`), "One-time purchase, never a
  subscription.", **Unlock Pro** (the store's price after it, "Unlock Pro for €3.49", only once the
  store has given one; no figure otherwise), **Restore purchases**, the pending or failed line (#107's
  words), and "Free. No ads. No account. No subscription." Bought, the button gives way to "Pro is
  unlocked. Thank you!". The Settings index row says "Unlocked" when Pro.
- **The badge** (`ProBadge` on both): "Pro" in the accent (tertiary / `accentText`) inside a
  thin accent outline at half strength, 11pt semibold Instrument Sans, no fill: quiet, no Material purple. It
  sits after a locked extra's own label; a locked extra with no button of its own gets one muted line
  with the badge (`ProTeaserRow`). An iOS context menu can't draw an outline, so a locked menu item
  would say "Pro" as its subtitle. Nothing uses them today.

**What is gated.** **Nothing, today** (owner, 2026-11-23: "Leave these off Pro. Pro will be future AI
features."). Every extra #413 first gated is free for everyone, on both platforms, with no badge, no
locked line and no Pro-screen redirect: Skip (#398: today's Skip button and the long-press Skip), fresh
food (#396: Buy later and the "Swap with ..." note, on the Week, in Shop for this week and in the
meal-plan review), the evening and Tonight reminders (#397: anyone switches them on, and the evening
one's default "on" applies to everyone), and Plan my week's choices (#392, #367: the meals, the start
day and the cook's own recipes). **The machinery stays** for the first AI extra: `ProAccess`/`ProState`
(the `proUnlock` flag, the store, the product id `unlimited_recipes`, Restore purchases, Developer
settings' "Pro (unlocked)" override), `ProBadge`, `ProTextButton`, `ProTeaserRow`, the Pro screen and
its strings. Nothing reads `ProState.locked` or `extras` to gate anything today.

**One list says whether the Pro page exists: `ProExtras.all`** (Android `data/ProAccess.kt`, iOS
`Data/ProAccess.swift`), **empty**. `ProState.available` carries it, and `ProState.showsPage` (`gated`
and not empty) is the one thing that lists Settings' Pro row; with no extras there is **no Pro row, and no
screen opens the Pro page** (the `week/pro` route and every `onOpenPro` are gone; `settings/pro` is
reachable only from the row). Adding the first extra to the list brings the row, the page and its line
back; the new extra then gates itself with `ProState.locked`/`extras` and shows `ProBadge` or
`ProTeaserRow`, the way #413 did. The Pro screen's "Extras for planning your week." intro is the old
copy: rewrite it with the first extra.

**Not gated** (owner, 2026-11-20 and 2026-11-23): everything. The prep plan (#366), hosting menu (#368),
shopping help (#365), alternate icons and themes and sync were to be Pro when built (2026-11-20); the
2026-11-23 decision reserves Pro for future AI features, so those are the owner's call again.

**Strings.** 19 new keys in six languages on both platforms (`pro_*`; drafts for a native speaker in
`docs/translations.md`). "Pro" and "Pith Pro" are kept as names in every language. 10 of them went
with the gates (2026-11-23): the four extras' titles and lines (`pro_feature_*`) and the two locked lines
(`pro_teaser_fresh`, `pro_teaser_plan`); the other nine stay for the Pro screen.

**Tests.** Android: `LibraryLimitTest` (core), `ProUnlockLimitTest` (every way in over the real
policy and SQLite, flag on and off), `ProAccessTest` (the state, and that Pro adds nothing today),
`ProScreenTest` (over `FakeProAccess` with a test extra), `SettingsProTest` (no Pro row with no extras,
the row back with one, the reminders free); `SettingsUnlockTest` pins `proUnlock` off. iOS:
`ProUnlockTests` (every way in over the mirror the share extension reads), `ProTests` (Pro, the screen's
ViewModel, Settings' row hidden with no extras and shown with a `StubProAccess` extra), `ProUITests`
(no Pro row; Skip with no badge); the free-tier tests (`FreeTierViewModelTests`, `OneCookbookTests`,
`OneCookbookViewModelTests`, `RecipesViewModelTests`) pin `proUnlock` off. The free extras'
(2026-11-23): Android `WeekFreeExtrasTest`, `WhatINeedFreeTest`, `PlanMyWeekFreeTest`,
`MealPlanReviewFreeTest`, `MealReminderFreeTest`; iOS `FreeExtrasTests` and `MealReminderFreeTests`
(each extra works with no Pro and shows no badge). No migration, no pure-logic change under the corpus
(only `LibraryLimit.of`, which the corpus doesn't cover).

## Plan and Pantry settings from their tabs' menus (#412, 2026-11-21)

The owner's decision (2026-11-19): Settings stays the gear beside the Recipes title (#375), and each
area's own section is also linked from that tab's menu, the common pattern for a rarely visited
Settings. Behind **`tabSettingsLinks`** (on by default in debug and release, both platforms); off,
neither menu has the item. No new settings: the links open the very pages Settings' index opens.

- **Plan:** the menu's last item, **"Plan settings"** ("Reminders about the meals you plan"), opens
  Settings' Plan section (`SettingsSection.WEEK`: the evening and Tonight reminders of #397). The issue says "Week settings"; the tab has been Plan since #414, so the item says
  Plan, like the section's title. Settings' Plan section shows only with `eveningReminders`, so the
  link does too (`FlagValues.planSettingsLink` / `FeatureFlags.planSettingsLink`): never a link to a
  page the index hides. Shown in the month view as well (it isn't about the week shown).
- **Pantry:** the menu's last item, after a divider, **"Pantry settings"** ("Expiry reminders", #52)
  opens Settings' Pantry section, always there (`pantrySettingsLink`). iOS gives it the gear glyph,
  as that menu's other actions have glyphs.
- **Groceries:** no Groceries section exists, so no link.
- **On the tab's own stack**, so the tab bar stays with that tab selected and Back returns to the
  tab. Android: `week/settings` and `pantry/settings` (`Routes.WEEK_SETTINGS`, `PANTRY_SETTINGS`), in
  the Week and Pantry graphs, since `settings/<section>` belongs to the Recipes graph and one NavHost
  can't hold a route twice; both show the bar. (`week/pro` is gone: nothing opens the Pro screen, 2026-11-23.)
  iOS: `Route.settingsSection(.week)` on `weekPath`, and the Pantry tab got a stack
  of its own (`Router.pantryPath`, cleared by choosing the open tab again, like the others).
- **The Pantry opened from outside lands on the pantry itself**, not on its Settings page if that was
  left open: an expiry reminder's tap (its Use it up sheet, #305, is the pantry's) and items received
  into the pantry (#149). Android's `openRoute` of the Pantry tab pops to `pantry`; iOS's
  `Router.showPantry()` clears `pantryPath`. The other tabs keep their place as before.

**Strings.** Four keys in six languages on both platforms (`action_plan_settings`,
`menu_desc_plan_settings`, `action_pantry_settings`, `menu_desc_pantry_settings`; drafts in
`docs/translations.md`).

**Tests.** Android: `TabSettingsLinksTest` (the real shell over real screens and ViewModels on the
fakes: each item opens its section on its tab, Back returns, the tabs keep their place, the reminder's
tap lands on the pantry, no item without a link, the flag's defaults). iOS: `TabSettingsLinksTests`
(the flag, the Pantry's stack, the reminder's tap) and three tests in `TabShellUITests` (each item
opens its page on its tab and Back returns; no item with the flag off).

## Recipe tips: HowToTip kept apart from the steps (#432, 2026-11-21)

Found by the 2026-11-21 site check-up: kingarthurbaking.com lists its tips after the method as
schema.org `HowToTip` entries in `recipeInstructions` (Mini Apple Pies: seventeen HowToSteps, then a
crumb-topping variation, a spice substitution and a baking-stone tip), and the parser read every
entry as a step, so cook mode ended on three steps that weren't. `stepNoise` (#120) only trims the
end of the last step, so a site rule could not fix it. The owner's decision: **keep them as tips**,
apart from the steps, below the method, unnumbered, and out of cook mode. Behind **`recipeTips`**
(on in debug and release, both platforms).

- **Parsing** (`JsonLdRecipeParser`, both platforms, pinned by the corpus's `Parse` rows, which now
  end in `tips:` when there are some): an entry whose `@type` is `HowToTip` (any case), at the top
  level or inside a HowToSection, is a tip; its text is read exactly as a step's (`text`, else
  `name`, through `jsonText`), never rewritten. Tips are not steps, so the rule stands: a recipe
  needs a name plus ingredients or steps, and one whose instructions are only tips has none. A
  condensed duplicate section ("Abbreviated Recipe") that would have emptied the steps is put back with its tips
  read once. Microdata, Reddit, the splitter and the importers have no tips. A `HowToStep` with
  text of its own that also nests a `HowToTip` is still one step; one with no text reads its
  `itemListElement` (#436, panelinha.com.br), and a `HowToTip` there is a tip.
- **Storage:** `recipes.tips`, a JSON list like `instructions`, `NOT NULL DEFAULT '[]'` (Room 19 →
  20, `MIGRATION_19_20`; iOS `user_version` 18 → 19, `addTips`). **Stored recipes are not rewritten:**
  a King Arthur recipe saved before keeps its tips as steps until it is shared again, and the
  re-share then refreshes it (ticks stay while the ingredients are the same). A change in the tips
  alone keeps the cook's progress: step indexes still mean the same steps.
- **Cook progress across the split** (owner, 2026-11-21, a follow-up to #444): that refresh changes
  the steps, but only by their last ones becoming tips, so **the progress is kept**. Exactly: the
  old steps equal the new steps followed by the new tips (same order, compared as stored, like the
  "steps unchanged" check) and the new tips aren't empty (`CookProgress.keptWhenTipsSplitOff`, in
  `:core` and iOS `Data/Model`, called from `RecipeDao`'s `keepingUserState` on both platforms, so
  a re-share, an edit and the iOS share extension alike). The saved progress is then clamped to
  the steps left (`clampedTo` / `clamped(to:)`): done marks and timers on a former tip row are
  dropped (a dropped running timer's alert finds no timer: Android's receiver re-checks the
  database; iOS's pending alert is squared when the recipe opens, as after any dropped progress),
  and a current step past the end moves back to the last step; nothing else changes. No steps
  left, no progress. Every other change of steps still drops it.
- **Reading view:** after the steps (and before Versions and the note), a muted "Tips" heading in
  the Versions style, then each tip as a plain paragraph: no number, no step amounts, no ingredient
  links, no temperature conversion (the text stays as the source wrote it). **Cook mode never shows
  them**: its steps, its "Step 1 of N", its timers and its progress are the steps alone.
- **Flag off:** a freshly parsed recipe's tips are its last steps, as before (the repository folds
  them in, `Recipe.withTipsAsSteps`, for a fetch, a rendered page, a page shared from Safari, the
  iOS share extension through the mirrored `recipe_tips_on`, and a chat's JSON in the editor or the
  meal-plan review). Tips already stored still show.
- **Everything that carries a recipe:** the export file and a shared file write `tips` only when
  there are some (older files, and recipes with none, have no key and read as none;
  `backup-v1-tips.json` is the shared fixture), and `BackupMerger` passes them through; shared text
  and the printed card put them after the steps under the heading, unnumbered (the text's heading in
  capitals, like the other two); Ask an AI's recipe JSON writes them after the steps as `HowToTip`
  objects, so a changed version (#354) comes back with them as tips (the prompt doesn't ask for
  tips; absent is fine). Search doesn't read them.
- **The editor (the simplest honest thing):** a recipe that has tips opens with a "Tips" box under
  the steps, one tip per line, saved as typed (empty lines dropped; a blank box is no tips). A
  recipe without tips, a typed-in one included, has no box, so nothing new to explain; tips are
  never what makes a draft valid. A chat's HowToTip fills the box the same way.
- **Tests:** `KingArthurPageTest` / `KingArthurPageTests` over
  `shared/fixtures/pages/site-kingarthur-mini-apple-pies.html` (the saved page's one JSON-LD block,
  verbatim); the parser's HowToTip tests; `RecipeTipsTest` / `RecipeTipsTests` (share text, print,
  the AI's JSON, the draft, the backup fixture); `DefaultRecipeRepositoryTipsTest` /
  `RecipeTipsDataTests` (the flag both ways, a re-share of a recipe saved with its tips as steps
  keeping its progress, clamped when it pointed at a former tip row, still dropped when a real step
  changed or with no tips, progress kept when only the tips change, an edit, and on iOS the
  18 → 19 migration); `CookProgressTest` / `CookProgressTests` (the comparison and the clamp);
  `EditRecipeTipsViewModelTest` / `EditRecipeTipsTests`; `RecipeTipsScreenTest` (Robolectric: the
  section, and none of it in cook mode); `MigrationTest.migration19To20…` (device only).

## Pith (#414, 2026-11-20)

The app is **Pith**, subtitle **"Skip the story. Cook."** (owner, 2026-11-19), with the
1 Ribbon icon (#360). The Week tab is **Plan**, in the same change: the rest of the app
already said "plan" (Add to plan, Plan my week, Remove from the plan). Before shipping (the
owner's): the pending PITH trademark application (USPTO 50168836) and the domains
(pithrecipes.com, pith.recipes). The store text is in `docs/store-listing.md`.

- **Pith is the name; where the app is offered as an action it says what it does** (owner,
  2026-11-20: "Pith" alone didn't say it). Android's link/text share target and text-selection
  entry are `share_target_label`, "Clip recipe to Pith"; iOS's share extension is "Clip Recipe"
  (`RecipeClipperShare/InfoPlist.xcstrings`), under the Pith icon. Six languages each.
- **What the cook sees changed.** Android `app_name` (the launcher, the widgets' empty title)
  and `home_title`; iOS `CFBundleDisplayName` for the app and the widgets (which also name the
  Live Activity), the iCloud Drive folder's name in Files (`NSUbiquitousContainerName`; Files
  shows it after a build with a new `CURRENT_PROJECT_VERSION`), and the file type's name ("Pith
  file"). Every string that said "Recipe Clipper", in six languages on both platforms (a name
  isn't translated): the Home hint, the share extension's cards, Ask an AI's and Change a
  recipe's intros, Send as file, the backup errors and prompts, the notifications-off line.
  The file a shared recipe falls back to is `Pith.recipeclipper`; an export is
  `pith-YYYY-MM-DD.json` (or `.zip`); the automatic copy is `pith-backup-….zip`. The site report
  (English by design) says "Pith found no recipe on this page."; a calendar file's `PRODID` is
  `-//Pith//Meal plan//EN`.
- **What data depends on never changed:** the package and bundle ids
  (`com.liberopat.recipeclipper`, `.share`, `.widgets`), the App Group and the iCloud container
  ids, `recipe_clipper.db`, `unit_preferences`, every settings key, the backup format
  (`"format": "recipe-clipper-backup"`), the `.recipeclipper` extension, its MIME type and UTI,
  the `recipeclipper://` scheme, a calendar entry's `UID` (`…@recipe-clipper`, so a calendar
  that imported a week before doesn't double it), and code names (packages, classes, the
  `RecipeClipper` targets, the log tag).
- **Old automatic copies are still the app's own.** `AutoBackupPolicy` (both platforms) takes
  `recipe-clipper-backup-…` and `pith-backup-…` alike, and keeps the newest three by the time in
  the name, not the name ("recipe-…" sorts after "pith-…", so name order would keep the old
  ones forever and delete each new copy).
- **Plan.** `tab_week` is the tab, the Plan screen's title and the Settings section ("Settings →
  Plan"): Plan, Plan, Planning, Plan, Piano, Plano, each the word the language's "Add to plan"
  already used (`docs/translations.md`). The tab named as a place changed with it ("Open Plan",
  "planned on its day in Plan", "from Plan"). "Week" as a stretch of days stays: the Week/Month
  toggle, This week, Shop for this week, Save week as menu, Plan my week. Routes (`week/…`),
  `Tab.WEEK` / `AppTab.week` and their stored names, the `settings/week` route, settings keys and
  code identifiers stay `week`.
- **Tests** match the new labels (`AppShellTest`, the walkthroughs, the iOS UI tests' `tab("Plan")`
  and `buttons["Plan"]`); `AutoBackupPolicyTest(s)` pins the old prefix's copies.

## Visual identity: Market (owner's pick, 2026-11-22)

The owner felt the app looked "very much a Claude AI UI" (a serif display over a warm off-white,
one terracotta accent) and asked for a revamp. Of five directions (private artifact
https://claude.ai/artifact/C2M4o4HiQSShy7B5uK8VX6, source
`~/Downloads/RecipeClipper-designs/visual-revamp/`, `revamp-A.png`) they picked **A, "Market"**:
bright, fresh and green, crisp white, basil, a lively grotesk; "a well-lit kitchen counter on a
Saturday". #455. The HTML's `.t-A` and `.t-A.dk` variables are the source of every value here.

- **Type:** **Bricolage Grotesque** (display: the screen titles, the recipe's name, section
  headings, card names, times, step numbers, the timer, the no-photo image) over **Instrument Sans**
  (body, labels, buttons). Both SIL OFL from Google Fonts; the licences are
  `assets/licenses/OFL-*.txt` and iOS `Resources/Licenses/OFL-*.txt`. **Static weights, bundled
  once per platform:** Bricolage 600, 700, 800 and Instrument Sans 400, 500, 600, 700, made with
  fontTools' instancer from the published variable files (Bricolage at optical size 24, the
  design's display sizes 19-32, and width 100; "optical size on" in the mockup, fixed at the size
  most of the type sits at). Android `res/font/bricolage_*.ttf`, `instrument_*.ttf` (`Bricolage`,
  `InstrumentSans` families); iOS `Resources/Fonts/bricolage-*.ttf`, `instrument-*.ttf` in
  `UIAppFonts` (the widgets register the four they use), picked by PostScript name nearest the
  weight asked (`AppFont.bricolage`, `AppFont.instrumentSans`, `AppFont.fontName`). The display
  slots are Bold, tracked -0.025em as the design: headlineMedium 32, headlineSmall 27 (the
  recipe), titleLarge 22, titleMedium 19 (cards); titleSmall is Instrument SemiBold 16 (rows),
  labels SemiBold, buttons Bold. Fraunces, its italic and Karla are gone.
- **Colour, light:** ground `#FFFFFF`, surface `#F2F6F2` (cards, the search field:
  `surfaceContainer`), raised surface `#E6EDE7` (steppers, a card's close button:
  `surfaceContainerHigh`), ink `#14201A`, muted `#56645B`, hairline `#E1E7E2`, basil `Accent`
  `#2C7A4A` with white on it (`primary`), `AccentText` `#25683E` (`tertiary`, the accent for
  words and icons: a shade deeper than the fill, the design's `accTx`), `AccentTone` `#E1F0E5` with
  `#1C5232` on it (`secondaryContainer`: the tab bar's indicator, the filter button with a filter on,
  the pills, the Lists cell), outline `#9AA79E` (checkboxes).
- **Colour, dark (the on-ink set):** ground `#0E1310` (`InkGround`), sheets `#151C17`, surface
  `#17201A`, raised `#202B24`, text `#EDF3EE`, muted `#9BA89F`, hairline `#28332C`, basil
  `#5DBE80` with **dark words** `#06170C` on it (the old dark scheme kept paprika with white; a light
  basil fill reads better on the green-black ground, as the design draws it), `AccentTextOnInk`
  `#7ED39C`, tone `#1B3324` with `#A9E3BC`, outline `#6B7A70`. Dark mode stays the system's call;
  cook mode follows it unless "Dark while cooking" is on.
- **Two colours the design doesn't name.** Errors and the swipe-to-delete ground were paprika,
  which doubled as the accent; on a green accent they need their own: a tomato `Danger` `#B2371F`
  (`DangerOnInk` `#F4A28C`), from the no-photo tomato tint's family (6.1:1 on white, 9.3:1 on the
  dark ground). **Lemon** `#F3C845`, in the design's palette, colours only its illustrations;
  nothing in the app calls for a second accent yet, so it is not a token.
- **Shape:** 18 for cards and panels, 14 for photos, thumbnails, fields and the timer box, 20 for
  the reading view's hero photo, 26 for a sheet's top, pills for buttons, chips and badges, 8 for
  tags, 7 for checkboxes (`Corners` in Theme.kt and Material's `Shapes`; iOS `Radius`). Rows are flat
  with hairlines.
- **Component treatments** (the design's, inside today's layouts): the Today card a soft filled panel on the surface
  (#449's outline bends back: on white it read as a form field), its close button on the raised
  surface; the search field filled, radius 14, no border at rest; the tab bar's open tab in the
  tone's deep basil (Android on a tone pill; iOS's system bar takes the colour); cook mode's current
  step a 2 dp basil ring at 18, its timer in a tone box at 14 with Bricolage ExtraBold digits; step
  numbers in Bricolage ExtraBold basil, the times row's values in Bricolage Bold; the servings
  stepper's − and + circles on the raised surface; the ingredient checkbox 22 dp, radius 7, the
  outline colour, filled basil when ticked (Android draws its own, since Material's can't take the
  radius; same semantics); the Settings rows' and the Plan's period chevrons are stroke icons
  (`ic_chevron_right` / `_left`, autoMirrored; iOS's SF Symbols), not "›" glyphs, which the new
  faces draw heavy. Every hard-coded radius moved onto the tokens (Android 108 in 49 files) and
  every literal colour onto the theme; white on a primary fill is `onPrimary`.
- **Token names describe roles now:** `Paprika`, `PaprikaTextOnInk`, `PaprikaTone` /
  `LocalPaprikaTone` (#449) and iOS `Palette.paprikaTone` became `Accent`, `AccentTextOnInk`,
  `AccentTone` / `LocalAccentTone`, `Palette.accentTone`, keeping the on-ink pattern for dark mode.
  `ThemeTokensTest` (Android) and `ThemeTests` (iOS) pin A's values in light and dark, so a
  stray revert fails.
- **Outside the app's screens:** the widgets (`widget_*` colours, a sans title; iOS `WidgetFont`
  by PostScript name), the Live Activity (Bricolage ExtraBold countdown), the notifications' accent
  (`R.color.accent`, set on every builder), the launch background (`ground`, iOS `LaunchBackground`:
  white, `#0E1310` dark), the share extension (registers the seven fonts from the app's bundle),
  print (Bricolage SemiBold headings, Bold step numbers, basil on white) and the clip view's page
  outlines (`clipper.js`, basil).
- **Not changed:** the app icon (its paprika ground stays, `ic_launcher_background`; the icon is a
  separate decision), every layout (the top of Recipes is being redrawn separately, branch
  `recipes-filters`: the mockup's "Lists 6" button beside search is that work's), and the icon set
  (Material and SF Symbols; the design's 1.8 px stroke icons would be a set of their own).
- **No feature flag** (owner's choice, an exception to #87's rule): a look half-applied behind a
  switch isn't useful, and the old look would have to be kept alive beside the new in every screen.
- **Left for later** (owner, 2026-11-23): row titles are Instrument Sans Bold 17, the design's are
  650 at 16; form fields other than search stay outlined; the cook step's "STEP 3" eyebrow is
  SemiBold, the design's 800; the icon set; the light checkbox outline `#9AA79E` is about 2.5:1 on
  white, under the 3:1 guideline for controls (A's value, kept; the old outline was no better).

## Fraction glyphs read from a photo (#245, 2026-10-13)

The owner's report: a card's fraction symbol came out as "%". Measured first, on two cards drawn
for it (`shared/fixtures/reddit/photos/card-fractions-print.jpg` in Georgia,
`card-fractions-hand.jpg` in Noteworthy: ½ cup sugar, 1 ¼ cups flour, ¾ tsp salt, ⅓ cup milk,
2 ⅔ cups oats, ⅛ tsp nutmeg, and 2 oz 70% chocolate as a real percentage):

| Glyph | Vision, iOS 27 simulator: print / hand | ML Kit (Play services), emulator: print / hand |
|---|---|---|
| ½ | ½ / ½ | "/2 cup" / ½ |
| 1 ¼ | 1¼ / 1¼ | "1 Y4 cups" / "1 a cups" |
| ¾ | "3¾4 tsp" / "3/4 tsp" | "/4 tsp" / "4 tsp" |
| ⅓ | "1/3 cup" / "½⅓ cup" | "3 cup" / "Ys cup" |
| 2 ⅔ | "2 2/3 cups" / "2% cups" | "2 23 cups" / "2 % cups" |
| ⅛ | "8 tsp" / "is tsp" | "Vs tsp" / "& tsp" |
| 70% | 70% / 70% | 70% / 70% |

Vision gave every line 1.0 confidence and ML Kit 0.52 to 0.84, so none was under "Check these
lines" before. macOS Vision differed again ("12 cup" for ½, "34 tsp" for ¾, "&" for ⅛).

- **Nothing is normalised.** No look-alike maps to one glyph: "%" was ⅔ here and is a real
  percentage elsewhere; "/4" may be ¼ or ¾. CLAUDE.md's rule: never a confident wrong number.
- **Flagged** (`PhotoTextSorter.check`, both platforms): a measured look-alike
  (`shared/tables/photo-fractions.json`: `%`, `&`, `/2`, `/3`, `/4`, `/8`, `Y4`, `Ys`, `Vs`, `is`,
  and `a` only after a number) in an amount's place: at the line's start or after a whole number
  there, and then only before a unit, unless a symbol starts the line. So "2% milk",
  "70% chocolate", "2 % Milch" and "1/2 cup" aren't flagged. Also a glyph run into a digit or
  another glyph ("3¾4", "½⅓"), which no recipe writes.
- **One-tap chips:** under a line with a look-alike, "A fraction? Tap the one in the photo:" and
  ½ ¼ ¾ ⅓ ⅔ ⅛ (the table's `fractions`), which replace the look-alike in place in the box and in
  the list ("2 % cups oats" → "2 ⅔ cups oats"); its chips then go. The cook can still type. Each
  chip is named "Use ⅔ in 2 % cups oats" for TalkBack and VoiceOver.
- **Never scaled or converted while it holds one:** `IngredientScaler`'s `unreadable` takes
  `FractionLookAlike.slot`, so "1 a cups flour" isn't doubled to "2 a cups flour" (it was).
  "2% cups oats" already stayed, through the percentage rule.
- **Not caught:** a glyph dropped or read as digits alone ("12 cup sugar", "4 tsp salt",
  "8 tsp nutmeg", "2 23 cups oats") is a plausible amount, so it can't be flagged without
  guessing; the review's note to check every amount against the photo is the guard. Follow-up:
  #249.
- **Tests:** `PhotoTextSorterTest(s)` and `IngredientScalerTest(s)` on both platforms; `Check` (`Sus` until #283),
  `Frac` and `Ing` rows in the differential corpus; `EditRecipePhotoScreenTest` and
  `PhotoTextUITests` tap a chip and save. The engines: `EditRecipeScanTests` runs real Vision over
  both cards (printing `FRACTION-OCR` lines), and the device-only `MlKitFractionGlyphsTest` runs
  ML Kit (push the cards to `/data/local/tmp` first; logcat tag `FractionOcr`).

**2026-10-16 (#276, after #247 and #249).** Clip 34 showed what was still missing. Vision read the
handwritten card's ⅓ as "½⅓ cup milk": flagged, but with no chips, since chips replaced only a table
look-alike. And ML Kit's "½ oup sugar" was saved as read, unflagged.

- **Chips for glyphs run together** (`FractionLookAlike.gluedRun`, both platforms): a fraction glyph
  straight before a digit or another glyph, and the run is every digit and glyph touching it
  ("3¾4", "½⅓", "1½⅓4"). A chip replaces the whole run ("3¾4 tsp" → "¾ tsp"); a whole number set apart
  by a space stays ("2 ½⅔ cups" → "2 ⅔ cups"), and so does one glued before the run's first glyph,
  set apart by a space ("1½⅓ cup" → "1 ⅓ cup", "3¾4 tsp" → "3 ¾ tsp"); only those leading digits
  are a whole number, so the digit after a glyph stays in the run, and none is ever invented.
  *Owner's call, 2026-10-16: keep the leading number* (#276 first dropped it, as possibly a stray
  stroke). `PhotoTextSorter.fractionSlot` is the
  look-alike slot, else the run. Still never replaced by itself, and the scaler leaves a line with
  a run as written (it is `unreadable`), as it does a look-alike.
- **A unit's near-miss is flagged** (`PhotoTextSorter.check`): a word straight after a line's
  leading amount that is in the language's `splitter.json` `unitLookAlikes`, a short curated list
  ("oup", "cuo", "c up", "tsb", "tps", "tbps", "tbsb", "isp"; "oucharada", "ouillère", "oucchiaino",
  "oolher", German "e l" and "t l"), never edit distance, so "oz", "g", "ml", "T" and "cupcake" never
  trip. Flagged only, never corrected, and with no chips.
- **Still not caught, by decision:** a misspelt ingredient name ("mik") and a lost fraction that
  reads as a plausible amount ("4 tsp salt" for ¾; Vision's "34 tsp", "23 cups", "18 tsp") are
  #249's guessing territory.
- **What the engines read this time** (clip 34): ML Kit on the emulator, "½ oup sugar", "1 a cups
  flour", "4 tsp salt", "Ys cup mik", "2 % cups oats", "& tsp nutmeg" (chips on four lines, the first
  flagged too); Vision on the simulator, "½ cup sugar", "1¼ cups flour", "34 tsp salt", "½⅓ cup milk",
  "23 cups oats", "18 tsp nutmeg" (chips on "½⅓ cup milk" only). Both clips now use the real reader
  and type the unflagged slips right before saving.

**2026-10-17 (#249). Owner's call: flag only, suggest nothing, correct nothing** (option 2). A
fraction glyph read as plain digits ("34 tsp salt" for ¾, "23 cups oats" for 2 ⅔, "18 tsp nutmeg"
for ⅛, ML Kit's "2 23 cups oats") is listed under "Check these lines" when the amount is more of
its unit than a recipe asks for. No chips, no suggestion, no fix, and the scaler is unaffected: the
number is still read as written ("34 tsp salt" doubles to "68 tsp salt").

- **`PhotoTextSorter.implausible`** (both platforms, part of `check`, `suspect` until #283, so photo and scan reviews
  only, never a parsed web recipe): the line's leading amount and unit, read as `IngredientScaler`
  reads them (the unit word mapped by the language's `units.json`, so it works in every language
  with units), against `shared/tables/plausible-amounts.json`. Either end of a range counts
  ("10–14 tsp"); a whole number then another before the unit ("2 23 cups") is judged by the second,
  so "2 14 oz cans" stays fine. A temperature ("180 C water"), Japanese (amounts after the name)
  and a unit the table doesn't list are never checked. The existing heading's wording covered it,
  with no new string, until #281 gave it its own (below).
- **Maximums** (above is flagged): tsp 10 (12 at first; lowered by the owner on 2026-10-17 so "12 tsp", a ½ read as digits, flags, with no real line hit), tbsp 16, cup 12, fl oz 64, stick 8, ml 5000, cl 200, dl 40, l 10, g 5000, kg 10, oz 64, lb 25. tbsp started
  at 8 and rose to 16: "16 tablespoons (227g) unsalted butter" (a cup, Sally's) and "10 c. à s. de
  sirop" are real.
- **Calibration:** every ingredient line of the 250 saved pages that parse (the harness pages in
  English and five other languages, `shared/fixtures/pages/`), plus the corpus `Ing` inputs and the
  fixtures' amount lines: 3,402 lines, none real flagged (the one hit is the corpus's
  "99999999999 cups flour"). Largest real amounts: 10 cups, 5 tsp, 16 tbsp, 36 oz, 3 lb, 2 kg,
  2000 ml, 1.5 l, 50 cl.
- **Still not caught, by decision** (corrected 2026-10-18, #281): a misread that stays
  plausible: "4 tsp" (¾ lost) and "8 tsp" (⅛); a fraction read as digits where its unit has
  no lower bar ("12 oz" for ½ oz, "12 fl oz", "14 g"), since those are everyday amounts; and a
  number after a range, fraction or decimal ("1–2 34 tsp"), which isn't judged. "12 cup" (½)
  is caught since #281. **Bare counts** ("12 eggs", "48 wonton wrappers") are skipped: counts
  vary too much to call one absurd, and pinch or clove, which the unit tables don't read, have
  no entry.
- **Tests:** `PhotoTextSorterTest(s)` on both platforms (the misreads caught; a big batch, decimals,
  ranges, counts and temperatures not), and `Check` rows (`Sus` until #283) in the differential corpus.

**2026-10-18 (#281): review fixes, and two owner decisions.** A code review of #279 and #280:

- **Ingredient lines only.** The maximums were calibrated on ingredient lines, but the check ran
  on steps too, and on every line of an unsorted reading, so a conversion line ("48 tsp = 1 cup")
  was flagged. Sorted, only the ingredient lines are size-checked; unsorted, each line is
  cleaned by the splitter's own line rule first (`RecipeTextSplitter.line`, so "▢", "•", "-",
  "3)" go and "▢ 34 tsp salt" is caught), and a "Step 4" line or one holding "=" is skipped.
  The other shapes (look-alikes, glued and misspelt units) still ran on every line as read;
  since #283 every check runs on the cleaned line, and steps shaped like an ingredient line are
  size-checked too (below).
- **The second number** ("2 23 cups") is taken only after a plain whole number, never after a
  range, fraction or decimal ("1–2 34 tsp" keeps its "1–2" and isn't judged); and the
  temperature exemption tests the number judged, not the lead ("2 180 C water" is a
  temperature; "180 14 c milk" isn't). This is where `implausible` reads a line differently
  from the scaler, which reads only the first number; the KDoc says so.
- **A malformed table fails on iOS too** (`PlausibleAmounts`, both platforms): iOS fell back to
  no maximums, switching the check off, where Kotlin threw. It now traps, as `SharedTables`
  does; Kotlin throws. Both refuse a unit that isn't a `MeasureUnit`, a value that isn't a
  number, and a fraction-shaped bar not below its maximum.
- **The table is read at the first size check** (`by lazy`), not when `PhotoTextSorter` loads:
  the editor reads `PhotoTextSorter.FRACTIONS` on every open, typed-in recipes too.
- **`CUP_200` and `RICE_CUP` are gone** from the table: only Japanese names them, and Japanese
  (amounts after the name) is never checked.
- **`suspect` (`check` since #283) finds the leading amount once** per line and hands it to every shape.

**Owner's call (A): a reason of its own.** A line listed only because its amount looks too large
(`PhotoReading.large`; since #283 a `LineFlag` whose only reason is `LARGE`) shows "This amount
looks large: check it" under it, a muted note like the chips' prompt, in all six languages on
both platforms. The heading "Check these lines: the photo was hard to read there" shows only
when another line is listed, since the photo wasn't hard to read there. A line flagged for a size
and another reason (unsure, or misread elsewhere on the line: "34 tsp salt, 11/2 tsp pepper")
keeps the heading and gets no note. (Corrected by #283: this said "a misspelt unit", which can't
be both, since a misspelt unit leaves the scaler no unit to judge the amount by.)

**Owner's call (B): fraction-shaped amounts get a lower bar.** An amount written exactly as a
fraction glyph's digits (`fractionShapes`: ½ 12, ⅓ 13, ¼ 14, ⅛ 18, ⅔ 23, ¾ 34, ⅜ 38, ⅝ 58,
⅞ 78) is judged by its unit's `fractionShapedMaximums`: **tbsp 10, cup 10, lb 10**, so "12 tbsp
vinegar", "12 cup sugar" and "12 lb ground beef" flag, while "16 tablespoons butter" (16 isn't
a shape) and "10 tbsp" don't. "14 cups milk" already flagged (cup 12). The second number counts
too ("1 12 cups" for 1½). Fifths and sixths (15, 16, 25, 35, 45, 56) are left out: no
calibration page writes ⅕ or ⅙ (½ 131 times, ¼ 120, ¾ 85, ⅓ 10, ⅛ 4, ⅔ 2, the others none),
and 15 and 16 are everyday amounts. Ounces, fluid ounces, grams and millilitres get no lower
bar ("12 oz spaghetti", "12 fl oz beer", "14 g", "15 ml" are real); tsp and stick need none
(every shape is above 10 and 8 already). Everything else keeps its maximum.

- **Calibration:** the same 3,402 real ingredient lines (250 parsed pages: the scratchpad's
  `pages/` and `lpages/` and `shared/fixtures/pages/`, plus the corpus `Ing` inputs and the
  fixtures' amount lines): **none real flagged**, as before (the one hit is still the corpus's
  "99999999999 cups flour"). The only amounts in those lines written as one of the nine shapes
  are "12 oz" (5 lines) and "14 g" (6), on units with no lower bar; counting fifths and sixths
  would add "15", "16", "35", "45" g, "15", "45" ml, "25 cl", "16 oz" and "16 tablespoons (227g)
  butter", which is why they're out. The trade-off: a real "12 tablespoons
  butter" (¾ cup) or "12 cups popped popcorn" now gets a "check this" ("12–14 lb turkey" did
  too, until the owner's range decision in #283); none is in the calibration lines, and a flag
  costs only a look at the photo.
- **Caught:** "34 tsp salt", "23 cups oats", "18 tsp nutmeg", "12 tsp sugar" (as before), and
  now "12 tbsp vinegar", "12 cup sugar", "1 12 cups flour" and "12 lb ground beef".
- **Clip 34:** Android's (ML Kit) is unchanged: its reading has no line flagged only for size.
  iOS's (Vision: "34 tsp salt", "23 cups oats", "18 tsp nutmeg") now shows the new note under
  those three; "½⅓ cup milk" keeps the heading. Not re-recorded.
- **Tests:** `PhotoTextSorterTest(s)` (fraction-shaped bars, the second number, ingredient lines
  only, unsorted markup and skips, `large`, a malformed table), `EditRecipePhotoViewModelTest` /
  `EditRecipePhotoTests` (`largeLines`), `EditRecipePhotoScreenTest` and `PhotoTextUITests`
  (the note, and no heading for a size alone); new `Sus` (now `Check`) and `Photo` rows in the corpus.

**2026-10-18 (#283): a second review, and one owner decision.** A second code review of #281
(PR #282) found fifteen problems, each now a test on both platforms:

- **One list, built by the sorter.** Each line's reasons travelled as parallel lists matched by
  text (`uncertain`, `large`/`largeLines`, `fractionLines`), which both ViewModels rebuilt.
  `PhotoReading.flags` is now one list of `LineFlag(raw, shown, reasons)`: the line as it is in
  the box (which a chip changes), as the list shows it, and why (`UNSURE`, `MISREAD`, `FRACTION`
  for the chips, always with `MISREAD`, and `LARGE`). The editor's state holds that list as it
  is, and derives the heading (`checkHeading`: some line isn't only large) in its UiState, not in
  the views; a line's note shows when `LARGE` is its only reason (`largeOnly`), its chips when
  it has `FRACTION` (`chips`). A chip (`onFraction`) updates that one entry: its look-alike
  replaced, its chips gone. `uncertain` and `large` stay as read-only views of the list.
- **One per-line check, the one production runs.** `suspect`, which production no longer called
  and which disagreed with `sort`, is gone: `PhotoTextSorter.check(line, words, labelled)`
  returns a line's reasons, and the corpus's `Check` rows (`Sus` before) pin it; `Photo` rows
  pin the whole list, reasons and `large` included.
- **Sub-recipes after the method are size-checked again.** #281 size-checked only `split()`'s
  ingredient lines, so a "GLAZE", "Frosting:" or "For the sauce:" section after the directions
  (which the splitter files under the steps) lost the flag: "34 tsp vanilla" there went
  unlisted. The size check now runs on steps too, and judges only a line that starts with an
  amount and its unit, as an ingredient line does ("Stir in 34 tsp salt." is never judged).
  A "Step 4" line and a line holding "=" ("48 tsp = 1 cup") are skipped on both paths, so a
  conversion under the ingredients isn't flagged either.
- **Every check runs on the line as cleaned, unsorted too.** List markup was taken off only for
  the size check, so "▢ 2% cups oats", "• 1 oup sugar" and "- 1 cupraisins" went unlisted. Each
  line is now cleaned once (the splitter's own `line`, kept by `RecipeTextSplitter.splitLines`,
  which also says which line each ingredient and step came from) and every check runs on that;
  the box keeps the line as read, and a chip replaces the look-alike inside it
  ("▢ 2% cups oats" → "▢ 2⅔ cups oats"). The list shows the cleaned text, so no more
  "• ▢ 34 tsp salt" or "• • 12 tbsp vinegar".
- **A line's confidence is its own.** `marked()` matched unsure text by substring (written for
  the splitter's markup-free text, "3. Bake…" shown as "Bake…"), so a clearly read
  "34 tsp salt" counted as unsure whenever an unsure step quoted it ("Stir in 34 tsp salt.") and
  lost its note. The splitter now says which read line each line came from, so the confidence,
  and a "Step 3" label, are keyed to the line itself, and `marked()` is gone.
- **A fraction's digits are never a temperature.** The Celsius exemption tested the judged
  number, and ⅝ and ⅞ read as 58 and 78 fall in a plausible Celsius range, so "1 58 C sugar"
  and "2 78 c. flour" went unflagged. A number in `fractionShapes` never counts as a
  temperature, alone or second ("58 C sugar" flags; "180 C water" and "2 180 C water" don't).
- **The table is read strictly, the same on both platforms.** Kotlin's `getDouble` took numeric
  strings, "NaN" included (tsp then never flagged on Android); Swift's `NSNumber` took `true`
  (a bar of 1); Android's `getString` took a numeric shape. Now a maximum must be a JSON number,
  finite and above zero, and a shape a JSON string of digits, or the table is refused (Kotlin
  throws, iOS traps), as any malformed table is.
- **iOS lists a line read twice once**, as Android did (Swift skipped only lines already flagged).
- **Off the main thread.** The sort ran on the main thread in both ViewModels. Android now sorts
  on an injected `@DefaultDispatcher` (`Dispatchers.Default`; tests pass theirs), iOS in a
  detached task; the state is written back on the main thread, and a read cancelled meanwhile
  (Try again, or the screen gone) is dropped. Each line is cleaned (Jsoup) once instead of
  twice, a repeated line is cleaned and checked once, and the leading amount is read once per
  line. Measured on the JVM, a 300-line unsorted scan, `main` against this change in one JVM:
  every line different, 8.0 ms → 7.3 ms (and 120 lines listed rather than 98: the markup lines
  now caught); 20 lines read 15 times each, 4.1 ms → 3.3 ms. What's left is mostly language
  detection (about 2.2 ms) and the cleaning (1.2 ms), now off the main thread.
- **One helper for the muted notes** (`LineNote` / `lineNote`) beside the screen's `Note` and
  `Message`, used by the size note and the chips' prompt alike.
- **Stale text:** `plausible-amounts.json`'s `about` (a line listed only for its size shows its
  own note, not the heading), `DifferentialCorpusTest`'s skip message (core/, not app/), and the
  KDocs, which described `suspect`.
- **A test that couldn't fail.** "A line with another reason isn't listed as only large" used
  "34 tbps sugar", which the size check can never judge (its unit is misspelt), so the guard
  was untested. It now uses "34 tsp salt, 11/2 tsp pepper", both misread and too large: listed,
  not as only large, with the heading (checked at ViewModel level on both platforms).

**Owner's call (2026-10-18): ranges skip the fraction-shaped rule.** Either end of a range
("16–18 lb", "12–14 lb", "2 to 3 tbsp") is judged against its unit's own maximum, never the lower
fraction-shaped bar, so "16–18 lb turkey" and "12–14 lb turkey" aren't flagged, while "30–40 lb
potatoes" still is (lb 25), as are "10–14 tsp" (tsp 10) and "12–14 cups" (cup 12).

- **Calibration** (the same pages and lines as #281, now with their steps): 250 parsed pages,
  3,402 real ingredient lines and 1,931 instruction lines. **None real flagged on either side**:
  the one ingredient hit is still the corpus's "99999999999 cups flour", and no instruction line
  is size-checked into a flag (none starts with an amount over its bar). No page line is
  flagged as misread either; the 23 misread hits are the fixtures' deliberate misreads. A
  stress test, taking every amount inside a step as the start of a wrapped card line (1,715 of
  them), flags one: "12 cups of water to a boil…" (Minimalist Baker's rice), a real 12 cups
  that a card would have to wrap right before. Caught, each checked: "34 tsp salt", "23 cups
  oats", "18 tsp nutmeg", "12 tsp sugar", "12 tbsp vinegar", "1 12 cups flour", "14 cups milk",
  "1 58 C sugar", "2 78 c. flour", "34 tsp vanilla" in a glaze after the method, and
  "▢ 2% cups oats" (with chips).
- **Still not caught, by decision:** a misread that stays plausible ("4 tsp" for ¾, "8 tsp" for
  ⅛); a fraction read as digits where its unit has no lower bar ("12 oz" for ½ oz, "12 fl oz",
  "14 g"), since those are everyday amounts; a number after a range, fraction or decimal
  ("1–2 34 tsp"), which isn't judged; a fraction's digits as either end of a range ("12–14 lb",
  the owner's call above); an amount that doesn't start its line ("Stir in 34 tsp salt."); a
  "Step 4" line or one holding "="; bare counts ("12 eggs"); and a misspelt ingredient name
  ("mik").
- **Tests:** `PhotoTextSorterTest(s)` (each finding's reproduction: the glaze, frosting and
  sauce sections, markup on every check, the line's own confidence, ⅝ and ⅞, ranges, a line
  read twice, the strict table with "NaN", "10" and `true`), `EditRecipePhotoViewModelTest` /
  `EditRecipePhotoTests` (the heading, alone, mixed and misread-and-large; a chip on one entry,
  keeping its markup), `EditRecipePhotoScreenTest` (no double bullets); `Photo` rows for each
  case and `Check` rows in the corpus.
- **Clip 34**, re-recorded on 2026-10-18 with real OCR on both platforms. iOS (Vision read "34
  tsp salt", "½⅓ cup milk", "23 cups oats" and "18 tsp nutmeg"): the heading over the four lines,
  "This amount looks large: check it" under the three amounts read as digits, and the chips under
  "½⅓ cup milk", whose ⅓ makes it "⅓ cup milk"; the walkthrough types the three amounts right.
  Android (ML Kit read the card as on 2026-10-16): the heading, "½ oup sugar" with no chips, and
  chips under "1 a cups flour", "Ys cup mik", "2 % cups oats" and "& tsp nutmeg"; no line is
  listed only for its size ("4 tsp salt" stays unflagged, a plausible amount). Clips: iOS 70 s,
  Android 67 s.

## Check the recipe: the photo beside each line (#286, 2026-10-21)

The owner's pick for "Check the recipe" (#286, V2 "The photo beside the line"): each line to check
is one card, with the strip of the photo it was read from, so the cook compares the handwriting
with the text without scrolling back to the photo. The rules stay: nothing is corrected or
suggested, nothing is saved unchecked, no name is guessed, and each line keeps its reason (#283).

- **The pages** sit as thumbnails in a row at the top with "2 pages · 5 lines to check" (a post's
  pictures are "photos"; with nothing to check, the count alone), two plurals in all six languages.
  They replace the full-width pictures. **Tapping one shows it large under the row**, and tapping it
  again hides it (decided here, not in the mockup: the note still says to check every line against
  the photo, which a 56-point thumbnail can't serve). The open page is in the UiState (`openPage`).
- **Each card:** the strip, the line as a field, then its note ("This amount looks large: check
  it") or the fraction prompt and chips. "Check these lines: the photo was hard to read there"
  still heads the cards when a line isn't only large. The field is the box's line, list markup
  and all (an unsorted reading's "▢ 34 tsp salt"); the chips' spoken names use it without.
  iOS's chips are outlined, as Android's are and the mockup draws them, in one row, else two
  rows of three: the tinted buttons sat in a grid narrower than they were, which drew them over
  each other in the card.
- **Where a line was read** (`PhotoLine.page` and `.box`, optional): the picture's index, and a
  rectangle in fractions of the picture, 0 to 1 from its top-left corner, the picture the right
  way up. ML Kit's `boundingBox` is in pixels of the bitmap Coil decoded (EXIF applied by Coil,
  given to ML Kit at rotation 0), divided by its size (`PhotoBox.fromPixels`); Vision's is
  normalised from the bottom-left corner of the picture as its EXIF orientation says (the handler
  is given it), so y is flipped (`VisionPhotoTextReader.box`). The sorter carries them to each
  `LineFlag`: a caller's line split at a break gives each piece the whole line's place; the
  splitter never joins lines, so every ingredient and step is its own line's; a line read twice
  (a card's two photos) is listed once, at its first copy's place. `PhotoAt` rows in the
  differential corpus pin it. A line with no box (an old caller, a reader that gave none) gets
  a card with no strip.
- **The strip** is the box with 0.35 of the line's height around it (`PhotoBox.padded`), cut from
  the page decoded at most 1600 px a side, and only the strip is kept: Android through Coil with
  a crop `Transformation` (`CropToBox`), iOS with ImageIO (`ReviewImages`: a thumbnail with its
  transform, cropped and redrawn so the page isn't held). Not `BitmapRegionDecoder`: it reads the
  stored, unrotated picture, so it would have to redo Coil's EXIF handling to land on the box ML
  Kit saw; going through the same decoder as the read keeps them in step. Shown 28 to 56 points
  tall, as wide as the card allows; a longer line's end is cut, never its start, where the amount
  is. iOS's thumbnails are decoded small the same way (the full-width pages were decoded whole).
- **Where the pictures come from:** a scan's pages are local and never cached (#226), as before.
  A post's pictures aren't kept by the app: the strips and thumbnails read them from the image
  cache the read filled (Coil's disk cache; iOS's `ImageLoader`), and fetch them again only if
  the cache has let them go.
- **The field and the box are one line.** Typing in a card types in its box; typing in the box
  changes the card (`followLine`: the lines before and after the change keep their place; a line
  inside it keeps its place while the change still has a line there; a line deleted takes its
  card with it). An edited line stays listed with its reasons, since nothing is checked again (as
  after a chip), and loses its chips once the look-alike is gone (`PhotoTextSorter.edited`). A
  chip still on screen for a line since changed does nothing.
- **Tests:** `PhotoTextSorterTest(s)` (the place carried, padded, an edit), `PhotoAt` corpus rows,
  `EditRecipePhotoViewModelTest` / `EditRecipePhotoTests` and the scan twins (strips, the field,
  following a line, the thumbnail, `followLine`), `EditRecipePhotoScreenTest` and
  `ScanEntryScreenTest`, the Vision box flip and a strip cut from the fixture card
  (`EditRecipeScanTests`), `MlKitFractionGlyphsTest` (device: every line has a box), and
  `PhotoTextUITests` (the stub reader places each line) and `ScanUITests` (Vision over the
  fraction card: a strip above its line's field). Walkthroughs 28, 29, 30 and 34 were
  re-recorded 2026-10-22; 30 now opens and closes a page.

## Undo snackbars close by themselves (2026-09-29)

The owner saw "Checked items removed" stay on the Groceries screen, and come back after changing
tabs. On Android, an undo snackbar was shown with an action and no duration, which Material keeps up
until it's tapped; and a tab's screen that left while it showed kept its pending removal, so the
snackbar showed again on the next visit. Now every undo snackbar (Groceries, Pantry, Week, Recipes,
the pantry use-up sheet, "Removed from your cooks") goes through `UndoSnackbarEffect`: it lasts
Material's `Long` (about 10 s), and leaving the screen, but not rotating it, settles the removal as
if it had timed out. iOS's `SnackbarTimeout` already closed them, after 4 s; its undo window is now
10 s too (`SnackbarTimeout.undo`), while plain notices keep 4 s.

**2026-10-14 (#268).** The owner saw the Recipes snackbar flash and come back after Undo. Two
causes, both Android. The swipe row's state was `rememberSwipeToDismissBoxState`, which is
`rememberSaveable`: the lazy list keeps a removed row's saved state under its key, so a row
put back by Undo came back dismissed, `SwipeToDismissBox` deleted it again and a new snackbar
popped up. The row now `remember`s a plain `SwipeToDismissBoxState`. And the snackbar was keyed
on the pending list, so each swipe of a burst took it down and showed another. It is now keyed
on `deleteBatch`, which the first swipe of a burst sets and Undo or the timeout clears, and
`UndoSnackbarEffect` reads its message live, so "3 recipes deleted" counts up in the one
snackbar. The undo window now runs from the burst's first swipe on both platforms (iOS's
`.task(id: deleteBatch)`; iOS never re-deleted or flashed). The other undo snackbars show one
removal per key, so they were unaffected.

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

## A designed image for a recipe with no photo (#403, 2026-11-19)

The owner picked design 4 ("Ingredient label") from the no-photo mockups, with lettering on
small thumbnails. Both platforms, behind the `noPhotoImage` flag (on by default; off, every slot
is the blank tile or band it was).

- **The logic is `NoPhoto`** (`core` `data/model/NoPhoto.kt`, iOS `Data/Model/NoPhoto.swift`,
  pinned by `NoPh` rows in the differential corpus, regenerated from the Kotlin):
  - `initial(title, language)`: the first letter of the title, uppercased, after a leading
    article from `shared/tables/<language>/articles.json` (an entry ending in an apostrophe,
    "l'", needs no space after it). An article that would leave no letter stays ("The" is T).
    "5-Ingredient Pasta" is I. A title with no letter has none.
  - `labels(ingredients, language, max)`: up to `max` ingredient names in order, read by
    `IngredientName` (so the amount, unit and preparation are gone) and shown as the line
    writes them (`written`: its own capitals, hyphen and apostrophe: "Zucker", "d'olives",
    "all-purpose flour"; else the lowercase name as read). Skipped: a heading
    (`IngredientHeading`), a line `IngredientName` cannot name ("salt and pepper", "juice of 1
    lemon": never guessed), a staple from `staples.json` (matched whole after the same
    normalising, never at the end of a longer name: "bell pepper" stays) and a name already
    taken. Nothing is translated or invented. A language with no tables gives none.
  - `tintIndex(uid)`: FNV-1a over the uid's UTF-8 bytes, modulo five, so a recipe has one
    colour on both platforms, everywhere, after a rename. A recipe not stored yet is tinted by
    its link. A recipe's own `uid` reaches the screens through `RecipeSummary`, `Recipe`,
    `PlannedMeal` and the covers' rows.
- **The drawing is one component per platform:** Android `RecipeImagePlaceholder` / `RecipeImage`
  (`ui/common/RecipeImagePlaceholder.kt`), iOS `RecipeImagePlaceholder` / `RecipeImage`
  (`UI/Common/RecipeImagePlaceholder.swift`). The style is picked by the size it is given, not
  the screen: the shorter side 64 dp/pt or less is the monogram (Bricolage Bold since #455, 56% of the side);
  larger is the label, upright in Bricolage SemiBold at 18% of the side (never under 10), centred, the
  first name only; a surface at least 1.5 times wider than tall (Home's band at 112, the
  reading view's banner at 150) sets up to three names low and left at 21 (25 from 140 tall),
  joined by a half-faded dot. No ingredient lines gives the monogram; no letter either, a plain
  tint tile. The five tints are theme tokens, light and on ink (`NoPhotoTint`, `LocalNoPhotoTints`;
  iOS `Tokens.noPhotoLight/OnInk`, `Palette.noPhotoTint`): since #455 Market's basil, lemon, tomato,
  sky and oat, the revamp mockup's values (until then paprika, saffron, sage, cocoa and rose, with
  the label in a bundled Fraunces Italic; Bricolage has no italic, so the names stand upright, as
  the design draws them). The names keep their own case: the design sets them lowercase, which
  would misspell German nouns ("Zucker").
- **Where it shows:** Recipes and list rows, Home's Recently viewed (84), Week rows (44), the
  Lists covers, Home's Continue cooking band (112 with no photo, 136 with one) and the reading
  view's banner (150 with no photo, 200 with one). A photo that fails to load shows the same
  (`RecipeImage`: Coil's `onError`, iOS `CachedAsyncImage.onFailure`, which also fires when the
  bytes do not decode). Not changed: the Reddit post's pictures, the clip view and the scan
  and photo reviews (they are the page's, not a recipe's image), print (the PDF keeps its
  photo or none), and the widgets, which draw no recipe images. A Lists cover's own photo that
  fails to load shows its recipe's image too: the cover rows carry the recipe's uid, title,
  language and ingredient lines beside the address (`CoverPhoto`, `ListDao.observeCoverPhotos`).
- **Lists covers (changes #286's rule, the owner's OK):** a list with any photo shows photos
  alone, as before; a list whose recipes all lack one shows up to four of their images in the
  usual 2×2/1/2/3 layout, newest added first (`ListDao.observeCoverPlaceholders`, iOS
  `coverPlaceholders`, a second query like the photos', no migration); an empty list keeps the
  outlined-bookmark tile ("nothing saved here yet").
- **Landing with the photo work (batch 2: #383's stored photos #386, #389's reorderable covers,
  #402's Today card).** A recipe's own stored picture is a `photo://<file>[#<link>]` model; every
  recipe image goes through `RecipeImage`, so a stored photo whose file is gone and whose link
  fails falls back to the designed image like any other failed load (`RecipeImagePlaceholderTest`,
  `testAStoredPhotoWhoseFileIsGoneReportsItsFailure`). The Lists cover rows carry the `photo://`
  model with the recipe's uid, title, language and lines (`CoverPhoto`), and a recipe with a stored
  photo is not a "no photo" recipe in the placeholder query (`photoFile IS NULL` and no link).
  Dragging a cover (#381) is untouched: `ListCoverTile` takes the cover's photos and placeholders
  beside the drag modifier and the move actions. **Home's Today card** shows the lead recipe's
  photo at 136, else its designed image at 112 (the Continue cooking band; a planned note keeps
  the plain band, and so does the flag off): `PlannedMeal` and `TodayMeal` carry the recipe's
  uid, language and ingredient lines (`MealPlanDao.observeDays` / iOS `days` select `r.ingredients`
  too). **The Recipes photo grid (#380)** cell is a `RecipeImage` square, the designed image in
  place of the blank tile.
- **Decorative:** no semantics of its own (Android `clearAndSetSemantics`, iOS hidden from
  VoiceOver), so what is announced is what was announced before; no new strings.
- Android reads the flag through `LocalFlagValues` (`noPhotoImageOn()`), iOS through an
  environment value `noPhotoImage` set once in `RootView`.

## Step photos beside each step (#433, 2026-11-21)

Cookist (and any site whose JSON-LD gives `HowToStep.image`) gives each step its own photo and ends
the step text in markers, "(1)", "(3) (4)", that point at them; without the photos they meant
nothing. The owner chose option B of the five layouts
(`~/Downloads/RecipeClipper-designs/step-photos/step-photos.html`): a small thumbnail beside the step,
full size on tap. Behind `stepPhotos`, on by default; off, every screen is as before (the photos are
still parsed, stored and exported, so turning it on shows them without a re-share).

- **Parsed from JSON-LD only.** `JsonLdRecipeParser` reads a HowToStep's `image` (a link, an
  ImageObject's `url` else `contentUrl`, or an array of either; nested arrays, numbers and nulls
  ignored), each through `WebImageUrl` (#235), once, at most 8 a step (`StepPhotos.MAX_PER_STEP`), into
  `Recipe.stepPhotos`: one list of links per step, lined up with the steps, an empty list for a recipe
  with none. A step given as a string has none; a blank step goes with its photo; sections flatten with
  theirs; the site rules' step noise (#120) drops a cut-away last step's photos with it
  (`BlogPageParser.refine`). Cookist needs nothing else: every step of its three pages in #433 carries
  one `image`, and its markers are already in the text. Microdata, Reddit, the on-device model's pick
  (#103) and the Paprika, Recipe Keeper and Mealie imports give no step photos. The one exception to
  "JSON-LD only" is a site rule's `stepPhotos` (#438, Giallo Zafferano), read from the page only
  when it proves every number, and only when JSON-LD gave the recipe no step photos of its own.
- **The numbers come from the markers, the text stays as written.** `StepPhotos.numbered`: a step's
  bare "(n)" markers (one or two digits in brackets) number its photos when there are exactly as many
  markers as photos; otherwise its photos take their place among the recipe's step photos (1, 2, 3...).
  The markers are never hidden or rewritten (hiding them once their photo shows would rewrite the
  text; the design's open point, not taken). Pinned on iOS by the corpus's `StepPh` rows.
- **Stored as a column, `recipes.stepPhotos`**: `StepPhotos.encode`'s JSON (an array of arrays of
  links), null for none, read back leniently (`decode`: anything odd is none, each link a web image
  again). Room 20 to 21 (`MIGRATION_20_21`, `MigrationTest` 20 to 21 and 19 to 21, schema `21.json`),
  iOS `user_version` 19 to 20 (`addStepPhotos`): numbered after #432's tips (Room 20, iOS 19), which
  landed first. A plain column rather than a field inside `instructions`, which
  every other reader (ticks, cook progress, Chef mode's hashes, share, print) takes as plain strings.
- **Content, not the cook's.** A re-share and "Update from source" refresh them with the steps (unlike
  `photoFile`, #386, which is the user's); the user's version (#29) keeps what it has, since a re-share
  never touches it. **The editor has no field for them and loses none on an edit**
  (`RecipeDraft.applyTo` through `StepPhotos.afterEdit`): a step whose text is unchanged keeps its photos
  wherever it moved (a reorder, steps added or removed around it); then the changed steps left over are
  paired with the old ones left over, in order, but only when as many are left on both sides, so an
  edited step keeps its own. A removed step's photos go; steps added and changed in the same edit leave
  the unpaired new ones with none.
- **The reading view** (`StepPhotoThumbnail`, iOS `StepPhotoThumbnail`): a step with photos gets a
  54 dp square, 9 dp corners, at the right of its text, 12 dp from it, badged bottom left with the first
  photo's number (white on ink at 78%). Two or more stack: the second behind, offset 4 dp right and up,
  at 55%, with a basil "+1" (count − 1) pill top right. A step without has no thumbnail and today's
  full-width text. One button for a screen reader: "Photo 3 of step 2" or "2 photos of step 3", action
  "View full screen".
- **The viewer** (`StepPhotoViewer`): full screen on the on-ink ground (`#0E1310` since #455) in both
  themes, words `TextOnInk`: Close, "Photo 3 of 7", "Step 3", the photo fitted, page dots, then the
  step's number (Bricolage, `AccentTextOnInk`) and its text as the reading view shows it (temperatures converted; not Chef mode's short
  version). Swipes through every step photo of the recipe in step order. The page shown is the
  ViewModel's (`stepPhotoViewer`, the photo's place among all of them), so a rotation keeps it.
- **Cook mode**: done and upcoming rows carry the first photo at 40 dp (7 dp corners, no badge or
  pill), decorative: the row's tap still makes the step current, and that card's thumbnail opens the
  viewer. The current card puts its thumbnail (the reading view's, badge and stack) at the end of the
  timer's row when the step has a timer, and beside the step's text, top right, when it has none (the
  owner's follow-up: alone in a row of its own under the text it looked stranded; iOS
  `CurrentStepPhoto.placement`). The card's text size and the rest of cook mode are unchanged.
- **Loading and failure.** Coil (iOS `ImageLoader`), cached like the hero, so a photo seen once shows
  offline. A thumbnail whose first photo fails to load is not drawn at all (the text takes the room),
  never a placeholder: a stand-in tile beside every step of a page whose images are gone would read as
  broken. The space before a thumbnail belongs to the thumbnail (its `gap`), so a failed one leaves no
  gap anywhere. A stack whose second photo fails keeps the first and its pill. A failed page in the
  viewer is the dark ground with its caption.
- **With tips (#432).** The tips sit below the method with no photos; a tip is never a step, so the
  parser gives tips none and the photos stay lined up with the steps alone.
- **Share, print, export.** Sharing as text is unchanged (the markers are in the text as they always
  were). The print and the cookbook PDF (#318) leave step photos out: the print only uses a cached hero
  photo, and fetching a dozen step photos for paper is not trivial. The export file and a shared file
  (#149) carry them as `"stepPhotos"` on a recipe, written only when a step has one (an older app
  ignores the key); an import reads them as web images lined up with the steps, for a new recipe; a
  recipe already here keeps its content, as for every other field (`BackupMerger`). Fixture:
  `shared/fixtures/backup/backup-v1-step-photos.json`, read by both platforms' `BackupJson` tests.
- **Tests.** `StepPhotosTest` / `StepPhotosTests` (markers, numbering, lining up, the edit, storage),
  `StepPhotosParserTest` and its iOS twin (Cookist's fixture `shared/fixtures/jsonld/cookist-fagottini-di-mortadella.json`,
  ImageObject and arrays, junk, sections, the site rule), the corpus's `StepPh` rows,
  `RecipeStepPhotosDbTest` (Room: stored, refreshed, the user's version, an edit, export and import),
  `MigrationTest`, `RecipeStepPhotosTest` (the ViewModel's viewer state) and `RecipeStepPhotosScreenTest`
  (Compose, over `FakeImages`, a Coil loader that never touches the network: thumbnails, none on a step
  without, the viewer paging, cook mode, a failed photo, no step photos and the flag off unchanged), with
  the iOS data, migration and view tests.

## The Plan's quiet pantry note (#456, 2026-11-22)

The owner: "If we add a recipe to the planner, can we have a simple quiet warning that maybe we don't
have all the ingredients in the pantry for it. I want a nonchalant warning, because some people can
still make do with the recipe." Flag `planPantryNote`, on by default in debug and release, both
platforms. Off, the Plan is as before.

- **Where.** Under each planned recipe on the Plan tab, today and later, one muted body-small line
  under the title: **"Not in your pantry: tahini, sumac"**. Up to three names; past that two and
  "and 3 more" (so "more" is never 1). No icon, colour, badge or dialog. **Nothing in the add flow**
  (the owner's call: the "+ Add" sheet and Add to plan still close on adding, with no confirmation),
  not on Home's Today card, not for a note, not for a meal in the past (the past can't be shopped for).
  "You have 6 of 8" (#378) was the other wording; the names were chosen as more useful at a glance.
- **What counts** (`PlanPantryNote`, `:core`, Swift port, corpus rows `Gap`): the recipe's nameable
  lines as #378 and Use it up read them (`UseItUp.names`: headings and blanks aside, a line with no
  name never counted), each missing when `PantryMatch` finds no item in stock or staple of that name in
  the recipe's language. Presence only, never "enough": an out-of-stock item is missing, a staple is
  had, "rice flour" is never "flour", quantities are never compared. The model's "same ingredient"
  answers (#104) count, as What I need's do, so the two screens agree. One name per ingredient
  (`IngredientName.key`). Names are the matcher's, lowercase, in the recipe's language.
- **Only when it means something**: no note when everything is had, when the recipe's language has
  no tables, or when the pantry has nothing in that language (a cook who doesn't keep a pantry would
  otherwise see one on every meal).
- **Opened in place** (the owner, the same day: "when the note says +2, will it show what those
  are?", then "what's a seamless way to get the user to add that to groceries?"). Every note is its own
  tap target (the meal's tap still opens the recipe), labelled "Show what's missing" / "Show less" for
  TalkBack and VoiceOver. A tap opens it: every missing name, and one quiet text button, **Add to
  groceries**; a tap again closes it. Open or closed is the view's alone (it may close on leaving the
  tab).
- **Add to groceries** adds exactly the recipe's lines naming what is still missing
  (`PlanPantryNote.linesToAdd`), rendered as What I need renders them (`GrocerySources.fromPlan`: the
  planned servings, the cook's units), through the same `GroceryRepository.add`, each line tagged with
  its recipe and planned day, so the list combines and groups them as it does What I need's. Then an
  Undo snackbar, "Added 2 to groceries", whose Undo deletes exactly the items that add wrote (their
  ids, read off the list before and after). One undo at a time with Remove and Skip: a new one settles
  the last.
- **The note follows the grocery list.** A missing name an unticked grocery line already names
  (`IngredientName.matches`, same language) is counted, not named; when all of them are on the list the
  note says **"On your grocery list"**, muted and not tappable. A ticked line doesn't count: bought is
  the pantry's to say, so a tick names it again until "Done shopping" restocks the pantry, and then
  the note goes. Everything is collected live (the meals held, today, the pantry, the answers, the
  list), never read once.
- **Not built**: a per-meal menu item (the row's menu stays Move, Skip, Remove), a note for the add
  sheets, any count of quantities.

**Tests.** `:core` `PlanPantryNoteTest` and the corpus's `Gap` rows (Swift generated from the Kotlin);
Android `WeekPantryNoteTest` (the ViewModel: the note, the pantry and plan followed, empty pantry, past
and flag off, the model's answers, add at planned servings and its undo, the grocery list followed, one
undo at a time) and `WeekPantryNoteScreenTest` (Compose: the note and its absence, opening it without
opening the recipe, adding, the snackbar, "On your grocery list", Undo, the capped note opened and
closed, the flag off); iOS `PlanPantryNoteTests`, `WeekPantryNoteTests` and `WeekPantryNoteUITests`
(seeded with `-uiTestPantry`).

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
               ClipSelection, ClipDraft, PageText, RecipeTextWindow, PageRecipeCheck (#103), PageReply (#273),
               PlanDays (the plan's epoch-day calendar), MealPlan (MealType, PlannedMeal), LibraryLimit (#107),
               Groceries (Aisle, Aisles, GroceryCombiner, GroceryShareText, GrocerySources),
               Pantry (PantryList: sort, search, expiry badge; PantryMatch: Have/Buy),
               PantryUseUp (what a finished cook takes from the pantry, #147),
               Perishables and FreshFood (the perishables table, Shop for this week's split, the plan's note, #396)
ui/            navigation, home, recipes (the library), recipe, clip, edit, savetolist, lists, listdetail,
               settings, week (with What I need), plan (Add to plan sheet), mealtypes,
               groceries (the tab and the Add to groceries sheet), pantry, sharefile (Send as file,
               the received file's sheet, #149), print (Print or save as PDF, the cookbook PDF, #318),
               theme, common
timers/        AlarmManager scheduler, alarm and boot receivers, the "time's up" notification
reminders/     the pantry's expiry reminder: one AlarmManager alarm, its receiver, the notification (#52);
               the evening and Tonight reminders: one alarm per kind, built from the plan when it fires (#397)
```

### Routes

`home`, `recipes` (the library; was `history`), `settings` (the index, #286: each
section's page is `settings/<section>`, such as `settings/units` or `settings/help`; and the
hidden `settings/developer`), `lists`, `lists/{listId}`, `recipe/{recipeId}?cook={cook}`
(`cook=true` from a timer notification or Home comes back to the cook: cook mode in its saved state, or the reading view once the run is finished, #333), `recipe/import?url={url}` (the
share target: parse, then upsert with no list membership), and
`edit?recipeId={recipeId}` (no id: a new recipe; saving replaces the edit screen, and
the recipe screen under it, with `recipe/{id}`), and `clip?url={url}&blocked={blocked}&check={check}` (Clip it
yourself; `blocked=true` when Reddit's block opened it in the import's place, #213, with a note;
`check=true` when Cloudflare's check wants the cook, #220, likewise;
saving replaces it and the error screen under it, if any, with `recipe/{id}`), and
`edit/photo?url={url}&title={title}&images={images}` (Read the photo, #198: the editor filled
from a Reddit post's pictures, one address per line in `images`; saving replaces it and the
error screen like a clip), and `edit/scan?pages={pages}` (Scan a recipe, #226: the same editor
over the cook's own pages, local URIs one per line; saving replaces it with `recipe/{id}`), and
`edit/text?key={key}` (text shared in, #306: the same editor, split or as written, found by its
key in `SharedTextInbox`, #334; saving replaces it with `recipe/{id}`), and
`edit/article?article={url}&index={n}` (a recipe written inside an article, #435: the same
editor, filled from what the import found at that link, opened in the import's place; saving
replaces it with `recipe/{id}`), and `article/choose?url={url}` (several such recipes: the
chooser, in the import's place). A bottom tab bar (#47) nests this graph under a Recipes tab
alongside `week` (with its own `week/recipe/{recipeId}?servings={servings}` and
`week/meal-types` and `week/need/{weekStart}`, and `week/settings`, #412), `groceries` (#50) and `pantry` (#51; with `pantry/settings`, #412)
(`AppShell`/iOS `RootView`'s `tabs`). Hidden on the recipe reading view and in cook
mode; any route from an intent (a shared link, a tapped timer notification) always
lands in Recipes, whichever tab is open.

### The database, in one paragraph

Room database `recipe_clipper.db`, **version 21** (iOS `user_version` 20): `recipes`
(with nullable `notes`, `language`, `cookState`, `servingsTarget`, `editedAt`, `basedOnUid`,
#354, `photoFile`, #386, `contentOrigin`, `tips`, #432, a JSON list, and `stepPhotos`, #433), `lists` and `recipe_list_cross_ref` (cascading), `meal_types` and
`meal_plan_entries` (#49), `grocery_items` (#50), `pantry_items` (#51; `runningLow`, #194), `menus` and
`menu_entries` (#52), `short_steps` (#100) and `ai_decisions` (#104) (derived: never
exported), `cooked_photos` (#116, cascading; no `fileName` for "Mark as cooked", #173). Recipes, lists, the plan, grocery,
pantry, menu and photo tables carry a unique, never-changing `uid`: what an export
file calls them. Plan, grocery, pantry, menu and photo rows also carry `updatedAt`
(for #53). iOS keeps the database in the App Group container that the share
extension writes to as well.

### Settings: the sections and the keys

**Sections, in order:** Units (with "Also convert liquids" for Ounces only), Oven temperature
(independent of units, default As written), Appearance ("Dark while cooking"), Steps
("Amounts in steps", off, #101; "Chef mode", behind the `chefMode` flag, #100),
Week (shown as Plan: "Show today's meal", behind `todayCardClose`, #448, then the evening-before and
Tonight reminders with their times, behind the `eveningReminders` flag, #397),
Pantry ("Expiry reminders"; asks for notifications when turned on,
never at launch), Your recipes (export, import and the automatic copy, #150), Help ("Show
tips again", #190), and Unlimited recipes (only with `freeTier`: "Unlock for <store price>"
and "Restore purchase", or the sentence "Unlocked: every recipe is kept."; Developer
settings has an "Unlocked" override). Reached from the gear beside
the Recipes title (Home's, with `libraryFirst` off), and the Plan and Pantry sections' pages
also from their tabs' menus (#412, below). Any other entry point is the owner's call.

**An index with values (#286, the owner's pick of the redesign):** Settings opens on one row
per section, in the order above: an icon (hand-drawn drawables on Android, SF Symbols on iOS),
the name, its current value muted on the right, and a chevron. Each row opens the section's
own page (`settings/<section>`; iOS `Route.settingsSection`) holding exactly the controls it
had on the one long screen; Back returns to the index. The values come from the settings, never
sample text: Units and Oven temperature their choice; Appearance "Dark while cooking" when on,
else "System theme" (dark mode is the system's call); Steps "Amounts on/off" (Chef mode isn't
summarised); Week and Pantry "Reminders on/off"; Your recipes the automatic copy's "Last backed up"
(how long ago) or "Not backed up yet", in the error colour while any of its page's warnings
shows; Unlimited recipes "Unlocked" once it is; Help none. The value takes at most half the row
and wraps; at the largest text sizes it sits under the name. Each page has its own ViewModel,
like any destination, and every one follows the preferences, so the index is never stale. The
version stays at the foot of the index (seven taps, #87). "Show tips again"'s tooltip (#190)
is hosted by Help's page, so its visit is that page's; the index hosts none.

**Keys** (the SharedPreferences file `unit_preferences`, and `UserDefaults` on iOS,
the same on both): `unit_system`, `convert_liquids`, `temperature_unit`,
`dark_while_cooking`, `expiry_reminders`, `evening_reminder`, `evening_reminder_minute`,
`tonight_reminder`, `tonight_reminder_minute` (#397), `chef_mode`, `amounts_in_steps`,
`recipe_sort` (the Recipes screen's sort), `recipes_layout` (its rows or photo grid, #380: `ROWS` or
`GRID`, an unknown name reads as `ROWS`), `plan_my_week_meals` (#392: the meal types ticked in
Plan my week, `MealType.key`s comma-joined), `show_today_card` and `today_card_dismissed_day`
(#448: "Show today's meal", and the local epoch day the Today card was last closed), the tour's
`tour_sample_added` (#151) and
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
