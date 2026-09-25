import XCTest
@testable import RecipeClipper

/// Android's MenuDaoTest (#52) against real SQLite: a saved week applies to another week on the
/// same weekdays, only ever adding; menu recipes are kept from the cull; deleting a meal type
/// moves menu meals to Dinner. Plus the user_version 10 step.
final class MenuDaoTests: XCTestCase {
    private var db: AppDatabase!
    private let today: Int64 = 20_000

    override func setUpWithError() throws { db = try AppDatabase(path: nil) }

    override func tearDown() { db = nil }

    private func typeId(_ key: String) async throws -> Int64 {
        try await db.read { try MealPlanDao(db: $0).mealTypes() }.first { $0.builtInKey == key }!.id
    }

    private func plan(_ recipeId: Int64?, day: Int64, type: String = "dinner", note: String? = nil) async throws {
        let mealTypeId = try await typeId(type)
        _ = try await db.write {
            try MealPlanDao(db: $0).add(MealPlanEntryRecord(
                day: day, mealTypeId: mealTypeId, recipeId: recipeId, servings: nil, note: note, sortOrder: 0, updatedAt: 1
            ))
        }
    }

    private func upsert(_ url: String, viewedAt: Int64) async throws -> Int64 {
        try await db.write { [today] in
            try RecipeDao(db: $0).upsert(dataRecipeRecord(url, viewedAt: viewedAt), historyLimit: historyLimit, today: today)
        }
    }

    private func week(_ start: Int64) async throws -> [PlannedMeal] {
        try await db.read { try MealPlanDao(db: $0).days(start: start, end: start + 6) }
    }

    private func save(_ name: String, _ start: Int64) async throws -> Int64? {
        try await db.write { try MenuDao(db: $0).saveWeek(name: name, weekStart: start, now: 5) }
    }

    func testASavedWeekAppliesToAnotherWeekOnTheSameWeekdaysAfterWhatIsPlanned() async throws {
        let recipe = try await upsert("https://a.com/adobo", viewedAt: 1)
        try await plan(recipe, day: today + 1)
        try await plan(nil, day: today + 3, type: "lunch", note: "Leftovers")
        let menuId = try await save("Usual", today)
        XCTAssertNotNil(menuId)
        try await plan(nil, day: today + 8, note: "Already here")

        let added = try await db.write { [today] in try MenuDao(db: $0).apply(menuId: menuId!, weekStart: today + 7, now: 6) }

        XCTAssertEqual(added, 2)
        let next = try await week(today + 7)
        XCTAssertEqual(next.filter { $0.day == today + 8 }.map { $0.note ?? "recipe" }, ["Already here", "recipe"])
        XCTAssertEqual(next.filter { $0.day == today + 10 }.map(\.note), ["Leftovers"])
        let menus = try await db.read { try MenuDao(db: $0).menus() }
        XCTAssertEqual(menus.map(\.mealCount), [2])
        let before = try await week(today)
        XCTAssertEqual(before.count, 2, "saving leaves the week as it was")
    }

    func testAnEmptyWeekSavesNoMenu() async throws {
        let id = try await save("Nothing", today)
        XCTAssertNil(id)
        let menus = try await db.read { try MenuDao(db: $0).menus() }
        XCTAssertTrue(menus.isEmpty)
    }

    func testARecipeInAMenuIsKeptFromTheCull() async throws {
        let kept = try await upsert("https://a.com/kept", viewedAt: 1)
        try await plan(kept, day: today - 30)
        _ = try await save("Old week", today - 30)
        for n in 0 ..< historyLimit + 5 { _ = try await upsert("https://a.com/\(n)", viewedAt: 1000 + Int64(n)) }
        let recipe = try await db.get(kept)
        XCTAssertNotNil(recipe)
    }

    func testDeletingAMealTypeMovesMenuMealsToDinnerAndDeletingAMenuLeavesThePlan() async throws {
        let mine = try await db.write { try MealPlanDao(db: $0).addType(name: "Brunch", now: 1) }
        _ = try await db.write { [today] in
            try MealPlanDao(db: $0).add(MealPlanEntryRecord(
                day: today, mealTypeId: mine, recipeId: nil, servings: nil, note: "Eggs", sortOrder: 0, updatedAt: 1
            ))
        }
        let menuId = try await save("Brunchy", today)!
        _ = try await db.write { try MealPlanDao(db: $0).deleteType(mine, now: 2) }
        let dinner = try await typeId("dinner")
        let entries = try await db.read { try MenuDao(db: $0).entries(menuId: menuId) }
        XCTAssertEqual(entries.map(\.mealTypeId), [dinner])

        _ = try await db.write { try MenuDao(db: $0).delete(menuId) }
        let left = try await db.read { try MenuDao(db: $0).entries(menuId: menuId) }
        XCTAssertTrue(left.isEmpty)
        let planned = try await week(today)
        XCTAssertEqual(planned.map(\.note), ["Eggs"])
    }

    /// A user_version 9 file, built by the real migrations, gains the menu tables and keeps its
    /// planned meal.
    func testAVersion9DatabaseMigratesToVersion10KeepingItsPlan() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 9)
            let dinner = try MealPlanDao(db: old).mealTypes().first { $0.builtInKey == "dinner" }!.id
            _ = try MealPlanDao(db: old).add(MealPlanEntryRecord(
                day: today, mealTypeId: dinner, recipeId: nil, servings: nil, note: "Stew", sortOrder: 0, updatedAt: 1
            ))
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 9)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        db = migrated
        let id = try await save("Stew week", today)
        XCTAssertNotNil(id)
        let menus = try await db.read { try MenuDao(db: $0).menus() }
        XCTAssertEqual(menus.map(\.name), ["Stew week"])
    }
}
