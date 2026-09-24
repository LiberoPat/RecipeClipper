import XCTest
@testable import RecipeClipper

/// Port of Android's UnitConverterTest.
final class UnitConverterTests: XCTestCase {

    private func grams(_ line: String, liquids: Bool = false) -> String {
        UnitConverter.convert(line, system: .grams, includeLiquids: liquids)
    }

    private func ounces(_ line: String, liquids: Bool = false) -> String {
        UnitConverter.convert(line, system: .ounces, includeLiquids: liquids)
    }

    private func metric(_ line: String) -> String {
        UnitConverter.convert(line, system: .metric, includeLiquids: false)
    }

    // --- Grams: volume to weight through the density table ---

    func testAsWrittenNeverChangesALine() {
        XCTAssertEqual("2 cups flour", UnitConverter.convert("2 cups flour", system: .asWritten, includeLiquids: true))
    }

    func testCupsOfDryGoodsBecomeGrams() {
        XCTAssertEqual("120 g all-purpose flour", grams("1 cup all-purpose flour"))
        XCTAssertEqual("240 g flour", grams("2 cups flour"))
        XCTAssertEqual("300 g sugar", grams("1 1/2 cups sugar"))
        XCTAssertEqual("225 g whole wheat flour", grams("2 cups whole wheat flour"))
        XCTAssertEqual("170 g semisweet chocolate chips", grams("1 cup semisweet chocolate chips"))
    }

    func testSpoonsAndSticksBecomeGrams() {
        XCTAssertEqual("7.5 g flour, sifted", grams("1 tbsp flour, sifted"))
        XCTAssertEqual("8 g baking powder", grams("2 tsp baking powder"))
        XCTAssertEqual("115 g butter", grams("1 stick butter"))
        XCTAssertEqual("1.5 g baking soda", grams("1/4 tsp baking soda"))
    }

    func testRangesConvertBothEnds() {
        XCTAssertEqual("120-240 g flour", grams("1-2 cups flour"))
    }

    func testModifiersAroundTheNameAreIgnored() {
        XCTAssertEqual("215 g packed brown sugar", grams("1 cup packed brown sugar"))
        XCTAssertEqual("225 g unsalted butter, softened", grams("1 cup unsalted butter, softened"))
        XCTAssertEqual("260 g peanut butter", grams("1 cup peanut butter"))
    }

    // --- Grams: weight to weight is exact, and needs no table ---

    func testOuncesAndPoundsBecomeGrams() {
        XCTAssertEqual("225 g cream cheese", grams("8 oz cream cheese"))
        XCTAssertEqual("455 g ground beef", grams("1 lb ground beef"))
        XCTAssertEqual("2.27 kg potatoes", grams("5 lb potatoes"))
    }

    func testAmountsAlreadyInGramsAreLeftExactlyAsWritten() {
        XCTAssertEqual("125 g flour", grams("125 g flour"))
        XCTAssertEqual("1 kg flour", grams("1 kg flour"))
    }

    // --- Things that must NOT convert ---

    func testUnknownIngredientsStayAsWritten() {
        XCTAssertEqual("1 tsp salt", grams("1 tsp salt"))
        XCTAssertEqual("1 cup rolled oats", grams("1 cup rolled oats"))
        XCTAssertEqual("1 cup chopped onion", grams("1 cup chopped onion"))
    }

    func testNamesThatMerelyEndLikeAKnownIngredientAreNotMatched() {
        XCTAssertEqual("1 cup butter beans", grams("1 cup butter beans"))
        XCTAssertEqual("1 cup apple butter", grams("1 cup apple butter"))
        XCTAssertEqual("1 cup rice flour", grams("1 cup rice flour"))
        XCTAssertEqual("2 cups sweetened condensed milk", grams("2 cups sweetened condensed milk", liquids: true))
    }

    func testLinesThatAreNotMeasuredAmountsStayAsWritten() {
        XCTAssertEqual("3 cloves garlic", grams("3 cloves garlic"))
        XCTAssertEqual("2 large eggs", grams("2 large eggs"))
        XCTAssertEqual("1 (14 oz) can tomatoes", grams("1 (14 oz) can tomatoes"))
        XCTAssertEqual("Salt to taste", grams("Salt to taste"))
        XCTAssertEqual("1 stick cinnamon", grams("1 stick cinnamon"))
        XCTAssertEqual("1-inch piece ginger", grams("1-inch piece ginger"))
    }

    // --- Liquids stay as written unless asked ---

    func testBareCreamIsALiquidButCreamsThatAreNotPourableAreNotMatched() {
        XCTAssertEqual("1 cup cream", grams("1 cup cream"))
        XCTAssertEqual("240 g cream", grams("1 cup cream", liquids: true))
        XCTAssertEqual("240 ml cream", metric("1 cup cream"))
        XCTAssertEqual("240 ml cream", metric("8 oz cream"))
        XCTAssertEqual("1 cup ice cream", grams("1 cup ice cream", liquids: true))
        XCTAssertEqual("1 cup whipped cream", grams("1 cup whipped cream", liquids: true))
        XCTAssertEqual("1/2 cup coconut cream", grams("1/2 cup coconut cream", liquids: true))
        XCTAssertEqual("230 g sour cream", grams("1 cup sour cream"))
        XCTAssertEqual("225 g cream cheese", grams("8 oz cream cheese"))
    }

    func testLiquidsAreLeftAloneByDefault() {
        XCTAssertEqual("1 cup milk", grams("1 cup milk"))
        XCTAssertEqual("8 oz milk", grams("8 oz milk"))
        XCTAssertEqual("1 cup water", ounces("1 cup water"))
        XCTAssertEqual("1 cup (245 g) milk", grams("1 cup (245 g) milk"))
    }

    func testLiquidsConvertWhenTheOptionIsOn() {
        XCTAssertEqual("245 g milk", grams("1 cup milk", liquids: true))
        XCTAssertEqual("245 g of milk", grams("1 cup of milk", liquids: true))
        XCTAssertEqual("8 1/4 oz water", ounces("1 cup water", liquids: true))
    }

    func testBareOzBesideALiquidMeansFluidOunces() {
        XCTAssertEqual("245 g milk", grams("8 oz milk", liquids: true))
    }

    // --- The site's own figure beats a calculated one ---

    func testAlternateMeasureInParenthesesIsUsedAsWritten() {
        XCTAssertEqual("120 g flour", grams("1 cup (120 g) flour"))
        XCTAssertEqual("18 g table salt", grams("1 tbsp (18 g) table salt"))
    }

    func testAlternateMeasureAfterASlashIsUsedAsWritten() {
        XCTAssertEqual("120 grams flour", grams("1 cup/120 grams flour"))
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
        XCTAssertEqual("4 g baking soda", grams("¾ tsp. (4 g) baking soda"))
        XCTAssertEqual("170 g bittersweet chocolate", grams("6 oz. (170 g) bittersweet chocolate"))
        XCTAssertEqual("4 g Diamond Crystal kosher salt", metric("1¼ tsp. (4 g) Diamond Crystal kosher salt"))
        XCTAssertEqual("455 g boneless chicken", grams("1 lb. boneless chicken"))
        XCTAssertEqual("15 g flour", grams("2 Tbsp. flour"))
        XCTAssertEqual("1 lb. butter", ounces("1 lb. butter"))
    }

    // --- Compound amounts: "1 cup plus 2 tbsp" ---

    func testACompoundAmountUsesTheSitesFigureForTheWholeAmount() {
        XCTAssertEqual("200 g all-purpose flour", grams("1½ cups plus 1 Tbsp. (200 g) all-purpose flour"))
        XCTAssertEqual("5 oz flour", ounces("1 cup plus 2 tbsp (140 g) flour"))
    }

    func testACompoundAmountConvertsTheSumOfItsParts() {
        XCTAssertEqual("135 g flour", grams("1 cup plus 2 tbsp flour"))
        XCTAssertEqual("135 g flour", grams("1 cup + 2 tbsp flour"))
        XCTAssertEqual("135 g flour", grams("1 cup and 2 tbsp flour"))
        XCTAssertEqual("4 3/4 oz flour", ounces("1 cup plus 2 tbsp flour"))
        XCTAssertEqual("510 g chicken", grams("1 lb plus 2 oz chicken"))
        XCTAssertEqual("270 ml milk", metric("1 cup plus 2 tbsp milk"))
        XCTAssertEqual("135 g flour", metric("1 cup plus 2 tbsp flour"))
        XCTAssertEqual("140 g butter", metric("1 stick plus 2 tbsp butter"))
        XCTAssertEqual("275 g milk", grams("1 cup plus 2 tbsp milk", liquids: true))
    }

    func testACompoundAmountThatCannotBeConvertedWholeIsLeftAsWritten() {
        XCTAssertEqual("1 cup plus 2 tbsp chopped onion", grams("1 cup plus 2 tbsp chopped onion"))
        XCTAssertEqual("1 cup plus 2 tbsp milk", grams("1 cup plus 2 tbsp milk"))
        XCTAssertEqual("1-2 cups plus 1 tbsp flour", grams("1-2 cups plus 1 tbsp flour"))
    }

    // --- Doubled or nested parentheses after the name ---

    func testDoubledParenthesesDoNotHideTheIngredientName() {
        XCTAssertEqual("23 g plain flour ((all-purpose flour))", grams("3 tbsp plain flour ((all-purpose flour))"))
        XCTAssertEqual("120 g flour (sifted (optional))", grams("1 cup flour (sifted (optional))"))
        XCTAssertEqual("1 cup butter beans ((canned))", grams("1 cup butter beans ((canned))"))
    }

    func testSingleParenthesesBehaveAsBefore() {
        XCTAssertEqual("215 g (packed) brown sugar", grams("1 cup (packed) brown sugar"))
        XCTAssertEqual("215 g brown sugar (packed)", grams("1 cup brown sugar (packed)"))
        XCTAssertEqual("120 g flour ((sifted))", grams("1 cup (120 g) flour ((sifted))"))
    }
}
