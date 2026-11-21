import XCTest
@testable import RecipeClipper

/// The cook's list order (#381) through `DefaultListRepository` over a real in-memory database,
/// and the user_version 16 database that gains it.
final class ListOrderTests: XCTestCase {
    private var db: AppDatabase!
    private var reorderOn = true
    private var repository: DefaultListRepository!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
        reorderOn = true
        repository = DefaultListRepository(db: db, clock: DataTestClock(1_000), reorderOn: { [unowned self] in reorderOn })
    }

    override func tearDown() {
        repository = nil
        db = nil
    }

    private func names() async -> [String] {
        await firstValue(repository.observeLists())?.map(\.name) ?? []
    }

    func testAReorderIsStoredAndBothReadersFollowIt() async throws {
        let ids = try await db.allLists().map(\.id)
        await repository.reorderLists(ids.reversed())

        let expected = ["Snacks", "Breakfast", "Desserts", "Dinner", "Lunch", "Favorites"]
        let screen = await names()
        let sheet = await firstValue(repository.observeListsFor(recipeId: 5))?.map(\.name)
        XCTAssertEqual(screen, expected)
        XCTAssertEqual(sheet, expected)
    }

    func testAListCreatedAfterAReorderGoesLast() async throws {
        await repository.reorderLists(try await db.allLists().map(\.id).reversed())
        await repository.createList(name: "Weeknights", addRecipeId: nil)

        let order = await names()
        XCTAssertEqual(order.last, "Weeknights")
    }

    func testWithTheFlagOffSeededListsAreFirstAgainWhateverTheStoredOrder() async throws {
        await repository.createList(name: "Weeknights", addRecipeId: nil)
        let ids = try await db.allLists().map(\.id)
        await repository.reorderLists([ids.last!] + ids.dropLast())
        let moved = await names()
        XCTAssertEqual(moved.first, "Weeknights")

        reorderOn = false

        let order = await names()
        XCTAssertEqual(order, ["Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks", "Weeknights"])
    }

    /// Android's `MigrationTest.migration17To18…`: every list is numbered 0, 1, 2... in the order
    /// it was shown (seeded first, then by position, then by id), with user lists and seeded ones
    /// sharing positions as old upgrades left them. Nothing moves, and membership comes across.
    func testAVersion16DatabaseMigratesToVersion17NumberingEveryListInTheOrderItWasShown() async throws {
        let path = NSTemporaryDirectory() + "rc-\(UUID().uuidString).sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        do {
            let old = try SQLiteConnection(path: path)
            try old.execute("PRAGMA foreign_keys = ON")
            try AppDatabase.migrate(old, upTo: 16)
            // The first migration seeds the six lists; this test sets up its own.
            try old.execute("DELETE FROM lists")
            try old.execute("""
                INSERT INTO lists (id, name, isBuiltIn, isFavorites, sortOrder, createdAt, uid) VALUES
                    (1, 'Favorites', 1, 1, 0, 0, 'uid-1'), (2, 'Lunch', 1, 0, 1, 0, 'uid-2'),
                    (3, 'Weeknights', 0, 0, 2, 0, 'uid-3'), (4, 'Breakfast', 1, 0, 2, 0, 'uid-4'),
                    (5, 'Aaa', 0, 0, 0, 0, 'uid-5')
                """)
            try old.execute("""
                INSERT INTO recipes (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime, cookTime,
                    totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid, language, cookState,
                    servingsTarget, contentOrigin, editedAt, basedOnUid) VALUES
                    (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]', '["Simmer."]', NULL, NULL, NULL,
                     '4', 'BLOG', 123, '[]', NULL, 'recipe-uid', 'en', NULL, NULL, 'PARSED', NULL, NULL)
                """)
            try old.execute("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (7, 3, 5)")
            XCTAssertEqual(try old.queryOne("PRAGMA user_version") { $0.int(0) }, 16)
        }

        let migrated = try AppDatabase(path: path)
        let version = try await migrated.read { try $0.queryOne("PRAGMA user_version") { $0.int(0) } }
        XCTAssertEqual(version, AppDatabase.schemaVersion)
        XCTAssertEqual(AppDatabase.schemaVersion, 20)

        let rows = try await migrated.read { try BackupDao(db: $0).snapshot().lists }
        XCTAssertEqual(rows.map(\.name), ["Favorites", "Lunch", "Breakfast", "Aaa", "Weeknights"])
        XCTAssertEqual(rows.map(\.sortOrder), [0, 1, 2, 3, 4])
        XCTAssertEqual(rows.map(\.uid), ["uid-1", "uid-2", "uid-4", "uid-5", "uid-3"])
        let shown = try await migrated.read { try ListDao(db: $0).lists(recipeId: ListDao.noRecipe).map(\.name) }
        XCTAssertEqual(shown, rows.map(\.name))
        let members = try await migrated.read { try ListDao(db: $0).recipesIn(listId: 3).map(\.id) }
        XCTAssertEqual(members, [7])
    }
}
