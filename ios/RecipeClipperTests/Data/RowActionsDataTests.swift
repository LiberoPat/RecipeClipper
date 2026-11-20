import XCTest
@testable import RecipeClipper

/// The bulk operations behind selecting several recipes (#377): one transaction each, through the
/// real repositories over an in-memory database. Android's `BulkListDaoTest` and
/// `DeleteAllTest`.
final class RowActionsDataTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    private func recipes() -> DefaultRecipeRepository {
        DefaultRecipeRepository(
            db: db, source: DataStubSource(), clock: DataTestClock(5_000), sleep: { _ in },
            library: FixedLibraryLimit(limit: .unlimited)
        )
    }

    private func lists(at now: Int64 = 100) -> DefaultListRepository {
        DefaultListRepository(db: db, clock: DataTestClock(now))
    }

    private func add(_ n: Int) async throws -> [Int64] {
        var ids: [Int64] = []
        for i in 0..<n {
            let row = dataRecipeRecord("https://old.com/\(i)", viewedAt: Int64(i))
            ids.append(try await db.write { try RecipeDao(db: $0).upsert(row, limit: .unlimited) })
        }
        return ids
    }

    private func members(of listId: Int64) async throws -> [Int64] {
        try await db.read { try ListDao(db: $0).recipesIn(listId: listId).map(\.id) }.sorted()
    }

    private func favorites() async throws -> Int64 {
        let all = try await db.allLists()
        return try XCTUnwrap(all.first { $0.isFavorites }).id
    }

    // MARK: Membership

    func testTickingAListForSeveralAddsAllOfThem() async throws {
        let ids = try await add(3)
        let favorites = try await favorites()

        await lists().setMembershipAll(recipeIds: [ids[0], ids[2]], listId: favorites, inList: true)

        let held = try await members(of: favorites)
        XCTAssertEqual(held, [ids[0], ids[2]])
    }

    func testAddingAgainNeverRewritesWhenARecipeWasAdded() async throws {
        let ids = try await add(2)
        let favorites = try await favorites()
        await lists(at: 100).setMembershipAll(recipeIds: [ids[0]], listId: favorites, inList: true)

        await lists(at: 900).setMembershipAll(recipeIds: ids, listId: favorites, inList: true)

        let stamps = try await db.read { conn in
            try conn.query(
                "SELECT recipeId, addedAt FROM recipe_list_cross_ref WHERE listId = ? ORDER BY recipeId", favorites,
                map: { ($0.int64(0), $0.int64(1)) }
            )
        }
        XCTAssertEqual(stamps.map(\.0), ids)
        XCTAssertEqual(stamps.first { $0.0 == ids[0] }?.1, 100, "insert-or-ignore: addedAt kept")
        XCTAssertEqual(stamps.first { $0.0 == ids[1] }?.1, 900)
    }

    func testUntickingAListForSeveralRemovesThemAndKeepsTheRest() async throws {
        let ids = try await add(3)
        let favorites = try await favorites()
        await lists().setMembershipAll(recipeIds: ids, listId: favorites, inList: true)

        await lists().setMembershipAll(recipeIds: [ids[0], ids[1]], listId: favorites, inList: false)

        let held = try await members(of: favorites)
        XCTAssertEqual(held, [ids[2]])
        let count = try await db.read { try RecipeDao(db: $0).count() }
        XCTAssertEqual(count, 3, "leaving a list is not a deletion")
    }

    func testAFailingMembershipWriteRollsTheWholeSelectionBack() async throws {
        let ids = try await add(2)
        let favorites = try await favorites()

        // A recipe that doesn't exist breaks the foreign key: none of the selection may stay.
        await lists().setMembershipAll(recipeIds: [ids[0], 9_999, ids[1]], listId: favorites, inList: true)

        let held = try await members(of: favorites)
        XCTAssertEqual(held, [], "all or nothing")
    }

    func testMembershipCountsSayHowManyOfTheSelectionEachListHolds() async throws {
        let ids = try await add(3)
        let all = try await db.allLists()
        let favorites = try XCTUnwrap(all.first { $0.isFavorites }).id
        let other = try XCTUnwrap(all.first { !$0.isFavorites }).id
        let repository = lists()
        await repository.setMembershipAll(recipeIds: ids, listId: favorites, inList: true)
        await repository.setMembershipAll(recipeIds: [ids[0]], listId: other, inList: true)

        let counts = try await db.read { try ListDao(db: $0).membershipCounts(recipeIds: [ids[0], ids[1]]) }

        XCTAssertEqual(counts[favorites], 2)
        XCTAssertEqual(counts[other], 1)
        let none = try await db.read { try ListDao(db: $0).membershipCounts(recipeIds: []) }
        XCTAssertEqual(none, [:])
    }

    func testANewListWithSeveralRecipesIsOneTransaction() async throws {
        let ids = try await add(3)

        let id = await lists().createList(name: "  Picnic ", addRecipeIds: [ids[0], ids[1]])

        XCTAssertGreaterThan(id, 0)
        let held = try await members(of: id)
        XCTAssertEqual(held, [ids[0], ids[1]])
        let list = try await db.allLists().first { $0.id == id }
        XCTAssertEqual(list?.name, "Picnic")
    }

    func testANewListWhoseMembersFailLeavesNoList() async throws {
        let ids = try await add(1)
        let before = try await db.allLists().count

        let id = await lists().createList(name: "Ghost", addRecipeIds: [ids[0], 9_999])

        XCTAssertEqual(id, 0)
        let after = try await db.allLists().count
        XCTAssertEqual(after, before, "the list is not left without the recipes that prompted it")
    }

    // MARK: Deleting

    func testDeleteAllTakesEveryRecipeAndHandsBackWhatEachNeedsToComeBack() async throws {
        let ids = try await add(3)
        let favorites = try await favorites()
        await lists().setMembershipAll(recipeIds: [ids[0], ids[1]], listId: favorites, inList: true)

        let gone = await recipes().deleteAll(ids: [ids[0], 9_999, ids[1]])

        XCTAssertEqual(gone.map(\.recipe.id), [ids[0], ids[1]], "an id already gone is skipped, the rest in order")
        XCTAssertEqual(gone.map { $0.memberships.count }, [1, 1])
        let left = try await db.read { try RecipeDao(db: $0).count() }
        XCTAssertEqual(left, 1)
        let held = try await members(of: favorites)
        XCTAssertEqual(held, [], "memberships go with them")

        let outcome = await recipes().restoreAll(gone)
        XCTAssertEqual(outcome, .restored)
        let back = try await members(of: favorites)
        XCTAssertEqual(back, [ids[0], ids[1]])
    }

    func testDeleteOneIsDeleteAllOfOne() async throws {
        let ids = try await add(2)

        let gone = await recipes().delete(id: ids[1])

        XCTAssertEqual(gone?.recipe.id, ids[1])
        let again = await recipes().delete(id: ids[1])
        XCTAssertNil(again)
    }

    func testAnUndoOfADeleteAllAtTheFreeLimitRestoresNoneOfIt() async throws {
        let ids = try await add(50)
        let capped = DefaultRecipeRepository(
            db: db, source: DataStubSource(), clock: DataTestClock(5_000), sleep: { _ in },
            library: FixedLibraryLimit(limit: .capped(max: 50))
        )
        let gone = await capped.deleteAll(ids: [ids[0], ids[1], ids[2]])
        // Three more arrive meanwhile, up to the limit again.
        for i in 0..<3 {
            let row = dataRecipeRecord("https://new.com/\(i)", viewedAt: 9_000)
            _ = try await db.write { try RecipeDao(db: $0).upsert(row, limit: .capped(max: 50)) }
        }

        let outcome = await capped.restoreAll(gone)

        XCTAssertEqual(outcome, .libraryFull)
        let count = try await db.read { try RecipeDao(db: $0).count() }
        XCTAssertEqual(count, 50, "none of the three came back")
    }
}
