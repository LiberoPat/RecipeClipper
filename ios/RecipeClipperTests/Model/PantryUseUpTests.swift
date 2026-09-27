import XCTest
@testable import RecipeClipper

/// Android's PantryUseUpTest (#147): what subtracts exactly, and every refusal that asks
/// instead. The corpus's `UseUp` rows pin the rest to the Kotlin.
final class PantryUseUpTests: XCTestCase {
    private var nextId: Int64 = 1

    private func item(_ name: String, _ quantity: String?, inStock: Bool = true, alwaysHave: Bool = false, language: String? = "en") -> PantryItem {
        defer { nextId += 1 }
        return PantryItem(
            id: nextId, name: name, quantity: quantity, language: language, aisle: .other, inStock: inStock,
            alwaysHave: alwaysHave, purchasedDay: nil, expiresDay: nil
        )
    }

    private func change(_ pantry: PantryItem, _ lines: String..., language: String = "en", file: StaticString = #filePath, line: UInt = #line) -> UseUpChange? {
        let rows = PantryUseUp.rows(lines, language: language, pantry: [pantry])
        XCTAssertEqual(rows.count, 1, "one row for \(pantry.name)", file: file, line: line)
        return rows.first?.change
    }

    private func after(_ pantry: PantryItem, _ lines: String..., language: String = "en") -> String? {
        let rows = PantryUseUp.rows(lines, language: language, pantry: [pantry])
        guard case .subtract(_, let after)? = rows.first?.change else {
            XCTFail("not worked out: \(String(describing: rows.first?.change))")
            return nil
        }
        return after
    }

    // MARK: Worked out

    func testAWeightFromTheSameUnitTheOwnersChicken() {
        XCTAssertEqual(change(item("chicken", "2 lb"), "1 lb chicken"), .subtract(before: "2 lb", after: "1 lb"))
    }

    func testACountSizesIncludedTheOwnersEggs() {
        XCTAssertEqual(after(item("eggs", "6"), "2 large eggs, beaten"), "4")
        XCTAssertEqual(after(item("Eggs", "6 eggs"), "2 eggs"), "4 eggs")
    }

    func testWithinOneFamilyTheResultIsExact() {
        XCTAssertEqual(after(item("chicken", "2 lb"), "8 oz chicken"), "1 1/2 lb")
        XCTAssertEqual(after(item("chicken", "2 lb"), "3 oz chicken"), "29 oz")
        XCTAssertEqual(after(item("milk", "4 cups"), "1 1/2 cups milk"), "2 1/2 cups")
        XCTAssertEqual(after(item("flour", "1 kg"), "120 g flour"), "880 g")
        XCTAssertEqual(after(item("flour", "1 kg"), "500 g flour"), "500 g")
        XCTAssertEqual(after(item("milk", "1 l"), "250 ml milk", "100 ml milk"), "650 ml")
    }

    func testTheRestOfTheQuantityAndADecimalCommaStay() {
        XCTAssertEqual(after(item("chicken", "2 lb pack"), "1 lb chicken"), "1 lb pack")
        XCTAssertEqual(after(item("flour", "2,5 kg"), "1 kg flour"), "1,5 kg")
    }

    func testVolumeToWeightThroughTheTableOrTheLinesOwnMeasure() {
        XCTAssertEqual(after(item("flour", "1 kg"), "2 cups all-purpose flour"), "760 g")
        XCTAssertEqual(after(item("flour", "1 kg"), "1 cup (125 g) flour"), "875 g")
        XCTAssertEqual(after(item("milk", "1 l"), "1 cup milk"), "760 ml")
    }

    func testAcrossWeightFamiliesItRoundsAsTheConverterDoes() {
        XCTAssertEqual(after(item("chicken", "2 lb"), "454 g chicken"), "1 lb")
        XCTAssertEqual(after(item("chicken", "1 kg"), "1 lb chicken"), "545 g")
    }

    func testAtZeroOrBelowTheItemIsUsedUp() {
        XCTAssertEqual(change(item("eggs", "2"), "3 eggs"), .subtract(before: "2", after: nil))
        XCTAssertEqual(change(item("chicken", "1 lb"), "16 oz chicken"), .subtract(before: "1 lb", after: nil))
    }

    func testSeveralLinesUsingOneItemAreOneRowAddedUp() {
        let rows = PantryUseUp.rows(["200 g flour", "2 tbsp butter", "100 g flour"], language: "en", pantry: [item("flour", "1 kg")])
        XCTAssertEqual(rows.map(\.lines), [["200 g flour", "100 g flour"]])
        XCTAssertEqual(rows.first?.change, .subtract(before: "1 kg", after: "700 g"))
    }

    func testOtherLanguagesReadWithTheirOwnWords() {
        XCTAssertEqual(after(item("Eier", "6", language: "de"), "2 große Eier", language: "de"), "4")
    }

    // MARK: Refusals: the row asks, never guesses

    func testNoQuantityOrOneThatIsntAnAmountAsks() {
        XCTAssertEqual(change(item("flour", nil), "1 cup flour"), .ask)
        XCTAssertEqual(change(item("flour", "half a bag"), "1 cup flour"), .ask)
        XCTAssertEqual(change(item("flour", "1 bag"), "1 cup flour"), .ask)
    }

    func testALineWithNoAmountOrMoreThanOneAsks() {
        XCTAssertEqual(change(item("flour", "1 kg"), "flour, for dusting"), .ask)
        XCTAssertEqual(change(item("milk", "1 l"), "1-2 cups milk"), .ask)
        XCTAssertEqual(change(item("flour", "1 kg"), "1 cup plus 2 tbsp flour"), .ask)
        XCTAssertEqual(change(item("eggs", "6"), "2 eggs plus 3 yolks"), .ask)
    }

    func testDifferentKindsOfUnitAsk() {
        XCTAssertEqual(change(item("chicken", "2 lb"), "1 chicken"), .ask)
        XCTAssertEqual(change(item("eggs", "6"), "100 g eggs"), .ask)
    }

    func testVolumeAndWeightWithNoDensityAsk() {
        XCTAssertEqual(change(item("walnuts", "500 g"), "1 cup walnuts"), .ask)
    }

    func testACountOfPartsOrPackagesAsks() {
        XCTAssertEqual(change(item("garlic", "3"), "2 cloves garlic"), .ask)
        XCTAssertEqual(change(item("tomatoes", "4"), "1 can tomatoes"), .ask)
    }

    func testAnImperialVolumeThatIsntExactAsks() {
        XCTAssertEqual(change(item("milk", "2 cups"), "100 ml milk"), .ask)
    }

    // MARK: Not listed at all

    func testADifferentIngredientIsNeverUsedUp() {
        XCTAssertTrue(PantryUseUp.rows(["1 cup flour", "2 tbsp butter"], language: "en", pantry: [item("rice flour", "1 kg"), item("butter beans", "2 cans")]).isEmpty)
    }

    func testStaplesItemsAlreadyOutUnnamedLinesAndHeadingsAreLeftOut() {
        let pantry = [item("salt", "1 kg", alwaysHave: true), item("milk", "1 l", inStock: false), item("pepper", "50 g")]
        XCTAssertTrue(PantryUseUp.rows(["1 tsp salt", "1 cup milk", "For the sauce:", "salt and pepper"], language: "en", pantry: pantry).isEmpty)
    }

    func testALanguageWithNoWordsOrADifferentLanguageListsNothing() {
        XCTAssertTrue(PantryUseUp.rows(["2 eggs"], language: "xx", pantry: [item("eggs", "6")]).isEmpty)
        XCTAssertTrue(PantryUseUp.rows(["2 eggs"], language: "en", pantry: [item("eggs", "6", language: "de")]).isEmpty)
    }
}
