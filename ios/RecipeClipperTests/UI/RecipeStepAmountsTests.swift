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

    private func viewModel(_ preferences: FakeAppPreferences) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        let clock = TestClock()
        let vm = RecipeViewModel(recipeId: 1, url: nil, repository: repository, preferences: preferences, clock: clock, sleep: clock.sleep)
        await settleMain()
        return vm
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

    func testSettingsShowsTheStepsSectionOnlyBehindTheFlagAndTheSwitchWrites() {
        let preferences = FakeAppPreferences()
        let flags = FeatureFlags(store: MemoryFeatureFlagStore(), definitions: [
            FlagDefinition(key: "amountsInSteps", description: "Amounts in steps", defaults: .init(debug: false, release: false), issue: 101)
        ], isDebug: true)
        let vm = SettingsViewModel(preferences: preferences, backups: FakeBackupRepository(), files: FakeBackupFiles(), flags: flags)
        XCTAssertFalse(vm.showsSteps)
        flags.set(.amountsInSteps, true)
        XCTAssertTrue(vm.showsSteps)

        XCTAssertFalse(vm.uiState.amountsInSteps)
        vm.onAmountsInStepsChange(true)
        XCTAssertTrue(preferences.amountsInSteps)
        XCTAssertTrue(vm.uiState.amountsInSteps)
    }
}
