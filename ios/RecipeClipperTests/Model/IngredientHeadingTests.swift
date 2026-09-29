import XCTest
@testable import RecipeClipper

/// Port of Android's IngredientHeadingTest: group headings among the ingredients, in every
/// language, the colon form the parsers write.
final class IngredientHeadingTests: XCTestCase {

    func testALineEndingInAColonIsAHeadingInAnyLanguage() {
        for line in [
            "For the sauce:", "Für die Füllung:", "Pour la garniture :", "Para la salsa:",
            "Per la crema:", "Para a cobertura:", "  Batter:  ", "Minted Yoghurt (optional):",
        ] {
            XCTAssertTrue(IngredientHeading.isHeading(line), line)
        }
    }

    func testAnIngredientLineIsNotAHeading() {
        for line in ["250 g Mehl", "2 cups flour", "Salz", "salt: to taste", "", "Note: use cold butter."] {
            XCTAssertFalse(IngredientHeading.isHeading(line), line)
        }
    }

    func testGroceriesUseTheSameTest() {
        XCTAssertFalse(GrocerySources.buyable("Für die Füllung:"))
        XCTAssertTrue(GrocerySources.buyable("200 g Quark"))
    }

    func testAHeadingIsNeverScaledConvertedOrCut() throws {
        let de = try XCTUnwrap(LanguageWords.forTag("de"))
        XCTAssertEqual(
            IngredientRendering.render(
                ["Für 2 Portionen Soße:", "250 g Mehl", "1/2 TL Salz"], factor: 1.25, system: .metric, convertLiquids: false,
                words: de
            ),
            ["Für 2 Portionen Soße:", "313 g Mehl", "3 ml Salz"]
        )
        XCTAssertEqual(
            IngredientRendering.render(
                ["2 Portionen Soße:", "250 g Mehl"], factor: 1.25, system: .asWritten, convertLiquids: false, words: de
            ),
            ["2 Portionen Soße:", "313 g Mehl"]
        )
    }
}
