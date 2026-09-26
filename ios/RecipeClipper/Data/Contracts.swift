import Combine
import Foundation

// The seams between layers. ViewModels depend only on these protocols, never on SQLite,
// URLSession or UserDefaults, so they stay unit-testable with hand-written fakes
// (RecipeClipperTests/Fakes). Mirrors Android's RecipeRepository / ListRepository /
// AppPreferences / Clock interfaces one to one.

/// While the free tier (#107) is off, history keeps this many unprotected recipes.
let historyLimit = LibraryLimit.historyRecipes

/// The current wall-clock time in epoch milliseconds. A seam so a test can line up timer
/// deadlines with its own virtual time.
protocol Clock {
    func now() -> Int64
}

struct SystemClock: Clock {
    func now() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }
}

/// Fetches and parses one recipe page. Pure network + parse; persists nothing.
protocol RecipeSource {
    func fetch(url: String) async -> ParseResult
    /// `fetch`, plus the page's text when it loaded but held no recipe data (#103), for the
    /// on-device model to pick one from. A source that can't say gives no text.
    func fetchPage(url: String) async -> FetchedPage
}

extension RecipeSource {
    func fetchPage(url: String) async -> FetchedPage { FetchedPage(result: await fetch(url: url)) }
}

/// A fetch's result, and `page` only when that is `.noRecipeFound` on a page that loaded.
struct FetchedPage {
    var result: ParseResult
    var page: PageText? = nil
}

/// The on-device model reading a page with no recipe data (#103), beside `StepShortener`:
/// `FoundationModelsPageRecipeExtractor` is the real one (Apple's Foundation Models, guided
/// generation); tests use `FakePageRecipeExtractor`. The parsers never call it: the repository
/// does, only after they found nothing.
protocol PageRecipeExtractor: AnyObject {
    /// How much page text, in characters, it can read now for a recipe in `language` ("en"), or
    /// nil when it can't (an unsupported phone or language, or a model not ready).
    func windowChars(language: String) async -> Int?
    /// What the model picked out of `text` as the recipe, or nil. Unchecked: `PageRecipeCheck`
    /// keeps only what is on the page.
    func extract(_ text: String, language: String) async -> PageSelection?
}

/// Reads nothing: the default for tests that aren't about extraction, and the UI-test graph.
final class NoPageRecipeExtractor: PageRecipeExtractor {
    func windowChars(language: String) async -> Int? { nil }
    func extract(_ text: String, language: String) async -> PageSelection? { nil }
}

/// The on-device model making typed decisions (#104), Android's `DecisionModel`:
/// `FoundationModelsDecisionModel` is the real one; tests use `FakeDecisionModel`.
protocol DecisionModel: AnyObject {
    /// True when it can answer now for a recipe in `language` ("en").
    func supports(language: String) async -> Bool
    /// One reply, unchecked (`DecisionRule` judges it), or nil when the model can't answer right
    /// now: nothing is cached and it is asked again next time.
    func ask(_ prompt: DecisionPrompt) async -> DecisionReply?
}

/// The on-device model's typed decisions (#104): asked lazily, judged, cached.
protocol DecisionRepository: AnyObject {
    /// Every cached answer, then every change. `.none` while the `aiDecisions` flag is off.
    func observe() -> AnyPublisher<Decisions, Never>
    func current() async -> Decisions
    /// Asks the model each question not cached yet, `DecisionRule.asks` times, and caches the
    /// judged answer. Nothing with the flag off or in a language the model can't do.
    func decide(_ questions: [DecisionQuestion]) async
}

/// Loads a page in an off-screen browser, lets its JavaScript run, and returns the resulting
/// HTML (Android's RenderedPageSource). The repository's last resort once the direct fetch and
/// its retry end `.blocked` or `.noRecipeFound`. `WebViewRenderedPageSource` is the real one,
/// built on the main actor in AppContainer; parsing stays with the pure parsers.
protocol RenderedPageSource {
    /// The page's `document.documentElement.outerHTML` once loaded and settled; nil if it
    /// couldn't be loaded, or when the calling task is cancelled (which stops the load). The
    /// caller caps the overall time.
    func render(url: String) async -> String?
}

/// Renders nothing: the default where the fallback isn't wanted (tests that aren't about it,
/// the UI-test graph).
struct NoRenderedPageSource: RenderedPageSource {
    func render(url: String) async -> String? { nil }
}

/// Whether the device has a usable network (Android's Connectivity). A seam so RecipeViewModel
/// never touches Network.framework and a test can drive it; `PathConnectivity` is the real one.
protocol Connectivity: AnyObject {
    /// A fresh stream per call: the current state first, then every change, never repeating a
    /// value. Ends when the consuming task is cancelled.
    func onlineUpdates() -> AsyncStream<Bool>
}

extension Connectivity {
    /// Waits for the connection to go from offline to online. Already online is not a
    /// transition: a failure while connected waits for a drop and a return. True once that
    /// happens; false if the stream ends first (or the waiting task is cancelled).
    func waitForReconnect() async -> Bool {
        var sawOffline = false
        for await online in onlineUpdates() {
            if !online {
                sawOffline = true
            } else if sawOffline {
                return true
            }
        }
        return false
    }
}

/// Connectivity that never changes: one value, then silence. The default where no reconnect
/// behaviour is wanted (previews, and tests that aren't about it).
final class StaticConnectivity: Connectivity {
    private let online: Bool
    init(online: Bool = true) { self.online = online }

    func onlineUpdates() -> AsyncStream<Bool> {
        AsyncStream { $0.yield(online) }
    }
}

/// The platform and app version, as a site report states them (Android's AppInfo). A seam so
/// RecipeViewModel never reads the bundle or the OS and a test can pin the values;
/// `BundleAppInfo` is the real one.
protocol AppInfo {
    /// e.g. "iOS 17.5".
    var platform: String { get }
    /// e.g. "1.0 (1)": marketing version, then build number.
    var appVersion: String { get }
}

/// Fixed values: the default where the real ones don't matter (previews, and tests).
struct StaticAppInfo: AppInfo {
    var platform = "iOS 17.0"
    var appVersion = "1.0 (1)"
}

/// Fetches, parses and persists recipes.
protocol RecipeRepository: AnyObject {
    /// The share-target path: clean the link, fetch, parse, then persist (upsert + history
    /// cull). If the fetch fails but the link was saved before, the saved copy is returned
    /// (and its lastViewedAt bumped), so anything opened once still opens offline.
    ///
    /// `renderedPage` is Safari's own copy of the page (#35: the share extension's JavaScript
    /// preprocessing file hands over `document.documentElement.outerHTML`, already past
    /// whatever blocked a plain fetch). When given, it's parsed directly and the fetch is
    /// skipped; only if that page holds no recipe does the ordinary fetch (with its retry and
    /// rendered-browser fallback) run, exactly as if nothing had been given.
    ///
    /// A new link on a full free library (#107) whose recipes are all protected is shown but
    /// not kept: `.notKept(recipe)`, with no id.
    func importFromUrl(_ sharedUrl: String, renderedPage: String?) async -> ParseResult

    /// Saves a recipe that was shown but not kept (#107), once there is room (after unlocking).
    /// `.notKept` again if there still isn't.
    func keep(_ recipe: Recipe) async -> ParseResult

    /// Saves a recipe the user clipped by hand from a page with no recipe data (#37), keyed on
    /// the cleaned `sourceUrl` like an import: a link seen before keeps its id, note and list
    /// membership, and its content is replaced by the clip. Counts as a view. Returns the saved
    /// recipe, or `.error(.saveFailed)`, or `.notKept` on a full library (#107), which the clip
    /// screen doesn't leave.
    func saveClip(_ recipe: Recipe) async -> ParseResult

    /// "Update from source" (#29): fetches the recipe's link again and replaces the user's
    /// version with the site's, keeping the id, note and list membership, and making it PARSED.
    /// On any failure nothing changes and the cause is returned.
    func updateFromSource(id: Int64) async -> ParseResult

    /// Saves the user's edit of recipe `id`: the content becomes `draft`'s, `editedAt` is now,
    /// and a parsed recipe becomes EDITED. Nil if `draft` isn't a recipe (no name, or neither
    /// ingredients nor steps), the recipe is gone, or the save failed.
    func saveEdit(id: Int64, draft: RecipeDraft) async -> Recipe?

    /// Saves a recipe typed in by hand (MANUAL, with a `manual:` link). Nil as for `saveEdit`.
    /// On a full free library (#107) with nothing to make room, the recipe comes back with id
    /// 0: not kept, and the editor stays open.
    func addManual(draft: RecipeDraft) async -> Recipe?

    /// Opens a recipe from history, a list or home. Counts as a view. Nil if it's gone.
    func open(id: Int64) async -> Recipe?

    func setChecked(id: Int64, checked: Set<Int>) async

    /// Saves the user's note on a recipe. A blank note is stored as no note.
    func setNotes(id: Int64, notes: String) async

    /// Saves where the cook stands. An empty `CookProgress` is stored as none.
    func setCookProgress(id: Int64, progress: CookProgress) async

    /// Saves the chosen servings; nil goes back to the recipe's own yield.
    func setServingsTarget(id: Int64, target: Int?) async

    /// Hard delete; memberships go with it. Nil if it was already gone.
    func delete(id: Int64) async -> DeletedRecipe?

    /// Undoes `delete`: same id, same list membership.
    func restore(_ deleted: DeletedRecipe) async

    /// Titles and ingredients containing `query` (case-insensitive, literal substring —
    /// never LIKE); everything when `query` is empty. Newest view first. Re-emits on change.
    func observeHistory(query: String) -> AnyPublisher<[RecipeSummary], Never>

    /// The `limit` most recently viewed. Re-emits on change.
    func observeRecent(limit: Int) -> AnyPublisher<[RecipeSummary], Never>

    /// How many recipes are saved, of every kind (#107: the Recipes screen's count).
    func observeCount() -> AnyPublisher<Int, Never>
}

extension RecipeRepository {
    func observeCount() -> AnyPublisher<Int, Never> {
        observeHistory(query: "").map(\.count).removeDuplicates().eraseToAnyPublisher()
    }
}

/// Schedules the background "time's up" alert for a running step timer, so it still sounds
/// with the app in the background or killed (Android's `TimerAlarmScheduler`). A seam: the
/// ViewModel never touches UserNotifications, and tests pass a fake. The real one is
/// `NotificationTimerScheduler`. Every call is idempotent.
@MainActor
protocol TimerAlarmScheduler: AnyObject {
    func schedule(_ alarm: StepAlarm)
    func cancel(recipeId: Int64, step: Int)
    /// Drops every pending alert for `recipeId` and schedules `alarms` instead. On opening a
    /// recipe: a local notification has no receiver that could check it is still wanted, so
    /// one left over from a re-share that changed the steps must be removed here.
    func replaceAll(recipeId: Int64, with alarms: [StepAlarm])
}

/// Schedules the pantry's expiry reminders (#52; Android's `ExpiryReminderScheduler`). The real
/// one is `NotificationExpiryReminderScheduler`; tests pass a fake.
@MainActor
protocol ExpiryReminderScheduler: AnyObject {
    /// Replaces whatever was scheduled with `reminders` (soonest first); empty cancels all.
    func replaceAll(_ reminders: [ExpiryReminder])
}

/// Asks to post notifications (Android asks in the Settings screen, which holds the Activity).
/// A seam so the Settings ViewModel can be tested, and UI tests never meet the system prompt.
@MainActor
protocol NotificationPermission: AnyObject {
    /// True when notifications are allowed, asking first if the user hasn't answered yet. The
    /// system asks once; after a refusal this answers false at once.
    func request() async -> Bool
}

extension RecipeRepository {
    /// The ordinary case: nothing rendered already, so the repository fetches. Every caller but
    /// the Safari share extension (#35) uses this.
    func importFromUrl(_ sharedUrl: String) async -> ParseResult {
        await importFromUrl(sharedUrl, renderedPage: nil)
    }
}

/// List membership. Separate from RecipeRepository on purpose (see docs/decisions.md, Lists).
protocol ListRepository: AnyObject {
    /// Every list with its recipe count, built-ins first then sortOrder then id.
    func observeLists() -> AnyPublisher<[RecipeList], Never>

    /// Same, each also saying whether it contains `recipeId`.
    func observeListsFor(recipeId: Int64) -> AnyPublisher<[RecipeList], Never>

    /// The recipes in one list, most recently added first.
    func observeRecipesIn(listId: Int64) -> AnyPublisher<[RecipeSummary], Never>

    /// Writes immediately. Adding is insert-or-ignore: re-adding never rewrites addedAt.
    func setMembership(recipeId: Int64, listId: Int64, inList: Bool) async

    /// Creates a user list (name trimmed) and, when given, puts `addRecipeId` straight in,
    /// in one transaction. Returns the new list's id.
    @discardableResult
    func createList(name: String, addRecipeId: Int64?) async -> Int64

    func rename(listId: Int64, name: String) async

    /// Refused for Favorites (guard lives in the SQL). Never deletes the recipes in it.
    func deleteList(listId: Int64) async
}

/// Export and import of every recipe and list as one file (#26; Android's BackupRepository).
protocol BackupRepository: AnyObject {
    /// Everything, as the text of one export file.
    func export() async -> Result<ExportedBackup, BackupError>

    /// Merges an export file into what's here (never replaces, never deletes; see
    /// BackupMerger). A file that can't be read writes nothing and says why.
    func importBackup(_ text: String) async -> Result<ImportSummary, BackupError>
}

/// Where an export file is written and a picked one is read (Android's BackupFiles), so the
/// Settings ViewModel stays free of the file system and its test can use a fake.
protocol BackupFiles: AnyObject {
    /// Writes `json` as `recipe-clipper-YYYY-MM-DD.json` and returns its URL for the share
    /// sheet, or nil if it couldn't be written.
    func writeExport(json: String, exportedAt: Int64) async -> URL?

    /// The picked file's text, `.readFailed` if it couldn't be read, or `.notABackup` if it's
    /// far bigger than any export (or not text).
    func readText(_ url: URL) async -> Result<String, BackupError>
}

/// Far beyond any real export (a few hundred recipes is well under 2 MB).
let backupMaxBytes = 20 * 1024 * 1024

/// `recipe-clipper-YYYY-MM-DD.json`, in the phone's time zone.
func backupFileName(exportedAt: Int64) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    let date = Date(timeIntervalSince1970: TimeInterval(exportedAt) / 1000)
    return "recipe-clipper-" + formatter.string(from: date) + ".json"
}

/// The week meal plan (#49; Android's MealPlanRepository): what is planned on which day, and
/// the meal types it is sorted by. Every write lands at once.
protocol MealPlanRepository: AnyObject {
    /// Every meal type, in the user's order. Re-emits on change.
    func observeMealTypes() -> AnyPublisher<[MealType], Never>

    /// The meals planned from day `start` to `end` inclusive (epoch days), in plan order.
    func observeDays(start: Int64, end: Int64) -> AnyPublisher<[PlannedMeal], Never>

    /// Plans `recipeId` on `day`; `servings` nil means the recipe's own yield.
    func addRecipe(recipeId: Int64, day: Int64, mealTypeId: Int64, servings: Int?) async

    /// Plans a free-text note on `day`. A blank note is ignored.
    func addNote(_ note: String, day: Int64, mealTypeId: Int64) async

    /// Moves a meal to another day or meal type, at the end of its new slot.
    func move(entryId: Int64, day: Int64, mealTypeId: Int64) async

    /// Removes a meal from the plan (never the recipe). Nil if it was already gone.
    func delete(entryId: Int64) async -> DeletedMeal?

    /// Undoes `delete`: the same meal, in the same place.
    func restore(_ deleted: DeletedMeal) async

    /// A user's own meal type, last in the order. A blank name is ignored.
    func addMealType(name: String) async

    func renameMealType(id: Int64, name: String) async

    /// Saves the whole order at once, as the meal-types screen shows it.
    func reorderMealTypes(_ orderedIds: [Int64]) async

    /// Deletes a user's meal type; its meals move to Dinner. Seeded types are ignored.
    func deleteMealType(id: Int64) async

    // MARK: Reusable weekly menus (#52)

    /// Every saved menu, by name.
    func observeMenus() -> AnyPublisher<[WeekMenu], Never>

    /// Saves the seven days from `weekStart` as a new menu called `name`. A blank name or an
    /// empty week saves nothing; returns whether a menu was saved.
    func saveWeekAsMenu(name: String, weekStart: Int64) async -> Bool

    /// Adds a menu's meals to the week from `weekStart`, after what is planned there. Never
    /// changes or removes a meal already planned. Returns how many meals it added.
    func applyMenu(id: Int64, weekStart: Int64) async -> Int

    /// A blank name is ignored.
    func renameMenu(id: Int64, name: String) async

    /// Deletes a menu; the plan and the recipes are untouched.
    func deleteMenu(id: Int64) async
}

/// What a meal-plan delete removed, for `restore`. Opaque to callers.
struct DeletedMeal: Equatable {
    let entry: MealPlanEntryRecord
}

/// The grocery list (#50; Android's GroceryRepository). Every write lands at once.
protocol GroceryRepository: AnyObject {
    /// Every item on the list, in the order added. Re-emits on change.
    func observeItems() -> AnyPublisher<[GroceryItem], Never>

    /// Adds `lines` at the end of the list, each in the aisle its name belongs to. Blank lines
    /// are skipped.
    func add(_ lines: [NewGroceryLine]) async

    func setChecked(_ ids: [Int64], checked: Bool) async

    /// Moves items to another aisle: the user's choice, kept from then on.
    func setAisle(_ ids: [Int64], aisle: Aisle) async
    /// Files items the keyword table left in Other into the model's `aisle` (#104), only while
    /// they are still in Other: a move the user made meanwhile stands.
    func fileFromOther(_ ids: [Int64], aisle: Aisle) async

    /// Deletes items; nil when none were there.
    func delete(_ ids: [Int64]) async -> DeletedGroceries?

    /// Deletes every checked item; nil when none was checked.
    func clearChecked() async -> DeletedGroceries?

    /// Undoes `delete` or `clearChecked`: the same items, in the same places.
    func restore(_ deleted: DeletedGroceries) async

    /// The recipes planned from day `start` to `end` inclusive, with their ingredients.
    func plannedIngredients(start: Int64, end: Int64) async -> [PlannedIngredients]
}

/// The pantry (#51). Shaped like GroceryRepository: every write lands at once.
protocol PantryRepository: AnyObject {
    /// Every item, re-emitted on change.
    func observeItems() -> AnyPublisher<[PantryItem], Never>

    /// Everything in the pantry now, for a one-off match (the grocery sheet's first ticks).
    func items() async -> [PantryItem]

    /// Adds an item in stock, in its `aisle` or else the one its name belongs to. A blank name
    /// is ignored.
    func add(_ item: NewPantryItem) async

    func setInStock(_ ids: [Int64], inStock: Bool) async

    /// Back in stock, bought on `day` (an epoch day).
    func restock(_ ids: [Int64], day: Int64) async

    /// A blank name is ignored; a blank quantity is none.
    func edit(_ id: Int64, _ edit: PantryEdit) async

    /// The rows `ids` as they are now, to undo a change to them.
    func snapshot(_ ids: [Int64]) async -> PantrySnapshot

    /// Deletes an item; nil when it was already gone.
    func delete(_ id: Int64) async -> PantrySnapshot?

    /// Puts rows back exactly as `snapshot` had them.
    func restore(_ snapshot: PantrySnapshot) async
}

/// Pantry rows as they were, for `restore`. Opaque to callers.
struct PantrySnapshot: Equatable {
    let items: [PantryItemRecord]
}

/// What a grocery delete removed, for `restore`. Opaque to callers.
struct DeletedGroceries: Equatable {
    let items: [GroceryItemRecord]
}

/// Today and the first day of the week on the user's calendar (#49; Android's PlanCalendar).
/// A seam so the Week and plan-sheet ViewModels never read the clock, zone or locale.
protocol PlanCalendar {
    /// Today, as an epoch day (see `PlanDays`).
    func today() -> Int64

    /// 1 = Sunday … 7 = Saturday: the locale's own, never a fixed Monday (owner's call).
    func firstDayOfWeek() -> Int

    /// Now, in epoch millis: when a calendar file (#52) was made.
    func now() -> Int64
}

struct SystemPlanCalendar: PlanCalendar {
    let clock: Clock

    func today() -> Int64 { PlanDays.today(millis: clock.now()) }
    func firstDayOfWeek() -> Int { Calendar.current.firstWeekday }
    func now() -> Int64 { clock.now() }
}

/// The user's global defaults. Read and written through the vars; `settings` publishes them so
/// a screen left open underneath Settings follows a change as it is made (#24), like
/// Android's `AppPreferences.settings` Flow.
protocol AppPreferences: AnyObject {
    var unitSystem: UnitSystem { get set }
    var convertLiquids: Bool { get set }
    var temperatureUnit: TemperatureUnit { get set }
    var darkWhileCooking: Bool { get set }
    /// A morning notification when something in the pantry is about to expire (#52). Off by
    /// default; Settings turns it on only once notifications are allowed.
    var expiryReminders: Bool { get set }
    /// Chef mode (#100): short steps written on the device, in the reading view and cook mode.
    /// Off by default, and only offered behind the `chefMode` flag on a phone that can do it.
    var chefMode: Bool { get set }
    /// Ingredient amounts inside steps (#101): "Add the carrots" reads "Add 2 carrots". Off by
    /// default, and shown only with the `amountsInSteps` flag on.
    var amountsInSteps: Bool { get set }
    /// The Recipes screen's sort (#102): a view preference, kept so it survives leaving the screen.
    var recipeSort: RecipeSort { get set }

    /// The current values first, then every change, never repeating a value. Delivery may be
    /// asynchronous, so a subscriber receives on main.
    var settings: AnyPublisher<AppSettings, Never> { get }
}

extension AppPreferences {
    /// The values as they are right now.
    var current: AppSettings {
        AppSettings(
            unitSystem: unitSystem,
            convertLiquids: convertLiquids,
            temperatureUnit: temperatureUnit,
            darkWhileCooking: darkWhileCooking,
            expiryReminders: expiryReminders,
            chefMode: chefMode,
            amountsInSteps: amountsInSteps,
            recipeSort: recipeSort
        )
    }
}

/// Chef mode's seam onto the on-device model (#100). `FoundationModelsStepShortener` is the real
/// one (Apple's Foundation Models); tests use `FakeStepShortener`. Nothing else in the app
/// imports the model's framework.
protocol StepShortener: AnyObject {
    /// Whether this phone can write short steps, and for recipes in which languages.
    func support() async -> ChefSupport
    /// The model's short version of `step`, written in `language` (the recipe's, e.g. "en"), or
    /// nil when it can't write one right now. Unchecked: `ShortStepCheck` decides if it shows.
    func shorten(_ step: String, language: String) async -> String?
}

/// What Settings says about Chef mode on this phone.
enum ChefSupport: Equatable {
    /// It can write short steps for recipes in `languages` (codes such as "en").
    case available(Set<String>)
    /// Apple Intelligence is turned off.
    case notEnabled
    /// The model is still being set up (downloading).
    case notReady
    /// This phone can't run the on-device model.
    case unsupported

    func covers(_ language: String?) -> Bool {
        if case .available(let languages) = self, let language { return languages.contains(language) }
        return false
    }

    var isAvailable: Bool {
        if case .available = self { return true }
        return false
    }
}

/// Chef mode's short steps (#100): written on the device, checked, and cached.
protocol ShortStepRepository: AnyObject {
    func support() async -> ChefSupport
    /// `recipe`'s saved short steps, one per step (nil: show it as written), then every change.
    func observe(_ recipe: Recipe) -> AnyPublisher<[String?], Never>
    /// Writes the short steps `recipe` is missing, one at a time in order, each saved once
    /// `ShortStepCheck` has judged it, and drops rows for steps it no longer has. A step the model
    /// can't do right now is asked again next time; one it failed is not.
    func fill(_ recipe: Recipe) async
}

/// One snapshot of AppPreferences, as its `settings` publisher emits them.
struct AppSettings: Equatable {
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
    var expiryReminders = false
    var chefMode = false
    var amountsInSteps = false
    var recipeSort: RecipeSort = .recentlyViewed
}
