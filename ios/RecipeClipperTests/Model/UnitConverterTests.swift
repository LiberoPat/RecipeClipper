import XCTest
@testable import RecipeClipper

/// Port of Android's UnitConverterTest.
final class UnitConverterTests: XCTestCase {

    private func ounces(_ line: String, liquids: Bool = false) -> String {
        UnitConverter.convert(line, system: .ounces, includeLiquids: liquids)
    }

    private func metric(_ line: String) -> String {
        UnitConverter.convert(line, system: .metric, includeLiquids: false)
    }

    // --- Volume to weight through the density table ---

    func testAsWrittenNeverChangesALine() {
        XCTAssertEqual("2 cups flour", UnitConverter.convert("2 cups flour", system: .asWritten, includeLiquids: true))
    }

    func testCupsOfDryGoodsBecomeGrams() {
        XCTAssertEqual("120 g all-purpose flour", metric("1 cup all-purpose flour"))
        XCTAssertEqual("240 g flour", metric("2 cups flour"))
        XCTAssertEqual("300 g sugar", metric("1 1/2 cups sugar"))
        XCTAssertEqual("225 g whole wheat flour", metric("2 cups whole wheat flour"))
        XCTAssertEqual("170 g semisweet chocolate chips", metric("1 cup semisweet chocolate chips"))
    }

    func testSpoonsAndSticksBecomeGrams() {
        XCTAssertEqual("7.5 g flour, sifted", metric("1 tbsp flour, sifted"))
        XCTAssertEqual("8 g baking powder", metric("2 tsp baking powder"))
        XCTAssertEqual("115 g butter", metric("1 stick butter"))
        XCTAssertEqual("1.5 g baking soda", metric("1/4 tsp baking soda"))
    }

    func testRangesConvertBothEnds() {
        XCTAssertEqual("120-240 g flour", metric("1-2 cups flour"))
    }

    func testModifiersAroundTheNameAreIgnored() {
        XCTAssertEqual("215 g packed brown sugar", metric("1 cup packed brown sugar"))
        XCTAssertEqual("225 g unsalted butter, softened", metric("1 cup unsalted butter, softened"))
        XCTAssertEqual("260 g peanut butter", metric("1 cup peanut butter"))
    }

    // --- Weight to weight is exact, and needs no table ---

    func testOuncesAndPoundsBecomeGrams() {
        XCTAssertEqual("225 g cream cheese", metric("8 oz cream cheese"))
        XCTAssertEqual("455 g ground beef", metric("1 lb ground beef"))
        XCTAssertEqual("2.27 kg potatoes", metric("5 lb potatoes"))
    }

    func testAmountsAlreadyInGramsAreLeftExactlyAsWritten() {
        XCTAssertEqual("125 g flour", metric("125 g flour"))
        XCTAssertEqual("1 kg flour", metric("1 kg flour"))
    }

    // --- Things that must NOT convert ---

    func testUnknownIngredientsStayAsWritten() {
        XCTAssertEqual("1 tsp salt", ounces("1 tsp salt"))
        XCTAssertEqual("1 cup rolled oats", ounces("1 cup rolled oats"))
        XCTAssertEqual("1 cup chopped onion", ounces("1 cup chopped onion"))
    }

    func testNamesThatMerelyEndLikeAKnownIngredientAreNotMatched() {
        XCTAssertEqual("1 cup butter beans", ounces("1 cup butter beans"))
        XCTAssertEqual("1 cup apple butter", ounces("1 cup apple butter"))
        XCTAssertEqual("1 cup rice flour", ounces("1 cup rice flour"))
        XCTAssertEqual("2 cups sweetened condensed milk", ounces("2 cups sweetened condensed milk", liquids: true))
    }

    func testLinesThatAreNotMeasuredAmountsStayAsWritten() {
        XCTAssertEqual("3 cloves garlic", ounces("3 cloves garlic"))
        XCTAssertEqual("2 large eggs", ounces("2 large eggs"))
        XCTAssertEqual("1 (14 oz) can tomatoes", ounces("1 (14 oz) can tomatoes"))
        XCTAssertEqual("Salt to taste", ounces("Salt to taste"))
        XCTAssertEqual("1 stick cinnamon", ounces("1 stick cinnamon"))
        XCTAssertEqual("1-inch piece ginger", ounces("1-inch piece ginger"))
    }

    // --- Liquids stay as written unless asked ---

    func testBareCreamIsALiquidButCreamsThatAreNotPourableAreNotMatched() {
        XCTAssertEqual("1 cup cream", ounces("1 cup cream"))
        XCTAssertEqual("8 1/2 oz cream", ounces("1 cup cream", liquids: true))
        XCTAssertEqual("240 ml cream", metric("1 cup cream"))
        XCTAssertEqual("240 ml cream", metric("8 oz cream"))
        XCTAssertEqual("1 cup ice cream", ounces("1 cup ice cream", liquids: true))
        XCTAssertEqual("1 cup whipped cream", ounces("1 cup whipped cream", liquids: true))
        XCTAssertEqual("1/2 cup coconut cream", ounces("1/2 cup coconut cream", liquids: true))
        XCTAssertEqual("230 g sour cream", metric("1 cup sour cream"))
        XCTAssertEqual("225 g cream cheese", metric("8 oz cream cheese"))
    }

    func testLiquidsAreLeftAloneByDefault() {
        XCTAssertEqual("1 cup milk", ounces("1 cup milk"))
        XCTAssertEqual("1 cup water", ounces("1 cup water"))
        XCTAssertEqual("1 cup (245 g) milk", ounces("1 cup (245 g) milk"))
    }

    func testLiquidsConvertWhenTheOptionIsOn() {
        XCTAssertEqual("8 3/4 oz milk", ounces("1 cup milk", liquids: true))
        XCTAssertEqual("8 3/4 oz of milk", ounces("1 cup of milk", liquids: true))
        XCTAssertEqual("8 1/4 oz water", ounces("1 cup water", liquids: true))
    }

    func testBareOzBesideALiquidMeansFluidOunces() {
        // As a weight, 8 oz would be 225 g.
        XCTAssertEqual("240 ml milk", metric("8 oz milk"))
    }

    // --- The site's own figure beats a calculated one ---

    func testAlternateMeasureInParenthesesIsUsedAsWritten() {
        XCTAssertEqual("120 g flour", metric("1 cup (120 g) flour"))
        XCTAssertEqual("18 g table salt", metric("1 tbsp (18 g) table salt"))
    }

    func testAlternateMeasureAfterASlashIsUsedAsWritten() {
        XCTAssertEqual("120 grams flour", metric("1 cup/120 grams flour"))
    }

    func testAlternateMeasureInTheWrongSystemIsConverted() {
        XCTAssertEqual("4 1/4 oz flour", ounces("1 cup (120 g) flour"))
    }

    // --- Ounces ---

    func testDryGoodsBecomeOunces() {
        XCTAssertEqual("4 1/4 oz flour", ounces("1 cup flour"))
        XCTAssertEqual("8 oz butter", ounces("2 sticks butter"))
    }

    func testGramsBecomeOuncesAndPounds() {
        XCTAssertEqual("8 3/4 oz sugar", ounces("250 g sugar"))
        XCTAssertEqual("1 lb 1 3/4 oz flour", ounces("500 g flour"))
    }

    func testAmountsAlreadyInOuncesOrPoundsAreLeftAlone() {
        XCTAssertEqual("8 oz chocolate", ounces("8 oz chocolate"))
        XCTAssertEqual("1 lb butter", ounces("1 lb butter"))
    }

    func testAmountsTooSmallToMeanAnythingInOuncesAreLeftAlone() {
        XCTAssertEqual("1/4 tsp baking soda", ounces("1/4 tsp baking soda"))
    }

    // --- Metric: g for solids, ml for everything poured or spooned ---

    func testMetricWeighsKnownSolids() {
        XCTAssertEqual("120 g all-purpose flour", metric("1 cup all-purpose flour"))
        XCTAssertEqual("115 g butter", metric("1 stick butter"))
        XCTAssertEqual("65 g peanut butter", metric("1/4 cup peanut butter"))
        XCTAssertEqual("225 g cream cheese", metric("8 oz cream cheese"))
        XCTAssertEqual("455 g butter", metric("1 lb butter"))
    }

    func testMetricTurnsLiquidsIntoMlWithoutNeedingTheOption() {
        XCTAssertEqual("240 ml milk", metric("1 cup milk"))
        XCTAssertEqual("360 ml milk", metric("1 1/2 cups milk"))
        XCTAssertEqual("240-480 ml milk", metric("1-2 cups milk"))
        XCTAssertEqual("30 ml olive oil", metric("2 tbsp olive oil"))
        XCTAssertEqual("15 ml honey", metric("1 tbsp honey"))
        XCTAssertEqual("240 ml milk", metric("8 oz milk"))
    }

    func testMetricTurnsSpoonsAndCupsOfUnknownThingsIntoMl() {
        XCTAssertEqual("5 ml salt", metric("1 tsp salt"))
        XCTAssertEqual("2.5 ml vanilla extract", metric("1/2 tsp vanilla extract"))
        XCTAssertEqual("240 ml chopped onion", metric("1 cup chopped onion"))
    }

    func testMetricSwitchesToLitresFromALitreUp() {
        XCTAssertEqual("960 ml water", metric("4 cups water"))
        XCTAssertEqual("1.2 L water", metric("5 cups water"))
    }

    func testMetricUsesTheSitesOwnMlFigure() {
        XCTAssertEqual("240 ml milk", metric("1 cup (240 ml) milk"))
    }

    // --- Metric: the site's own weight beats ml for anything that isn't a liquid ---

    func testMetricKeepsTheSitesGramFigureForAnIngredientNotInTheTable() {
        XCTAssertEqual("4 g salt", metric("1 tsp (4 g) salt"))
        XCTAssertEqual("4 g Diamond Crystal kosher salt", metric("1¼ tsp. (4 g) Diamond Crystal kosher salt"))
        XCTAssertEqual("120 grams chopped onion", metric("1 cup/120 grams chopped onion"))
    }

    func testMetricKeepsTheSitesGramFigureForASkipEntry() {
        XCTAssertEqual("125 g rice flour", metric("1 cup (125 g) rice flour"))
    }

    func testMetricKeepsTheSitesFigureForACompoundAmount() {
        XCTAssertEqual("140 g chopped onion", metric("1 cup plus 2 tbsp (140 g) chopped onion"))
    }

    func testMetricConvertsASitesOunceFigureToGrams() {
        XCTAssertEqual("115 g chopped walnuts", metric("1 cup (4 oz) chopped walnuts"))
        XCTAssertEqual("115 g chopped walnuts", metric("1 cup/4 oz chopped walnuts"))
    }

    func testMetricKeepsLiquidsInMlEvenBesideAGramFigure() {
        XCTAssertEqual("240 ml milk", metric("1 cup (245 g) milk"))
        XCTAssertEqual("240 ml milk", metric("1 cup (240 ml) milk"))
    }

    func testMetricLeavesARangeWithAGramFigureAsMl() {
        XCTAssertEqual("5-10 ml salt", metric("1-2 tsp (4 g) salt"))
    }

    func testMetricWithoutASiteFigureIsUnchanged() {
        XCTAssertEqual("5 ml salt", metric("1 tsp salt"))
        XCTAssertEqual("270 ml chopped onion", metric("1 cup plus 2 tbsp chopped onion"))
    }

    func testMetricLeavesMetricAmountsAndNonMeasuresAlone() {
        XCTAssertEqual("250 ml milk", metric("250 ml milk"))
        XCTAssertEqual("500 g flour", metric("500 g flour"))
        XCTAssertEqual("1 stick cinnamon", metric("1 stick cinnamon"))
        XCTAssertEqual("3 cloves garlic", metric("3 cloves garlic"))
    }

    // --- Abbreviations with a trailing period ("tsp.", "Tbsp.", "oz.", "lb.") ---

    func testAPeriodAfterTheUnitIsPartOfTheUnit() {
        XCTAssertEqual("4 g baking soda", metric("¾ tsp. (4 g) baking soda"))
        XCTAssertEqual("170 g bittersweet chocolate", metric("6 oz. (170 g) bittersweet chocolate"))
        XCTAssertEqual("4 g Diamond Crystal kosher salt", metric("1¼ tsp. (4 g) Diamond Crystal kosher salt"))
        XCTAssertEqual("455 g boneless chicken", metric("1 lb. boneless chicken"))
        XCTAssertEqual("15 g flour", metric("2 Tbsp. flour"))
        XCTAssertEqual("1 lb. butter", ounces("1 lb. butter"))
    }

    // --- Compound amounts: "1 cup plus 2 tbsp" ---

    func testACompoundAmountUsesTheSitesFigureForTheWholeAmount() {
        XCTAssertEqual("200 g all-purpose flour", metric("1½ cups plus 1 Tbsp. (200 g) all-purpose flour"))
        XCTAssertEqual("5 oz flour", ounces("1 cup plus 2 tbsp (140 g) flour"))
    }

    func testACompoundAmountConvertsTheSumOfItsParts() {
        XCTAssertEqual("135 g flour", metric("1 cup plus 2 tbsp flour"))
        XCTAssertEqual("135 g flour", metric("1 cup + 2 tbsp flour"))
        XCTAssertEqual("135 g flour", metric("1 cup and 2 tbsp flour"))
        XCTAssertEqual("4 3/4 oz flour", ounces("1 cup plus 2 tbsp flour"))
        XCTAssertEqual("510 g chicken", metric("1 lb plus 2 oz chicken"))
        XCTAssertEqual("270 ml milk", metric("1 cup plus 2 tbsp milk"))
        XCTAssertEqual("135 g flour", metric("1 cup plus 2 tbsp flour"))
        XCTAssertEqual("140 g butter", metric("1 stick plus 2 tbsp butter"))
        XCTAssertEqual("9 3/4 oz milk", ounces("1 cup plus 2 tbsp milk", liquids: true))
    }

    func testACompoundAmountThatCannotBeConvertedWholeIsLeftAsWritten() {
        XCTAssertEqual("1 cup plus 2 tbsp chopped onion", ounces("1 cup plus 2 tbsp chopped onion"))
        XCTAssertEqual("1 cup plus 2 tbsp milk", ounces("1 cup plus 2 tbsp milk"))
        XCTAssertEqual("1-2 cups plus 1 tbsp flour", ounces("1-2 cups plus 1 tbsp flour"))
    }

    // --- Doubled or nested parentheses after the name ---

    func testDoubledParenthesesDoNotHideTheIngredientName() {
        XCTAssertEqual("23 g plain flour ((all-purpose flour))", metric("3 tbsp plain flour ((all-purpose flour))"))
        XCTAssertEqual("120 g flour (sifted (optional))", metric("1 cup flour (sifted (optional))"))
        XCTAssertEqual("1 cup butter beans ((canned))", ounces("1 cup butter beans ((canned))"))
    }

    func testSingleParenthesesBehaveAsBefore() {
        XCTAssertEqual("215 g (packed) brown sugar", metric("1 cup (packed) brown sugar"))
        XCTAssertEqual("215 g brown sugar (packed)", metric("1 cup brown sugar (packed)"))
        XCTAssertEqual("120 g flour ((sifted))", metric("1 cup (120 g) flour ((sifted))"))
    }

    // --- Decimal commas (#12) ---

    func testADecimalCommaIsConvertedAsADecimalAndKeepsItsComma() {
        XCTAssertEqual("3 lb 5 oz flour", ounces("1,5 kg flour"))
        XCTAssertEqual("680 g pork shoulder", metric("1,5 lb pork shoulder"))
        XCTAssertEqual("1,13 kg potatoes", metric("2,5 lb potatoes"))
        XCTAssertEqual("7,5 ml water", metric("1,5 tsp water"))
    }

    func testAScaledLineKeepsTheSeparatorOfTheLineItWasScaledFrom() {
        // "2,5 lb" doubled is "5 lb", which no longer shows its comma.
        let scaled = IngredientScaler.scale("2,5 lb potatoes", factor: 2.0)
        XCTAssertEqual(
            "2,27 kg potatoes",
            UnitConverter.convert(scaled, system: .metric, includeLiquids: false, separatorFrom: "2,5 lb potatoes")
        )
        XCTAssertEqual("2.27 kg potatoes", UnitConverter.convert("5 lb potatoes", system: .metric, includeLiquids: false))
    }

    func testACommaBeforeThreeDigitsIsAmbiguousAndLeftAsWritten() {
        XCTAssertEqual("1,500 g flour", ounces("1,500 g flour"))
        XCTAssertEqual("1,500 lb beef", metric("1,500 lb beef"))
        XCTAssertEqual("2 cups (1,250 g) flour", ounces("2 cups (1,250 g) flour"))
    }
}
