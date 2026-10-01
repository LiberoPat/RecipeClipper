import XCTest
@testable import RecipeClipper

/// Loads a file from `shared/fixtures/backup/` (the same files the Android tests load), copied
/// into the test bundle as the `fixtures` folder by project.yml.
func backupFixture(_ name: String) throws -> String {
    let bundle = Bundle(for: BackupJsonTests.self)
    let url = try XCTUnwrap(
        bundle.url(forResource: name, withExtension: "json", subdirectory: "fixtures/backup"),
        "missing shared fixture backup/\(name).json"
    )
    return try String(contentsOf: url, encoding: .utf8)
}

func decodeOrFail(_ text: String, file: StaticString = #filePath, line: UInt = #line) throws -> Backup {
    switch BackupJson.decode(text) {
    case .success(let backup): return backup
    case .failure(let error):
        XCTFail("decode failed: \(error)", file: file, line: line)
        throw error
    }
}

/// The export file format. Mirrors Android's BackupJsonTest assertion for assertion, against the
/// same fixture file: that pair is what proves an export from one platform reads the same on
/// the other.
final class BackupJsonTests: XCTestCase {

    private func error(_ text: String) -> BackupError? {
        if case .failure(let e) = BackupJson.decode(text) { return e }
        return nil
    }

    func testTheSharedFixtureDecodesFieldForField() throws {
        let backup = try decodeOrFail(backupFixture("backup-v1"))

        XCTAssertEqual(backup.exportedAt, 1790000000000)
        XCTAssertEqual(backup.recipes.map(\.id), ["r-soup", "r-pie", "r-pie-dup", "r-bread", "r-old", "e-bread"])
        let soup = backup.recipes[0]
        XCTAssertEqual(soup.sourceUrl, "https://example.com/soup?utm_source=newsletter")
        XCTAssertEqual(soup.sourceType, "BLOG")
        XCTAssertEqual(soup.title, "Tomato Soup")
        XCTAssertEqual(soup.imageUrl, "https://example.com/img/soup.jpg")
        XCTAssertEqual(soup.ingredients, ["2 lb tomatoes", "1 onion, \"chopped\"", "½ tsp salt"])
        XCTAssertEqual(soup.instructions, ["Roast the tomatoes.", "Blend with the onion."])
        XCTAssertEqual(soup.prepTime, "10m")
        XCTAssertEqual(soup.cookTime, "40m")
        XCTAssertEqual(soup.totalTime, "50m")
        XCTAssertEqual(soup.servings, "4 servings")
        XCTAssertEqual(soup.lastViewedAt, 1789000000500)
        XCTAssertEqual(soup.checkedIngredients, [1])
        XCTAssertEqual(soup.notes, "Less salt.\nDouble the onion.")

        let pie = backup.recipes[1]
        XCTAssertNil(pie.imageUrl)
        XCTAssertNil(pie.prepTime)
        XCTAssertEqual(pie.checkedIngredients, [0, 5])
        XCTAssertNil(pie.notes)

        let dup = backup.recipes[2]
        XCTAssertNil(dup.imageUrl)
        XCTAssertNil(dup.servings)
        XCTAssertEqual(dup.checkedIngredients, [])

        XCTAssertEqual(backup.recipes[5].sourceType, "SOMETHING_NEW")
        XCTAssertEqual(backup.recipes[5].notes, "   ")

        XCTAssertEqual(
            backup.lists[0],
            BackupList(id: "f-fav", name: "Faves", isFavorites: true, isBuiltIn: true, sortOrder: 0, createdAt: 1700000000000)
        )
        XCTAssertEqual(backup.lists.map(\.id), ["f-fav", "f-lunch", "l-week", "f-fakefav", "f-party", "f-party2"])
        XCTAssertEqual(backup.lists[4].name, " Party food ")
        XCTAssertEqual(backup.memberships.count, 7)
        XCTAssertEqual(backup.memberships[0], BackupMembership(recipeId: "r-soup", listId: "f-fav", addedAt: 10))

        // The pantry (#51) and groceries (#50): later sections, no version bump.
        XCTAssertEqual(backup.pantry.map(\.id), ["p-flour", "p-salt", "p-here", "p-flour2"])
        XCTAssertEqual(backup.pantry[0], BackupPantryItem(
            id: "p-flour", name: " Flour ", quantity: "half a bag", language: "en", aisle: "baking", inStock: true,
            alwaysHave: false, purchasedDay: 20700, expiresDay: 20900, updatedAt: 1789000000000
        ))
        let defaults = backup.pantry[3]
        XCTAssertFalse(defaults.inStock)
        XCTAssertFalse(defaults.alwaysHave)
        XCTAssertNil(defaults.quantity)
        XCTAssertNil(defaults.expiresDay)
        XCTAssertEqual(defaults.updatedAt, 0)

        XCTAssertEqual(backup.groceries.map(\.id), ["g-tomatoes", "g-apples", "g-beef", "g-milk", "g-here"])
        XCTAssertEqual(backup.groceries[0], BackupGroceryItem(
            id: "g-tomatoes", text: "2 lb tomatoes", language: "en", aisle: "produce", checked: false, recipeId: "r-soup",
            plannedDay: 20720, updatedAt: 1789000000000
        ))
        XCTAssertTrue(backup.groceries[1].checked)
        XCTAssertNil(backup.groceries[3].recipeId)

        // The meal plan (#49): meal types and entries, later sections too.
        XCTAssertEqual(backup.mealTypes.map(\.id), ["t-dinner", "t-brunch", "t-fakedinner", "t-tea"])
        XCTAssertEqual(backup.mealTypes[0], BackupMealType(id: "t-dinner", name: "Dinner", builtInKey: "dinner", sortOrder: 2, updatedAt: 1))
        XCTAssertEqual(backup.mealTypes[2], BackupMealType(id: "t-fakedinner", name: "Dinner", builtInKey: nil, sortOrder: 5, updatedAt: 0))
        XCTAssertEqual(backup.mealPlan.map(\.id), ["m-soup", "m-old", "m-note", "m-pie", "m-missing", "m-here"])
        XCTAssertEqual(backup.mealPlan[0], BackupPlanEntry(
            id: "m-soup", day: 20720, mealTypeId: "t-dinner", recipeId: "r-soup", servings: 6, note: nil, sortOrder: 0,
            updatedAt: 1789000000000
        ))
        XCTAssertEqual(backup.mealPlan[2], BackupPlanEntry(
            id: "m-note", day: 20721, mealTypeId: nil, recipeId: nil, servings: nil, note: "Leftovers", sortOrder: 1, updatedAt: 3
        ))
        XCTAssertNil(backup.mealPlan[4].recipeId)
    }

    // #194: running low travels with the item; a file from before it (the fixture has none) reads
    // each item as in stock or run out, and running low never survives an item that is out.
    func testRunningLowRoundTripsAndAnOlderFilesItemsAreInStockOrRunOut() throws {
        let old = try decodeOrFail(try backupFixture("backup-v1"))
        XCTAssertEqual(old.pantry.map(\.runningLow), [false, false, false, false])

        let low = BackupPantryItem(
            id: "p-low", name: "garlic", quantity: nil, language: "en", aisle: "produce", inStock: true, alwaysHave: false,
            purchasedDay: nil, expiresDay: nil, updatedAt: 1, runningLow: true
        )
        let file = Backup(exportedAt: 1, recipes: [], lists: [], memberships: [], pantry: [low])
        XCTAssertEqual(try decodeOrFail(BackupJson.encode(file)).pantry, [low])

        let outAndLow = try decodeOrFail(
            #"{"format": "recipe-clipper-backup", "formatVersion": 1, "pantry": [{"id": "p", "name": "rice", "inStock": false, "runningLow": true}]}"#
        )
        XCTAssertEqual(outAndLow.pantry.first?.runningLow, false)
    }

    func testAFileWithoutPantryOrGroceriesReadsThemAsEmpty() throws {
        let backup = try decodeOrFail(#"{"format": "recipe-clipper-backup", "formatVersion": 1}"#)
        XCTAssertTrue(backup.pantry.isEmpty)
        XCTAssertTrue(backup.groceries.isEmpty)
        XCTAssertTrue(backup.mealTypes.isEmpty)
        XCTAssertTrue(backup.mealPlan.isEmpty)
    }

    func testAPlannedMealNeedsADayAMealTypeANameAndIdsAreUnique() {
        XCTAssertEqual(
            error(#"{"format": "recipe-clipper-backup", "formatVersion": 1, "mealPlan": [{"id": "m", "note": "x"}]}"#),
            .malformed("mealPlan[0].day")
        )
        XCTAssertEqual(
            error(#"{"format": "recipe-clipper-backup", "formatVersion": 1, "mealTypes": [{"id": "t", "name": ""}]}"#),
            .malformed("mealTypes[0].name")
        )
        XCTAssertEqual(
            error(#"{"format": "recipe-clipper-backup", "formatVersion": 1, "mealPlan": [{"id": "m", "day": 1}, {"id": "m", "day": 2}]}"#),
            .malformed("mealPlan[1].id")
        )
    }

    func testAPantryItemNeedsANameAndIdsAreUnique() {
        XCTAssertEqual(
            error(#"{"format": "recipe-clipper-backup", "formatVersion": 1, "pantry": [{"id": "p", "name": " "}]}"#),
            .malformed("pantry[0].name")
        )
        XCTAssertEqual(
            error(#"{"format": "recipe-clipper-backup", "formatVersion": 1, "groceries": [{"id": "g", "text": "a"}, {"id": "g", "text": "b"}]}"#),
            .malformed("groceries[1].id")
        )
    }

    func testANewerFormatVersionIsRefusedBeforeAnythingElseIsRead() throws {
        XCTAssertEqual(error(try backupFixture("backup-v2-newer")), .newerVersion(found: 2))
    }

    func testEncodeThenDecodeRoundTripsEveryField() throws {
        let original = try decodeOrFail(backupFixture("backup-v1"))
        XCTAssertEqual(try decodeOrFail(BackupJson.encode(original)), original)
    }

    func testARecipesLanguageRoundTripsAndAFileWithoutOneReadsAsNone() throws {
        var backup = try decodeOrFail(backupFixture("backup-v1"))
        XCTAssertTrue(backup.recipes.allSatisfy { $0.language == nil })
        for i in backup.recipes.indices { backup.recipes[i].language = "de-de" }
        XCTAssertEqual(try decodeOrFail(BackupJson.encode(backup)), backup)
    }

    func testARecipesContentOriginRoundTripsAndAFileWithoutOneReadsAsParsed() throws {
        var backup = try decodeOrFail(backupFixture("backup-v1"))
        XCTAssertTrue(backup.recipes.allSatisfy { $0.contentOrigin == "PARSED" && $0.editedAt == nil })
        for i in backup.recipes.indices {
            backup.recipes[i].contentOrigin = "EDITED"
            backup.recipes[i].editedAt = 42
        }
        XCTAssertEqual(try decodeOrFail(BackupJson.encode(backup)), backup)
    }

    func testTheEncodedFileCarriesTheMarkerTheVersionAndExplicitNulls() throws {
        let text = BackupJson.encode(try decodeOrFail(backupFixture("backup-v1")))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
        XCTAssertEqual(root["format"] as? String, "recipe-clipper-backup")
        XCTAssertEqual(root["formatVersion"] as? Int, 1)
        let pie = try XCTUnwrap((root["recipes"] as? [[String: Any]])?[1])
        XCTAssertTrue(pie["imageUrl"] is NSNull)
        XCTAssertFalse(text.contains("\\/"), "slashes are written plainly")
    }

    /// "Mark as cooked" (#173): the shared fixture, read the same way by Android's `BackupJsonTest`.
    func testACookingWithNoPhotoReadsFromItsOwnSectionWithNoFile() throws {
        let backup = try decodeOrFail(backupFixture("backup-v1-cooked"))
        // The one naming no recipe in the file is left out, as a photo would be.
        XCTAssertEqual(backup.cookedPhotos.map(\.id), ["p-photo", "c-weeknight"])
        XCTAssertEqual(backup.cookedPhotos[1], BackupCookedPhoto(
            id: "c-weeknight", recipeId: "r-soup", day: 20_007, note: "Doubled the garlic",
            createdAt: 1789000000900, updatedAt: 1789000000950, file: nil
        ))

        // A plain JSON file brings no pictures: the photo stays out, the cooking with none comes
        // in, and its recipe with it, past a full history.
        let plan = BackupMerger.plan(
            backup, existingRecipes: [], existingLists: [], maxSortOrder: 0, historyLimit: 0, newUid: { "fresh" },
            availablePhotoFiles: []
        )
        XCTAssertEqual(plan.newRecipes.map(\.id), ["r-soup"])
        XCTAssertEqual(plan.newCookedPhotos.map(\.photo.id), ["c-weeknight"])
        XCTAssertEqual(plan.summary.photosAdded, 0)
    }

    /// An older app requires a `file` on every `cookedPhotos` entry and ignores sections it
    /// doesn't know (#173): so a cooking with no photo is written apart, and the file without that
    /// section, which is what an older app reads, still decodes, with the photos.
    func testACookingWithNoPhotoIsWrittenInItsOwnSectionSoAnOlderReaderSkipsIt() throws {
        let backup = try decodeOrFail(backupFixture("backup-v1-cooked"))
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(BackupJson.encode(backup).utf8)) as? [String: Any])
        XCTAssertEqual(root["formatVersion"] as? Int, 1)
        let photos = try XCTUnwrap(root["cookedPhotos"] as? [[String: Any]])
        XCTAssertEqual(photos.count, 1)
        XCTAssertEqual(photos[0]["file"] as? String, "photos/p-photo.jpg")
        let without = try XCTUnwrap(root["cookedWithoutPhotos"] as? [[String: Any]])
        XCTAssertEqual(without[0]["id"] as? String, "c-weeknight")
        XCTAssertNil(without[0]["file"])
        XCTAssertEqual(try decodeOrFail(BackupJson.encode(backup)), backup)

        root["cookedWithoutPhotos"] = nil
        let older = String(decoding: try JSONSerialization.data(withJSONObject: root), as: UTF8.self)
        XCTAssertEqual(try decodeOrFail(older).cookedPhotos.map(\.id), ["p-photo"])

        // Ids are one set across both sections.
        let head = #"{"format":"recipe-clipper-backup","formatVersion":1,"recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T"}],"#
        XCTAssertEqual(
            error(head + #""cookedPhotos":[{"id":"x","recipeId":"a","day":1,"file":"photos/x.jpg"}],"#
                + #""cookedWithoutPhotos":[{"id":"x","recipeId":"a","day":1}]}"#),
            .malformed("cookedWithoutPhotos[0].id")
        )
        XCTAssertEqual(
            error(head + #""cookedWithoutPhotos":[{"id":"y","recipeId":"a"}]}"#),
            .malformed("cookedWithoutPhotos[0].day")
        )
    }

    /// #235: a recipe's photo address in a file someone sent is a web image or none, never one on
    /// the device; the archive's own photos (`photos/…` entries) still come in. Android's
    /// `BackupJsonTest`, the same case.
    func testAFilesPhotoAddressMustBeAWebImageAndTheArchivesOwnPhotosStillImport() throws {
        func recipe(_ id: String, _ image: String) -> String {
            #"{"id":"\#(id)","sourceUrl":"https://a.b/\#(id)","title":"T","imageUrl":"\#(image)"}"#
        }
        let backup = try decodeOrFail(
            #"{"format":"recipe-clipper-backup","formatVersion":1,"recipes":["#
                + recipe("f", "file:///data/data/com.liberopat.recipeclipper/databases/recipe_clipper.db") + ","
                + recipe("c", "content://media/external/images/media/1") + ","
                + recipe("p", "/data/user/0/com.liberopat.recipeclipper/files/x.jpg") + ","
                + recipe("h", "http://img.example/h.jpg") + ","
                + recipe("s", "https://img.example/s.jpg")
                + #"],"cookedPhotos":[{"id":"x","recipeId":"s","day":1,"file":"photos/x.jpg"}]}"#
        )
        XCTAssertEqual(
            backup.recipes.map(\.imageUrl),
            [nil, nil, nil, "https://img.example/h.jpg", "https://img.example/s.jpg"]
        )
        XCTAssertEqual(backup.cookedPhotos.map(\.file), ["photos/x.jpg"])

        let plan = BackupMerger.plan(
            backup, existingRecipes: [], existingLists: [], maxSortOrder: 0, historyLimit: 50, newUid: { "fresh" },
            availablePhotoFiles: ["photos/x.jpg"]
        )
        XCTAssertEqual(plan.newCookedPhotos.map(\.photo.id), ["x"])
        XCTAssertEqual(plan.summary.photosAdded, 1)
    }

    func testAnEmptyExportIsValid() throws {
        let backup = try decodeOrFail(#"{"format":"recipe-clipper-backup","formatVersion":1}"#)
        XCTAssertEqual(backup, Backup(exportedAt: 0, recipes: [], lists: [], memberships: []))
    }

    func testAnythingThatIsNotAnExportIsNotABackup() {
        XCTAssertEqual(error(""), .notABackup)
        XCTAssertEqual(error("not json"), .notABackup)
        XCTAssertEqual(error("[1, 2]"), .notABackup)
        XCTAssertEqual(error(#"{"formatVersion":1,"recipes":[]}"#), .notABackup)
        XCTAssertEqual(error(#"{"format":"something-else","formatVersion":1}"#), .notABackup)
        XCTAssertEqual(error(String(repeating: "[", count: 100_000)), .notABackup)
    }

    func testADamagedExportNamesTheFirstBadField() {
        let head = #"{"format":"recipe-clipper-backup","formatVersion":1,"#
        XCTAssertEqual(error(#"{"format":"recipe-clipper-backup"}"#), .malformed("formatVersion"))
        XCTAssertEqual(error(#"{"format":"recipe-clipper-backup","formatVersion":"1"}"#), .malformed("formatVersion"))
        XCTAssertEqual(error(#"{"format":"recipe-clipper-backup","formatVersion":0}"#), .malformed("formatVersion"))
        XCTAssertEqual(error(head + #""recipes":{}}"#), .malformed("recipes"))
        XCTAssertEqual(error(head + #""recipes":[{"sourceUrl":"https://a.b/c","title":"T"}]}"#), .malformed("recipes[0].id"))
        XCTAssertEqual(error(head + #""recipes":[{"id":"a","sourceUrl":" ","title":"T"}]}"#), .malformed("recipes[0].sourceUrl"))
        XCTAssertEqual(error(head + #""recipes":[{"id":"a","sourceUrl":"https://a.b/c"}]}"#), .malformed("recipes[0].title"))
        XCTAssertEqual(
            error(head + #""recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T","ingredients":["x",2]}]}"#),
            .malformed("recipes[0].ingredients[1]")
        )
        XCTAssertEqual(
            error(head + #""recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T","lastViewedAt":1.5}]}"#),
            .malformed("recipes[0].lastViewedAt")
        )
        XCTAssertEqual(
            error(head + #""recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T"},{"id":"a","sourceUrl":"https://a.b/d","title":"U"}]}"#),
            .malformed("recipes[1].id")
        )
        XCTAssertEqual(error(head + #""lists":[{"id":"l","name":""}]}"#), .malformed("lists[0].name"))
        XCTAssertEqual(error(head + #""lists":[{"id":"l","name":"L","isFavorites":1}]}"#), .malformed("lists[0].isFavorites"))
        XCTAssertEqual(
            error(head + #""recipes":[{"id":"a","sourceUrl":"https://a.b/c","title":"T"}],"memberships":[{"recipeId":"a","listId":"nope"}]}"#),
            .malformed("memberships[0].listId")
        )
        XCTAssertEqual(
            error(head + #""lists":[{"id":"l","name":"L"}],"memberships":[{"recipeId":"nope","listId":"l"}]}"#),
            .malformed("memberships[0].recipeId")
        )
    }
}
