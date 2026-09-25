import XCTest
@testable import RecipeClipper

/// Android's MealPlanDaoTest (#49) against real SQLite: the cull keeps recipes planned for
/// today or later, a recipe's meals go and come back with it, and meal types are deleted only
/// when they are the user's own, their meals moving to Dinner. Plus the user_version 7 step.
final class MealPlanDaoTests: XCTestCase {
    private var db: AppDatabase!
    private let today: Int64 = 20_000

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
    }

    override func tearDown() {
        db = nil
    }

    private func typeId(_ key: String) async throws -> Int64 {
        try await db.read { try MealPlanDao(db: $0).mealTypes() }.first { $0.builtInKey == key }!.id
    }

    @discardableResult
    private func plan(_ recipeId: Int64?, day: Int64, type: String = "dinner", note: String? = nil) async throws -> Int64 {
        let mealTypeId = try await typeId(type)
        return try await db.write {
            try MealPlanDao(db: $0).add(MealPlanEntryRecord(
                day: day, mealTypeId: mealTypeId, recipeId: recipeId, servings: nil, note: note,
                sortOrder: 0, updatedAt: 1
            ))
        }
    }

    private func upsert(_ url: String, viewedAt: Int64) async throws -> Int64 {
        try await db.write { [today] in
            try RecipeDao(db: $0).upsert(dataRecipeRecord(url, viewedAt: viewedAt), historyLimit: historyLimit, today: today)
        }
    }

    private func fillHistory() async throws {
        for n in 0 ..< historyLimit + 5 {
            _ = try await upsert("https://a.com/\(n)", viewedAt: 1000 + Int64(n))
        }
    }

    private func days(_ start: Int64, _ end: Int64) async throws -> [PlannedMeal] {
        try await db.read { try MealPlanDao(db: $0).days(start: start, end: end) }
    }

    // MARK: - Seeding and the migration

    func testTheFourMealTypesAreSeededWithTheirKeys() async throws {
        let types = try await db.read { try MealPlanDao(db: $0).mealTypes() }
        XCTAssertEqual(types.map(\.name), ["Breakfast", "Lunch", "Dinner", "Snack"])
        XCTAssertEqual(types.map(\.builtInKey), ["breakfast", "lunch", "dinner", "snack"])
        XCTAssertEqual(Set(types.map(\.uid)).count, 4)
    }

    /// A user_version 6 file, built by the real migrations, gains the plan and keeps its recipe.
    func testAVersion6DatabaseMigratesToVersion7KeepingItsData() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 6)
            try RecipeDao(db: old).insert(RecipeRecord(
                id: 7, sourceUrl: "https://example.com/a", title: "Adobo", imageUrl: nil,
                ingredients: ["1 cup soy sauce"], instructions: ["Simmer."], prepTime: nil, cookTime: nil,
                totalTime: nil, servings: "4", sourceType: "BLOG", lastViewedAt: 123,
                servingsTarget: 6, contentOrigin: "EDITED"
            ))
        }

        let migrated = try AppDatabase(path: path)
        let recipe = try await migrated.get(7)
        XCTAssertEqual(recipe?.title, "Adobo")
        XCTAssertEqual(recipe?.servingsTarget, 6)
        XCTAssertEqual(recipe?.contentOrigin, "EDITED")
        let types = try await migrated.read { try MealPlanDao(db: $0).mealTypes() }
        XCTAssertEqual(types.map(\.name), ["Breakfast", "Lunch", "Dinner", "Snack"])
        let dinner = types.first { $0.builtInKey == "dinner" }!.id
        try await migrated.write {
            try MealPlanDao(db: $0).add(MealPlanEntryRecord(
                day: 20_000, mealTypeId: dinner, recipeId: 7, servings: 4, note: nil, sortOrder: 0, updatedAt: 1
            ))
        }
        let planned = try await migrated.read { try MealPlanDao(db: $0).days(start: 20_000, end: 20_006) }
        XCTAssertEqual(planned.map(\.title), ["Adobo"])
    }

    // MARK: - The cull rule

    func testARecipePlannedForTodayOrLaterIsNeverCulled() async throws {
        let forToday = try await upsert("https://a.com/today", viewedAt: 1)
        let forLater = try await upsert("https://a.com/later", viewedAt: 2)
        try await plan(forToday, day: today)
        try await plan(forLater, day: today + 10)

        try await fillHistory()

        let keptToday = try await db.get(forToday)
        let keptLater = try await db.get(forLater)
        XCTAssertNotNil(keptToday)
        XCTAssertNotNil(keptLater)
        // Like a saved recipe, a planned one is outside the cap: 50 others stay too.
        let count = try await db.recipeCount()
        XCTAssertEqual(count, historyLimit + 2)
    }

    func testARecipePlannedOnlyForPastDaysIsOrdinaryHistory() async throws {
        let past = try await upsert("https://a.com/past", viewedAt: 1)
        try await plan(past, day: today - 1)

        try await fillHistory()

        let gone = try await db.get(past)
        XCTAssertNil(gone)
        let count = try await db.recipeCount()
        XCTAssertEqual(count, historyLimit)
    }

    func testAPlannedNoteDoesNotStopTheCull() async throws {
        // A note's recipeId is NULL; `NOT IN` a set holding NULL would match nothing at all.
        try await plan(nil, day: today, note: "Eat out")
        try await fillHistory()
        let count = try await db.recipeCount()
        XCTAssertEqual(count, historyLimit)
    }

    // MARK: - Deleting a recipe

    func testADeletedRecipeTakesItsMealsAndUndoBringsThemBack() async throws {
        let repository = DefaultRecipeRepository(db: db, source: DataStubSource(), clock: DataTestClock())
        let id = try await upsert("https://a.com/soup", viewedAt: 1)
        try await plan(id, day: today)
        try await plan(id, day: today + 2, type: "lunch")

        let deleted = await repository.delete(id: id)
        XCTAssertNotNil(deleted)
        let afterDelete = try await days(today, today + 6)
        XCTAssertTrue(afterDelete.isEmpty)

        await repository.restore(deleted!)
        let restored = try await days(today, today + 6)
        XCTAssertEqual(restored.map(\.day), [today, today + 2])
    }

    // MARK: - Order and moving

    func testDaysAreOrderedByDayThenMealTypeThenWhenAdded() async throws {
        let a = try await upsert("https://a.com/a", viewedAt: 1)
        let b = try await upsert("https://a.com/b", viewedAt: 2)
        try await plan(a, day: today + 1)
        try await plan(b, day: today)
        try await plan(nil, day: today, type: "breakfast", note: "Toast")
        try await plan(a, day: today)

        let rows = try await days(today, today + 6)
        XCTAssertEqual(rows.map { $0.note ?? $0.title ?? "" },
                       ["Toast", "Recipe https://a.com/b", "Recipe https://a.com/a", "Recipe https://a.com/a"])
        XCTAssertEqual(rows.map(\.day), [today, today, today, today + 1])
    }

    func testAMovedMealGoesToTheEndOfItsNewSlot() async throws {
        let first = try await plan(nil, day: today, note: "First")
        try await plan(nil, day: today + 1, note: "Already there")
        let dinner = try await typeId("dinner")

        try await db.write { [today] in try MealPlanDao(db: $0).move(first, day: today + 1, mealTypeId: dinner, now: 5) }

        let rows = try await days(today + 1, today + 1)
        XCTAssertEqual(rows.map(\.note), ["Already there", "First"])
        let moved = try await db.read { try MealPlanDao(db: $0).entry(first) }
        XCTAssertEqual(moved?.updatedAt, 5)
    }

    // MARK: - Meal types

    func testDeletingAUserTypeMovesItsMealsToDinner() async throws {
        let brunch = try await db.write { try MealPlanDao(db: $0).addType(name: "Brunch", now: 1) }
        try await db.write { [today] in
            try MealPlanDao(db: $0).add(MealPlanEntryRecord(
                day: today, mealTypeId: brunch, recipeId: nil, servings: nil, note: "Pancakes", sortOrder: 0, updatedAt: 1
            ))
        }

        try await db.write { try MealPlanDao(db: $0).deleteType(brunch, now: 2) }

        let types = try await db.read { try MealPlanDao(db: $0).mealTypes() }
        XCTAssertEqual(types.map(\.name), ["Breakfast", "Lunch", "Dinner", "Snack"])
        let meal = try await days(today, today).first
        let dinner = try await typeId("dinner")
        XCTAssertEqual(meal?.mealTypeId, dinner)
        XCTAssertEqual(meal?.note, "Pancakes")
    }

    func testASeededTypeIsNeverDeletedAndKeepsItsMeals() async throws {
        let lunch = try await typeId("lunch")
        try await plan(nil, day: today, type: "lunch", note: "Sandwich")

        try await db.write { try MealPlanDao(db: $0).deleteType(lunch, now: 2) }

        let types = try await db.read { try MealPlanDao(db: $0).mealTypes() }
        XCTAssertEqual(types.count, 4)
        let meal = try await days(today, today).first
        XCTAssertEqual(meal?.mealTypeId, lunch)
    }

    func testReorderingTypesReordersTheWeek() async throws {
        try await plan(nil, day: today, type: "breakfast", note: "Toast")
        try await plan(nil, day: today, type: "dinner", note: "Stew")
        let ids = try await db.read { try MealPlanDao(db: $0).mealTypes() }.map(\.id)

        try await db.write { try MealPlanDao(db: $0).reorderTypes(Array(ids.reversed()), now: 4) }

        let names = try await db.read { try MealPlanDao(db: $0).mealTypes() }.map(\.name)
        XCTAssertEqual(names, ["Snack", "Dinner", "Lunch", "Breakfast"])
        let notes = try await days(today, today).map(\.note)
        XCTAssertEqual(notes, ["Stew", "Toast"])
    }
}
