import XCTest
@testable import RecipeClipper

/// Reading a Recipe Keeper export (#390; Android's RecipeKeeperArchiveTest). The files in
/// `shared/fixtures/recipekeeper/` are built by `tools/recipekeeper-fixtures/make_fixtures.py` from the
/// format the open-source importers document; Android's test reads the same files and expects the same counts.
final class RecipeKeeperZipReaderTests: XCTestCase {

    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: name, withExtension: "zip", subdirectory: "fixtures/recipekeeper"
        ))
        return try Data(contentsOf: url)
    }

    private func contents(_ read: PaprikaRead, file: StaticString = #filePath, line: UInt = #line) throws -> PaprikaContents {
        guard case .success(let contents) = read else {
            XCTFail("expected a success, got \(read)", file: file, line: line)
            throw CancellationError()
        }
        return contents
    }

    /// A store like the real one: a name for a picture, nil for what isn't one.
    private final class Store {
        var seen: [Data] = []
        func store(_ data: Data) -> String? {
            if String(decoding: data, as: UTF8.self).hasPrefix("bad") { return nil }
            seen.append(data)
            return "stored-\(seen.count).jpg"
        }
    }

    func testAValidExportGivesItsRecipesInOrderAndCountsWhatItSkipped() throws {
        let store = Store()
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-valid"), storePhoto: store.store))
        XCTAssertEqual(c.recipes.map(\.name), [
            "Blueberry Cheesecake", "Chocolate Chip Cookies", "Tiramisù", "Pasta al Pomodoro",
            "Blueberry Cheesecake (copy)", "Mint Tea", "Mac & Cheese – Quick",
        ])
        // The block with no name, and the one with a name and nothing else.
        XCTAssertEqual(c.notRecipes, 2)
        XCTAssertEqual(c.unreadable, 0)
        // Pasta's only picture isn't in the zip.
        XCTAssertEqual(c.photosLeftOut, 1)
    }

    func testARecipeReadsAsTheShapeThePaprikaImportMaps() throws {
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-valid"), storePhoto: Store().store))
        let cheesecake = c.recipes[0]
        XCTAssertEqual(cheesecake.uid, "a2489b23-050f-5c8a-8e5d-12fdb9fdeea3")
        XCTAssertEqual(cheesecake.ingredients, [
            "For the base:", "1 1/2 cups digestive biscuit crumbs", "1/2 cup melted butter",
            "For the filling:", "600 g cream cheese", "2 large eggs",
        ])
        XCTAssertEqual(cheesecake.directions, [
            "Melt the butter and mix it into the crumbs. Press into a 9-inch pan. Bake at 325°F for 10 minutes.",
            "Beat the cream cheese with the eggs until smooth.",
            "Pour over the base and bake for 40 minutes.",
        ])
        XCTAssertEqual(cheesecake.notes, "Better the next day.\nFreezes well.")
        XCTAssertEqual(cheesecake.servings, "Serves 6-8")
        XCTAssertEqual(cheesecake.prepTime, "PT10M")
        XCTAssertEqual(cheesecake.cookTime, "PT1H")
        XCTAssertNil(cheesecake.totalTime)
        XCTAssertNil(cheesecake.sourceUrl, "'Recipe Keeper' is no link")
        XCTAssertEqual(cheesecake.categories, ["Dessert", "Cake", "Holiday Baking"])
        XCTAssertFalse(cheesecake.favorite)

        let cookies = c.recipes[1]
        XCTAssertEqual(cookies.sourceUrl, "https://www.example.com/cookies?id=3")
        XCTAssertTrue(cookies.favorite)
        XCTAssertEqual(cookies.categories, ["Snack", "Dessert", "Baking", "Cookie"])

        let tiramisu = c.recipes[2]
        XCTAssertEqual(tiramisu.servings, "4 persone")
        XCTAssertEqual(tiramisu.ingredients, ["Per la crema:", "3 uova", "250 g mascarpone", "Per la base:", "savoiardi q.b."])

        XCTAssertEqual(c.recipes[3].directions, ["Cook the pasta.", "2 minutes before the end, add the basil.", "Serve."])
        XCTAssertNil(c.recipes[5].uid)
    }

    func testEachRecipesFirstReadablePictureIsStoredTheNextIsTriedWhenOneIsNotAnImage() throws {
        let store = Store()
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-valid"), storePhoto: store.store))
        // Cheesecake's first, cookies', tiramisu's second (the first is not an image), and the
        // percent-encoded name of the macaroni one; pasta's is missing and the others have none.
        XCTAssertEqual(c.recipes.map(\.photoFile), ["stored-1.jpg", "stored-2.jpg", "stored-3.jpg", nil, nil, nil, "stored-4.jpg"])
        XCTAssertEqual(store.seen.count, 4)
        XCTAssertTrue(c.recipes.allSatisfy { $0.photoData == nil })
    }

    func testWithNoStoreEveryPictureIsLeftOutAndCountedTheRecipesStay() throws {
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-valid")))
        XCTAssertEqual(c.recipes.count, 7)
        // Cheesecake, cookies, tiramisu, pasta and macaroni each named a picture.
        XCTAssertEqual(c.photosLeftOut, 5)
    }

    func testThePageMaySitInOneWrapperFolderBehindABOMAndOnlyTheShallowestIsRead() throws {
        let store = Store()
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-wrapper"), storePhoto: store.store))
        XCTAssertEqual(c.recipes.map(\.name), ["Gran's Soup", "Flapjacks"])
        XCTAssertEqual(c.recipes.map(\.photoFile), ["stored-1.jpg", nil])
        // Flapjacks names five pictures that all point outside the zip: none is followed.
        XCTAssertEqual(c.photosLeftOut, 1)
        XCTAssertEqual(store.seen.count, 1)
    }

    func testAPicturesAddressIsOnlyEverComparedWithEntryNames() {
        XCTAssertEqual(RecipeKeeperZipReader.resolve(folder: "a", src: "images/x.jpg"), "a/images/x.jpg")
        XCTAssertEqual(RecipeKeeperZipReader.resolve(folder: "", src: "./images/./x.jpg"), "images/x.jpg")
        XCTAssertEqual(RecipeKeeperZipReader.resolve(folder: "", src: "images/x%20y.jpg"), "images/x y.jpg")
        XCTAssertEqual(RecipeKeeperZipReader.resolve(folder: "a", src: "../b/x.jpg"), "b/x.jpg")
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "", src: "../x.jpg"))
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "a", src: "../../x.jpg"))
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "", src: "/etc/passwd"))
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "", src: "https://evil.example/x.jpg"))
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "", src: "C:\\x.jpg"))
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "", src: "images\\x.jpg"))
        XCTAssertNil(RecipeKeeperZipReader.resolve(folder: "", src: ""))
    }

    func testFoldersMacOSCopiesAndHiddenFilesAreNoPage() {
        XCTAssertTrue(RecipeKeeperZipReader.isPageEntry("recipes.html"))
        XCTAssertTrue(RecipeKeeperZipReader.isPageEntry("RecipeKeeper_20261119/recipes.html"))
        XCTAssertTrue(RecipeKeeperZipReader.isPageEntry("Recipes.HTML"))
        XCTAssertFalse(RecipeKeeperZipReader.isPageEntry("__MACOSX/x/recipes.html"))
        XCTAssertFalse(RecipeKeeperZipReader.isPageEntry("x/__MACOSX/recipes.html"))
        XCTAssertFalse(RecipeKeeperZipReader.isPageEntry("._recipes.html"))
        XCTAssertFalse(RecipeKeeperZipReader.isPageEntry("recipes.html/"))
        XCTAssertFalse(RecipeKeeperZipReader.isPageEntry("my-recipes.html"))
    }

    func testAPageWithNoRecipeBlocksOrNoPageAtAllIsNotRecipeKeepers() throws {
        XCTAssertEqual(RecipeKeeperZipReader.read(try fixture("recipekeeper-not")), .failure(.notARecipeKeeperFile))
        XCTAssertEqual(RecipeKeeperZipReader.read(try fixture("recipekeeper-no-page")), .failure(.notARecipeKeeperFile))
        XCTAssertEqual(RecipeKeeperZipReader.read(Data("Dear diary, today I made soup.".utf8)), .failure(.notARecipeKeeperFile))
        XCTAssertEqual(RecipeKeeperZipReader.read(Data()), .failure(.notARecipeKeeperFile))
    }

    func testAPaprikaFileIsNotRecipeKeepersAndTheOtherWayRound() throws {
        let paprika = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "paprika-valid", withExtension: "paprikarecipes", subdirectory: "fixtures/paprika"
        ))
        XCTAssertEqual(RecipeKeeperZipReader.read(try Data(contentsOf: paprika)), .failure(.notARecipeKeeperFile))
        XCTAssertEqual(PaprikaZipReader.read(try fixture("recipekeeper-valid")), .failure(.notAPaprikaFile))
    }

    func testATruncatedZipIsDamagedAndImportsNothing() throws {
        XCTAssertEqual(RecipeKeeperZipReader.read(try fixture("recipekeeper-truncated")), .failure(.damaged))
    }

    func testAPageThatClaims4GBOrUnpacksPastTheCapIsRefusedForTheWholeFile() throws {
        XCTAssertEqual(RecipeKeeperZipReader.read(try fixture("recipekeeper-huge-declared")), .failure(.tooLarge))
        // The bomb's header tells the truth (40 MB), so it is refused unread; a page that lied would read as damaged here.
        let bomb = try fixture("recipekeeper-bomb")
        XCTAssertLessThan(bomb.count, 100_000, "the bomb is small")
        XCTAssertEqual(RecipeKeeperZipReader.read(bomb), .failure(.tooLarge))
    }

    func testTooManyEntriesOrMoreUnpackedThanTheTotalCapIsRefused() throws {
        let file = try fixture("recipekeeper-valid")
        var limits = PaprikaLimits()
        limits.maxEntries = 3
        XCTAssertEqual(RecipeKeeperZipReader.read(file, limits: limits), .failure(.tooLarge))
        limits = PaprikaLimits()
        limits.maxTotalBytes = 600
        XCTAssertEqual(RecipeKeeperZipReader.read(file, limits: limits), .failure(.tooLarge))
        limits = PaprikaLimits()
        limits.maxEntryBytes = 600
        XCTAssertEqual(RecipeKeeperZipReader.read(file, limits: limits), .failure(.tooLarge))
    }

    func testAPictureThatIsTooBigOrOnlyClaimsToBeIsLeftOutAndTheRecipeStays() throws {
        let store = Store()
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-hostile-photos"), storePhoto: store.store))
        XCTAssertEqual(c.recipes.map(\.name), ["Toast", "Jam", "Honey", "Salt"])
        XCTAssertEqual(c.recipes.map(\.photoFile), ["stored-1.jpg", nil, nil, nil])
        // Jam's unpacks past 8 MiB, Honey's header says 4 GB; Salt names none.
        XCTAssertEqual(c.photosLeftOut, 2)
        XCTAssertEqual(store.seen.count, 1)
    }

    func testALoweredPictureCapLeavesAPictureOut() throws {
        var limits = PaprikaLimits()
        limits.maxPhotoBytes = 8
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-valid"), limits: limits, storePhoto: { _ in "kept.jpg" }))
        XCTAssertEqual(c.photosLeftOut, 5)
        XCTAssertTrue(c.recipes.allSatisfy { $0.photoFile == nil })
    }

    func testNestingAHundredThousandDeepNeitherCrashesNorLosesTheRecipe() throws {
        let c = try contents(RecipeKeeperZipReader.read(fixture("recipekeeper-deep")))
        XCTAssertEqual(c.recipes.map(\.name), ["Deep"])
    }

    func testAnEntryNameThatIsAPathDoesNothing() throws {
        let html = Data(#"<div class="recipe-details"><h2 itemprop="name">Soup</h2><div itemprop="recipeIngredients"><p>1 onion</p></div></div>"#.utf8)
        let archive = PaprikaZipTestSupport.storedZip(["../../etc/recipes.html": html])
        XCTAssertEqual(try contents(RecipeKeeperZipReader.read(archive)).recipes.map(\.name), ["Soup"])
    }
}
