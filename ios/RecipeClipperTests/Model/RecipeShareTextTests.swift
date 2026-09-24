import XCTest
@testable import RecipeClipper

/// Port of Android's RecipeShareTextTest.
final class RecipeShareTextTests: XCTestCase {

    private let recipe = Recipe(
        name: "Chicken Adobo",
        image: "https://example.com/adobo.jpg",
        ingredients: ["2 lb chicken thighs", "1/2 cup soy sauce"],
        instructions: ["Marinate the chicken.", "Simmer 30 minutes."],
        prepTime: "10m",
        cookTime: "30m",
        totalTime: "40m",
        yield: "6 servings",
        sourceUrl: "https://example.com/adobo"
    )

    private func format(
        recipe: Recipe? = nil,
        servings: ServingsScale? = ServingsScale(base: 6, target: 6),
        ingredients: [String]? = nil,
        instructions: [String]? = nil
    ) -> String {
        let r = recipe ?? self.recipe
        return RecipeShareText.format(
            recipe: r,
            servings: servings,
            ingredients: ingredients ?? r.ingredients,
            instructions: instructions ?? r.instructions
        )
    }

    private func lines(_ text: String) -> [String] { text.components(separatedBy: "\n") }

    func testTitleIsTheFirstLineFollowedByABlankLine() {
        let lines = lines(format())
        XCTAssertEqual("Chicken Adobo", lines[0])
        XCTAssertEqual("", lines[1])
    }

    func testScaledServingsAreLabelledWithTheOriginal() {
        let text = format(servings: ServingsScale(base: 6, target: 3))
        XCTAssertTrue(text.contains("Serves 3 (originally 6)"))
    }

    func testUnscaledServingsAreNotLabelled() {
        let text = format(servings: ServingsScale(base: 6, target: 6))
        XCTAssertTrue(text.contains("Serves 6"))
        XCTAssertFalse(text.contains("originally"))
    }

    func testAYieldThatCountsThingsMadeSaysMakes() {
        let cookies = Recipe(
            name: recipe.name,
            image: recipe.image,
            ingredients: recipe.ingredients,
            instructions: recipe.instructions,
            prepTime: recipe.prepTime,
            cookTime: recipe.cookTime,
            totalTime: recipe.totalTime,
            yield: "Makes 16",
            sourceUrl: recipe.sourceUrl
        )
        let scaled = format(recipe: cookies, servings: ServingsScale(base: 16, target: 32))
        XCTAssertTrue(scaled.contains("Makes 32 (originally 16)"))
        XCTAssertFalse(scaled.contains("Serves"))

        let unscaled = format(recipe: cookies, servings: ServingsScale(base: 16, target: 16))
        XCTAssertTrue(lines(unscaled).contains("Makes 16"))
        XCTAssertFalse(unscaled.contains("Serves"))
    }

    func testARecipeWithNoUsableYieldOmitsTheServesLineEntirely() {
        let text = format(servings: nil)
        XCTAssertFalse(text.contains("Serves"))
    }

    func testTimesLineIncludesOnlyTheTimesThatArePresent() {
        let all = format()
        XCTAssertTrue(all.contains("Prep 10m · Cook 30m · Total 40m"))

        var cookOnlyRecipe = recipe
        cookOnlyRecipe.prepTime = nil
        cookOnlyRecipe.totalTime = nil
        let cookOnly = format(recipe: cookOnlyRecipe)
        XCTAssertTrue(cookOnly.contains("Cook 30m"))
        XCTAssertFalse(cookOnly.contains("Prep"))
        XCTAssertFalse(cookOnly.contains("Total"))

        var noneRecipe = recipe
        noneRecipe.prepTime = nil
        noneRecipe.cookTime = nil
        noneRecipe.totalTime = nil
        let none = format(recipe: noneRecipe)
        XCTAssertFalse(none.contains("Prep"))
        XCTAssertFalse(none.contains("Cook"))
        XCTAssertFalse(none.contains("Total"))
    }

    func testIngredientsAreListedOnePerLineUnderAHeader() {
        let text = format()
        XCTAssertTrue(text.contains("INGREDIENTS\n2 lb chicken thighs\n1/2 cup soy sauce"))
    }

    func testInstructionsAreNumberedUnderAHeader() {
        let text = format()
        XCTAssertTrue(text.contains("INSTRUCTIONS\n1. Marinate the chicken.\n2. Simmer 30 minutes."))
    }

    func testTheIngredientsAndInstructionsPassedInAreUsedNotTheRecipesOwn() {
        // Proves scaling/conversion isn't re-derived here: whatever is passed in is what's shared.
        let text = format(ingredients: ["1 cup flour (scaled)"], instructions: ["Do the thing."])
        XCTAssertTrue(text.contains("1 cup flour (scaled)"))
        XCTAssertTrue(text.contains("1. Do the thing."))
        XCTAssertFalse(text.contains(recipe.ingredients[0]))
    }

    func testTheSourceUrlIsNotSharedAndTheLastStepIsTheLastLine() {
        let text = format()
        XCTAssertFalse(text.contains(recipe.sourceUrl))
        XCTAssertEqual("2. Simmer 30 minutes.", lines(text).last)
    }

    func testNoMarkdownSyntaxAppearsAnywhere() {
        let text = format()
        XCTAssertFalse(text.contains("**"))
        XCTAssertFalse(text.contains("##"))
        XCTAssertFalse(text.contains("- "))
    }
}
