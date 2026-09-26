import XCTest
@testable import RecipeClipper

/// Chef mode (#100) in the recipe screen's ViewModel, with the model faked.
@MainActor
final class RecipeChefModeTests: XCTestCase {
    private let oven = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    private let bake = "Bake for 25 to 30 minutes, until the top is golden and springy."
    private let shortOven = "Preheat oven to 350°F; butter a 9-inch tin."

    private func recipe(language: String = "en") -> Recipe {
        Recipe(
            name: "Cake", image: nil, ingredients: ["2 eggs"], instructions: [oven, bake], prepTime: nil,
            cookTime: nil, totalTime: nil, yield: "8", sourceUrl: "https://example.com/cake", id: 1, language: language
        )
    }

    private func open(
        _ recipe: Recipe, model: FakeStepShortener, preferences: FakeAppPreferences = FakeAppPreferences(chefMode: true),
        flagOn: Bool = true
    ) async throws -> RecipeViewModel {
        let (_, shortSteps) = try await makeShortStepRepository(recipe, model: model)
        let repository = FakeRecipeRepository()
        repository.openResult = recipe
        let flags = FeatureFlags(store: MemoryFeatureFlagStore())
        flags.set(.chefMode, flagOn)
        let vm = RecipeViewModel(
            recipeId: recipe.id, url: nil, repository: repository, preferences: preferences, clock: TestClock(),
            shortSteps: shortSteps, flags: flags
        )
        await settleMain()
        return vm
    }

    private func model() -> FakeStepShortener {
        FakeStepShortener(written: [oven: shortOven, bake: "Bake 25 min."])
    }

    func testAPassingShortStepShowsAndATapShowsItAsWritten() async throws {
        let vm = try await open(recipe(), model: model())
        let content = try XCTUnwrap(vm.uiState.content.success)
        XCTAssertEqual(content.shortInstructions, [shortOven, nil])
        XCTAssertEqual(content.shownStep(0, asWritten: vm.uiState.asWrittenSteps), shortOven)
        XCTAssertEqual(content.shownStep(1, asWritten: vm.uiState.asWrittenSteps), bake)
        // Timers still come from the steps as written.
        XCTAssertEqual(content.stepTimerSeconds, [nil, 25 * 60])

        vm.onStepAsWrittenToggle(0)
        XCTAssertEqual(vm.uiState.content.success?.shownStep(0, asWritten: vm.uiState.asWrittenSteps), oven)
    }

    func testShortStepsRenderTemperaturesLikeTheSteps() async throws {
        let vm = try await open(recipe(), model: model(), preferences: FakeAppPreferences(temperatureUnit: .celsius, chefMode: true))
        XCTAssertEqual(vm.uiState.content.success?.shortInstructions.first, "Preheat oven to 180°C; butter a 9-inch tin.")
    }

    func testOffUnflaggedUnsupportedOrAnotherLanguageAsksNothing() async throws {
        let cases: [(Recipe, FakeStepShortener, FakeAppPreferences, Bool)] = [
            (recipe(), model(), FakeAppPreferences(), true),
            (recipe(), model(), FakeAppPreferences(chefMode: true), false),
            (recipe(language: "pt"), model(), FakeAppPreferences(chefMode: true), true),
            (recipe(), FakeStepShortener(support: .unsupported), FakeAppPreferences(chefMode: true), true),
        ]
        for (recipe, model, preferences, flagOn) in cases {
            let vm = try await open(recipe, model: model, preferences: preferences, flagOn: flagOn)
            XCTAssertTrue(model.asked.isEmpty)
            XCTAssertTrue(vm.uiState.content.success?.shortInstructions.allSatisfy { $0 == nil } ?? false)
        }
    }

    func testTurningChefModeOffClearsTheShortSteps() async throws {
        let preferences = FakeAppPreferences(chefMode: true)
        let vm = try await open(recipe(), model: model(), preferences: preferences)
        preferences.chefMode = false
        await settleMain()
        XCTAssertEqual(vm.uiState.content.success?.shortInstructions, [])
    }

    /// A recipe the free tier didn't keep (#107) has no row to cache short steps against: it
    /// shows as written, and Unlock keeps it and writes them.
    func testARecipeNotKeptShowsAsWrittenUntilUnlockKeepsIt() async throws {
        let model = model()
        let (_, shortSteps) = try await makeShortStepRepository(recipe(), model: model)
        let repository = FakeRecipeRepository()
        var shown = recipe()
        shown.id = 0
        repository.importResult = .notKept(shown)
        let flags = FeatureFlags(store: MemoryFeatureFlagStore())
        flags.set(.chefMode, true)
        let vm = RecipeViewModel(
            recipeId: nil, url: shown.sourceUrl, repository: repository, preferences: FakeAppPreferences(chefMode: true),
            clock: TestClock(), shortSteps: shortSteps, flags: flags, entitlements: FakeEntitlements()
        )
        await settleMain()
        XCTAssertTrue(model.asked.isEmpty)
        XCTAssertTrue(vm.uiState.content.success?.shortInstructions.allSatisfy { $0 == nil } ?? false)

        vm.onUnlock()
        await settleMain()
        XCTAssertEqual(vm.uiState.content.success?.shortInstructions, [shortOven, nil])
    }
}
