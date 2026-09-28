import XCTest
@testable import RecipeClipper

/// The shared file (#149, phase 2) against real SQLite (Android's ShareFileRepositoryTest): what
/// "Send as file" reads, and a received file merged in (never replacing a recipe here, each item
/// once, the history cap after).
final class ShareFileRepositoryTests: XCTestCase {
    private var db: AppDatabase!
    private let now: Int64 = 1_800_000_000_000

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
    }

    override func tearDown() {
        db = nil
    }

    private func repo(_ limit: LibraryLimit = .history(keep: 50)) -> DefaultShareFileRepository {
        DefaultShareFileRepository(db: db, clock: DataTestClock(now), library: FixedLibraryLimit(limit: limit))
    }

    private func insert(_ url: String, title: String = "Soup", viewedAt: Int64 = 1) async throws -> Int64 {
        var row = dataRecipeRecord(url, viewedAt: viewedAt, title: title, checked: [1])
        row.notes = "salt"
        return try await db.write { try RecipeDao(db: $0).insert(row) }
    }

    private func addGroceries(_ items: [(String, Int64?, Bool)]) async throws {
        try await db.write { conn in
            try GroceryDao(db: conn).add(items.map { text, recipeId, checked in
                GroceryItemRecord(
                    text: text, language: "en", aisle: "other", checked: checked, sortOrder: 0,
                    recipeId: recipeId, plannedDay: nil, updatedAt: 1
                )
            })
        }
    }

    private func groceries() async throws -> [GroceryItemRecord] {
        try await db.read { try GroceryDao(db: $0).items() }
    }

    private func recipe(url: String) async throws -> RecipeRecord? {
        try await db.read { try RecipeDao(db: $0).findByUrl(url) }
    }

    private func summary(_ result: Result<ImportSummary, BackupError>) throws -> ImportSummary {
        switch result {
        case .success(let s): return s
        case .failure(let e): XCTFail("receive failed: \(e)"); throw e
        }
    }

    func testARecipeIsSentCompleteWithoutTheSendersTicksOrNote() async throws {
        let id = try await insert("https://example.com/soup")

        let made = await repo().recipeFile(recipeId: id)
        let file = try decodeOrFail(try XCTUnwrap(made))

        XCTAssertTrue(file.isShare)
        let sent = try XCTUnwrap(file.recipes.first)
        XCTAssertEqual(file.recipes.count, 1)
        XCTAssertEqual(sent.ingredients, ["1 cup flour", "2 eggs"])
        XCTAssertTrue(sent.checkedIngredients.isEmpty)
        XCTAssertNil(sent.notes)
        XCTAssertTrue(file.groceries.isEmpty && file.lists.isEmpty)
        let missing = await repo().recipeFile(recipeId: id + 99)
        XCTAssertNil(missing)
    }

    func testTheGroceriesFileHoldsTheUntickedItemsAndTheirRecipes() async throws {
        let soup = try await insert("https://example.com/soup", title: "Soup")
        _ = try await insert("https://example.com/other", title: "Other")
        try await addGroceries([("2 onions", soup, false), ("bread", nil, false), ("1 egg", soup, true)])

        let made = await repo().groceriesFile()
        let file = try decodeOrFail(try XCTUnwrap(made))

        XCTAssertEqual(file.groceries.map(\.text), ["2 onions", "bread"])
        XCTAssertEqual(file.recipes.map(\.title), ["Soup"])
        XCTAssertEqual(file.groceries.map(\.recipeId), [file.recipes.first?.id, nil])

        let ids = try await groceries().map(\.id)
        try await db.write { try GroceryDao(db: $0).setChecked(ids, checked: true, now: 2) }
        let none = await repo().groceriesFile()
        XCTAssertNil(none)
    }

    func testThePantryFileHoldsWhatIsInStockAsItIs() async throws {
        func item(_ name: String, inStock: Bool, quantity: String? = nil) -> PantryItemRecord {
            PantryItemRecord(
                name: name, quantity: quantity, language: "en", aisle: "grains", inStock: inStock,
                alwaysHave: false, purchasedDay: 20_000, expiresDay: 20_100, updatedAt: 1
            )
        }
        let rice = item("basmati rice", inStock: true, quantity: "half a bag")
        let riceId = try await db.write { conn in
            let dao = PantryDao(db: conn)
            _ = try dao.insert(item("oats", inStock: false))
            return try dao.insert(rice)
        }

        let made = await repo().pantryFile()
        let file = try decodeOrFail(try XCTUnwrap(made))

        XCTAssertTrue(file.isShare)
        XCTAssertEqual(file.pantry.map(\.name), ["basmati rice"])
        XCTAssertEqual(file.pantry.first?.quantity, "half a bag")
        XCTAssertEqual(file.pantry.first?.expiresDay, 20_100)
        XCTAssertEqual(file.pantry.first?.id, rice.uid)
        XCTAssertTrue(file.recipes.isEmpty && file.groceries.isEmpty)

        try await db.write { try PantryDao(db: $0).setStock([riceId], inStock: false, runningLow: false, now: 2) }
        let none = await repo().pantryFile()
        XCTAssertNil(none)
    }

    func testAReceivedFileMergesWithoutReplacingAndOnlyOnce() async throws {
        // The chicken is here already, under a link with a tracking parameter the file's lacks.
        let here = try await insert("https://example.com/sheet-pan-chicken", title: "My chicken", viewedAt: 3)
        let file = try decodeOrFail(shareFixture())
        let everything = ShareChoice(
            recipeIds: ["r-chicken", "r-cake"], groceryIds: ["g-thighs", "g-lemon", "g-paper"],
            pantryIds: ["p-rice"], pantryTo: .groceries
        )

        let result = try summary(await repo().receive(file, choice: everything))

        XCTAssertEqual(result.recipesAdded, 1)
        XCTAssertEqual(result.recipesAlreadyHere, 1)
        let chicken = try await db.read { try RecipeDao(db: $0).get(here) }
        XCTAssertEqual(chicken?.title, "My chicken")
        XCTAssertEqual(chicken?.notes, "salt")
        XCTAssertEqual(chicken?.lastViewedAt, now)
        let cake = try await recipe(url: "manual:5d1c2a4e-2f61-4a8e-9b1a-6e0f2c9d7a11")
        XCTAssertEqual(cake?.lastViewedAt, now)
        XCTAssertEqual(cake?.checkedIngredients, [])

        let items = try await groceries()
        XCTAssertEqual(items.map(\.text), ["2 lb chicken thighs", "1 lemon", "baking paper", "Basmati rice"])
        XCTAssertEqual(items.map(\.recipeId), [here, here, nil, nil])
        XCTAssertTrue(items.allSatisfy { !$0.checked })
        let pantry = try await db.read { try PantryDao(db: $0).items() }
        XCTAssertTrue(pantry.isEmpty)

        // The same file opened again adds nothing.
        let again = try summary(await repo().receive(file, choice: everything))
        XCTAssertEqual(again.recipesAdded + again.groceriesAdded, 0)
        let after = try await groceries()
        XCTAssertEqual(after.count, 4)
    }

    func testPantryItemsGoToThePantryWhereWhatIsHereStands() async throws {
        _ = try await db.write { conn in
            try PantryDao(db: conn).insert(PantryItemRecord(
                name: "basmati rice", quantity: nil, language: "en", aisle: "grains", inStock: false,
                alwaysHave: false, purchasedDay: nil, expiresDay: nil, updatedAt: 1
            ))
        }
        let file = try decodeOrFail(shareFixture())

        let result = try summary(await repo().receive(file, choice: ShareChoice(recipeIds: [], groceryIds: [], pantryIds: ["p-rice"])))

        XCTAssertEqual(result.pantryAdded, 0)
        let pantry = try await db.read { try PantryDao(db: $0).items() }
        XCTAssertEqual(pantry.map(\.inStock), [false])
        let items = try await groceries()
        XCTAssertTrue(items.isEmpty)
    }

    func testAReceivedRecipeIsTheNewestViewedAndTheHistoryCapFollows() async throws {
        _ = try await insert("https://example.com/old", viewedAt: 1)
        _ = try await insert("https://example.com/newer", viewedAt: 2)
        let file = try decodeOrFail(shareFixture())

        _ = try summary(await repo(.history(keep: 2)).receive(file, choice: ShareChoice(recipeIds: ["r-chicken"], groceryIds: [])))

        let old = try await recipe(url: "https://example.com/old")
        let newer = try await recipe(url: "https://example.com/newer")
        let chicken = try await recipe(url: "https://example.com/sheet-pan-chicken")
        XCTAssertNil(old)
        XCTAssertNotNil(newer)
        XCTAssertNotNil(chicken)
    }
}
