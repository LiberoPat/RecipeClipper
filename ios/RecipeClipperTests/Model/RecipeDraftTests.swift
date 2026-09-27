import XCTest
@testable import RecipeClipper

/// Android's RecipeDraftTest.
final class RecipeDraftTests: XCTestCase {
    func testLinesAreTrimmedAndBlankLinesDropped() {
        XCTAssertEqual(RecipeDraft.lines("  1 onion\r\n\n\t\n2 carrots \n"), ["1 onion", "2 carrots"])
    }

    func testARecipeNeedsANamePlusIngredientsOrSteps() {
        XCTAssertFalse(RecipeDraft().isValid)
        XCTAssertFalse(RecipeDraft(name: "Soup").isValid)
        XCTAssertFalse(RecipeDraft(name: "  ", ingredientsText: "1 onion").isValid)
        XCTAssertFalse(RecipeDraft(name: "Soup", ingredientsText: "\n  \n").isValid)
        XCTAssertTrue(RecipeDraft(name: "Soup", ingredientsText: "1 onion").isValid)
        XCTAssertTrue(RecipeDraft(name: "Soup", instructionsText: "Cook.").isValid)
    }

    func testApplyingKeepsTheRecipesIdentityAndMakesBlankFieldsAbsent() {
        let base = Recipe(
            name: "Old", image: "https://example.com/a.jpg", ingredients: ["x"], instructions: [],
            prepTime: "5m", cookTime: nil, totalTime: nil, yield: "2", sourceUrl: "https://example.com/a",
            id: 4, notes: "Mine"
        )

        let applied = RecipeDraft(name: " New ", prepTime: " ", ingredientsText: "a\nb").apply(to: base)

        XCTAssertEqual(applied.name, "New")
        XCTAssertEqual(applied.ingredients, ["a", "b"])
        XCTAssertNil(applied.prepTime)
        XCTAssertNil(applied.image)
        XCTAssertNil(applied.yield)
        XCTAssertEqual(applied.id, 4)
        XCTAssertEqual(applied.notes, "Mine")
        XCTAssertEqual(applied.sourceUrl, "https://example.com/a")
    }

    func testADraftOfARecipeRoundTripsItsContent() {
        let recipe = Recipe(
            name: "Soup", image: nil, ingredients: ["1 onion", "2 carrots"], instructions: ["Cook."],
            prepTime: nil, cookTime: "1h", totalTime: nil, yield: "4", sourceUrl: "https://example.com/a"
        )
        XCTAssertEqual(RecipeDraft.of(recipe).apply(to: recipe), recipe)
    }

    func testContentOriginIsReadByNameAnUnknownOneAsTheUsersVersion() {
        XCTAssertEqual(ContentOrigin.from(name: nil), .parsed)
        XCTAssertEqual(ContentOrigin.from(name: "CLIPPED"), .clipped)
        XCTAssertEqual(ContentOrigin.from(name: "SOMETHING_NEWER"), .edited)
        XCTAssertEqual(ContentOrigin.parsed.afterEdit(), .edited)
        XCTAssertEqual(ContentOrigin.clipped.afterEdit(), .clipped)
        XCTAssertEqual(ContentOrigin.manual.afterEdit(), .manual)
    }
}
