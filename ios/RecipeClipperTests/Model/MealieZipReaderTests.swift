import XCTest
@testable import RecipeClipper

/// Reading a Mealie file (#391; Android's MealieArchiveTest). The files in `shared/fixtures/mealie/` are
/// built by `tools/mealie-fixtures/make_fixtures.py` from Mealie's schema; Android's test reads the same
/// files and expects the same counts.
final class MealieZipReaderTests: XCTestCase {

    private func fixture(_ name: String, _ ext: String = "zip") throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: ext, subdirectory: "fixtures/mealie"))
        return try Data(contentsOf: url)
    }

    private func contents(_ read: MealieRead, file: StaticString = #filePath, line: UInt = #line) throws -> MealieContents {
        guard case .success(let contents) = read else {
            XCTFail("expected a success, got \(read)", file: file, line: line)
            throw CancellationError()
        }
        return contents
    }

    private func recipeJson(_ slug: String) -> Data {
        Data(#"{"slug": "\#(slug)", "name": "R \#(slug)", "recipe_ingredient": [{"original_text": "1 egg", "reference_id": "a"}], "recipe_instructions": []}"#.utf8)
    }

    func testABulkExportGivesItsRecipesInOrderAndCountsWhatItSkipped() throws {
        let c = try contents(MealieZipReader.read(fixture("mealie-bulk")))
        XCTAssertEqual(c.recipes.map(\.name), ["Pasta e Ceci", "Old Lentil Soup", "Mint Tea", "Pasta e Ceci (again)"])
        // The nameless one, the one with only a title, and the schema.org file.
        XCTAssertEqual(c.notRecipes, 3)
        // The file that isn't JSON.
        XCTAssertEqual(c.unreadable, 1)
        XCTAssertEqual(c.photosLeftOut, 0)
        XCTAssertTrue(c.recipes.allSatisfy { $0.photoFile == nil })
    }

    func testARecipesDownloadGivesItsRecipeAndHandsItsPictureToTheStore() throws {
        var seen: [Data] = []
        let c = try contents(MealieZipReader.read(fixture("mealie-recipe-download"), storePhoto: { data in seen.append(data); return "stored.jpg" }))
        XCTAssertEqual(c.recipes.map(\.name), ["Pasta e Ceci"])
        XCTAssertEqual(c.recipes.map(\.photoFile), ["stored.jpg"])
        XCTAssertEqual(seen.count, 1)
        // A WebP: RIFF....WEBP.
        XCTAssertEqual(String(decoding: seen[0].prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(String(decoding: seen[0][8..<12], as: UTF8.self), "WEBP")
        XCTAssertEqual(c.photosLeftOut, 0)
    }

    func testThePictureOfADownloadIsAWebPTheRealPhotoStoreDecodes() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = FilePhotoStore(directory: directory)
        defer { try? FileManager.default.removeItem(at: directory) }
        let c = try contents(MealieZipReader.read(fixture("mealie-recipe-download"), storePhoto: { store.importData($0) }))
        let name = try XCTUnwrap(c.recipes.first?.photoFile)
        XCTAssertTrue(store.exists(name))
        XCTAssertEqual(c.photosLeftOut, 0)
        // Stored as the JPEG the app keeps every photo as.
        let stored = try Data(contentsOf: URL(fileURLWithPath: store.path(name)))
        XCTAssertEqual(Array(stored.prefix(2)), [0xFF, 0xD8])
    }

    func testAPictureBesideARecipesJsonInItsFolderBelongsToItAndWithNoStoreItIsLeftOutAndCounted() throws {
        let file = try fixture("mealie-with-pictures")
        let withStore = try contents(MealieZipReader.read(file, storePhoto: { _ in "kept.jpg" }))
        XCTAssertEqual(withStore.recipes.map(\.photoFile), ["kept.jpg", nil])
        XCTAssertEqual(withStore.photosLeftOut, 0)
        let without = try contents(MealieZipReader.read(file))
        XCTAssertEqual(without.recipes.count, 2)
        XCTAssertEqual(without.photosLeftOut, 1)
    }

    func testAPictureThatIsTooBigNotAnImageOrBelongsToNoRecipe() throws {
        let c = try contents(MealieZipReader.read(fixture("mealie-photos"), storePhoto: { data in
            String(decoding: data.prefix(3), as: UTF8.self) == "bad" ? nil : "good.jpg"
        }))
        XCTAssertEqual(c.recipes.map(\.name), ["Toast", "Jam", "Honey", "Salt"])
        // Toast's is stored; Jam's is refused by the store; Honey's is over 8 MiB and never offered; Salt has none; the stray one fits no recipe.
        XCTAssertEqual(c.recipes.map(\.photoFile), ["good.jpg", nil, nil, nil])
        XCTAssertEqual(c.photosLeftOut, 2)
    }

    func testALoweredPictureCapLeavesAPictureOut() throws {
        var limits = MealieLimits()
        limits.maxPhotoBytes = 100
        let c = try contents(MealieZipReader.read(fixture("mealie-recipe-download"), limits: limits, storePhoto: { _ in "kept.jpg" }))
        XCTAssertEqual(c.photosLeftOut, 1)
        XCTAssertNil(c.recipes.first?.photoFile)
    }

    func testABareJsonIsOneRecipe() throws {
        let c = try contents(MealieZipReader.read(fixture("mealie-recipe", "json")))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        XCTAssertEqual(c.notRecipes, 0)
        // The pre 3.1 file: the unparsed lines are their notes.
        let old = try XCTUnwrap(try contents(MealieZipReader.read(fixture("mealie-pre31", "json"))).recipes.first)
        XCTAssertEqual(old.ingredients, ["2 cups red lentils", "1 onion, chopped", "2 cups water"])
    }

    func testASchemaOrgFileIsNotAMealieFileAloneOrInAZip() throws {
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-schema-org", "json")), .failure(.notAMealieFile))
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-only-schema")), .failure(.notAMealieFile))
    }

    func testFoldersMacOSCopiesAndOtherFilesAreIgnoredNotCounted() {
        XCTAssertFalse(MealieZipReader.isRecipeEntry("__MACOSX/recipes/a/._a.json"))
        XCTAssertFalse(MealieZipReader.isRecipeEntry("dir/__MACOSX/a.json"))
        XCTAssertFalse(MealieZipReader.isRecipeEntry("recipes/.DS_Store.json"))
        XCTAssertFalse(MealieZipReader.isRecipeEntry("readme.txt"))
        XCTAssertFalse(MealieZipReader.isRecipeEntry("recipes/"))
        XCTAssertTrue(MealieZipReader.isRecipeEntry("recipes/a/a.json"))
        XCTAssertTrue(MealieZipReader.isRecipeEntry("A.JSON"))
        XCTAssertTrue(MealieZipReader.isPictureEntry("a.webp"))
        XCTAssertTrue(MealieZipReader.isPictureEntry("recipes/a/images/original.WEBP"))
        XCTAssertTrue(MealieZipReader.isPictureEntry("a.jpeg"))
        XCTAssertFalse(MealieZipReader.isPictureEntry("__MACOSX/._a.webp"))
        XCTAssertFalse(MealieZipReader.isPictureEntry("a.json"))
    }

    func testATruncatedZipIsDamagedAndImportsNothing() throws {
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-truncated")), .failure(.damaged))
    }

    func testSomethingThatIsNotAZipOrARecipesJsonIsNotAMealieFile() throws {
        XCTAssertEqual(MealieZipReader.read(Data("Dear diary, today I made soup.".utf8)), .failure(.notAMealieFile))
        XCTAssertEqual(MealieZipReader.read(Data()), .failure(.notAMealieFile))
        // A zip with no json in it.
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-not-mealie")), .failure(.notAMealieFile))
    }

    func testABareJsonThatIsNoRecipe() throws {
        let noName = Data(#"{"slug": "a", "name": "", "recipe_ingredient": [{"original_text": "1 egg", "reference_id": "r"}]}"#.utf8)
        let c = try contents(MealieZipReader.read(noName))
        XCTAssertTrue(c.recipes.isEmpty)
        XCTAssertEqual(c.notRecipes, 1)
    }

    func testFilesThatAreNotUTF8NotJsonOrNotAnObjectAreCountedAndTheRestStillImport() throws {
        let c = try contents(MealieZipReader.read(fixture("mealie-bad-entries")))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        XCTAssertEqual(c.unreadable, 3)
        XCTAssertEqual(c.notRecipes, 0)
    }

    func testAnEntryThatClaimsToUnpackTo4GBIsRefusedUnread() throws {
        let c = try contents(MealieZipReader.read(fixture("mealie-huge-declared")))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        XCTAssertEqual(c.unreadable, 1)
    }

    func testAnEntryThatUnpacksPastThePerEntryCapIsDroppedHoweverSmallTheFileAndWhateverItClaims() throws {
        let file = try fixture("mealie-bomb")
        XCTAssertLessThan(file.count, 100_000, "the bomb is small")
        let c = try contents(MealieZipReader.read(file))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        XCTAssertEqual(c.unreadable, 1)
    }

    func testTooManyEntriesIsRefusedBeforeAnyIsRead() throws {
        var limits = MealieLimits()
        limits.maxEntries = 3
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-bulk"), limits: limits), .failure(.tooLarge))
    }

    func testMoreUnpackedTextThanTheTotalCapIsRefusedAndNothingIsImported() throws {
        var limits = MealieLimits()
        limits.maxTotalBytes = 600
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-bulk"), limits: limits), .failure(.tooLarge))
    }

    func testABareJsonOverTheCapIsTooLarge() throws {
        var limits = MealieLimits()
        limits.maxEntryBytes = 100
        XCTAssertEqual(MealieZipReader.read(try fixture("mealie-recipe", "json"), limits: limits), .failure(.tooLarge))
    }

    func testAnEntryNameIsOnlyComparedSoAPathNameDoesNothing() throws {
        let archive = PaprikaZipTestSupport.storedZip(["../../etc/passwd.json": recipeJson("a"), "/abs/b.json": recipeJson("b")])
        XCTAssertEqual(try contents(MealieZipReader.read(archive)).recipes.count, 2)
    }

    func testAPictureGoesToTheNearestRecipeUpItsPathAndALoneRecipeTakesAnyPicture() throws {
        var seen: [String] = []
        let store: (Data) -> String? = { data in seen.append(String(decoding: data, as: UTF8.self)); return "f" }
        let nested = PaprikaZipTestSupport.storedZip([
            "recipes/a/a.json": recipeJson("a"), "recipes/a/images/min-original.webp": Data("A-min".utf8),
            "recipes/a/images/original.webp": Data("A".utf8),
            "recipes/b/b.json": recipeJson("b"), "recipes/b/b.png": Data("B".utf8),
            "recipes/c/c.json": recipeJson("c"), "recipes/other/c.webp": Data("nobody".utf8),
        ])
        let c = try contents(MealieZipReader.read(nested, storePhoto: store))
        // Of two pictures for a, the one called original wins; c's folder holds none.
        XCTAssertEqual(seen, ["A", "B"])
        XCTAssertEqual(c.recipes.map(\.photoFile), ["f", "f", nil])
        seen = []
        let lone = PaprikaZipTestSupport.storedZip(["one.json": recipeJson("a"), "somewhere/deep/p.jpg": Data("P".utf8)])
        _ = try contents(MealieZipReader.read(lone, storePhoto: store))
        XCTAssertEqual(seen, ["P"])
    }
}

/// A Mealie recipe page already imports through the link (#391; Android's MealiePageTest): Mealie writes a
/// schema.org `application/ld+json` block into the HTML of a public or shared recipe's page itself, so no
/// JavaScript is needed. The page is made to the shape of Mealie's server code, not saved from a server.
final class MealiePageTests: XCTestCase {
    func testAMealiePagesJsonLdGivesItsRecipeThroughTheOrdinaryLinkImport() throws {
        let html = try pageFixture("mealie-pasta-e-ceci")
        guard case .success(let recipe) = BlogRecipeSource.parse(html: html, url: "https://mealie.example.com/g/home/shared/r/9a8b7c6d") else {
            return XCTFail("no recipe in the Mealie page")
        }
        XCTAssertEqual(recipe.name, "Pasta e Ceci")
        XCTAssertEqual(recipe.ingredients, ["2 cups canned chickpeas, drained", "1 tablespoon olive oil", "200 g ditalini", "salt & pepper, to taste"])
        XCTAssertEqual(recipe.instructions.count, 3)
        XCTAssertEqual(recipe.instructions[1], "Add the chickpeas and 4 cups of water, then simmer for 20 minutes.")
        XCTAssertEqual(recipe.prepTime, "10m")
        XCTAssertEqual(recipe.cookTime, "35m")
        XCTAssertEqual(recipe.totalTime, "45m")
        XCTAssertEqual(recipe.yield, "4 servings")
        XCTAssertEqual(recipe.language, "en")
    }
}
