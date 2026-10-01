import XCTest
@testable import RecipeClipper

/// `RecipeDisplay` on its own (#234; Android's RecipeDisplayTest): the settings, servings and short
/// steps the recipe renders with.
@MainActor
final class RecipeDisplayTests: XCTestCase {
    private var shortSteps: [String?] = []
    private lazy var display = RecipeDisplay(shortSteps: { [unowned self] in self.shortSteps }, decisions: { .none })

    private let recipe = Recipe(
        name: "Cake", image: nil, ingredients: ["2 cups flour", "1 cup milk"],
        instructions: ["Mix everything together until smooth.", "Bake."],
        prepTime: nil, cookTime: nil, totalTime: nil, yield: "4 servings",
        sourceUrl: "https://example.com/cake", id: 1
    )

    private func loaded(_ state: RecipeUiState = RecipeUiState()) -> RecipeUiState {
        var next = state
        next.content = .success(display.content(recipe, state))
        return next
    }

    // The lines as the renderer itself shows them under these settings and servings.
    private func rendered(_ system: UnitSystem, servings: Int? = nil) -> [String] {
        var scaled = recipe
        scaled.servingsTarget = servings
        return RecipeRenderer.content(scaled, settings: RecipeRenderer.Settings(unitSystem: system)).ingredients
    }

    func testAUnitsChangeReRendersTheRecipe() {
        let metric = display.withSettings(loaded(), AppSettings(unitSystem: .metric))

        XCTAssertEqual(metric.unitSystem, .metric)
        XCTAssertEqual(metric.content.success?.ingredients, rendered(.metric))
        XCTAssertNotEqual(metric.content.success?.ingredients, rendered(.asWritten))
    }

    func testDarkWhileCookingAloneLeavesTheRecipeAsItWas() {
        let state = loaded()

        let dark = display.withSettings(state, AppSettings(darkWhileCooking: true))

        XCTAssertTrue(dark.darkWhileCooking)
        XCTAssertEqual(dark.content, state.content)
    }

    func testASettingsChangeKeepsTheChosenServings() throws {
        var doubled = loaded()
        doubled.content = .success(try XCTUnwrap(display.withServings(doubled, 8)))

        let metric = display.withSettings(doubled, AppSettings(unitSystem: .metric))

        XCTAssertEqual(metric.content.success?.servings, ServingsScale(base: 4, target: 8))
        XCTAssertEqual(metric.content.success?.ingredients, rendered(.metric, servings: 8))
    }

    func testTheUnitsDropdownReRendersAtOnce() {
        let metric = display.withUnitSystem(loaded(), .metric)

        XCTAssertEqual(metric.unitSystem, .metric)
        XCTAssertEqual(metric.content.success?.ingredients, rendered(.metric))
    }

    func testServingsScaleTheIngredientsAndTheRecipesOwnYieldIsSavedAsNone() throws {
        let doubled = try XCTUnwrap(display.withServings(loaded(), 8))

        XCTAssertEqual(doubled.servings, ServingsScale(base: 4, target: 8))
        XCTAssertEqual(doubled.ingredients, rendered(.asWritten, servings: 8))
        XCTAssertNotEqual(doubled.ingredients, rendered(.asWritten))
        XCTAssertEqual(display.savedServings(ServingsScale(base: 4, target: 8)), 8)
        XCTAssertNil(display.savedServings(ServingsScale(base: 4, target: 4)))
    }

    func testNothingLoadedIsLeftAsItIs() {
        let state = RecipeUiState()

        XCTAssertNil(display.withServings(state, 8))
        XCTAssertEqual(display.rerendered(state), state)
        XCTAssertNil(display.withShortSteps(state))
    }

    func testChefModesShortStepsAreShownOnlyWhenTheyChange() {
        let state = loaded()
        XCTAssertNil(display.withShortSteps(state), "no short steps yet: nothing to show")

        shortSteps = ["Mix until smooth.", nil]
        let shown = display.withShortSteps(state)

        XCTAssertEqual(shown?.content.success?.shortInstructions, ["Mix until smooth.", nil])
    }
}
