import Foundation

// Domain types shared by every layer. Ported from Android's data/model/Recipe.kt,
// RecipeList.kt and ServingsScale.kt. Times are epoch milliseconds (Int64) to match the
// Android schema, so the same rules (history cap ordering, addedAt ordering) port verbatim.

/// Where a recipe was parsed from. Kept so a saved recipe can be re-fetched the right way.
enum SourceType: String, Equatable {
    case blog = "BLOG"
    case reddit = "REDDIT"
}

/// Whose words a recipe's content is (#29, #37), stored by name in `contentOrigin`. Only
/// `parsed` and `extracted` are the source's: every other value is the user's version, which a
/// re-share never refreshes. "Update from source" is the one way back to the source's.
enum ContentOrigin: String, Equatable, CaseIterable {
    /// As parsed from its link. Refreshed on every re-share.
    case parsed = "PARSED"
    /// Parsed, then changed by the user.
    case edited = "EDITED"
    /// Picked from the page by hand (#37). Stays CLIPPED when edited.
    case clipped = "CLIPPED"
    /// Typed in by hand; its link is a synthetic `ManualRecipe` key, never fetched.
    case manual = "MANUAL"
    /// Picked from the page's text by the on-device model (#103), every line checked to be on
    /// the page. The source's, like `parsed`: refreshed on every re-share.
    case extracted = "EXTRACTED"

    /// The user's version: a re-share opens it as it is, without fetching.
    var isUsersVersion: Bool { !Self.isSources(rawValue) }

    /// The origin after the user saves an edit: the source's becomes EDITED; the rest keep theirs.
    func afterEdit() -> ContentOrigin { isUsersVersion ? self : .edited }

    /// A stored `contentOrigin` that is the source's, so a re-share refreshes it.
    static func isSources(_ name: String?) -> Bool {
        name == ContentOrigin.parsed.rawValue || name == ContentOrigin.extracted.rawValue
    }

    /// Stored by name; an unknown name (a newer app's) reads as the user's version, EDITED, so
    /// it is never overwritten by a re-share.
    static func from(name: String?) -> ContentOrigin {
        guard let name else { return .parsed }
        return ContentOrigin(rawValue: name) ?? .edited
    }
}

/// A recipe typed in by hand has no link, but `sourceUrl` is the unique upsert key, so it gets
/// a synthetic one: `manual:<uuid>`. It is never fetched or cleaned, and has no host, so no
/// source credit, Open original or Report is shown for it.
enum ManualRecipe {
    static let scheme = "manual:"

    static func newSourceUrl(_ uuid: String) -> String { scheme + uuid }

    static func isManual(_ sourceUrl: String) -> Bool { sourceUrl.hasPrefix(scheme) }
}

/// A recipe as the rest of the app sees it, distinct from the database row: the repository
/// maps between them. A freshly parsed recipe has no `id` yet (0); one that came out of the
/// database always does.
struct Recipe: Equatable {
    var name: String
    var image: String?
    var ingredients: [String]
    var instructions: [String]
    var prepTime: String?
    var cookTime: String?
    var totalTime: String?
    var yield: String?
    var sourceUrl: String
    var sourceType: SourceType = .blog
    var id: Int64 = 0
    /// Indexes into `ingredients` the user has ticked off. Persisted so cooking can resume.
    var checkedIngredients: Set<Int> = []
    var lastViewedAt: Int64 = 0
    /// The user's own free-text note, or nil. Never parsed, so re-sharing keeps it.
    var notes: String? = nil
    /// The recipe's language tag as the parser chose it ("en", "de-de"; see `LanguageWords`),
    /// which picks the words its lines are read with. Nil for a recipe stored before #14, which
    /// is detected from its words when shown.
    var language: String? = nil
    /// Where the cook stands on this recipe: cook mode, steps done, step timers.
    var cook = CookProgress()
    /// The servings the user chose, or nil for the recipe's own yield.
    var servingsTarget: Int? = nil
    /// Whose words the content is; see `ContentOrigin`.
    var origin: ContentOrigin = .parsed
    /// When the user last saved an edit, or nil if never.
    var editedAt: Int64? = nil

    /// "Update from source" applies: the user's version of a recipe that has a real link.
    var canUpdateFromSource: Bool {
        origin.isUsersVersion && origin != .manual && !ManualRecipe.isManual(sourceUrl)
    }
}

/// What a list row (history, home) needs, without loading every ingredient and step.
struct RecipeSummary: Equatable, Identifiable {
    let id: Int64
    let title: String
    let imageUrl: String?
    let totalTime: String?
    let lastViewedAt: Int64
    /// In at least one list. Derived from list membership, never stored.
    let isSaved: Bool
    /// Picked from the page by hand (#37, origin CLIPPED): the row says "Clipped by you".
    var isClipped: Bool = false
    /// The latest day (epoch day) of the user's own photos of it (#116); nil for none.
    var lastCookedDay: Int64? = nil
}

/// How the Recipes screen orders its rows (#102). Remembered in `AppPreferences` by its
/// Android name, so it survives leaving the screen; an unknown stored name reads as
/// `.recentlyViewed`.
enum RecipeSort: String, CaseIterable {
    case recentlyViewed = "RECENTLY_VIEWED", name = "NAME", dateAdded = "DATE_ADDED"
    /// Recipes with the user's own photos first, most recently cooked first (#116).
    case recentlyCooked = "RECENTLY_COOKED"

    init(storedName: String?) {
        self = storedName.flatMap(RecipeSort.init(rawValue:)) ?? .recentlyViewed
    }
}

/// A list of recipes as a screen needs it. `isFavorites` is carried from the column rather
/// than matched on the name, so it survives a rename. `containsRecipe` is meaningful only
/// where a recipe was named in the query (the save-to-list sheet).
struct RecipeList: Equatable, Identifiable {
    let id: Int64
    var name: String
    let isBuiltIn: Bool
    let isFavorites: Bool
    var recipeCount: Int
    var containsRecipe: Bool = false
}

/// `base` is what the recipe was written for; `target` is what the user has picked.
struct ServingsScale: Equatable {
    var base: Int
    var target: Int
}

/// Why a parse or a load failed, as a cause rather than a sentence: the UI chooses the copy.
/// `fetchFailed`'s detail is diagnostic (the platform error message), shown in parentheses.
enum ParseError: Equatable, Error {
    /// The page loaded but carried no recipe data. Never retried automatically; "Try again" is
    /// still offered, because a captive portal's login page lands here too.
    case noRecipeFound
    /// The site answered, but refused: 403, 404, 429 or any 5xx. Usually a bot block, and not a
    /// stable one (the same site can refuse one minute and answer the next, and some send 404
    /// as a disguise for a block), so this reads as "try again", not "unsupported".
    case blocked(httpStatus: Int)
    /// No connection at all (URLError notConnectedToInternet, networkConnectionLost,
    /// dataNotAllowed, internationalRoamingOff). Not retried; the recipe screen reloads by
    /// itself once the connection returns.
    case offline
    /// Any other network failure (DNS, TLS, a timeout) or any HTTP status that isn't a block.
    /// `timedOut` marks a timeout: those are not retried automatically, so a dead Wi-Fi fails
    /// in one timeout rather than two.
    case fetchFailed(String?, timedOut: Bool = false)
    case saveFailed
    case notSaved
    case nothingToShow

    /// The statuses that mean "the site refused", as opposed to some other HTTP failure.
    static func isBlockStatus(_ status: Int) -> Bool {
        status == 403 || status == 404 || status == 429 || (500...599).contains(status)
    }

    /// The cause for a non-2xx response.
    static func forHttpStatus(_ status: Int) -> ParseError {
        isBlockStatus(status) ? .blocked(httpStatus: status) : .fetchFailed("HTTP \(status)")
    }

    /// Worth the repository's single automatic retry: a block, or a network failure that wasn't
    /// a timeout. Never `.offline` (fails straight away), never `.noRecipeFound`. Every error
    /// still offers "Try again" on screen, `.noRecipeFound` included.
    var shouldAutoRetry: Bool {
        switch self {
        case .blocked: return true
        case .fetchFailed(_, let timedOut): return !timedOut
        default: return false
        }
    }

    /// Worth one load in an off-screen browser once the direct fetch (and its retry) has ended
    /// here: a block, which a real browser engine often gets past, or a page with no recipe
    /// data, which may be built by JavaScript. Never `.offline` or a `.fetchFailed`.
    var triesRenderedPage: Bool {
        switch self {
        case .blocked, .noRecipeFound: return true
        default: return false
        }
    }

    /// The recipe screen reloads once when the connection comes back while showing these.
    var reloadsOnReconnect: Bool {
        switch self {
        case .offline, .fetchFailed: return true
        default: return false
        }
    }
}

enum ParseResult: Equatable {
    case success(Recipe)
    case error(ParseError)
    /// The free library is full and every recipe in it is protected (#107): the recipe is shown
    /// but was not saved (its id is 0). Android's `Success(kept = false)`.
    case notKept(Recipe)
}

/// One membership row (Android's RecipeListCrossRef).
struct ListMembership: Equatable {
    let recipeId: Int64
    let listId: Int64
    let addedAt: Int64
}

/// What a delete removed: enough for `restore` to undo it, list membership included.
/// `recipe` is the full stored row (id, ticked ingredients, lastViewedAt all intact).
/// Callers treat it as opaque — they hold it and hand it back.
struct DeletedRecipe: Equatable {
    let recipe: Recipe
    let memberships: [ListMembership]
    /// The row's stable uid (#26), so an undone delete is the same recipe to an export.
    var uid: String? = nil
    /// Its planned meals (#49), which the delete cascades, so undo brings them back too.
    var planEntries: [MealPlanEntryRecord] = []
    /// Its meals in saved menus (#52), which the delete cascades too.
    var menuEntries: [MenuEntryRecord] = []
    /// Its own photos (#116): their rows; the files stay until `RecipeRepository.forget`.
    var cookedPhotos: [CookedPhotoRecord] = []
}
