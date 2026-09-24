import XCTest
@testable import RecipeClipper

/// The same cases and expectations as Android's `RecipeTextSplitterTest`.
final class RecipeTextSplitterTests: XCTestCase {

    private func split(_ text: String) -> SplitRecipe? { RecipeTextSplitter.split(text) }

    func testSplitsAMarkdownRecipeWithBoldHeadersBulletsAndNumberedSteps() throws {
        let result = try XCTUnwrap(split("""
            **Ingredients**

            * 2 cups flour
            * 1 tsp salt

            **Instructions**

            1. Mix everything.
            2. Bake for 20 minutes.
            """))
        XCTAssertEqual(result.ingredients, ["2 cups flour", "1 tsp salt"])
        XCTAssertEqual(result.instructions, ["Mix everything.", "Bake for 20 minutes."])
        XCTAssertNil(result.yield)
    }

    func testProseWithoutHeadersIsNeverSplit() {
        XCTAssertNil(split("I mixed 2 cups of flour with some butter and baked it at 350 for 20 minutes. So good!"))
        XCTAssertNil(split("2 cups flour\n1 tsp salt\nMix and bake for 20 minutes."))
    }

    func testNeedsBothSectionsEachWithLines() {
        XCTAssertNil(split("Ingredients:\n2 cups flour\n1 tsp salt"))
        XCTAssertNil(split("Directions:\nMix.\nBake."))
        XCTAssertNil(split("Ingredients:\n\nDirections:\nMix.\nBake."))
        XCTAssertNil(split("Ingredients:\n2 cups flour\nDirections:\n"))
    }

    func testAHeaderMustBeTheWholeLine() {
        XCTAssertNil(split("Ingredients: flour, salt, water\nInstructions: mix and bake"))
        XCTAssertNil(split("The ingredients are simple\n2 cups flour\nThe method is easy\nBake."))
    }

    func testRecognisesTheUsualHeaderSpellings() {
        XCTAssertEqual(RecipeTextSplitter.section("Ingredients"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("INGREDIENTS:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("You'll need:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("Ingredients (serves 4)"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("Ingredients for the cake:"), .ingredients)
        XCTAssertEqual(RecipeTextSplitter.section("Directions"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("Method -"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("Steps:"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("How to make it"), .instructions)
        XCTAssertEqual(RecipeTextSplitter.section("Notes:"), .end)
        XCTAssertEqual(RecipeTextSplitter.section("Edit: fixed formatting"), .end)
        XCTAssertEqual(RecipeTextSplitter.section("EDIT 2: thanks all"), .end)
        XCTAssertNil(RecipeTextSplitter.section("Ingredients for this are cheap"))
        XCTAssertNil(RecipeTextSplitter.section("Preparation time: 10 min"))
        XCTAssertNil(RecipeTextSplitter.section("Update the seasoning to taste: salt"))
        XCTAssertNil(RecipeTextSplitter.section("2 cups flour"))
    }

    func testCleansMarkdownOffEachLine() {
        let clean = RecipeTextSplitter.cleanLine
        XCTAssertEqual(clean("* 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("- 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("• 2 cups flour"), "2 cups flour")
        XCTAssertEqual(clean("1. Mix."), "Mix.")
        XCTAssertEqual(clean("2) Mix."), "Mix.")
        XCTAssertEqual(clean("Step 3: Mix."), "Mix.")
        XCTAssertEqual(clean("1.5 cups milk"), "1.5 cups milk")
        XCTAssertEqual(clean("**Ingredients:**"), "Ingredients:")
        XCTAssertEqual(clean("## Directions"), "Directions")
        XCTAssertEqual(clean("> __Directions__"), "Directions")
        XCTAssertEqual(clean("#10 can tomatoes"), "#10 can tomatoes")
        XCTAssertEqual(clean("[King Arthur](https://example.com/flour) flour"), "King Arthur flour")
        XCTAssertEqual(clean("salt &amp; pepper"), "salt & pepper")
        XCTAssertEqual(clean("1 cup sugar\\*"), "1 cup sugar*")
        XCTAssertEqual(clean("*butter, softened*"), "butter, softened")
        XCTAssertEqual(clean("---"), "")
        XCTAssertEqual(clean("&#x200B;"), "")
        XCTAssertEqual(clean("  2 eggs  "), "2 eggs")
    }

    func testTheStoryIsDroppedButALabelledYieldAndTimesAreKept() throws {
        let result = try XCTUnwrap(split("""
            This one is from my aunt, who made it every summer.
            Serves 4
            Prep time: 10 min
            Cook time: 1 hour 30 minutes
            Total time: Overnight

            Ingredients
            2 eggs
            Instructions
            Whisk.
            """))
        XCTAssertEqual(result.yield, "Serves 4")
        XCTAssertEqual(result.prepTime, "10m")
        XCTAssertEqual(result.cookTime, "1h 30m")
        XCTAssertEqual(result.totalTime, "Overnight")
        XCTAssertEqual(result.ingredients, ["2 eggs"])
        XCTAssertEqual(result.instructions, ["Whisk."])
    }

    func testALabelledYieldLosesItsLabelServesAndMakesStayWhole() {
        XCTAssertEqual(split("Yield: 12 cookies\nIngredients\n1 egg\nMethod\nBake.")?.yield, "12 cookies")
        XCTAssertEqual(split("Servings: 4\nIngredients\n1 egg\nMethod\nBake.")?.yield, "4")
        XCTAssertEqual(split("Makes 2 loaves\nIngredients\n1 egg\nMethod\nBake.")?.yield, "Makes 2 loaves")
    }

    func testNotesAndEditsAfterTheStepsAreDropped() {
        let result = split("Ingredients\n1 egg\nDirections\nBoil for 7 minutes.\nNotes\nUse fresh eggs.\nEdit: typo")
        XCTAssertEqual(result?.instructions, ["Boil for 7 minutes."])
    }

    func testSectionsMayRepeatAndComeInEitherOrder() throws {
        let result = try XCTUnwrap(split("""
            For the sauce
            Method
            Simmer the tomatoes.
            Ingredients
            1 can tomatoes
            Ingredients for the pasta:
            200 g spaghetti
            Directions
            Boil the pasta.
            """))
        XCTAssertEqual(result.ingredients, ["1 can tomatoes", "200 g spaghetti"])
        XCTAssertEqual(result.instructions, ["Simmer the tomatoes.", "Boil the pasta."])
    }

    func testASubHeadingInsideASectionStaysAsWritten() {
        let result = split("Ingredients\nFor the dough:\n2 cups flour\nInstructions\nKnead.")
        XCTAssertEqual(result?.ingredients, ["For the dough:", "2 cups flour"])
    }

    func testWindowsAndOldMacLineEndingsSplitTheSame() {
        let expected = split("Ingredients\n1 egg\nMethod\nBoil.")
        XCTAssertNotNil(expected)
        XCTAssertEqual(split("Ingredients\r\n1 egg\r\nMethod\r\nBoil."), expected)
        XCTAssertEqual(split("Ingredients\r1 egg\rMethod\rBoil."), expected)
    }
}
