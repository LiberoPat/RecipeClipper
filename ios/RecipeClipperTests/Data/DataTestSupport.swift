import Combine
import XCTest
@testable import RecipeClipper

// Helpers for the data-layer tests. Prefixed `Data…` so they can't collide with fakes other
// test files define.

/// A clock a test sets by hand.
final class DataTestClock: Clock {
    var time: Int64
    init(_ time: Int64 = 1_000) { self.time = time }
    func now() -> Int64 { time }
}

/// A RecipeSource returning whatever the test staged for a URL (`.error(.fetchFailed)` if none).
final class DataStubSource: RecipeSource {
    var results: [String: ParseResult] = [:]
    private(set) var fetched: [String] = []

    func fetch(url: String) async -> ParseResult {
        fetched.append(url)
        return results[url] ?? .error(.fetchFailed("offline"))
    }
}

/// A RecipeSource answering each fetch with the next staged result, the last one repeating.
final class DataScriptedSource: RecipeSource {
    private let script: [ParseResult]
    private(set) var fetches = 0

    init(_ script: [ParseResult]) { self.script = script }

    func fetch(url: String) async -> ParseResult {
        defer { fetches += 1 }
        return script[min(fetches, script.count - 1)]
    }
}

/// Stands in for the repository's retry pause: records each requested wait instead of waiting.
final class DataPauseRecorder {
    private(set) var durations: [Duration] = []
    var sleep: DefaultRecipeRepository.RetrySleep {
        { [unowned self] duration in self.durations.append(duration) }
    }
}

func dataRecipeRecord(
    _ url: String,
    viewedAt: Int64,
    title: String? = nil,
    ingredients: [String] = ["1 cup flour", "2 eggs"],
    checked: Set<Int> = []
) -> RecipeRecord {
    RecipeRecord(
        sourceUrl: url,
        title: title ?? "Recipe \(url)",
        imageUrl: nil,
        ingredients: ingredients,
        instructions: ["Mix.", "Bake 20 minutes."],
        prepTime: "5m",
        cookTime: "20m",
        totalTime: "25m",
        servings: "4",
        sourceType: "BLOG",
        lastViewedAt: viewedAt,
        checkedIngredients: checked
    )
}

func dataRecipe(_ url: String, title: String = "Soup", ingredients: [String] = ["1 onion"]) -> Recipe {
    Recipe(
        name: title, image: nil, ingredients: ingredients, instructions: ["Cook."],
        prepTime: nil, cookTime: nil, totalTime: "30m", yield: "4", sourceUrl: url
    )
}

extension AppDatabase {
    /// DAO helpers for tests: each is its own write / read.
    @discardableResult
    func upsert(_ row: RecipeRecord, limit: Int = historyLimit) async throws -> Int64 {
        try await write { try RecipeDao(db: $0).upsert(row, historyLimit: limit) }
    }

    func get(_ id: Int64) async throws -> RecipeRecord? {
        try await read { try RecipeDao(db: $0).get(id) }
    }

    func recipeCount() async throws -> Int {
        try await read { try $0.queryOne("SELECT COUNT(*) FROM recipes") { $0.int(0) } ?? -1 }
    }

    func putInList(_ recipeId: Int64, _ listId: Int64, at: Int64 = 1) async throws {
        try await write {
            try $0.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, ?, ?)",
                       recipeId, listId, at)
        }
    }

    func crossRefs(_ recipeId: Int64) async throws -> [ListMembership] {
        try await read { try RecipeDao(db: $0).crossRefsFor(recipeId) }
    }

    func history(_ query: String = "") async throws -> [RecipeSummaryRecord] {
        try await read { try RecipeDao(db: $0).history(query: query) }
    }

    func allLists(for recipeId: Int64 = ListDao.noRecipe) async throws -> [ListRecord] {
        try await read { try ListDao(db: $0).lists(recipeId: recipeId) }
    }

    func favoritesId() async throws -> Int64 {
        try await allLists().first { $0.isFavorites }!.id
    }

    func listId(named name: String) async throws -> Int64 {
        try await allLists().first { $0.name == name }!.id
    }
}

extension XCTestCase {
    /// The first value a publisher emits (the observe* publishers emit the current state on
    /// subscribe).
    func firstValue<T>(_ publisher: AnyPublisher<T, Never>, timeout: TimeInterval = 2) async -> T? {
        var value: T?
        let done = expectation(description: "first value")
        let cancellable = publisher.first().sink { value = $0; done.fulfill() }
        await fulfillment(of: [done], timeout: timeout)
        cancellable.cancel()
        return value
    }
}
