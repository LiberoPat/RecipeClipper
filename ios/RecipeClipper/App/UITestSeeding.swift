#if DEBUG
import Foundation

/// Debug builds only: `-uiTestSeed <scenario>` makes the app build its container for the
/// XCUITest suite (RecipeClipperUITests) instead of the real one. Nothing touches the disk or
/// the network:
///   - an in-memory database, seeded per scenario before the first screen draws;
///   - a stub RecipeSource, so any import resolves offline to one canned recipe;
///   - a throwaway UserDefaults suite, wiped at launch unless `-uiTestKeepPrefs` is also passed
///     (which is how a test proves a setting survives a relaunch).
///
/// Scenarios:
///   empty     no recipes; only the six seeded lists
///   many      "Recipe 1" (newest) … "Recipe 10" (oldest), in no list
///   standard  four recipes, some in lists — see `seedStandard`
enum UITestSeeding {
    static let flag = "-uiTestSeed"
    static let keepPrefsFlag = "-uiTestKeepPrefs"
    static let defaultsSuite = "RecipeClipperUITests"

    /// The title every import resolves to under test.
    static let stubRecipeTitle = "Stub Chicken Soup"

    private static var arguments: [String] { ProcessInfo.processInfo.arguments }

    static var scenario: String? {
        guard let index = arguments.firstIndex(of: flag) else { return nil }
        return index + 1 < arguments.count ? arguments[index + 1] : "standard"
    }

    /// The container for a UI-test launch, or nil when the app wasn't launched for one.
    @MainActor
    static func makeContainer() -> AppContainer? {
        guard let scenario else { return nil }
        let clock = SystemClock()
        let database: AppDatabase
        do {
            database = try AppDatabase(path: nil)
        } catch {
            fatalError("UI test database: \(error)")
        }
        seed(database, scenario: scenario, now: clock.now())

        let defaults = UserDefaults(suiteName: defaultsSuite) ?? .standard
        if !arguments.contains(keepPrefsFlag) {
            defaults.removePersistentDomain(forName: defaultsSuite)
        }
        return AppContainer(
            recipeRepository: DefaultRecipeRepository(db: database, source: StubRecipeSource(), clock: clock),
            listRepository: DefaultListRepository(db: database, clock: clock),
            backupRepository: DefaultBackupRepository(db: database, clock: clock),
            preferences: UserDefaultsAppPreferences(defaults: defaults),
            clock: clock
        )
    }

    /// Seeds synchronously so the first frame already shows the scenario: the write runs on
    /// the database's own queue while launch waits for it.
    private static func seed(_ database: AppDatabase, scenario: String, now: Int64) {
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            do {
                try await database.write { conn in
                    switch scenario {
                    case "empty": break
                    case "many": try seedMany(conn, now: now)
                    default: try seedStandard(conn, now: now)
                    }
                }
            } catch {
                fatalError("UI test seed: \(error)")
            }
            done.signal()
        }
        done.wait()
    }

    private static let minute: Int64 = 60_000

    private static func recipe(_ title: String, slug: String, viewedAt: Int64, ingredients: [String]) -> RecipeRecord {
        RecipeRecord(
            sourceUrl: "https://example.com/\(slug)", title: title, imageUrl: nil,
            ingredients: ingredients, instructions: ["Cook the \(slug).", "Serve."],
            prepTime: nil, cookTime: nil, totalTime: nil, servings: "4",
            sourceType: SourceType.blog.rawValue, lastViewedAt: viewedAt
        )
    }

    private static func seedMany(_ conn: SQLiteConnection, now: Int64) throws {
        let dao = RecipeDao(db: conn)
        for n in 1...10 {
            try dao.insert(recipe("Recipe \(n)", slug: "recipe-\(n)", viewedAt: now - Int64(n) * minute,
                                  ingredients: ["1 cup water"]))
        }
    }

    /// Viewed newest first: Chicken Adobo, Spaghetti Carbonara, Banana Bread, Miso Soup.
    /// Chicken Adobo is in Favorites and Dinner (several places, one Home row). The user list
    /// "Weeknights" holds Adobo (added first) and Carbonara (added later), so ordered by
    /// addedAt it reads Carbonara, Adobo — the reverse of the viewing order. Banana Bread and
    /// Miso Soup are in no list.
    private static func seedStandard(_ conn: SQLiteConnection, now: Int64) throws {
        let recipes = RecipeDao(db: conn)
        let lists = ListDao(db: conn)
        let adobo = try recipes.insert(recipe("Chicken Adobo", slug: "adobo", viewedAt: now - 1 * minute,
                                              ingredients: ["2 lb chicken thighs", "1/2 cup soy sauce"]))
        let carbonara = try recipes.insert(recipe("Spaghetti Carbonara", slug: "carbonara", viewedAt: now - 2 * minute,
                                                  ingredients: ["400 g spaghetti", "4 egg yolks"]))
        try recipes.insert(recipe("Banana Bread", slug: "banana-bread", viewedAt: now - 3 * minute,
                                  ingredients: ["3 ripe bananas", "2 cups flour"]))
        try recipes.insert(recipe("Miso Soup", slug: "miso-soup", viewedAt: now - 4 * minute,
                                  ingredients: ["3 tbsp white miso paste", "1 block tofu"]))

        let byName = Dictionary(uniqueKeysWithValues: try lists.lists(recipeId: ListDao.noRecipe).map { ($0.name, $0.id) })
        guard let favorites = byName["Favorites"], let dinner = byName["Dinner"] else {
            fatalError("UI test seed: built-in lists missing")
        }
        try lists.addToList(ListMembership(recipeId: adobo, listId: favorites, addedAt: now - 30 * minute))
        try lists.addToList(ListMembership(recipeId: adobo, listId: dinner, addedAt: now - 30 * minute))
        let weeknights = try lists.create(name: "Weeknights", recipeId: ListDao.noRecipe, now: now - 60 * minute)
        try lists.addToList(ListMembership(recipeId: adobo, listId: weeknights, addedAt: now - 20 * minute))
        try lists.addToList(ListMembership(recipeId: carbonara, listId: weeknights, addedAt: now - 10 * minute))
    }
}

/// Every link resolves, offline, to the same canned recipe under that link, except two paths
/// that stand in for failures: `/no-recipe` (the page loaded, no recipe data) and `/blocked`
/// (the site answered 403 every time, so the repository's one automatic retry fails too).
private struct StubRecipeSource: RecipeSource {
    func fetch(url: String) async -> ParseResult {
        let path = URL(string: url)?.path ?? ""
        if path.hasSuffix("/no-recipe") { return .error(.noRecipeFound) }
        if path.hasSuffix("/blocked") { return .error(.blocked(httpStatus: 403)) }
        return .success(Recipe(
            name: UITestSeeding.stubRecipeTitle,
            image: nil,
            ingredients: ["1 whole chicken", "2 carrots", "8 cups water"],
            instructions: ["Simmer everything for 1 hour.", "Season and serve."],
            prepTime: nil, cookTime: nil, totalTime: nil,
            yield: "4",
            sourceUrl: url
        ))
    }
}
#endif
