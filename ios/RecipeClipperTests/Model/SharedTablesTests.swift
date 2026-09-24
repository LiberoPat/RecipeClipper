import XCTest
@testable import RecipeClipper

/// Every shared table (#9, #14) loads from the app bundle, as the app loads it, and is well formed.
final class SharedTablesTests: XCTestCase {

    private let languageTables = [
        "densities", "units", "timers", "temperature", "yield", "ranges", "sections",
        "amounts", "durations", "language",
    ]

    func testEveryBundledLanguageIsDetectedAndEveryShippedOneHasEveryTable() throws {
        let root = try XCTUnwrap(Bundle.main.url(forResource: "tables", withExtension: nil))
        let languages = try FileManager.default.contentsOfDirectory(atPath: root.path)
            .filter { !$0.hasSuffix(".json") }
        XCTAssertEqual(Set(languages), Set(LanguageWords.detected))
        XCTAssertTrue(Set(LanguageWords.shipped).isSubset(of: Set(LanguageWords.detected)))
        for language in languages {
            let onDisk = try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent(language).path)
                .map { ($0 as NSString).deletingPathExtension }
            let expected = LanguageWords.shipped.contains(language) ? Set(languageTables) : ["language"]
            XCTAssertEqual(Set(onDisk), expected, language)
            XCTAssertEqual(SharedTables.load("language", language)["language"] as? String, language)
        }
    }

    func testEveryTableLoadsWithItsSchemaVersion() {
        for language in LanguageWords.shipped {
            for name in languageTables {
                let table = SharedTables.load(name, language)
                XCTAssertEqual(table["schemaVersion"] as? Int, 1, "\(language)/\(name)")
                XCTAssertEqual(table["language"] as? String, language, "\(language)/\(name)")
            }
        }
        XCTAssertEqual(SharedTables.read("url")["schemaVersion"] as? Int, 1)
    }

    func testEveryUnitNameIsAMeasureUnit() {
        for language in LanguageWords.shipped {
            let names = SharedTables.objects(SharedTables.load("units", language), "names").compactMap { $0["unit"] as? String }
            XCTAssertTrue(Set(names).isSubset(of: Set(MeasureUnit.byTableName.keys)), language)
        }
        let english = SharedTables.objects(SharedTables.load("units", "en"), "names").compactMap { $0["unit"] as? String }
        XCTAssertEqual(Set(english), Set(MeasureUnit.byTableName.keys))
        XCTAssertEqual(Set(MeasureUnit.byTableName.values), Set(MeasureUnit.allCases))
    }

    func testEveryTimerTableLabelsHoursMinutesAndSeconds() {
        for language in LanguageWords.shipped {
            let units = SharedTables.objects(SharedTables.load("timers", language), "units")
            XCTAssertEqual(Set(units.compactMap { $0["seconds"] as? Int }), [3600, 60, 1], language)
            for unit in units { XCTAssertFalse((unit["label"] as? String ?? "").isEmpty, language) }
        }
    }

    func testTheCodeReadsTheTables() {
        XCTAssertNotNil(IngredientDensities.find("1 cup flour"))
        XCTAssertEqual(MeasureUnit.fromText("Tbsp."), .tbsp)
        XCTAssertEqual(StepTimers.parse("Bake 1 hour and 30 minutes"), 90 * 60)
        XCTAssertEqual(
            TemperatureConverter.convert("Preheat to 350 degrees Fahrenheit", unit: .celsius), "Preheat to 180°C"
        )
        XCTAssertEqual(Servings.kind("Makes 12"), .makes)
        XCTAssertEqual(UrlCleaner.clean("https://a.com/r?id=1&utm_source=x&fbclid=y"), "https://a.com/r?id=1")
        XCTAssertNotNil(JRegex(UnitPatterns.of().captured).find("2 cups"))
    }
}
