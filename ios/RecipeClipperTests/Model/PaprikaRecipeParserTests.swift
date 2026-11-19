import XCTest
@testable import RecipeClipper

/// One Paprika recipe's JSON (#382; Android's PaprikaRecipeParserTest), with the fields libpaprika
/// documents and the ones that go missing.
final class PaprikaRecipeParserTests: XCTestCase {

    private func recipe(_ json: String, file: StaticString = #filePath, line: UInt = #line) throws -> PaprikaRecipe {
        guard case .recipe(let recipe) = PaprikaRecipeParser.parse(json) else {
            XCTFail("not a recipe: \(json)", file: file, line: line)
            throw CancellationError()
        }
        return recipe
    }

    private let full = """
        {"uid": "7C3B1A52-0F1E-4C8E-9A57-1D2E3F4A5B6C", "name": "  Brownies ", "ingredients": "For the batter:\\n1 cup butter\\r\\n\\n2 eggs\\n",
         "directions": "Melt.\\n\\nBake.", "notes": " Dad's favourite ", "servings": "12 brownies", "prep_time": "20 mins",
         "cook_time": "1 hour 30 minutes", "total_time": "Overnight", "source_url": "http://www.Example.com/b?utm_source=x&id=7#top",
         "image_url": "http://img.example.com/b.jpg", "categories": ["Dessert", "", 5, "Baking"], "photo_data": "AAAA",
         "rating": 5, "unknown": {"deep": [1, [2]]}}
        """

    func testEveryFieldIsReadAsWrittenTrimmed() throws {
        let r = try recipe(full)
        XCTAssertEqual(r.uid, "7C3B1A52-0F1E-4C8E-9A57-1D2E3F4A5B6C")
        XCTAssertEqual(r.name, "Brownies")
        XCTAssertEqual(r.ingredients, ["For the batter:", "1 cup butter", "2 eggs"])
        XCTAssertEqual(r.directions, ["Melt.", "Bake."])
        XCTAssertEqual(r.notes, "Dad's favourite")
        XCTAssertEqual(r.servings, "12 brownies")
        // Times are text here; the importer turns whole-string phrases into "1h 30m" later.
        XCTAssertEqual(r.prepTime, "20 mins")
        XCTAssertEqual(r.cookTime, "1 hour 30 minutes")
        XCTAssertEqual(r.totalTime, "Overnight")
        XCTAssertEqual(r.categories, ["Dessert", "Baking"])
        XCTAssertTrue(r.hasPhoto)
    }

    func testALinkIsCleanedAndUpgradedAndAPictureLinkIsAWebLink() throws {
        let r = try recipe(full)
        XCTAssertEqual(r.sourceUrl, "https://www.example.com/b?id=7")
        XCTAssertEqual(r.imageUrl, "https://img.example.com/b.jpg")
    }

    func testASourceThatIsNotAWebLinkIsNoLink() throws {
        for source in ["", "n/a", "Grandma", "ftp://x.example.com/r", "file:///etc/passwd", "javascript:alert(1)"] {
            let r = try recipe(#"{"name": "A", "ingredients": "1 egg", "source_url": "\#(source)", "image_url": "\#(source)"}"#)
            XCTAssertNil(r.sourceUrl, source)
            XCTAssertNil(r.imageUrl, source)
        }
    }

    func testMissingAndNullFieldsAreAbsentNeverTheTextNull() throws {
        let r = try recipe(#"""
            {"name": "Tea", "directions": "Steep.", "notes": null, "servings": null, "uid": null, "photo_data": null,
             "categories": null, "source_url": null, "prep_time": ""}
            """#)
        XCTAssertEqual(r.directions, ["Steep."])
        XCTAssertEqual(r.ingredients, [])
        XCTAssertNil(r.notes)
        XCTAssertNil(r.servings)
        XCTAssertNil(r.uid)
        XCTAssertNil(r.prepTime)
        XCTAssertNil(r.sourceUrl)
        XCTAssertEqual(r.categories, [])
        XCTAssertFalse(r.hasPhoto)
    }

    func testAPhotosListCountsAsAPictureTheFileCarries() throws {
        XCTAssertTrue(try recipe(#"{"name": "A", "ingredients": "1 egg", "photos": [{"filename": "a.jpg", "data": "AAAA"}]}"#).hasPhoto)
        XCTAssertFalse(try recipe(#"{"name": "A", "ingredients": "1 egg", "photos": []}"#).hasPhoto)
    }

    func testThePicturesTextIsCarriedUntilTheArchiveStoresIt() throws {
        XCTAssertEqual(try recipe(full).photoData, "AAAA")
        // Else the first of the photos' data (libpaprika's RecipePhoto).
        let r = try recipe(#"{"name": "A", "ingredients": "1 egg", "photos": [{"filename": "a.jpg"}, {"filename": "b.jpg", "data": "BBBB"}]}"#)
        XCTAssertEqual(r.photoData, "BBBB")
        XCTAssertNil(try recipe(#"{"name": "A", "ingredients": "1 egg"}"#).photoData)
        XCTAssertNil(try recipe(full).photoFile)
    }

    func testPaprikasFavouriteFlagIsABooleanOrANumberThatIsntZero() throws {
        func fav(_ value: String) throws -> Bool {
            try recipe(#"{"name": "A", "ingredients": "1 egg", "on_favorites": \#(value)}"#).favorite
        }
        XCTAssertTrue(try fav("1"))
        XCTAssertTrue(try fav("true"))
        XCTAssertFalse(try fav("0"))
        XCTAssertFalse(try fav("false"))
        XCTAssertFalse(try fav("null"))
        XCTAssertFalse(try fav("\"yes\""))
        XCTAssertFalse(try recipe(#"{"name": "A", "ingredients": "1 egg"}"#).favorite)
    }

    func testANumberWhereTextGoesIsKeptAsItsText() throws {
        XCTAssertEqual(try recipe(#"{"name": "A", "ingredients": "1 egg", "servings": 4}"#).servings, "4")
        XCTAssertNil(try recipe(#"{"name": "A", "ingredients": "1 egg", "servings": true}"#).servings)
    }

    func testARecipeNeedsANamePlusIngredientsOrSteps() {
        XCTAssertEqual(PaprikaRecipeParser.parse(#"{"name": "Only a title"}"#), .notARecipe)
        XCTAssertEqual(PaprikaRecipeParser.parse(#"{"ingredients": "1 egg", "directions": "Boil."}"#), .notARecipe)
        XCTAssertEqual(PaprikaRecipeParser.parse(#"{"name": "  ", "ingredients": "1 egg"}"#), .notARecipe)
        XCTAssertEqual(PaprikaRecipeParser.parse(#"{"name": "A", "ingredients": " \n \n", "directions": ""}"#), .notARecipe)
        XCTAssertEqual(PaprikaRecipeParser.parse("{}"), .notARecipe)
    }

    func testTextThatIsNotAJsonObjectIsUnreadable() {
        for text in ["", "not json", "[]", #"[{"name": "A", "ingredients": "1 egg"}]"#, #""text""#, "42", #"{"name": "#] {
            XCTAssertEqual(PaprikaRecipeParser.parse(text), .unreadable, text)
        }
    }

    func testAByteOrderMarkIsIgnored() throws {
        XCTAssertEqual(try recipe("\u{FEFF}{\"name\": \"A\", \"ingredients\": \"1 egg\"}").name, "A")
    }

    func testNestingThousandsDeepIsUnreadableNotACrash() {
        let deep = String(repeating: "[", count: 200_000)
        XCTAssertEqual(PaprikaRecipeParser.parse("{\"name\": \"A\", \"x\": \(deep)"), .unreadable)
        XCTAssertEqual(PaprikaRecipeParser.parse(deep), .unreadable)
    }
}
