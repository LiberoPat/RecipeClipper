import XCTest
@testable import RecipeClipper

/// The String Catalog, as compiled into the app: every language has every key, plurals have
/// their forms, and the placeholders survive translation (#13).
final class LocalizationTests: XCTestCase {
    private static let languages = ["es", "fr", "de", "it", "pt-BR"]

    private func bundle(_ language: String) throws -> Bundle {
        let path = try XCTUnwrap(
            Bundle.main.path(forResource: language, ofType: "lproj"), "no \(language).lproj in the app")
        return try XCTUnwrap(Bundle(path: path))
    }

    private func table(_ language: String) throws -> [String: String] {
        let url = try XCTUnwrap(try bundle(language).url(forResource: "Localizable", withExtension: "strings"))
        return try XCTUnwrap(NSDictionary(contentsOf: url) as? [String: String])
    }

    private func plurals(_ language: String) throws -> [String: [String: Any]] {
        let url = try XCTUnwrap(try bundle(language).url(forResource: "Localizable", withExtension: "stringsdict"))
        return try XCTUnwrap(NSDictionary(contentsOf: url) as? [String: [String: Any]])
    }

    private func specifiers(_ s: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "%(\\d\\$)?(@|lld|d)")
        return regex.matches(in: s, range: NSRange(s.startIndex..., in: s))
            .map { String(s[Range($0.range, in: s)!]) }
            .sorted()
    }

    func testEveryLanguageHasEveryString() throws {
        let english = try table("en")
        XCTAssertGreaterThan(english.count, 50)
        for language in Self.languages {
            let translated = try table(language)
            for (key, value) in english {
                let t = try XCTUnwrap(translated[key], "\(language) is missing \(key)")
                XCTAssertFalse(t.isEmpty, "\(language): \(key) is empty")
                XCTAssertEqual(specifiers(t), specifiers(value), "\(language): \(key) placeholders")
            }
        }
    }

    func testEveryLanguageHasEveryPluralWithOneAndOther() throws {
        let english = try plurals("en")
        XCTAssertFalse(english.isEmpty)
        for language in Self.languages {
            let translated = try plurals(language)
            for key in english.keys {
                let entry = try XCTUnwrap(translated[key], "\(language) is missing plural \(key)")
                let rule = try XCTUnwrap(
                    entry.values.compactMap { $0 as? [String: Any] }.first, "\(language): \(key) has no rule")
                XCTAssertNotNil(rule["one"], "\(language): \(key) has no one form")
                XCTAssertNotNil(rule["other"], "\(language): \(key) has no other form")
            }
        }
    }

    func testFrenchCountsZeroAsSingular() throws {
        let french = try bundle("fr")
        let format = french.localizedString(forKey: "servings %lld", value: nil, table: nil)
        let zero = String(format: format, locale: Locale(identifier: "fr"), 0)
        let one = String(format: format, locale: Locale(identifier: "fr"), 1)
        let two = String(format: format, locale: Locale(identifier: "fr"), 2)
        XCTAssertEqual(zero.replacingOccurrences(of: "0", with: "1"), one)
        XCTAssertNotEqual(two.replacingOccurrences(of: "2", with: "1"), one)
    }
}
