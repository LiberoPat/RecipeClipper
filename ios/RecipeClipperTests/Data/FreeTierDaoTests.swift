import XCTest
@testable import RecipeClipper

/// Port of Android's FreeTierDaoTest: the free tier's rules (#107) against real SQLite. One out
/// for one in, never below what's here.
final class FreeTierDaoTests: XCTestCase {
    private var db: AppDatabase!
    private let free = LibraryLimit.free(max: 20)
    private let today: Int64 = 20_000

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }
    override func tearDown() { db = nil }

    @discardableResult
    private func add(_ url: String, viewedAt: Int64, limit: LibraryLimit? = nil, origin: String = "PARSED") async throws -> Int64 {
        var row = dataRecipeRecord(url, viewedAt: viewedAt)
        row.contentOrigin = origin
        let limit = limit ?? free
        return try await db.write { [today] in try RecipeDao(db: $0).upsert(row, limit: limit, today: today) }
    }

    /// `n` recipes put there before the limit, as the old history cap allowed.
    private func existing(_ n: Int, from: Int64 = 100) async throws -> [Int64] {
        var ids: [Int64] = []
        for i in 0..<n { ids.append(try await add("https://old.com/\(from)-\(i)", viewedAt: from + Int64(i), limit: .unlimited)) }
        return ids
    }

    private func plan(_ id: Int64, day: Int64) async throws {
        let dinner = try await db.read { try MealPlanDao(db: $0).mealTypes() }.first { $0.builtInKey == "dinner" }!.id
        _ = try await db.write {
            try MealPlanDao(db: $0).add(MealPlanEntryRecord(
                day: day, mealTypeId: dinner, recipeId: id, servings: nil, note: nil, sortOrder: 0, updatedAt: 1
            ))
        }
    }

    func testUnderTheLimitNothingIsRemoved() async throws {
        let first = try await existing(19)
        try await add("https://a.com/new", viewedAt: 10_000)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 20)
        let oldest = try await db.get(first[0])
        XCTAssertNotNil(oldest)
    }

    func testAtTheLimitTheOldestUnprotectedMakesRoomOneForOne() async throws {
        let old = try await existing(20)
        let added = try await add("https://a.com/new", viewedAt: 10_000)
        let new = try await db.get(added), gone = try await db.get(old[0]), next = try await db.get(old[1])
        XCTAssertNotNil(new)
        XCTAssertNil(gone, "the oldest viewed goes")
        XCTAssertNotNil(next)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 20)
    }

    func testListedPlannedAndTypedInRecipesAreNeverTheOneRemoved() async throws {
        let old = try await existing(16)
        try await db.putInList(old[0], 1)
        try await plan(old[1], day: today)
        try await plan(old[2], day: today + 3)
        let typed = try await add("manual:typed", viewedAt: 50, limit: .unlimited, origin: "MANUAL")
        try await plan(old[3], day: today - 1) // planned only in the past: ordinary again
        _ = try await existing(3, from: 5_000) // 20 now
        try await add("https://a.com/new", viewedAt: 10_000)
        for id in [old[0], old[1], old[2], typed] {
            let kept = try await db.get(id)
            XCTAssertNotNil(kept)
        }
        let past = try await db.get(old[3])
        XCTAssertNil(past)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 20)
    }

    func testExistingUsersOverTheLimitKeepEverything() async throws {
        let old = try await existing(50)
        let added = try await add("https://a.com/new", viewedAt: 10_000)
        let new = try await db.get(added), gone = try await db.get(old[0])
        XCTAssertNotNil(new)
        XCTAssertNil(gone)
        // Room is made for the new one; the library is never cut down to 20.
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 50)
    }

    func testWhenEveryRecipeIsProtectedTheNewOneIsNotKept() async throws {
        for id in try await existing(20) { try await db.putInList(id, 1) }
        let id = try await add("https://a.com/new", viewedAt: 10_000)
        XCTAssertEqual(id, RecipeDao.notKept)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 20)
    }

    func testReSharingARecipeAlreadyHereRemovesNothing() async throws {
        let old = try await existing(20)
        try await add("https://old.com/100-5", viewedAt: 10_000)
        let count = try await db.recipeCount(), oldest = try await db.get(old[0])
        XCTAssertEqual(count, 20)
        XCTAssertNotNil(oldest)
    }

    func testUnlockedIsNeverCulled() async throws {
        _ = try await existing(60)
        try await add("https://a.com/new", viewedAt: 10_000, limit: .unlimited)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, 61)
    }
}
