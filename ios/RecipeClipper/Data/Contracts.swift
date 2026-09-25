import Combine
import Foundation

// The seams between layers. ViewModels depend only on these protocols, never on SQLite,
// URLSession or UserDefaults, so they stay unit-testable with hand-written fakes
// (RecipeClipperTests/Fakes). Mirrors Android's RecipeRepository / ListRepository /
// AppPreferences / Clock interfaces one to one.

/// Everything shared lands in history, capped at this many recipes that are in no list.
let historyLimit = 50

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
    func importFromUrl(_ sharedUrl: String, renderedPage: String?) async -> ParseResult

    /// Saves a recipe the user clipped by hand from a page with no recipe data (#37), keyed on
    /// the cleaned `sourceUrl` like an import: a link seen before keeps its id, note and list
    /// membership, and its content is replaced by the clip. Counts as a view. Returns the saved
    /// recipe, or `.error(.saveFailed)`.
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

    /// Deletes items; nil when none were there.
    func delete(_ ids: [Int64]) async -> DeletedGroceries?

    /// Deletes every checked item; nil when none was checked.
    func clearChecked() async -> DeletedGroceries?

    /// Undoes `delete` or `clearChecked`: the same items, in the same places.
    func restore(_ deleted: DeletedGroceries) async

    /// The recipes planned from day `start` to `end` inclusive, with their ingredients.
    func plannedIngredients(start: Int64, end: Int64) async -> [PlannedIngredients]
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
}

struct SystemPlanCalendar: PlanCalendar {
    let clock: Clock

    func today() -> Int64 { PlanDays.today(millis: clock.now()) }
    func firstDayOfWeek() -> Int { Calendar.current.firstWeekday }
}

/// The user's global defaults. Read and written through the vars; `settings` publishes them so
/// a screen left open underneath Settings follows a change as it is made (#24), like
/// Android's `AppPreferences.settings` Flow.
protocol AppPreferences: AnyObject {
    var unitSystem: UnitSystem { get set }
    var convertLiquids: Bool { get set }
    var temperatureUnit: TemperatureUnit { get set }
    var darkWhileCooking: Bool { get set }

    /// The current values first, then every change, never repeating a value. Delivery may be
    /// asynchronous, so a subscriber receives on main.
    var settings: AnyPublisher<AppSettings, Never> { get }
}

extension AppPreferences {
    /// The four values as they are right now.
    var current: AppSettings {
        AppSettings(
            unitSystem: unitSystem,
            convertLiquids: convertLiquids,
            temperatureUnit: temperatureUnit,
            darkWhileCooking: darkWhileCooking
        )
    }
}

/// One snapshot of AppPreferences, as its `settings` publisher emits them.
struct AppSettings: Equatable {
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
}
