#if DEBUG
import Foundation

/// Debug builds only: `-uiTestSeed <scenario>` makes the app build its container for the
/// XCUITest suite (RecipeClipperUITests) instead of the real one. Nothing touches the disk or
/// the network:
///   - an in-memory database, seeded per scenario before the first screen draws;
///   - a stub RecipeSource, so any import resolves offline to one canned recipe;
///   - a throwaway UserDefaults suite, wiped at launch unless `-uiTestKeepPrefs` is also passed
///     (which is how a test proves a setting survives a relaunch);
///   - feature flags (#87) in their own throwaway suite, wiped likewise, then overridden on
///     through the store for each key in `-uiTestFlags key1,key2` (`UITestSupport.launch(flags:)`).
///
/// Scenarios:
///   empty     no recipes; only the six seeded lists
///   many      "Recipe 1" (newest) … "Recipe 10" (oldest), in no list
///   standard  four recipes, some in lists — see `seedStandard`
///   cook      one recipe with timed steps, for cook mode — see `seedCook`
///   chef      one recipe with a long step, for Chef mode (#100); every launch gets a stub model
enum UITestSeeding {
    static let flag = "-uiTestSeed"
    static let keepPrefsFlag = "-uiTestKeepPrefs"
    static let defaultsSuite = "RecipeClipperUITests"
    static let flagsFlag = "-uiTestFlags"
    static let flagsSuite = "RecipeClipperUITestsFlags"

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
        let flagStore = UserDefaultsFeatureFlagStore(suiteName: flagsSuite)
        if !arguments.contains(keepPrefsFlag) { flagStore.clear() }
        let flags = FeatureFlags(store: flagStore)
        if let index = arguments.firstIndex(of: flagsFlag), index + 1 < arguments.count {
            for key in arguments[index + 1].split(separator: ",") {
                if let flag = Flag(rawValue: String(key)) { flags.set(flag, true) }
            }
        }
        // "I made this" (#116): photos in a throwaway folder, emptied at every launch.
        let photoDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("UITestPhotos")
        try? FileManager.default.removeItem(at: photoDirectory)
        let photoStore = FilePhotoStore(directory: photoDirectory)
        return AppContainer(
            recipeRepository: DefaultRecipeRepository(db: database, source: StubRecipeSource(), clock: clock, photos: photoStore),
            listRepository: DefaultListRepository(db: database, clock: clock),
            mealPlanRepository: DefaultMealPlanRepository(db: database, clock: clock),
            groceryRepository: DefaultGroceryRepository(db: database, clock: clock),
            pantryRepository: DefaultPantryRepository(db: database, clock: clock),
            backupRepository: DefaultBackupRepository(db: database, clock: clock, photos: photoStore),
            preferences: UserDefaultsAppPreferences(defaults: defaults),
            clock: clock,
            clipFixtureHTML: clipFixtureHTML,
            featureFlags: flags,
            shortStepRepository: DefaultShortStepRepository(db: database, shortener: UITestStepShortener(), clock: clock),
            cookedPhotoRepository: DefaultCookedPhotoRepository(db: database, store: photoStore, clock: clock)
        )
    }

    /// The page "Clip it yourself" shows under test, in place of the live one. XCUITest can't
    /// drag a selection in a web view reliably, so the page carries buttons that select a block
    /// by script, as a finger would; the app hears it through the page's `selectionchange`, the
    /// same path a person's selection takes. Android's ClipScreenTest uses the same page.
    static let clipFixtureHTML = """
    <!doctype html><html><head><meta name="viewport" content="width=device-width">
    <style>body{font:16px -apple-system,sans-serif;margin:16px}button{font-size:14px;margin:2px}</style>
    <script>function sel(id){var r=document.createRange();r.selectNodeContents(document.getElementById(id));
    var s=getSelection();s.removeAllRanges();s.addRange(r);}</script></head><body>
    <p><button onclick="sel('title')">Select title</button><button onclick="sel('ingredients')">Select ingredients</button><button onclick="sel('steps')">Select steps</button><button onclick="sel('step2')">Select last step</button></p>
    <h1 id="title">Brown Butter Oat Cookies</h1>
    <img id="photo" src="/img/cookies.jpg" width="200" height="120" alt="Cookies photo" style="background:#c98b4e">
    <h2>Ingredients</h2>
    <ul id="ingredients"><li>1 cup (226 g) unsalted butter</li><li>1 cup packed brown sugar</li><li>3 cups rolled oats</li></ul>
    <h2>Method</h2>
    <ol id="steps"><li id="step1">Brown the butter until it smells nutty.</li><li id="step2">Bake at 350°F for 11 to 13 minutes.</li></ol>
    </body></html>
    """

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
                    case "cook": try seedCook(conn, now: now)
                    case "chef": try seedChef(conn, now: now)
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

    /// One recipe for cook mode, "Weeknight Chili", in no list. Four steps: two state a time
    /// ("20 minutes" for a timer to start, pause and reset; "3 seconds" for one to finish
    /// while the test waits, since XCUITest can't move the app's clock) and two don't.
    private static func seedCook(_ conn: SQLiteConnection, now: Int64) throws {
        try RecipeDao(db: conn).insert(RecipeRecord(
            sourceUrl: "https://example.com/chili", title: "Weeknight Chili", imageUrl: nil,
            ingredients: ["2 cups flour", "1 cup milk", "1 lb beef"],
            instructions: [
                "Brown the beef in a large pot.",
                "Simmer for 20 minutes.",
                "Rest off the heat for 3 seconds.",
                "Serve with rice."
            ],
            prepTime: "10m", cookTime: "20m", totalTime: "30m", servings: "4 servings",
            sourceType: SourceType.blog.rawValue, lastViewedAt: now - minute
        ))
    }

    /// The step `UITestStepShortener` writes a short version of (#100).
    static let chefStep = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    static let chefShortStep = "Oven to 350°F; butter a 9-inch tin."

    private static func seedChef(_ conn: SQLiteConnection, now: Int64) throws {
        try RecipeDao(db: conn).insert(RecipeRecord(
            sourceUrl: "https://example.com/sponge", title: "Sponge Cake", imageUrl: nil,
            ingredients: ["4 eggs", "1 cup sugar"],
            instructions: [chefStep, "Serve."],
            prepTime: nil, cookTime: nil, totalTime: nil, servings: "8",
            sourceType: SourceType.blog.rawValue, lastViewedAt: now - minute
        ))
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

/// Chef mode's model under UI test (#100): English only, one canned short step, so the tests
/// never depend on Apple Intelligence being on the simulator.
private final class UITestStepShortener: StepShortener {
    func support() async -> ChefSupport { .available(["en"]) }

    func shorten(_ step: String, language: String) async -> String? {
        step == UITestSeeding.chefStep ? UITestSeeding.chefShortStep : nil
    }
}
#endif
