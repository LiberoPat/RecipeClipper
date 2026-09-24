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

/// Connectivity that never changes: one value, then silence. The default where no reconnect
/// behaviour is wanted (previews, and tests that aren't about it).
final class StaticConnectivity: Connectivity {
    private let online: Bool
    init(online: Bool = true) { self.online = online }

    func onlineUpdates() -> AsyncStream<Bool> {
        AsyncStream { $0.yield(online) }
    }
}

/// Fetches, parses and persists recipes.
protocol RecipeRepository: AnyObject {
    /// The share-target path: clean the link, fetch, parse, then persist (upsert + history
    /// cull). If the fetch fails but the link was saved before, the saved copy is returned
    /// (and its lastViewedAt bumped), so anything opened once still opens offline.
    func importFromUrl(_ sharedUrl: String) async -> ParseResult

    /// Opens a recipe from history, a list or home. Counts as a view. Nil if it's gone.
    func open(id: Int64) async -> Recipe?

    func setChecked(id: Int64, checked: Set<Int>) async

    /// Saves the user's note on a recipe. A blank note is stored as no note.
    func setNotes(id: Int64, notes: String) async

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
