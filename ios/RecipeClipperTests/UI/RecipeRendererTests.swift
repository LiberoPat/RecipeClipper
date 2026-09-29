import XCTest
@testable import RecipeClipper

/// `RecipeRenderer` on its own (#169; Android's `RecipeRendererTest`): what the screen shows of
/// a recipe under given settings. The corpus's `Render` rows pin the same to the Kotlin.
final class RecipeRendererTests: XCTestCase {
    private let steps = ["Preheat the oven to 350°F.", "Whisk the flour and sugar, then bake 20 minutes."]

    private func recipe(
        yield: String? = "4 servings", target: Int? = nil, ingredients: [String] = ["2 cups flour", "1 cup sugar"]
    ) -> Recipe {
        Recipe(
            name: "Cake", image: nil, ingredients: ingredients, instructions: steps, prepTime: nil, cookTime: nil,
            totalTime: nil, yield: yield, sourceUrl: "https://www.example.com/cake", language: "en", servingsTarget: target
        )
    }

    private let metric = RecipeRenderer.Settings(unitSystem: .metric, temperatureUnit: .celsius, amountsInSteps: true)

    func testAsWrittenShowsTheRecipesOwnLinesStepsTimersAndSource() {
        let shown = RecipeRenderer.content(recipe(), settings: RecipeRenderer.Settings())

        XCTAssertEqual(shown.servings, ServingsScale(base: 4, target: 4))
        XCTAssertEqual(shown.ingredients, ["2 cups flour", "1 cup sugar"])
        XCTAssertEqual(shown.instructions, steps)
        XCTAssertEqual(shown.stepTimerSeconds, [nil, 20 * 60])
        XCTAssertEqual(shown.sourceDomain, "example.com")
        XCTAssertNil(shown.stepAmounts, "amounts in steps are off")
        XCTAssertEqual(shown.shortInstructions, [])
    }

    func testTheChosenServingsScaleTheLinesBeforeTheyAreConverted() throws {
        let shown = RecipeRenderer.content(recipe(target: 8), settings: metric)

        XCTAssertEqual(shown.servings, ServingsScale(base: 4, target: 8))
        XCTAssertEqual(shown.ingredients, ["480 g flour", "400 g sugar"])
        XCTAssertEqual(shown.instructions[0], "Preheat the oven to 180°C.")
        XCTAssertEqual(
            StepAmounts.marked(try XCTUnwrap(shown.stepAmounts)[1]),
            "Whisk ⟦480 g⟧ flour and ⟦400 g⟧ sugar, then bake 20 minutes."
        )
    }

    func testChosenServingsStayWithinTheStepperAndAYieldWithNoNumberHasNone() {
        XCTAssertEqual(RecipeRenderer.content(recipe(target: 0), settings: metric).servings?.target, 1)
        XCTAssertEqual(RecipeRenderer.content(recipe(target: 1000), settings: metric).servings?.target, Servings.max)
        XCTAssertNil(RecipeRenderer.content(recipe(yield: "Makes plenty", target: 6), settings: metric).servings)
        XCTAssertNil(RecipeRenderer.content(recipe(yield: nil), settings: metric).servings)
    }

    func testNewServingsRescaleOnlyTheIngredientsAndTheirAmountsInSteps() throws {
        let shown = RecipeRenderer.content(recipe(), settings: metric)

        let doubled = try XCTUnwrap(RecipeRenderer.withServings(shown, target: 8, settings: metric))

        XCTAssertEqual(doubled.servings?.target, 8)
        XCTAssertEqual(doubled.ingredients, ["480 g flour", "400 g sugar"])
        XCTAssertEqual(doubled.instructions, shown.instructions)
        XCTAssertEqual(
            StepAmounts.marked(try XCTUnwrap(doubled.stepAmounts)[1]),
            "Whisk ⟦480 g⟧ flour and ⟦400 g⟧ sugar, then bake 20 minutes."
        )
        XCTAssertEqual(RecipeRenderer.withServings(shown, target: 500, settings: metric)?.servings?.target, Servings.max)
        XCTAssertNil(RecipeRenderer.withServings(RecipeRenderer.content(recipe(yield: nil), settings: metric), target: 8, settings: metric))
    }

    func testRenderingAgainUnderNewSettingsKeepsTheChosenServingsAndTheShortSteps() {
        let shorts: [String?] = [nil, "Whisk flour and sugar; bake 20 min at 350°F."]
        let shown = RecipeRenderer.content(recipe(target: 8), settings: RecipeRenderer.Settings(), shortSteps: shorts)

        let again = RecipeRenderer.rerender(shown, settings: metric, shortSteps: shorts)

        XCTAssertEqual(again, RecipeRenderer.content(recipe(target: 8), settings: metric, shortSteps: shorts))
        XCTAssertEqual(again.servings?.target, 8)
        XCTAssertEqual(again.shortInstructions, [nil, "Whisk flour and sugar; bake 20 min at 180°C."])
    }

    func testShortStepsShowOnlyOnePerStepRenderedLikeTheStepsWithTheirOwnAmounts() {
        let shown = RecipeRenderer.content(recipe(), settings: metric)

        let short = RecipeRenderer.withShortSteps(shown, ["Oven to 350°F.", nil], settings: metric)
        XCTAssertEqual(short.shortInstructions, ["Oven to 180°C.", nil])
        XCTAssertEqual(short.shortStepAmounts?.map(StepAmounts.marked), ["Oven to 180°C.", ""])

        let stale = RecipeRenderer.withShortSteps(shown, ["Oven to 350°F."], settings: metric)
        XCTAssertEqual(stale.shortInstructions, [])
        XCTAssertNil(stale.shortStepAmounts)

        let none = RecipeRenderer.withShortSteps(shown, [nil, nil], settings: metric)
        XCTAssertNil(none.shortStepAmounts, "no short step, no amounts for them")
    }

    func testTheModelsDecidedCountBracketsReachTheIngredients() {
        let apples = "3 large apples, peeled and sliced (about 3 cups)"
        let doubled = recipe(target: 8, ingredients: [apples])
        let total = Decisions(answers: [.countBracket(apples, language: "en"): "total"])

        let asToday = RecipeRenderer.content(doubled, settings: RecipeRenderer.Settings())
        let decided = RecipeRenderer.content(doubled, settings: RecipeRenderer.Settings(decisions: total))

        XCTAssertNotEqual(asToday.ingredients, decided.ingredients)
        XCTAssertEqual(decided.ingredients, ["6 large apples, peeled and sliced (about 6 cups)"])
    }

    // Junk after an ingredient (#174), decided as Groceries decides it.
    private let junk = Decisions(answers: [
        .trailingText("(dfsafs -", language: "en"): "junk",
        .ingredientName("2 onions dfsafs", language: "en"): "onions",
        .trailingText("dfsafs", language: "en"): "junk",
        .trailingText(", beaten", language: "en"): "note",
    ])

    func testJunkTheModelDecidedIsHiddenAtASeparatorOrAfterItsNameAndANoteStays() {
        let lines = ["2 eggs (dfsafs -", "2 onions dfsafs", "2 eggs, beaten", "1 cup sugar"]
        let shown = RecipeRenderer.content(recipe(ingredients: lines), settings: RecipeRenderer.Settings(decisions: junk))

        XCTAssertEqual(shown.ingredients, ["2 eggs", "2 onions", "2 eggs, beaten", "1 cup sugar"])
        XCTAssertEqual(shown.recipe.ingredients, lines, "the stored lines are never rewritten")
    }

    func testTheRestOfALineWithJunkHiddenStillScalesAndConverts() throws {
        var settings = metric
        settings.decisions = junk
        let shown = RecipeRenderer.content(recipe(target: 8, ingredients: ["1 cup flour (dfsafs -", "2 onions dfsafs"]), settings: settings)

        XCTAssertEqual(shown.ingredients, ["240 g flour", "4 onions"])
        XCTAssertEqual(try XCTUnwrap(RecipeRenderer.withServings(shown, target: 16, settings: settings)).ingredients, ["480 g flour", "8 onions"])
    }

    func testTextHoldingADigitAHeadingUnsureOrNoDecisionsShowAsWritten() {
        let lines = ["2 eggs (dfsafs 2 -", "For the eggs (dfsafs -:", "3 eggs (dfsafs -"]
        let unsure = Decisions(answers: [.trailingText("(dfsafs -", language: "en"): "unsure"])
        var all: [DecisionQuestion: String] = [:]
        for text in ["(dfsafs 2 -", "(dfsafs -:", "(dfsafs -"] { all[.trailingText(text, language: "en")] = "junk" }

        for decisions in [Decisions.none, unsure] {
            XCTAssertEqual(RecipeRenderer.content(recipe(ingredients: lines), settings: RecipeRenderer.Settings(decisions: decisions)).ingredients, lines)
        }
        XCTAssertEqual(
            RecipeRenderer.content(recipe(ingredients: lines), settings: RecipeRenderer.Settings(decisions: Decisions(answers: all))).ingredients,
            ["2 eggs (dfsafs 2 -", "For the eggs (dfsafs -:", "3 eggs"],
            "only the ingredient line whose junk holds no digit loses it"
        )
    }

    func testJunkIsNeverCutInALanguageWrittenWithoutSpacesOrWithNoWords() {
        var ja = recipe(ingredients: ["卵 2個 (dfsafs -"])
        ja.language = "ja"
        let decided = Decisions(answers: [.trailingText("(dfsafs -", language: "ja"): "junk"])
        XCTAssertEqual(RecipeRenderer.content(ja, settings: RecipeRenderer.Settings(decisions: decided)).ingredients, ja.ingredients)

        var zz = recipe(ingredients: ["2 eggs (dfsafs -"])
        zz.language = "zz"
        XCTAssertEqual(RecipeRenderer.content(zz, settings: RecipeRenderer.Settings(decisions: junk)).ingredients, zz.ingredients)
    }

    /// The owner's German recipe, 4 to 5 servings: "312 1/2 g Mehl" is now "313 g Mehl", in both systems.
    func testScaledMetricAmountsAreDecimalsAndHeadingsStayAsWritten() {
        var german = recipe(target: 5, ingredients: ["Für den Teig:", "250 g Mehl", "5 g Hefe", "1/2 l Milch", "2 EL Zucker"])
        german.language = "de"

        XCTAssertEqual(
            RecipeRenderer.content(german, settings: RecipeRenderer.Settings()).ingredients,
            ["Für den Teig:", "313 g Mehl", "6,3 g Hefe", "0,63 l Milch", "2 1/2 EL Zucker"]
        )
        XCTAssertEqual(
            RecipeRenderer.content(german, settings: metric).ingredients,
            ["Für den Teig:", "313 g Mehl", "6,3 g Hefe", "0,63 l Milch", "31 g Zucker"]
        )
    }
}
