import XCTest
@testable import RecipeClipper

/// Android's GroceryDaoTest (#50) against real SQLite: items keep the order added, a delete
/// restores whole, a recipe's items outlive it, and the week's planned recipes come in plan
/// order. Plus the user_version 8 step.
final class GroceryDaoTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
    }

    override func tearDown() {
        db = nil
    }

    private func item(_ text: String, recipeId: Int64? = nil) -> GroceryItemRecord {
        GroceryItemRecord(text: text, language: "en", aisle: "other", sortOrder: 99, recipeId: recipeId, plannedDay: nil, updatedAt: 1)
    }

    private func items() async throws -> [GroceryItemRecord] {
        try await db.read { try GroceryDao(db: $0).items() }
    }

    private func upsert(_ url: String) async throws -> Int64 {
        try await db.write { try RecipeDao(db: $0).upsert(dataRecipeRecord(url, viewedAt: 1), historyLimit: historyLimit) }
    }

    func testItemsGoLastInTheOrderGiven() async throws {
        try await db.write { conn in
            try GroceryDao(db: conn).add([self.item("a"), self.item("b")])
            try GroceryDao(db: conn).add([self.item("c")])
        }
        let rows = try await items()
        XCTAssertEqual(rows.map(\.text), ["a", "b", "c"])
        XCTAssertEqual(rows.map(\.sortOrder), [0, 1, 2])
        XCTAssertEqual(Set(rows.map(\.uid)).count, 3)
    }

    func testCheckingMovingAndDeletingTouchOnlyTheGivenItems() async throws {
        try await db.write { try GroceryDao(db: $0).add([self.item("a"), self.item("b"), self.item("c")]) }
        let ids = try await items().map(\.id)
        try await db.write { conn in
            let dao = GroceryDao(db: conn)
            try dao.setChecked([ids[0], ids[2]], checked: true, now: 5)
            try dao.setAisle([ids[1]], aisle: "dairy", now: 6)
        }
        var rows = try await items()
        XCTAssertEqual(rows.map(\.checked), [true, false, true])
        XCTAssertEqual(rows.map(\.aisle), ["other", "dairy", "other"])
        XCTAssertEqual(rows.map(\.updatedAt), [5, 6, 5])

        let checked = try await db.read { try GroceryDao(db: $0).checkedItems() }
        XCTAssertEqual(checked.map(\.text), ["a", "c"])
        try await db.write { try GroceryDao(db: $0).delete([ids[0], ids[2]]) }
        rows = try await items()
        XCTAssertEqual(rows.map(\.text), ["b"])
    }

    func testADeleteRestoresWholeInItsPlace() async throws {
        try await db.write { try GroceryDao(db: $0).add([self.item("a"), self.item("b"), self.item("c")]) }
        let before = try await items()
        try await db.write { conn in
            let dao = GroceryDao(db: conn)
            try dao.delete([before[1].id])
            try dao.restore([before[1]])
        }
        let after = try await items()
        XCTAssertEqual(after, before)
    }

    func testARecipesItemsOutliveItAndUndoAfterItsDeleteDropsOnlyTheSource() async throws {
        let id = try await upsert("https://example.com/a")
        try await db.write { try GroceryDao(db: $0).add([self.item("1 cup flour", recipeId: id)]) }
        let saved = try await items()
        try await db.write { conn in
            try GroceryDao(db: conn).delete(saved.map(\.id))
            try RecipeDao(db: conn).delete(id)
            try GroceryDao(db: conn).restore(saved)
        }
        let left = try await items()
        XCTAssertEqual(left.map(\.text), ["1 cup flour"])
        XCTAssertNil(left[0].recipeId)
    }

    func testDeletingARecipeKeepsItsItemsWithoutASource() async throws {
        let id = try await upsert("https://example.com/a")
        try await db.write { try GroceryDao(db: $0).add([self.item("1 cup flour", recipeId: id)]) }
        try await db.write { try RecipeDao(db: $0).delete(id) }
        let left = try await items()
        XCTAssertEqual(left.map(\.text), ["1 cup flour"])
        XCTAssertNil(left[0].recipeId)
    }

    func testTheWeeksPlannedRecipesComeInPlanOrderWithoutNotes() async throws {
        let soup = try await upsert("https://example.com/soup")
        let bread = try await upsert("https://example.com/bread")
        let types = try await db.read { try MealPlanDao(db: $0).mealTypes() }
        let dinner = types.first { $0.builtInKey == "dinner" }!.id
        let lunch = types.first { $0.builtInKey == "lunch" }!.id
        func entry(_ day: Int64, _ type: Int64, _ recipeId: Int64?, note: String? = nil, servings: Int? = nil) -> MealPlanEntryRecord {
            MealPlanEntryRecord(day: day, mealTypeId: type, recipeId: recipeId, servings: servings, note: note, sortOrder: 0, updatedAt: 1)
        }
        try await db.write { conn in
            let plan = MealPlanDao(db: conn)
            try plan.add(entry(20_001, dinner, soup, servings: 6))
            try plan.add(entry(20_001, lunch, bread))
            try plan.add(entry(20_000, dinner, nil, note: "Leftovers"))
            try plan.add(entry(20_010, dinner, soup))
        }
        let planned = try await db.read { try GroceryDao(db: $0).plannedIngredients(start: 20_000, end: 20_006) }
        XCTAssertEqual(planned.map(\.recipeId), [bread, soup])
        XCTAssertEqual(planned.map(\.servings), [nil, 6])
        XCTAssertTrue(planned.allSatisfy { $0.day == 20_001 })
        XCTAssertFalse(planned[0].ingredients.isEmpty)
    }

    /// A user_version 7 file, built by the real migrations, gains the grocery list and keeps
    /// its recipe and planned meal.
    func testAVersion7DatabaseMigratesToVersion8KeepingItsData() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 7)
            try RecipeDao(db: old).insert(RecipeRecord(
                id: 7, sourceUrl: "https://example.com/a", title: "Adobo", imageUrl: nil,
                ingredients: ["1 cup soy sauce"], instructions: ["Simmer."], prepTime: nil, cookTime: nil,
                totalTime: nil, servings: "4", sourceType: "BLOG", lastViewedAt: 123
            ))
            let dinner = try MealPlanDao(db: old).mealTypes().first { $0.builtInKey == "dinner" }!.id
            try MealPlanDao(db: old).add(MealPlanEntryRecord(
                day: 20_000, mealTypeId: dinner, recipeId: 7, servings: 8, note: nil, sortOrder: 0, updatedAt: 1
            ))
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let recipe = try await migrated.get(7)
        XCTAssertEqual(recipe?.title, "Adobo")
        let planned = try await migrated.read { try GroceryDao(db: $0).plannedIngredients(start: 20_000, end: 20_006) }
        XCTAssertEqual(planned.map(\.servings), [8])
        XCTAssertEqual(planned.first?.ingredients, ["1 cup soy sauce"])
        try await migrated.write {
            try GroceryDao(db: $0).add([GroceryItemRecord(
                text: "1 cup soy sauce", language: "en", aisle: "condiments", sortOrder: 0, recipeId: 7,
                plannedDay: 20_000, updatedAt: 1
            )])
        }
        let items = try await migrated.read { try GroceryDao(db: $0).items() }
        XCTAssertEqual(items.map(\.text), ["1 cup soy sauce"])
    }
}
