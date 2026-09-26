import XCTest
@testable import RecipeClipper

/// WP Recipe Maker's ingredient parts (#118), on the real pages Android's `WprmIngredientsTest`
/// reads. Edge cases are pinned to the Kotlin by the differential corpus's `Wprm` rows.
final class WprmIngredientsTests: XCTestCase {

    private func ingredients(_ page: String) throws -> [String] {
        let html = try pageFixture(page)
        guard case .success(let recipe) = BlogRecipeSource.parse(html: html, url: "https://example.com/r") else {
            XCTFail("no recipe in \(page)"); return []
        }
        return recipe.ingredients
    }

    func testRecipeTinEatsNotesReadAsTheCardShowsThemWithGroupsAsHeadings() throws {
        let lines = try ingredients("wprm-recipetineats-greek-zucchini-tots")
        XCTAssertEqual(Array(lines.prefix(4)), [
            "Zucchini:", "1 lb / 500 g zucchinis (courgettes)", "1/4 tsp cooking salt / kosher salt", "Batter:",
        ])
        // JSON-LD writes these "2 garlic cloves (, minced)" and "3/4 cup green onion (, finely sliced (…))".
        XCTAssertTrue(lines.contains("2 garlic cloves, minced"))
        XCTAssertTrue(lines.contains("3/4 cup green onion, finely sliced (white and pale green parts only)"))
        XCTAssertTrue(lines.contains("Minted Yoghurt (optional):"))
        XCTAssertEqual(lines.count, 22)
    }

    func testAnUnnamedFirstGroupHasNoHeading() throws {
        let lines = try ingredients("wprm-recipetineats-chicken-chow-mein")
        XCTAssertEqual(lines[0], "200g /6 oz chicken breast or thigh fillets, thinly sliced (Note 1 tenderise option)")
        XCTAssertEqual(lines[9], "Chow Mein Sauce:")
    }

    func testACommaThePagePutsBetweenNameAndNotesIsKept() throws {
        XCTAssertEqual(try ingredients("wprm-skinnytaste-air-fryer-chicken")[0], "kosher salt, *see notes")
        let hummus = try ingredients("wprm-loveandlemons-hummus")
        XCTAssertEqual(hummus[0], "1½ cups cooked chickpeas, drained and rinsed")
        // JSON-LD drops this note entirely.
        XCTAssertEqual(hummus[7], "Paprika, red pepper flakes, and/or fresh parsley, for garnish")
    }

    func testBracketedNotesFollowTheName() throws {
        let lines = try ingredients("wprm-minimalistbaker-vegan-fried-rice")
        XCTAssertEqual(lines[0], "RICE + VEGETABLES:")
        XCTAssertEqual(lines[3], "4 cloves garlic (minced)")
    }
}
