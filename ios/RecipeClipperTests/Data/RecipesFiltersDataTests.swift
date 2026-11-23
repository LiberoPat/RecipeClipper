import XCTest
@testable import RecipeClipper

/// The filter sheet's views (#454) through the real repository and SQLite: one view selects what its own screen
/// does, and several together select exactly the recipes every one of those screens holds, the search (`instr`,
/// a literal `%`) still applying. Android's `RecipeQuickFiltersDaoTest.theSheetsViewsTogetherSelectWhatEveryViewsScreenHolds`.
final class RecipesFiltersDataTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    private func insert(_ url: String, title: String, origin: String = "PARSED", viewedAt: Int64) async throws -> Int64 {
        var row = dataRecipeRecord(url, viewedAt: viewedAt, title: title)
        row.contentOrigin = origin
        return try await db.write { [row] in try RecipeDao(db: $0).upsert(row, limit: .unlimited) }
    }

    private func markCooked(_ id: Int64) async throws {
        try await db.write {
            try $0.run(
                "INSERT INTO cooked_photos (uid, recipeId, fileName, day, note, createdAt, updatedAt) VALUES (?, ?, NULL, 20000, NULL, 1, 1)",
                "c\(id)", id
            )
        }
    }

    func testTheSheetsViewsTogetherSelectWhatEveryViewsScreenHolds() async throws {
        let repository = DefaultRecipeRepository(db: db, source: DataStubSource(), clock: DataTestClock(5_000), sleep: { _ in })
        let typedLoose = try await insert("manual:a", title: "100% Typed loose", origin: "MANUAL", viewedAt: 1)
        let typedListed = try await insert("manual:b", title: "Typed listed", origin: "MANUAL", viewedAt: 2)
        let chatLoose = try await insert("manual:chat", title: "Chat loose", origin: "CHAT", viewedAt: 3)
        let parsedCooked = try await insert("https://a.com/d", title: "Parsed cooked", viewedAt: 4)
        let sample = try await insert(SampleRecipe.sourceUrl, title: "Sample", origin: "MANUAL", viewedAt: 5)
        try await db.putInList(typedListed, try await db.favoritesId())
        try await markCooked(typedLoose)
        try await markCooked(parsedCooked)

        func ids(_ views: Set<RecipeFilter>, _ query: String = "") async -> Set<Int64> {
            Set(await firstValue(repository.observeHistory(query: query, views: views))?.map(\.id) ?? [])
        }
        func view(_ filter: RecipeFilter) async -> Set<Int64> {
            Set(await firstValue(repository.observeHistory(query: "", filter: filter))?.map(\.id) ?? [])
        }

        for filter in RecipeFilter.views {
            let single = await view(filter)
            let asSet = await ids([filter])
            XCTAssertEqual(asSet, single, "\(filter)")
        }
        let notInList = await view(.notInAList)
        XCTAssertEqual(notInList, [typedLoose, chatLoose, parsedCooked, sample])
        let typed = await view(.typed)
        XCTAssertEqual(typed, [typedLoose, typedListed])

        let combos: [Set<RecipeFilter>] = [
            [.notInAList, .typed], [.notInAList, .cooked], [.cooked, .typed], [.notInAList, .chat], [.notInAList, .cooked, .typed]
        ]
        for combo in combos {
            var expected: Set<Int64>?
            for filter in combo {
                let one = await view(filter)
                expected = expected.map { $0.intersection(one) } ?? one
            }
            let together = await ids(combo)
            XCTAssertEqual(together, expected, "\(combo)")
        }
        let all3 = await ids([.notInAList, .cooked, .typed])
        XCTAssertEqual(all3, [typedLoose])
        let none = await ids([.chat, .cooked])
        XCTAssertEqual(none, [])
        // The search still applies inside, as a literal substring.
        let searched = await ids([.notInAList, .typed], "100%")
        XCTAssertEqual(searched, [typedLoose])
        let everything = await ids([])
        XCTAssertEqual(everything, [typedLoose, typedListed, chatLoose, parsedCooked, sample])
    }
}
