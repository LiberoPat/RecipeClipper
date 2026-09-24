import XCTest
@testable import RecipeClipper

/// Every shared table (#9) loads from the app bundle, as the app loads it, and is well formed.
final class SharedTablesTests: XCTestCase {

    private let languageTables = ["densities", "units", "timers", "temperature", "yield", "ranges", "sections"]

    func testEveryBundledTableIsCoveredHere() throws {
        let dir = try XCTUnwrap(Bundle.main.url(forResource: "tables/\(SharedTables.language)", withExtension: nil))
        let onDisk = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .map { ($0 as NSString).deletingPathExtension }
        XCTAssertEqual(Set(onDisk), Set(languageTables))
    }

    func testEveryTableLoadsWithItsSchemaVersion() {
        for name in languageTables {
            let table = SharedTables.load(name)
            XCTAssertEqual(table["schemaVersion"] as? Int, 1, name)
            XCTAssertEqual(table["language"] as? String, SharedTables.language, name)
        }
        XCTAssertEqual(SharedTables.read("url")["schemaVersion"] as? Int, 1)
    }

    func testEveryUnitNameIsAMeasureUnit() {
        let names = SharedTables.objects(SharedTables.load("units"), "names").compactMap { $0["unit"] as? String }
        XCTAssertEqual(Set(names), Set(MeasureUnit.byTableName.keys))
        XCTAssertEqual(Set(MeasureUnit.byTableName.values), Set(MeasureUnit.allCases))
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
    }
}
