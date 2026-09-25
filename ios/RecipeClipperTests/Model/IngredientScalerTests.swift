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
        // A count's bracket may be each clove's size, and a total beside it would contradict it (#63).
        XCTAssertEqual("3 cloves garlic, minced (about 1 tbsp)", scale("3 cloves garlic, minced (about 1 tbsp)", 2.0))
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
        XCTAssertEqual("2 cup plus 2 egg", scale("1 cup plus 1 egg", 2.0)) // a count scales too (#62)
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

    // --- Decimal commas (#12): "1,5" is 1.5; "1,500" could be 1500, so it is left alone ---

    func testADecimalCommaIsReadAsADecimal() {
        XCTAssertEqual("3 kg flour", IngredientScaler.scale("1,5 kg flour", factor: 2.0))
        XCTAssertEqual("3-4 kg potatoes", IngredientScaler.scale("1,5-2 kg potatoes", factor: 2.0))
        XCTAssertEqual("2,5 dl water", IngredientScaler.scale("1,25 dl water", factor: 2.0))
    }

    func testADecimalCommaLineKeepsItsComma() {
        XCTAssertEqual("2,25 kg flour", IngredientScaler.scale("1,5 kg flour", factor: 1.5))
        XCTAssertEqual("0,17 l milk", IngredientScaler.scale("0,5 l milk", factor: 1 / 3.0))
        XCTAssertEqual("3 kg (6,6 lb) potatoes", IngredientScaler.scale("2 kg (4,4 lb) potatoes", factor: 1.5))
        // A decimal point keeps the fractions it always had.
        XCTAssertEqual("2 1/4 kg flour", IngredientScaler.scale("1.5 kg flour", factor: 1.5))
    }

    func testACommaBeforeThreeDigitsIsAmbiguousAndLeftAsWritten() {
        XCTAssertEqual("1,500 g flour", IngredientScaler.scale("1,500 g flour", factor: 2.0))
        XCTAssertEqual("1,000 ml water", IngredientScaler.scale("1,000 ml water", factor: 0.5))
        XCTAssertEqual("2 cups (1,250 g) flour", IngredientScaler.scale("2 cups (1,250 g) flour", factor: 2.0))
    }

    // MARK: - Shapes found in real ingredient lines (#33)

    func testAFractionWrittenWithTheFractionSlashScalesAsAWhole() {
        // BBC Good Food writes "1⁄2" with U+2044; "1⁄2 lemon" doubled once read "2⁄2 lemon".
        XCTAssertEqual("1 lemon zested and juiced", IngredientScaler.scale("1⁄2 lemon zested and juiced", factor: 2.0))
        XCTAssertEqual("1/4 tsp olive oil", IngredientScaler.scale("1⁄2 tsp olive oil", factor: 0.5))
        XCTAssertEqual("3 cups flour", IngredientScaler.scale("1 1⁄2 cups flour", factor: 2.0))
    }

    func testAMixedNumberJoinedByAndScalesAsAWhole() {
        // "2 and 1/2 cups" doubled once read "4 and 1/2 cups".
        XCTAssertEqual("5 cups flour", IngredientScaler.scale("2 and 1/2 cups flour", factor: 2.0))
        XCTAssertEqual("3/4 cups milk", IngredientScaler.scale("1 and ½ cups milk", factor: 0.5))
        // "and" between two amounts with units is still a compound, not a mixed number.
        XCTAssertEqual("2 cups and 4 tbsp flour", IngredientScaler.scale("1 cups and 2 tbsp flour", factor: 2.0))
    }

    func testARangeAfterASlashScalesAtBothEnds() {
        // RecipeTin Eats: doubling once left "/ 8 - 10 oz" as written beside "500 - 600 g".
        XCTAssertEqual(
            "500 - 600 g / 16 - 20 oz pasta",
            IngredientScaler.scale("250 - 300 g / 8 - 10 oz pasta", factor: 2.0)
        )
        XCTAssertEqual("1 cup / 240 to 250 g flour", IngredientScaler.scale("2 cup / 480 to 500 g flour", factor: 0.5))
    }

    // MARK: - Alternatives, compound parts and totals after the name (#61, #62, #63)

    func testAnAlternativeAmountWithAUnitScalesWithTheFirst() {
        XCTAssertEqual("2 cup butter or 1 cup oil", scale("1 cup butter or 1/2 cup oil", 2.0))
        XCTAssertEqual("2 cup butter (or 1 cup oil)", scale("1 cup butter (or 1/2 cup oil)", 2.0))
        XCTAssertEqual("2 cup (240 g) sugar or 1 cup (200 g) honey", scale("1 cup (120 g) sugar or 1/2 cup (100 g) honey", 2.0))
        // "or" with no amount after it is part of the name.
        XCTAssertEqual("2 cup butter or margarine", scale("1 cup butter or margarine", 2.0))
    }

    func testAnAlternativeWithoutAUnitLeavesTheWholeLineAsWritten() {
        XCTAssertEqual("1 cup butter or 2 eggs", scale("1 cup butter or 2 eggs", 2.0))
        XCTAssertEqual("1 cup butter or 2-inch piece", scale("1 cup butter or 2-inch piece", 2.0))
    }

    func testAnOrInsideAPackageSizeIsNotAnAlternative() {
        XCTAssertEqual("2 can (14 oz or 400 g) tomatoes", scale("1 can (14 oz or 400 g) tomatoes", 2.0))
    }

    func testASecondPartAddedLaterInTheLineScalesCountedOrMeasured() {
        XCTAssertEqual("2 cup flour, plus 4 tbsp for dusting", scale("1 cup flour, plus 2 tbsp for dusting", 2.0))
        XCTAssertEqual("6 eggs + 3 yolk", scale("2 eggs + 1 yolk", 3.0))
        XCTAssertEqual("4 eggs plus 2 yolks", scale("2 eggs plus 1 yolks", 2.0))
    }

    func testAPartTakenAwayScalesToo() {
        XCTAssertEqual("1 cups minus 1 tbsp flour", scale("2 cups minus 2 tbsp flour", 0.5))
        XCTAssertEqual("4 eggs minus 2 whites", scale("2 eggs minus 1 whites", 2.0))
    }

    func testAMeasuresTotalAfterTheNameScalesWithIt() {
        XCTAssertEqual("4 cups flour (500 g)", scale("2 cups flour (250 g)", 2.0))
        XCTAssertEqual("2 lb potatoes, peeled (about 900-1000 g)", scale("1 lb potatoes, peeled (about 450-500 g)", 2.0))
        XCTAssertEqual("1 cup oats (~45 g/1 1/2 oz)", scale("2 cup oats (~90 g/3 oz)", 0.5))
    }

    func testPackageAndPerItemSizesNeverScale() {
        XCTAssertEqual("4 cans (15 oz) beans", scale("2 cans (15 oz) beans", 2.0))
        XCTAssertEqual("4 (15 oz) cans beans", scale("2 (15 oz) cans beans", 2.0))
        XCTAssertEqual("8 steaks (8 oz each)", scale("4 steaks (8 oz each)", 2.0))
        XCTAssertEqual("2 can tomatoes (400 g)", scale("1 can tomatoes (400 g)", 2.0))
    }

    func testABracketThatMayContradictTheScaledAmountLeavesTheLineAsWritten() {
        // A count's bracket may be each one's weight or the total.
        XCTAssertEqual("4 steaks (about 2 lb)", scale("4 steaks (about 2 lb)", 2.0))
        XCTAssertEqual("1 onion (150 g)", scale("1 onion (150 g)", 2.0))
        // Not only an amount: it can't be scaled whole.
        XCTAssertEqual("1 cup rice (cooked in 2 cups water)", scale("1 cup rice (cooked in 2 cups water)", 2.0))
        // No unit in the bracket: nothing to contradict.
        XCTAssertEqual("4 cups chopped onion (2 medium)", scale("2 cups chopped onion (2 medium)", 2.0))
    }
}
