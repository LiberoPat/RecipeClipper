import XCTest
@testable import RecipeClipper

/// Mirrors the Kotlin RecipeStepAmountsTest: amounts inside steps (#101) follow the servings
/// stepper, the unit menu and the Settings switch.
@MainActor
final class RecipeStepAmountsTests: XCTestCase {

    private let recipe = Recipe(
        name: "Carrot Cake", image: nil,
        ingredients: ["2 carrots, grated", "1 cup all-purpose flour"],
        instructions: ["Stir in the flour and the carrots."],
        prepTime: nil, cookTime: nil, totalTime: nil, yield: "4 servings",
        sourceUrl: "https://example.com/cake", id: 1
    )

    private let clock = TestClock()

    private func viewModel(_ preferences: FakeAppPreferences, _ opened: Recipe? = nil) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = opened ?? recipe
        let vm = RecipeViewModel(recipeId: 1, url: nil, repository: repository, preferences: preferences, clock: clock, sleep: clock.sleep)
        await settleMain()
        return vm
    }

    // Two butter lines with different amounts (#254): the step's "butter" is a link, not a number.
    private var twoButters: Recipe {
        var r = recipe
        r.ingredients = ["200g butter", "100g butter", "1 cup flour"]
        r.instructions = ["Melt the butter.", "Stir in the flour."]
        return r
    }

    func testALinkHighlightsItsLinesUntilTheyFadeThenGoesBackToItsStep() async {
        let vm = await viewModel(FakeAppPreferences(amountsInSteps: true), twoButters)
        XCTAssertEqual(step(vm), "Melt the ⟪butter|0,1⟫.")

        vm.onIngredientLink(0, [0, 1])
        XCTAssertEqual(vm.uiState.ingredientJump, IngredientJump(step: 0, lines: [0, 1]))
        vm.onIngredientJumpScrolled()
        XCTAssertEqual(vm.uiState.ingredientJump, IngredientJump(step: 0, lines: [0, 1], scrollTo: nil))

        await clock.advance(by: 3_001)
        await settleMain()
        XCTAssertEqual(vm.uiState.ingredientJump?.highlighted, false)

        vm.onBackToStep()
        XCTAssertEqual(vm.uiState.ingredientJump?.scrollTo, .step)
        vm.onIngredientJumpScrolled()
        XCTAssertNil(vm.uiState.ingredientJump)
    }

    func testInCookModeALinkOpensTheFoldedIngredientsAndBackFoldsThemAgain() async {
        let vm = await viewModel(FakeAppPreferences(amountsInSteps: true), twoButters)
        vm.onCookStart()

        vm.onIngredientLink(0, [0, 1])
        XCTAssertTrue(vm.uiState.cook.ingredientsExpanded)
        XCTAssertEqual(vm.uiState.ingredientJump?.refold, true)

        vm.onBackToStep()
        XCTAssertFalse(vm.uiState.cook.ingredientsExpanded)
        XCTAssertNil(vm.uiState.ingredientJump)

        // Opened by hand first: back leaves the bar open.
        vm.onIngredientsToggle()
        vm.onIngredientLink(0, [0, 1])
        vm.onBackToStep()
        XCTAssertTrue(vm.uiState.cook.ingredientsExpanded)
    }

    func testTheLinkReadsAsAButtonForVoiceOver() {
        XCTAssertEqual(Strings.stepLinkLabel("butter", matches: 2), "butter, 2 matching ingredients, show")
    }

    private func step(_ vm: RecipeViewModel) -> String? {
        vm.uiState.content.success?.stepAmounts.map { StepAmounts.marked($0[0]) }
    }

    func testTheAmountFollowsTheServingsAndTheUnits() async {
        let vm = await viewModel(FakeAppPreferences(amountsInSteps: true))
        XCTAssertEqual(step(vm), "Stir in ⟦1 cup⟧ flour and ⟦2⟧ carrots.")

        vm.onServingsChange(8)
        XCTAssertEqual(step(vm), "Stir in ⟦2 cup⟧ flour and ⟦4⟧ carrots.")

        vm.onUnitSystemChange(.metric)
        XCTAssertEqual(step(vm), "Stir in ⟦240 g⟧ flour and ⟦4⟧ carrots.")
    }

    func testOffByDefaultAndTheSwitchAppliesToAnOpenRecipe() async {
        let preferences = FakeAppPreferences()
        let vm = await viewModel(preferences)
        XCTAssertNil(step(vm))

        preferences.amountsInSteps = true
        await settleMain()
        XCTAssertEqual(step(vm), "Stir in ⟦1 cup⟧ flour and ⟦2⟧ carrots.")
        // The steps as written stay what the screen shares.
        XCTAssertEqual(vm.uiState.content.success?.instructions, recipe.instructions)
    }

    func testTheSettingsSwitchWrites() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles())
        XCTAssertFalse(vm.uiState.amountsInSteps)
        vm.onAmountsInStepsChange(true)
        XCTAssertTrue(preferences.amountsInSteps)
        XCTAssertTrue(vm.uiState.amountsInSteps)
    }
}
