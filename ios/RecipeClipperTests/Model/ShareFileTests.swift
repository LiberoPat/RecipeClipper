import XCTest
@testable import RecipeClipper

/// Loads `shared/fixtures/backup/<name>.recipeclipper`, the shared file both platforms read (#149).
func shareFixture(_ name: String = "share-v1") throws -> String {
    let bundle = Bundle(for: ShareFileTests.self)
    let url = try XCTUnwrap(
        bundle.url(forResource: name, withExtension: ShareFile.fileExtension, subdirectory: "fixtures/backup"),
        "missing shared fixture backup/\(name).\(ShareFile.fileExtension)"
    )
    return try String(contentsOf: url, encoding: .utf8)
}

/// The shared file (#149, phase 2). Mirrors Android's ShareFileTest one for one, over the same
/// fixture.
final class ShareFileTests: XCTestCase {

    private func recipe(_ id: String) -> BackupRecipe {
        BackupRecipe(
            id: id, sourceUrl: "https://example.com/\(id)", sourceType: "BLOG", title: "Recipe \(id)", imageUrl: nil,
            ingredients: ["1 egg", "2 cups flour"], instructions: ["Mix."], prepTime: nil, cookTime: nil,
            totalTime: nil, servings: "4", lastViewedAt: 5, checkedIngredients: [0, 1], notes: "Mine",
            language: "en", contentOrigin: "EDITED", editedAt: 3
        )
    }

    private func grocery(_ id: String, _ recipeId: String?, checked: Bool = false) -> BackupGroceryItem {
        BackupGroceryItem(
            id: id, text: "item \(id)", language: "en", aisle: "other", checked: checked,
            recipeId: recipeId, plannedDay: 20_000, updatedAt: 1
        )
    }

    private func pantry(_ id: String, _ name: String) -> BackupPantryItem {
        BackupPantryItem(
            id: id, name: name, quantity: "a bag", language: "en", aisle: "grains", inStock: true,
            alwaysHave: false, purchasedDay: 19_000, expiresDay: nil, updatedAt: 1
        )
    }

    func testTheFixtureReadsAsAShareAndUnknownKeysAreIgnored() throws {
        let file = try decodeOrFail(shareFixture())

        XCTAssertTrue(file.isShare)
        XCTAssertEqual(file.recipes.map(\.id), ["r-chicken", "r-cake"])
        XCTAssertEqual(file.recipes[1].contentOrigin, "MANUAL")
        XCTAssertEqual(file.groceries.map(\.id), ["g-thighs", "g-lemon", "g-paper"])
        XCTAssertEqual(file.groceries.map(\.recipeId), ["r-chicken", "r-chicken", nil])
        XCTAssertEqual(file.pantry.map(\.name), ["Basmati rice"])
        XCTAssertTrue(file.lists.isEmpty && file.memberships.isEmpty && file.mealPlan.isEmpty)
    }

    func testABackupIsNotAShareAndWritingOneAddsNoKind() throws {
        let backup = try decodeOrFail(backupFixture("backup-v1"))
        XCTAssertFalse(backup.isShare)
        XCTAssertFalse(BackupJson.encode(backup).contains("\"kind\""))
    }

    func testAShareSurvivesARoundTripKindAndAll() throws {
        let file = ShareFile.make(now: 9, recipes: [recipe("a")], groceries: [grocery("g", "a")])
        let text = BackupJson.encode(file)

        XCTAssertTrue(text.contains("\"kind\" : \"share\""))
        XCTAssertTrue(text.contains("\"format\" : \"recipe-clipper-backup\""))
        XCTAssertTrue(text.contains("\"formatVersion\" : 1"))
        XCTAssertEqual(try decodeOrFail(text), file)
    }

    func testARecipeIsSentCompleteWithoutTheSendersTicksNoteOrLastView() throws {
        let sent = try XCTUnwrap(ShareFile.make(now: 9, recipes: [recipe("a"), recipe("a")]).recipes.first)
        XCTAssertEqual(ShareFile.make(now: 9, recipes: [recipe("a"), recipe("a")]).recipes.count, 1)

        XCTAssertEqual(sent.ingredients, ["1 egg", "2 cups flour"])
        XCTAssertEqual(sent.contentOrigin, "EDITED")
        XCTAssertEqual(sent.editedAt, 3)
        XCTAssertTrue(sent.checkedIngredients.isEmpty)
        XCTAssertNil(sent.notes)
        XCTAssertEqual(sent.lastViewedAt, 9)
    }

    func testGroceryItemsGoUntickedNameOnlyARecipeTheFileCarriesAndKeepNoPlannedDay() {
        let file = ShareFile.make(
            now: 9, recipes: [recipe("a")],
            groceries: [grocery("g1", "a", checked: true), grocery("g2", "gone"), grocery("g1", "a")]
        )

        XCTAssertEqual(file.groceries.map(\.id), ["g1", "g2"])
        XCTAssertEqual(file.groceries.map(\.recipeId), ["a", nil])
        XCTAssertTrue(file.groceries.allSatisfy { !$0.checked && $0.plannedDay == nil })
        XCTAssertTrue(file.lists.isEmpty && file.memberships.isEmpty && file.cookedPhotos.isEmpty)
    }

    func testOnlyWhatWasTickedIsMergedEachNewToThisPhone() throws {
        let file = ShareFile.make(now: 9, recipes: [recipe("a"), recipe("b")], groceries: [grocery("g1", "a"), grocery("g2", "b")])
        let chosen = ShareFile.chosen(file, ShareChoice(recipeIds: ["b"], groceryIds: ["g1", "g2"]), now: 50)

        XCTAssertEqual(chosen.recipes.map(\.id), ["b"])
        XCTAssertEqual(chosen.recipes.first?.lastViewedAt, 50)
        // An item's recipe that wasn't ticked isn't brought in for it.
        XCTAssertEqual(chosen.groceries.map(\.recipeId), [nil, "b"])
        XCTAssertEqual(chosen.groceries.map(\.updatedAt), [50, 50])
    }

    func testPantryItemsGoToThePantryOrOntoTheGroceryListAsTheirNames() {
        let file = ShareFile.make(now: 9, recipes: [], groceries: [], pantry: [pantry("p1", "Basmati rice"), pantry("p2", "Oats")])

        let toPantry = ShareFile.chosen(file, ShareChoice(recipeIds: [], groceryIds: [], pantryIds: ["p1"]), now: 50)
        XCTAssertEqual(toPantry.pantry.map(\.name), ["Basmati rice"])
        XCTAssertTrue(toPantry.groceries.isEmpty)

        let toGroceries = ShareFile.chosen(
            file, ShareChoice(recipeIds: [], groceryIds: [], pantryIds: ["p1", "p2"], pantryTo: .groceries), now: 50
        )
        XCTAssertTrue(toGroceries.pantry.isEmpty)
        XCTAssertEqual(toGroceries.groceries.map(\.text), ["Basmati rice", "Oats"])
        // Their uids stay, so the same file opened twice adds each once.
        XCTAssertEqual(toGroceries.groceries.map(\.id), ["p1", "p2"])
        XCTAssertTrue(toGroceries.groceries.allSatisfy { !$0.checked && $0.recipeId == nil })
    }

    func testTheFileIsNamedForItsTitleSafely() {
        XCTAssertEqual(ShareFile.fileName("Sheet-pan chicken"), "Sheet-pan chicken.recipeclipper")
        XCTAssertEqual(ShareFile.fileName("Mac/and\\cheese: 1*2?"), "Mac and cheese 1 2.recipeclipper")
        XCTAssertEqual(ShareFile.fileName("..hidden"), "hidden.recipeclipper")
        XCTAssertEqual(ShareFile.fileName(" / "), "Recipe Clipper.recipeclipper")
        XCTAssertEqual(ShareFile.fileName(String(repeating: "x", count: 200)).count, 60 + ".recipeclipper".count)
    }
}
