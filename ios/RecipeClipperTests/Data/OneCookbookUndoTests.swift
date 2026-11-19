import XCTest
@testable import RecipeClipper

/// One cookbook (#345, Android's OneCookbookUndoTest): Undo of a delete takes the same limit as every
/// other way in. On the free tier (locked), a restore that would take the library past 50 restores
/// nothing, so delete, add one, Undo can't grow it by a recipe each time; a burst is all or nothing.
/// Unlocked, or with the free tier off, Undo works as it always did. A real in-memory database,
/// through `DefaultRecipeRepository`.
final class OneCookbookUndoTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    private func repository(_ limit: LibraryLimit = .capped(max: 50)) -> DefaultRecipeRepository {
        DefaultRecipeRepository(
            db: db, source: DataStubSource(), clock: DataTestClock(5_000), sleep: { _ in },
            library: FixedLibraryLimit(limit: limit)
        )
    }

    @discardableResult
    private func fill(_ n: Int) async throws -> [Int64] {
        var ids: [Int64] = []
        for i in 0..<n {
            let row = dataRecipeRecord("https://old.com/\(i)", viewedAt: Int64(i))
            ids.append(try await db.write { try RecipeDao(db: $0).upsert(row, limit: .unlimited) })
        }
        return ids
    }

    /// What a free-tier user does: adds one under the limit as it stands.
    private func add(_ url: String, limit: LibraryLimit = .capped(max: 50)) async throws {
        let row = dataRecipeRecord(url, viewedAt: 9_000)
        _ = try await db.write { try RecipeDao(db: $0).upsert(row, limit: limit) }
    }

    private func count() async throws -> Int { try await db.read { try RecipeDao(db: $0).count() } }

    func testDeleteOneAtTheLimitAddOneThenUndoDoesNotRestoreTheLoophole() async throws {
        let ids = try await fill(50)
        let recipes = repository()
        let gone = await recipes.delete(id: ids[0])!
        var total = try await count()
        XCTAssertEqual(total, 49)
        try await add("https://a.com/new")
        total = try await count()
        XCTAssertEqual(total, 50)

        let outcome = await recipes.restore(gone)

        XCTAssertEqual(outcome, .libraryFull)
        total = try await count()
        XCTAssertEqual(total, 50)
        let row = try await db.get(ids[0])
        XCTAssertNil(row, "the deleted recipe stays deleted")
    }

    func testUndoStraightAwayAtTheLimitRestoresItTheLibraryBeingBackToItsSize() async throws {
        let ids = try await fill(50)
        let recipes = repository()
        let gone = await recipes.delete(id: ids[0])!
        let outcome = await recipes.restore(gone)
        XCTAssertEqual(outcome, .restored)
        let total = try await count()
        XCTAssertEqual(total, 50)
        let row = try await db.get(ids[0])
        XCTAssertNotNil(row)
    }

    func testUndoUnderTheLimitRestoresWithItsListMembership() async throws {
        let ids = try await fill(10)
        let recipes = repository()
        let lunch = try await db.listId(named: "Lunch")
        try await db.write { conn in
            try conn.run("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?1, ?2, 1)", ids[3], lunch)
        }
        let gone = await recipes.delete(id: ids[3])!
        let outcome = await recipes.restore(gone)
        XCTAssertEqual(outcome, .restored)
        let refs = try await db.crossRefs(ids[3])
        XCTAssertEqual(refs.map(\.listId), [lunch])
    }

    func testABurstThatFitsIsRestoredWhole() async throws {
        let ids = try await fill(50)
        let recipes = repository()
        var burst: [DeletedRecipe] = []
        for id in ids.prefix(3) { burst.append(await recipes.delete(id: id)!) }
        try await add("https://a.com/new")
        // 48 + 3 would be 51: free one more place and the three fit (47 + 3 = 50).
        let added = try await db.read { try RecipeDao(db: $0).findByUrl("https://a.com/new")! }
        _ = await recipes.delete(id: added.id)

        let outcome = await recipes.restoreAll(burst)

        XCTAssertEqual(outcome, .restored)
        let total = try await count()
        XCTAssertEqual(total, 50)
    }

    func testABurstThatWouldNotFitRestoresNoneOfIt() async throws {
        let ids = try await fill(50)
        let recipes = repository()
        var burst: [DeletedRecipe] = []
        for id in ids.prefix(3) { burst.append(await recipes.delete(id: id)!) }
        try await add("https://a.com/one")
        try await add("https://a.com/two")
        var total = try await count()
        XCTAssertEqual(total, 49)

        let outcome = await recipes.restoreAll(burst)

        XCTAssertEqual(outcome, .libraryFull)
        total = try await count()
        XCTAssertEqual(total, 49, "all or nothing: not even the first")
        for id in ids.prefix(3) {
            let row = try await db.get(id)
            XCTAssertNil(row)
        }
    }

    func testUnlockedUndoRestoresPastTheLimitAsItAlwaysDid() async throws {
        let ids = try await fill(50)
        let gone = await repository().delete(id: ids[0])!
        try await add("https://a.com/new")

        let outcome = await repository(.unlimited).restore(gone) // the free tier off, or the unlock bought

        XCTAssertEqual(outcome, .restored)
        let total = try await count()
        XCTAssertEqual(total, 51)
    }

    func testWithOneCookbookOffTheOldRulesAreUntouchedAndUndoRestores() async throws {
        let ids = try await fill(20)
        let old = repository(.free(max: 20))
        let gone = await old.delete(id: ids[0])!
        try await add("https://a.com/new", limit: .unlimited)

        let outcome = await old.restore(gone)

        XCTAssertEqual(outcome, .restored)
        let total = try await count()
        XCTAssertEqual(total, 21)
    }

    func testTheTourSampleCountsTowardNothingDeletedOrNot() async throws {
        var sample = dataRecipeRecord(SampleRecipe.sourceUrl, viewedAt: 5)
        sample.contentOrigin = "MANUAL"
        let sampleId = try await db.write { [sample] in try RecipeDao(db: $0).upsert(sample, limit: .unlimited) }
        try await fill(49)
        let recipes = repository()
        let gone = await recipes.delete(id: sampleId)!
        try await add("https://a.com/new")
        var total = try await count()
        XCTAssertEqual(total, 50)

        let outcome = await recipes.restore(gone)

        XCTAssertEqual(outcome, .restored, "the sample takes no place, so it comes back")
        total = try await count()
        XCTAssertEqual(total, 50)
    }
}
