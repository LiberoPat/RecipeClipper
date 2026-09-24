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
    }

    func testANewerFormatVersionIsRefusedBeforeAnythingElseIsRead() throws {
        XCTAssertEqual(error(try backupFixture("backup-v2-newer")), .newerVersion(found: 2))
    }

    func testEncodeThenDecodeRoundTripsEveryField() throws {
        let original = try decodeOrFail(backupFixture("backup-v1"))
        XCTAssertEqual(try decodeOrFail(BackupJson.encode(original)), original)
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
