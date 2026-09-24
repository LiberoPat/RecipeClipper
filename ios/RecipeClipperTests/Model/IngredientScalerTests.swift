import XCTest
@testable import RecipeClipper

/// Port of Android's IngredientScalerTest (scaling, servings and yield parsing).
final class IngredientScalerTests: XCTestCase {

    private func scale(_ line: String, _ factor: Double) -> String { IngredientScaler.scale(line, factor: factor) }

    func testWholeNumbers() {
        XCTAssertEqual("4 cups all-purpose flour", scale("2 cups all-purpose flour", 2.0))
        XCTAssertEqual("2 cups flour", scale("4 cups flour", 0.5))
    }

    func testFactorOfOneLeavesTheLineAlone() {
        XCTAssertEqual("1 1/2 cups sugar", scale("1 1/2 cups sugar", 1.0))
    }

    func testMixedNumbersAndFractions() {
        XCTAssertEqual("3 cups sugar", scale("1 1/2 cups sugar", 2.0))
        XCTAssertEqual("1 1/2 cup milk", scale("1/2 cup milk", 3.0))
        XCTAssertEqual("2/3 cup oil", scale("1/3 cup oil", 2.0))
        XCTAssertEqual("1/3 cup flour", scale("1 cup flour", 1 / 3.0))
    }

    func testUnicodeFractions() {
        XCTAssertEqual("2 tsp salt", scale("½ tsp salt", 4.0))
        XCTAssertEqual("3 tsp salt", scale("1½ tsp salt", 2.0))
        XCTAssertEqual("3 tsp salt", scale("1 ½ tsp salt", 2.0))
    }

    func testDecimals() {
        XCTAssertEqual("1 cup milk", scale("0.5 cup milk", 2.0))
        XCTAssertEqual("1 1/2 eggs", scale("3 eggs", 0.5))
    }

    func testRangesScaleBothEndsAndKeepTheSeparator() {
        XCTAssertEqual("2-4 tbsp oil", scale("1-2 tbsp oil", 2.0))
        XCTAssertEqual("2–4 tbsp oil", scale("1–2 tbsp oil", 2.0))
        XCTAssertEqual("2 to 4 tbsp oil", scale("1 to 2 tbsp oil", 2.0))
    }

    func testOnlyTheLeadingQuantityIsScaled() {
        XCTAssertEqual("2 (14 oz) can tomatoes", scale("1 (14 oz) can tomatoes", 2.0))
        XCTAssertEqual("6 cloves garlic, minced (about 1 tbsp)", scale("3 cloves garlic, minced (about 1 tbsp)", 2.0))
    }

    func testAlternateMeasuresAreScaledWithTheLeadingAmount() {
        XCTAssertEqual("2 cup (240 g) flour", scale("1 cup (120 g) flour", 2.0))
        XCTAssertEqual("2 cup/240 grams flour", scale("1 cup/120 grams flour", 2.0))
        XCTAssertEqual("1 1/2 cup (360 ml) milk", scale("1 cup (240 ml) milk", 1.5))
        XCTAssertEqual("1 cup (2 stick, 226 g) butter", scale("1/2 cup (1 stick, 113 g) butter", 2.0))
    }

    func testAPeriodAfterTheUnitDoesNotStopTheAlternateMeasureScaling() {
        XCTAssertEqual("2 tsp. (8 g) x", scale("1 tsp. (4 g) x", 2.0))
        XCTAssertEqual("2 lb. (910 g) chicken", scale("1 lb. (455 g) chicken", 2.0))
    }

    func testCompoundAmountsScaleBothPartsAndTheAlternateMeasure() {
        XCTAssertEqual("2 cup plus 4 tbsp (280 g) flour", scale("1 cup plus 2 tbsp (140 g) flour", 2.0))
        XCTAssertEqual(
            "3/4 cups plus 1/2 Tbsp. (100 g) all-purpose flour",
            scale("1½ cups plus 1 Tbsp. (200 g) all-purpose flour", 0.5)
        )
        XCTAssertEqual("3 cup + 6 tbsp sugar", scale("1 cup + 2 tbsp sugar", 3.0))
        XCTAssertEqual("2 cup plus 1 egg", scale("1 cup plus 1 egg", 2.0))
    }

    func testPackageSizesAndNonMeasuresInParenthesesAreNotScaled() {
        XCTAssertEqual("2 can (14 oz) tomatoes", scale("1 can (14 oz) tomatoes", 2.0))
        XCTAssertEqual("2 cup (packed) brown sugar", scale("1 cup (packed) brown sugar", 2.0))
    }

    func testLinesWithoutALeadingQuantityAreUnchanged() {
        XCTAssertEqual("Salt and pepper to taste", scale("Salt and pepper to taste", 2.0))
        XCTAssertEqual("A pinch of nutmeg", scale("A pinch of nutmeg", 2.0))
        XCTAssertEqual("", scale("", 2.0))
    }

    func testSizesAndPercentagesAreNotAmounts() {
        XCTAssertEqual("1-inch piece ginger", scale("1-inch piece ginger", 2.0))
        XCTAssertEqual("2 inch piece ginger", scale("2 inch piece ginger", 2.0))
        XCTAssertEqual("2% milk", scale("2% milk", 2.0))
    }

    func testLeadingWhitespaceIsPreserved() {
        XCTAssertEqual("  4 cups flour", scale("  2 cups flour", 2.0))
    }

    func testZeroDenominatorIsLeftAlone() {
        XCTAssertEqual("1/0 cup flour", scale("1/0 cup flour", 2.0))
    }

    func testFormatRoundsToCookingFractions() {
        XCTAssertEqual("1/4", IngredientScaler.format(0.26))
        XCTAssertEqual("1 1/3", IngredientScaler.format(1.33))
        XCTAssertEqual("2", IngredientScaler.format(1.99))
        XCTAssertEqual("3 3/4", IngredientScaler.format(3.75))
        XCTAssertEqual("0.06", IngredientScaler.format(0.0625))
    }

    func testYieldPrefersTheEntryThatStatesARange() {
        XCTAssertEqual("4 to 6 servings", Servings.pickYield(["4", "4 to 6 servings"]))
        XCTAssertEqual("4-6", Servings.pickYield(["4", "4-6", "4 servings"]))
        XCTAssertEqual("4–6 servings", Servings.pickYield(["4", "4–6 servings"]))
    }

    func testYieldWithoutARangeKeepsTheFirstEntry() {
        XCTAssertEqual("4", Servings.pickYield(["4", "4 servings"]))
        XCTAssertNil(Servings.pickYield([]))
    }

    func testABareNumberYieldReportsItsCount() {
        XCTAssertEqual(6, Servings.bareCount("6"))
        XCTAssertEqual(6, Servings.bareCount(" 6 "))
        XCTAssertEqual(1, Servings.bareCount("1"))
    }

    func testAYieldThatAlreadySaysWhatItIsIsNotBare() {
        XCTAssertNil(Servings.bareCount("4 to 6 servings"))
        XCTAssertNil(Servings.bareCount("4-6"))
        XCTAssertNil(Servings.bareCount("24 cookies"))
        XCTAssertNil(Servings.bareCount(""))
    }

    func testYieldKindReadsServesOrMakesFromTheYieldText() {
        let cases: [(String, YieldKind)] = [
            ("6", .serves),
            ("4 servings", .serves),
            ("Serves 4-6", .serves),
            ("4 to 6 servings", .serves),
            ("Feeds 8", .serves),
            ("8 people", .serves),
            ("", .serves),
            ("a crowd", .serves),
            ("4 to 6", .serves),
            ("Makes 16", .makes),
            ("16 cookies", .makes),
            ("1 loaf", .makes),
            ("12 muffins", .makes),
            ("2 dozen", .makes),
            ("1 (9-inch) pie", .makes),
            ("1 9-inch pie", .makes),
            ("Yield: 24 cookies", .makes),
            ("about 24 cookies", .makes),
            ("Makes 4 servings", .serves),
        ]
        for (yield, expected) in cases {
            XCTAssertEqual(expected, Servings.kind(yield), "kind(\"\(yield)\")")
        }
        XCTAssertEqual(.serves, Servings.kind(nil))
    }

    func testServingsParsing() {
        XCTAssertEqual(4, Servings.parse("4 servings"))
        XCTAssertEqual(4, Servings.parse("Serves 4-6"))
        XCTAssertEqual(24, Servings.parse("Makes 24 cookies"))
        XCTAssertNil(Servings.parse("a crowd"))
        XCTAssertNil(Servings.parse(nil))
        XCTAssertNil(Servings.parse("0 servings"))
        XCTAssertNil(Servings.parse("2024"))
    }

    // --- Swift-only: the BigDecimal HALF_UP stand-in, since every decimal goes through it ---

    func testPlainDecimalMatchesBigDecimalHalfUpOnTheExactBinaryValue() {
        XCTAssertEqual("0.13", plainDecimal(0.125, scale: 2))   // exact tie rounds up
        XCTAssertEqual("2.67", plainDecimal(2.675, scale: 2))   // really 2.67499999...
        XCTAssertEqual("240", plainDecimal(240.0, scale: 0))
        XCTAssertEqual("7.5", plainDecimal(7.5, scale: 1))
        XCTAssertEqual("100", plainDecimal(99.996, scale: 2))
        XCTAssertEqual("0", plainDecimal(0.001, scale: 2))
        XCTAssertEqual("-1.5", plainDecimal(-1.5, scale: 1))
    }
}
