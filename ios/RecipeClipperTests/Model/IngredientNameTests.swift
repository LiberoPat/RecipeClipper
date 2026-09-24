import XCTest
@testable import RecipeClipper

/// Mirrors the Kotlin IngredientNameTest.
final class IngredientNameTests: XCTestCase {

    // drops the amount, unit and preparation
    func testDropsTheAmountUnitAndPreparation() {
        XCTAssertEqual(IngredientName.of("1 cup all-purpose flour"), "all purpose flour")
        XCTAssertEqual(IngredientName.of("3 Tbsp. unsalted butter, melted"), "unsalted butter")
        XCTAssertEqual(IngredientName.of("2 large eggs, beaten"), "eggs")
        XCTAssertEqual(IngredientName.of("3/4 cup packed light brown sugar"), "light brown sugar")
        XCTAssertEqual(IngredientName.of("2-3 tablespoons fresh lemon juice"), "fresh lemon juice")
        XCTAssertEqual(IngredientName.of("2 medium onions, finely chopped"), "onions")
    }

    // drops alternate measures, package sizes and compound amounts
    func testDropsAlternateMeasuresPackageSizesAndCompoundAmounts() {
        XCTAssertEqual(IngredientName.of("1 1/2 cups (190 g) all-purpose flour"), "all purpose flour")
        XCTAssertEqual(IngredientName.of("1 cup/120 grams bread flour"), "bread flour")
        XCTAssertEqual(IngredientName.of("1 cup plus 2 tbsp (140 g) flour"), "flour")
        XCTAssertEqual(IngredientName.of("1 (14 oz) can diced tomatoes"), "diced tomatoes")
        XCTAssertEqual(IngredientName.of("1 can (14 oz) coconut milk"), "coconut milk")
        XCTAssertEqual(IngredientName.of("1 (8-ounce) package cream cheese, softened"), "cream cheese")
    }

    // drops sizes and containers, and the trailing clauses
    func testDropsSizesAndContainersAndTheTrailingClauses() {
        XCTAssertEqual(IngredientName.of("3 cloves garlic, minced"), "garlic")
        XCTAssertEqual(IngredientName.of("1 pinch of salt"), "salt")
        XCTAssertEqual(IngredientName.of("a pinch of nutmeg"), "nutmeg")
        XCTAssertEqual(IngredientName.of("1 heaping cup flour"), "flour")
        XCTAssertEqual(IngredientName.of("1 quart milk"), "milk")
        XCTAssertEqual(IngredientName.of("4 boneless, skinless chicken breasts"), "chicken breasts")
        XCTAssertEqual(IngredientName.of("1-inch piece fresh ginger, peeled and grated"), "fresh ginger")
        XCTAssertEqual(IngredientName.of("1 tablespoon olive oil, plus more for drizzling"), "olive oil")
        XCTAssertEqual(IngredientName.of("Kosher salt, to taste"), "kosher salt")
        XCTAssertEqual(IngredientName.of("Fresh basil leaves, for serving"), "fresh basil leaves")
        XCTAssertEqual(IngredientName.of("Vegetable oil, for frying"), "vegetable oil")
    }

    // a conjunction inside a table alias is part of the name
    func testAConjunctionInsideATableAliasIsPartOfTheName() {
        XCTAssertEqual(IngredientName.of("1 cup half-and-half"), "half and half")
    }

    // what isn't one ingredient has no name
    func testWhatIsntOneIngredientHasNoName() {
        XCTAssertNil(IngredientName.of(""))
        XCTAssertNil(IngredientName.of("   "))
        XCTAssertNil(IngredientName.of("For the frosting:"))
        XCTAssertNil(IngredientName.of("Salt and pepper, to taste"))
        XCTAssertNil(IngredientName.of("1/2 cup butter or margarine"))
        XCTAssertNil(IngredientName.of("Juice of 1 lemon"))
    }

    // matches by the end of the name, as the density table does
    func testMatchesByTheEndOfTheNameAsTheDensityTableDoes() {
        XCTAssertTrue(IngredientName.matches("unsalted butter", "butter"))
        XCTAssertTrue(IngredientName.matches("Butter", "unsalted butter"))
        XCTAssertTrue(IngredientName.matches("flour", "flour"))
        XCTAssertFalse(IngredientName.matches("butter beans", "butter"))
        XCTAssertFalse(IngredientName.matches("flour tortillas", "flour"))
        XCTAssertFalse(IngredientName.matches("", "butter"))
        XCTAssertFalse(IngredientName.matches("buttermilk", "milk"))
    }

    // render scales, then converts with the line's own separator
    func testRenderScalesThenConvertsWithTheLinesOwnSeparator() {
        XCTAssertEqual(
            IngredientRendering.render(["1 cup flour", "1,25 kg potatoes"], factor: 2, system: .metric, convertLiquids: false),
            ["240 g flour", "2,5 kg potatoes"]
        )
        XCTAssertEqual(
            IngredientRendering.render(["1 cup flour"], factor: 1, system: .asWritten, convertLiquids: false), ["1 cup flour"]
        )
    }
}
