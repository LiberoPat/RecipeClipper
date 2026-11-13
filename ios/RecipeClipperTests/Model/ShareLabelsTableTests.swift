import XCTest
@testable import RecipeClipper

/// The words around a shared recipe (`RecipeShareText`, #149) in each UI language, as the String
/// Catalog compiles them, are the ones `shared/tables/<language>/splitter.json` holds under
/// `shareText`, so a recipe shared from one phone is read back on another (#306, #334). A changed
/// string changes the table too. Android: `ShareLabelsTableTest`.
final class ShareLabelsTableTests: XCTestCase {

    /// The catalog's language folder for each table.
    private static let languages = ["en": "en", "es": "es", "fr": "fr", "de": "de", "it": "it", "pt": "pt-BR"]

    private func strings(_ language: String) throws -> [String: String] {
        let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), "no \(language).lproj")
        let url = try XCTUnwrap(try XCTUnwrap(Bundle(path: path)).url(forResource: "Localizable", withExtension: "strings"))
        return try XCTUnwrap(NSDictionary(contentsOf: url) as? [String: String])
    }

    func testEveryUILanguagesShareWordsAreInItsTable() throws {
        for (table, folder) in Self.languages {
            let words = try XCTUnwrap(SharedText.shareLabels(table), "\(table) has no share words in its table")
            let catalog = try strings(folder)
            func string(_ key: String) throws -> String { try XCTUnwrap(catalog[key], "\(folder) is missing \(key)") }
            let fromStrings = [
                "ingredients": try string("share_heading_ingredients"),
                "instructions": try string("share_heading_instructions"),
                "serves": String(format: try string("share_serves %lld"), locale: Locale(identifier: folder), 7),
                "makes": String(format: try string("share_makes %lld"), locale: Locale(identifier: folder), 7),
                "prep": try string("label_prep"), "cook": try string("label_cook"), "total": try string("label_total"),
            ]
            XCTAssertEqual(words.mapValues { $0.replacingOccurrences(of: "{n}", with: "7") }, fromStrings, table)
        }
    }
}
