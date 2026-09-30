import XCTest
@testable import RecipeClipper

/// Android's PantryDaoTest (#51) against real SQLite: stock changes, edits, a delete that
/// restores whole, and the user_version 9 step.
final class PantryDaoTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
    }

    override func tearDown() {
        db = nil
    }

    private func item(_ name: String, inStock: Bool = true) -> PantryItemRecord {
        PantryItemRecord(
            name: name, quantity: nil, language: "en", aisle: "other", inStock: inStock, alwaysHave: false,
            purchasedDay: 10, expiresDay: nil, updatedAt: 1
        )
    }

    private func get(_ id: Int64) async throws -> PantryItemRecord? {
        try await db.read { try PantryDao(db: $0).item(id) }
    }

    func testRestockPutsItBackBoughtOnTheDay() async throws {
        let id = try await db.write { try PantryDao(db: $0).insert(self.item("milk", inStock: false)) }
        try await db.write { try PantryDao(db: $0).restock([id], day: 20_000, now: 5) }
        var milk = try await get(id)
        XCTAssertEqual(milk?.inStock, true)
        XCTAssertEqual(milk?.purchasedDay, 20_000)
        XCTAssertEqual(milk?.updatedAt, 5)

        try await db.write { try PantryDao(db: $0).setStock([id], inStock: false, runningLow: false, now: 6) }
        milk = try await get(id)
        XCTAssertEqual(milk?.inStock, false)
        XCTAssertEqual(milk?.purchasedDay, 20_000) // running out keeps the date
    }

    // #194: "Clear run-out items" deletes only what has run out (running low stays), and the
    // repository's snapshot puts them back whole: ids, uids and every field.
    func testDeleteRunOutRemovesOnlyRunOutItemsAndRestoresThemWhole() async throws {
        let repository = DefaultPantryRepository(db: db, clock: TestClock(now: 99))
        var milkRecord = item("milk", inStock: false)
        milkRecord.quantity = "1 l"
        milkRecord.expiresDay = 20_100
        milkRecord.alwaysHave = true
        var saltRecord = item("salt")
        saltRecord.runningLow = true
        let oil = try await db.write { try PantryDao(db: $0).insert(self.item("oil")) }
        let milk = try await db.write { try PantryDao(db: $0).insert(milkRecord) }
        let salt = try await db.write { try PantryDao(db: $0).insert(saltRecord) }
        let rice = try await db.write { try PantryDao(db: $0).insert(self.item("rice", inStock: false)) }
        let all = { try await self.db.read { try PantryDao(db: $0).items() } }
        let before = try await all()

        let gone = await repository.deleteRunOut()
        XCTAssertEqual(gone?.items.map(\.id), [milk, rice])
        var now = try await all()
        XCTAssertEqual(now.map(\.id), [oil, salt])
        let again = await repository.deleteRunOut()
        XCTAssertNil(again)

        await repository.restore(gone!)
        now = try await all()
        XCTAssertEqual(now, before)
    }

    // #194: running low is a flag on an in-stock item; a restock clears it.
    func testRunningLowIsKeptUntilARestock() async throws {
        let id = try await db.write { try PantryDao(db: $0).insert(self.item("milk")) }
        try await db.write { try PantryDao(db: $0).setStock([id], inStock: true, runningLow: true, now: 5) }
        var milk = try await get(id)
        XCTAssertEqual(milk?.inStock, true)
        XCTAssertEqual(milk?.runningLow, true)
        XCTAssertEqual(milk?.domain.stock, .runningLow)

        try await db.write { try PantryDao(db: $0).restock([id], day: 20_001, now: 6) }
        milk = try await get(id)
        XCTAssertEqual(milk?.inStock, true)
        XCTAssertEqual(milk?.runningLow, false)
    }

    /// A user_version 14 file (#173's), built by the real migrations, gains `runningLow` (#194):
    /// every item keeps its stock, none is running low, and one can then be marked so.
    func testAVersion14DatabaseMigratesToVersion15KeepingEveryItemsStock() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 14)
            try old.execute("""
                INSERT INTO pantry_items (id, name, quantity, language, aisle, inStock, alwaysHave, purchasedDay,
                    expiresDay, updatedAt, uid) VALUES
                    (1, 'garlic', '1 head', 'en', 'produce', 1, 0, 20000, 20010, 5, 'garlic-uid'),
                    (2, 'rice', NULL, 'en', 'grains', 0, 0, NULL, NULL, 6, 'rice-uid')
                """)
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 14)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let items = try await migrated.read { try PantryDao(db: $0).items() }
        XCTAssertEqual(items, [
            PantryItemRecord(
                id: 1, name: "garlic", quantity: "1 head", language: "en", aisle: "produce", inStock: true, alwaysHave: false,
                purchasedDay: 20_000, expiresDay: 20_010, updatedAt: 5, uid: "garlic-uid", runningLow: false
            ),
            PantryItemRecord(
                id: 2, name: "rice", quantity: nil, language: "en", aisle: "grains", inStock: false, alwaysHave: false,
                purchasedDay: nil, expiresDay: nil, updatedAt: 6, uid: "rice-uid", runningLow: false
            ),
        ])
        try await migrated.write { try PantryDao(db: $0).setStock([1], inStock: true, runningLow: true, now: 7) }
        let garlic = try await migrated.read { try PantryDao(db: $0).item(1) }
        XCTAssertEqual(garlic?.runningLow, true)
    }

    func testEditChangesOnlyWhatTheSheetShows() async throws {
        let id = try await db.write { try PantryDao(db: $0).insert(self.item("oil")) }
        try await db.write {
            try PantryDao(db: $0).edit(id, name: "olive oil", quantity: "half a bottle", alwaysHave: true, expiresDay: 20_100, now: 7)
        }
        let oil = try await get(id)
        XCTAssertEqual(oil?.name, "olive oil")
        XCTAssertEqual(oil?.quantity, "half a bottle")
        XCTAssertEqual(oil?.alwaysHave, true)
        XCTAssertEqual(oil?.expiresDay, 20_100)
        XCTAssertEqual(oil?.purchasedDay, 10)
        XCTAssertEqual(oil?.inStock, true)
    }

    func testADeleteRestoresWithItsIdAndUid() async throws {
        let id = try await db.write { try PantryDao(db: $0).insert(self.item("rice")) }
        let before = try await get(id)!
        try await db.write { try PantryDao(db: $0).delete(id) }
        let gone = try await get(id)
        XCTAssertNil(gone)
        try await db.write { try PantryDao(db: $0).put([before]) }
        let all = try await db.read { try PantryDao(db: $0).items() }
        XCTAssertEqual(all, [before])
    }

    func testUidsAreUnique() async throws {
        var a = item("a"); a.uid = "same"
        var b = item("b"); b.uid = "same"
        try await db.write { try PantryDao(db: $0).insert(a) }
        do {
            try await db.write { try PantryDao(db: $0).insert(b) }
            XCTFail("a second row with the same uid was accepted")
        } catch {}
    }

    /// A user_version 8 file, built by the real migrations, gains the pantry and keeps its
    /// recipe and grocery item.
    func testAVersion8DatabaseMigratesToVersion9KeepingItsData() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 8)
            try RecipeDao(db: old).insert(RecipeRecord(
                id: 7, sourceUrl: "https://example.com/a", title: "Adobo", imageUrl: nil,
                ingredients: ["1 cup soy sauce"], instructions: ["Simmer."], prepTime: nil, cookTime: nil,
                totalTime: nil, servings: "4", sourceType: "BLOG", lastViewedAt: 123
            ))
            try GroceryDao(db: old).add([GroceryItemRecord(
                text: "1 cup soy sauce", language: "en", aisle: "condiments", checked: true, sortOrder: 0,
                recipeId: 7, plannedDay: nil, updatedAt: 1
            )])
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 8)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        let recipe = try await migrated.read { try RecipeDao(db: $0).get(7) }
        XCTAssertEqual(recipe?.title, "Adobo")
        let groceries = try await migrated.read { try GroceryDao(db: $0).items() }
        XCTAssertEqual(groceries.map(\.text), ["1 cup soy sauce"])
        let empty = try await migrated.read { try PantryDao(db: $0).items() }
        XCTAssertTrue(empty.isEmpty)
        try await migrated.write {
            try PantryDao(db: $0).insert(PantryItemRecord(
                name: "soy sauce", quantity: nil, language: "en", aisle: "condiments", inStock: true,
                alwaysHave: false, purchasedDay: 20_000, expiresDay: nil, updatedAt: 1
            ))
        }
        let pantry = try await migrated.read { try PantryDao(db: $0).items() }
        XCTAssertEqual(pantry.map(\.name), ["soy sauce"])
    }
}
